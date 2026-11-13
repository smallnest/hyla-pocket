package io.rpcx.hylapocket.tailcat

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/**
 * A socket of which only the port tells the target: the host is its pair code, and the connection
 * runs over tailcat, as hyla's own clients do (hyla/internal/remote/tailcat.go, DialTCPPort).
 *
 * OkHttp takes it as it takes any socket: its streams are the stream's, and closing it ends both.
 * Why not a local HTTP proxy in front of tailcat, which would also serve a plain java.net URL: it
 * adds a listener, a port and a hop per connection to say no more than this class does, and OkHttp
 * already asks a SocketFactory for its connections.
 */
class TailcatSocket(
    private val factory: TailcatSocketFactory,
    private val pair: String,
) : Socket() {

    /** The stream's handle, or -1 before connect and after close. */
    private val id = AtomicInteger(-1)
    private val closed = AtomicBoolean(false)

    private lateinit var input: TailcatInputStream
    private lateinit var output: TailcatOutputStream

    @Volatile
    private var remotePort = -1

    @Volatile
    private var readTimeoutMs = 0

    /**
     * Opens the stream. [address] names nothing but the port: the pair code, from the factory, is
     * the host. This overrides [Socket.connect], which OkHttp calls through
     * [javax.net.SocketFactory], and which Java's Socket has as public.
     */
    fun dial(address: SocketAddress, timeout: Int) {
        val port = (address as? InetSocketAddress)?.port ?: throw IOException("tailcat: an address needs a port")
        if (closed.get()) throw SocketException("tailcat: socket is closed")
        if (id.get() >= 0) throw SocketException("tailcat: already connected")

        val handle = Native.connect(pair, port, if (timeout > 0) timeout else factory.connectTimeoutMs)
        if (handle < 0) throw IOException("tailcat: cannot reach the host on port $port: " + Native.lastError())
        id.set(handle)
        remotePort = port
        input = TailcatInputStream()
        output = TailcatOutputStream()
        // A close that came while the dial ran: the JNI close now has a handle to end.
        if (closed.get()) release()
    }

    override fun connect(endpoint: SocketAddress, timeout: Int) = dial(endpoint, timeout)

    override fun getInputStream(): InputStream {
        checkConnected()
        return input
    }

    override fun getOutputStream(): OutputStream {
        checkConnected()
        return output
    }

    override fun isConnected() = id.get() >= 0

    override fun isClosed() = closed.get()

    override fun isInputShutdown() = closed.get()

    override fun isOutputShutdown() = closed.get()

    /** A stream ends both ways: a tailcat stream has no half close. */
    override fun shutdownInput() = close()

    override fun shutdownOutput() = close()

    override fun setSoTimeout(timeout: Int) {
        readTimeoutMs = timeout
    }

    override fun getSoTimeout() = readTimeoutMs

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }

    private fun release() {
        val handle = id.getAndSet(-1)
        if (handle >= 0) Native.release(handle)
    }

    private fun checkConnected() {
        if (closed.get()) throw SocketException("tailcat: socket is closed")
        if (id.get() < 0) throw SocketException("tailcat: socket is not connected")
    }

    /** A blocked read's end: the socket closed, by its timeout or its caller. */
    private fun readEnded() = if (closed.get()) SocketTimeoutException("tailcat: read timed out") else null

    override fun getRemoteSocketAddress(): SocketAddress? = InetSocketAddress(factory.hostName, remotePort)

    override fun getInetAddress(): InetAddress? = InetAddress.getByName(factory.hostName)

    override fun getPort() = remotePort

    override fun getLocalPort() = -1

    override fun toString() = "TailcatSocket($pair:$remotePort, closed=$closed)"

    /**
     * Reads of the stream, under [setSoTimeout]: a timer closes the socket when a read has waited
     * that long, which wakes the blocked read with an error, and the read fails as a timed-out
     * socket read does. OkHttp asks for a timeout on the server's replies and none on the shell's
     * stream, which an idle terminal keeps open.
     */
    private inner class TailcatInputStream : InputStream() {
        // Java's InputStream reads one byte at a time into a temporary array, then checks the
        // caller's buffer through getInputStream(), which no socket has: the native call must
        // copy into the caller's array, not into a temporary.
        override fun read(b: ByteArray): Int = read(b, 0, b.size)

        override fun skip(n: Long): Long = 0

        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            checkConnected()
            val timeout = readTimeoutMs
            val timer = if (timeout > 0) {
                Timers.schedule({ close() }, timeout.toLong())
            } else {
                null
            }
            try {
                val started = System.nanoTime() / 1_000_000
                // A native read waits only a moment, with the array pinned (see Native.read): the
                // wait for data goes on here, where the JVM can still pin the arrays of its other
                // threads.
                while (true) {
                    val n = Native.read(id.get(), b, off, len)
                    if (n > 0) return n
                    // A close ends a read, whether it came of a timeout or of OkHttp's cancel.
                    readEnded()?.let { throw it }
                    if (n < 0) return -1
                    if (timeout > 0 && System.nanoTime() / 1_000_000 - started >= timeout) {
                        throw SocketTimeoutException("tailcat: read timed out")
                    }
                    LockSupport.parkNanos(POLL_NS)
                }
            } finally {
                timer?.cancel(false)
            }
        }

        override fun available() = 0

        override fun close() = this@TailcatSocket.close()
    }

    private inner class TailcatOutputStream : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            checkConnected()
            if (Native.write(id.get(), b, off, len) < 0) {
                throw IOException("tailcat: write failed: " + Native.lastError())
            }
        }

        override fun close() = this@TailcatSocket.close()
    }

    private companion object {
        /** How long a native read waits, in ms, as bridge.Poll sets it: under it, a read returns 0. */
        const val POLL_NS = 100L * 1_000_000
    }

    private object Timers {
        private val executor = ScheduledThreadPoolExecutor(1) { r ->
            Thread(r, "tailcat-socket-timeout").apply { isDaemon = true }
        }

        fun schedule(task: () -> Unit, ms: Long) =
            executor.schedule({ runCatching(task) }, ms, TimeUnit.MILLISECONDS)
    }
}
