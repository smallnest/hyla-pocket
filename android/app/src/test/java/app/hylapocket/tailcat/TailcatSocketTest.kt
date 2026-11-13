package app.hylapocket.tailcat

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The whole path, as the app uses it: Kotlin, JNI, libtailcat, and tailcat over the public relays
 * to tools/tailcat-testhost, a Go tailcat server on this computer. The tests skip without the
 * network and the darwin library (tools/build-tailcat.py --host).
 */
class TailcatSocketTest {
    private var host: Process? = null
    private lateinit var pair: String
    private var port = 0
    private var stderr = ""

    @Before
    fun startHost() {
        val lib = File(System.getProperty("tailcat.lib") ?: "")
        org.junit.Assume.assumeTrue("build libtailcat.dylib first: tools/build-tailcat.py --host", lib.exists())
        System.load(lib.absolutePath)

        val errors = StringBuilder()
        val process = ProcessBuilder(GO, "run", ".").directory(HOST_SOURCE).start()
        host = process
        Thread { process.errorStream.bufferedReader().forEachLine { errors.appendLine(it) } }.apply { isDaemon = true }.start()
        // `go run` compiles first, which may take a while on a cold cache.
        val line = process.inputStream.bufferedReader().readLine() ?: run {
            Thread.sleep(500)
            throw AssertionError("the host did not start: $errors")
        }
        stderr = errors.toString()
        pair = line.removePrefix("pair=").substringBefore(' ')
        port = line.substringAfter("port=").trim().toInt()
    }

    @After
    fun stopHost() {
        // `go run` runs the host as a child: its process group goes too, or the next test's
        // host finds the relay of the last one in use.
        host?.toHandle()?.descendants()?.forEach { it.destroy() }
        host?.destroy()
        stderr.takeIf { it.isNotBlank() }?.let { System.err.println(it) }
    }

    @Test
    fun `a socket carries bytes both ways to a port of the host`() {
        val socket = TailcatSocketFactory(pair).createSocket()
        socket.connect(InetSocketAddress(TailcatSocketFactory.DEFAULT_HOST, port), 90_000)
        socket.soTimeout = 30_000
        socket.getOutputStream().write("ping\n".toByteArray())
        val reply = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
        println("DEBUG reply=[$reply] timeout=${socket.soTimeout} closed=${socket.isClosed}")
        assertEquals("echo ping", reply)
        socket.close()
        assertTrue(socket.isClosed)
    }

    @Test
    fun `a read timeout ends the socket as it does a plain socket`() {
        val socket = TailcatSocketFactory(pair).createSocket("127.0.0.1", port)
        socket.soTimeout = 700
        try {
            socket.getInputStream().read()
            throw AssertionError("a silent stream should time out")
        } catch (expected: SocketTimeoutException) {
            assertTrue(socket.isClosed)
        } finally {
            socket.close()
        }
    }

    @Test
    fun `OkHttp websockets run over tailcat`() {
        val client = OkHttpClient.Builder()
            .socketFactory(TailcatSocketFactory(pair))
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val echoes = java.util.concurrent.LinkedBlockingQueue<String>()
        val opened = CountDownLatch(1)
        val ws = client.newWebSocket(
            Request.Builder().url("ws://127.0.0.1:$port/stream").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.countDown()
                    webSocket.send("hello over tailcat")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    echoes.add(text)
                }
            },
        )
        assertTrue("the handshake did not finish", opened.await(30, TimeUnit.SECONDS))
        assertEquals("hello over tailcat", echoes.poll(30, TimeUnit.SECONDS))
        ws.close(1000, "done")
        client.dispatcher.executorService.shutdown()
    }

    private companion object {
        // app/build.gradle.kts gives both: the build makes them, this test uses them.
        val HOST_SOURCE = File(System.getProperty("tailcat.testhost") ?: ".")
        val GO = System.getProperty("tailcat.go") ?: "go"
    }
}
