package app.hylapocket.tailcat

import app.hylapocket.core.HostCodec
import app.hylapocket.core.Transport
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * The app's transport: every socket to a host goes over tailcat.
 *
 * Why one pair code for all of them: one OkHttp client serves one host of the app at a time, and
 * a socket carries only a port, no host.
 */
class TailcatTransport(pair: String) : Transport {
    val pair: String = HostCodec.withScheme(pair)

    override fun socket(pair: String, port: Int): Socket {
        NativeLoader.ensureLoaded()
        return TailcatSocketFactory(if (pair.isEmpty()) this.pair else HostCodec.withScheme(pair)).createSocket(this.pair, port)
    }
}

/**
 * OkHttp's sockets over tailcat. OkHttp connects a socket to the host name of a URL, which tailcat
 * does not carry by name, so [hostName] gives it a name that resolves, and only the port of the
 * URL matters: the host is the pair code.
 *
 * The connect timeout ends the dial; the read timeout ends a read, and with it the socket, as a
 * deadline on a socket read does. Both numbers come from OkHttp's own client.
 */
class TailcatSocketFactory @JvmOverloads constructor(
    pair: String,
    val hostName: String = DEFAULT_HOST,
    val connectTimeoutMs: Int = 30_000,
) : SocketFactory() {
    val pair: String = HostCodec.withScheme(pair)

    override fun createSocket(): Socket = TailcatSocket(this, pair)

    override fun createSocket(host: String?, port: Int): Socket = dial(InetSocketAddress(host, port))

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        createSocket(host, port)

    override fun createSocket(host: InetAddress?, port: Int): Socket = dial(InetSocketAddress(host, port))

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        createSocket(address, port)

    private fun dial(address: InetSocketAddress): Socket = TailcatSocket(this, pair).also { it.dial(address, connectTimeoutMs) }

    companion object {
        /** A name OkHttp can dial: loopback, which no request goes to. */
        const val DEFAULT_HOST = "127.0.0.1"
    }
}

/** The app's OkHttp client: every connection it opens runs over tailcat to [pair]. */
fun tailcatHttp(
    pair: String,
    configure: OkHttpClient.Builder.() -> Unit = {},
): OkHttpClient {
    NativeLoader.ensureLoaded()
    val builder = OkHttpClient.Builder()
        .socketFactory(TailcatSocketFactory(pair))
        // A terminal's stream stays open while the pane idles: only its ping can say the stream
        // died, so the read waits, as HylaClient.defaultHttp lets it.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
    builder.configure()
    // After configure, so that a call that changes the factory keeps the pair code.
    builder.socketFactory(TailcatSocketFactory(pair))
    return builder.build()
}
