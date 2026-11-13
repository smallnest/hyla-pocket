package app.hylapocket.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.URLEncoder

/**
 * One terminal's bytes from hylad: a WebSocket on `/api/data` carrying the pane's VT snapshot and
 * then its output as it comes. Typing still goes over the control connection; bytes only flow
 * this way.
 *
 * hylad does not resume a stream (docs/wire.md): a connection that drops starts over, and the
 * bytes it sends again from the start are a snapshot, which replaces the screen rather than
 * printing over it. [ticket] asks the control connection for the one-time ticket a new stream needs.
 */
class TerminalStream(
    private val endpoint: Endpoint,
    private val token: String,
    private val httpFactory: () -> OkHttpClient,
    /** Diagnostics: each connection, how its handshake went and why it ended. */
    private val trace: (String) -> Unit = {},
    /** A fresh ticket for the stream, with the session and pane it was asked for. */
    private val ticket: suspend () -> Triple<String, String, String>,
) {
    /** Stream bytes handed over so far: only what the diagnostics say of a stream that dropped. */
    @Volatile private var received = 0L
    @Volatile private var active: WebSocket? = null
    @Volatile private var stopped = false

    /** Wakes a wait between attempts, and cuts an attempt short; see [retryNow]. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /**
     * Starts the next attempt now. An attempt still trying to open is abandoned: it was started
     * when the network may have been cut, and a dial that went nowhere waits out its whole timeout.
     * A stream that is flowing is left alone.
     */
    fun retryNow() {
        wake.trySend(Unit)
    }

    /**
     * Drops the connection that is flowing and starts another at once: the network under it went
     * away, and a socket on a vanished network looks open until a ping goes unanswered, half a
     * minute later. The new stream starts from a snapshot, so nothing is lost or shown twice.
     */
    fun reconnectNow() {
        active?.cancel()
        wake.trySend(Unit)
    }

    /** The pane's bytes in order, for as long as this flow is collected. */
    fun feeds(): Flow<TerminalFeed> = flow {
        var attempt = 0
        while (!stopped) {
            val openedAt = System.nanoTime()
            // Anything left from an earlier wake belongs to an attempt that has ended.
            wake.tryReceive()
            val socket = try {
                coroutineScope {
                    val opening = async { open() }
                    val woken = launch { wake.receive(); opening.cancel(Retried()) }
                    try {
                        opening.await()
                    } finally {
                        woken.cancel()
                    }
                }
            } catch (e: Retried) {
                trace("stream open abandoned after ${elapsed(openedAt)}ms: trying again now")
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trace("stream open failed after ${elapsed(openedAt)}ms: ${e.javaClass.simpleName}: ${e.message}")
                if (++attempt > MAX_ATTEMPTS) throw e
                pause(backoff(attempt))
                continue
            }
            attempt = 0
            trace("stream open after ${elapsed(openedAt)}ms")
            val decoder = StreamDecoder(0uL)
            try {
                for (frame in socket.frames) {
                    when (frame) {
                        is Frame.Bytes -> {
                            // A stream that carries bytes is a stream that worked: what failed
                            // before it must not count against how often this one may start over.
                            attempt = 0
                            received += frame.bytes.size
                            for (feed in decoder.feed(frame.bytes)) emit(feed)
                        }
                        is Frame.Closed -> throw frame.cause ?: DroppedStream("The terminal stream ended")
                    }
                }
                return@flow
            } catch (e: CancellationException) {
                // The screen stopped collecting: not a failure, and not worth reconnecting.
                throw e
            } catch (e: DroppedStream) {
                trace("stream dropped after ${elapsed(openedAt)}ms at $received bytes: ${e.message}")
                if (e.fatal || stopped) throw e
                if (++attempt > MAX_ATTEMPTS) throw e
                pause(backoff(attempt))
            } catch (e: Exception) {
                trace("stream failed after ${elapsed(openedAt)}ms at $received bytes: ${e.javaClass.simpleName}: ${e.message}")
                if (stopped) return@flow
                if (++attempt > MAX_ATTEMPTS) throw e
                // A drop asked for by reconnectNow is retried at once: its wake is still pending.
                pause(backoff(attempt))
            } finally {
                if (stopped) socket.webSocket.close(CLOSE_NORMAL, "bye") else socket.webSocket.cancel()
                active = null
            }
        }
    }

    fun close() {
        stopped = true
        active?.close(CLOSE_NORMAL, "bye")
    }

    /** Waits [ms] between attempts, or until [retryNow]. */
    private suspend fun pause(ms: Long) {
        withTimeoutOrNull(ms) { wake.receive() }
    }

    /** An attempt given up for a fresh one, not a failure of the stream. */
    private class Retried : CancellationException("retrying now")

    /**
     * The ticket and what it is for: hylad ties the ticket to the session and pane it was asked
     * for, and checks the query's against it, so all three go in the URL.
     */
    private suspend fun query(): String {
        val (session, block, t) = ticket()
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "ticket=" + enc(t) + "&session=" + enc(session) + "&block=" + enc(block)
    }

    /** Opens one connection, on a fresh ticket. */
    private suspend fun open(): Connected {
        val welcome = CompletableDeferred<Connected>()
        // Each connection gets its own: a socket that failed still reports its close afterwards,
        // and the next connection must not take that close for its own.
        val frames = Channel<Frame>(Channel.UNLIMITED)
        val request = Request.Builder()
            .url(endpoint.ws("/api/data?" + query()))
            .header("Sec-WebSocket-Protocol", PROTOCOL)
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()
        val ws = httpFactory().newWebSocket(request, listener(welcome, frames))
        active = ws
        try {
            return welcome.await()
        } catch (e: CancellationException) {
            ws.cancel()
            throw e
        } catch (e: Exception) {
            ws.cancel()
            throw e
        }
    }

    private fun listener(
        welcome: CompletableDeferred<Connected>,
        frames: Channel<Frame>,
    ) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // The ticket in the query is the handshake: the stream starts with its snapshot.
            welcome.complete(Connected(webSocket, frames))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // hylad sends the pane's VT, not a handshake of its own, from the first byte on.
            if (welcome.isCompleted) frames.trySend(Frame.Bytes(bytes.toByteArray()))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val dropped = DroppedStream(describe(code, reason), fatal = code == CLOSE_BLOCK_CLOSED)
            if (!welcome.isCompleted) welcome.completeExceptionally(dropped)
            frames.trySend(Frame.Closed(dropped))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!welcome.isCompleted) welcome.completeExceptionally(t)
            frames.trySend(Frame.Closed(t))
        }
    }

    private fun describe(code: Int, reason: String): String = when {
        code == CLOSE_BLOCK_CLOSED -> "The pane closed"
        reason.isNotBlank() -> reason
        else -> "The terminal stream closed ($code)"
    }

    private fun elapsed(since: Long): Long = (System.nanoTime() - since) / 1_000_000

    private class Connected(val webSocket: WebSocket, val frames: Channel<Frame>)

    private sealed interface Frame {
        class Bytes(val bytes: ByteArray) : Frame
        class Closed(val cause: Throwable?) : Frame
    }

    companion object {
        /** The subprotocol hylad uses for a terminal's output. */
        const val PROTOCOL = "rex.data.terminal.v1"

        /** hylad closes with 4000 when the pane is gone; no reconnect helps there. */
        private const val CLOSE_BLOCK_CLOSED = 4000
        private const val CLOSE_NORMAL = 1000

        private const val MAX_ATTEMPTS = 5

        private fun backoff(attempt: Int): Long = (300L * attempt).coerceAtMost(2_000L)

    }
}

/** The stream ended. [fatal] when connecting again cannot help, such as a pane that closed. */
class DroppedStream(message: String, val fatal: Boolean = false) : Exception(message)
