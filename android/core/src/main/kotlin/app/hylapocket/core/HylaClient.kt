package app.hylapocket.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One Hyla control connection: WebSocket `GET /api/control` with subprotocol `rex.control.v1`,
 * rexpocket's protocol as hylad's HylaPocket listener serves it (hyla-pocket's docs/wire.md).
 * Every message is one JSON object. A tailcat listener admits a request by its pair code alone,
 * which is in [endpoint]; a network listener by the token, which is empty for a tailcat one.
 */
class HylaClient(
    private val endpoint: Endpoint,
    private val token: String,
    private val http: OkHttpClient = defaultHttp(),
    private val connectTimeoutMs: Long = 12_000,
    /** The API revision asked for in the hello; hylad answers the highest it knows up to this one. */
    private val apiRevision: Int = 1,
) {
    private val ids = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    /** Completed when the WebSocket opens: hylad sends no welcome before it answers requests. */
    private val opened = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)
    private var socket: WebSocket? = null

    private val finished = CompletableDeferred<Throwable?>()
    private val _events = MutableSharedFlow<JSONObject>(
        replay = 64,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<JSONObject> = _events

    suspend fun awaitClosed(): Throwable? = finished.await()

    suspend fun connect() {
        socket = http.newWebSocket(controlRequest(endpoint, token), listener())
        // withTimeout throws a CancellationException, which callers rightly pass through; a host
        // that never says hello is a failure to retry, not a cancellation.
        withTimeoutOrNull(connectTimeoutMs) { opened.await() }
            ?: throw HylaTimeoutException("No answer from hylad within ${connectTimeoutMs / 1000}s")
    }

    suspend fun call(method: String, payload: JSONObject? = null, timeoutMs: Long = 20_000): JSONObject {
        val ws = socket ?: throw IllegalStateException("Not connected to hylad")
        // A call waits for the connection to open first; one that never opens fails this call the
        // way it failed to open.
        if (!opened.isCompleted) {
            withTimeoutOrNull(timeoutMs) { opened.await() }
                ?: throw HylaTimeoutException("$method got no answer within ${timeoutMs / 1000}s")
        }
        val id = ids.incrementAndGet().toString()
        val waiter = CompletableDeferred<JSONObject>()
        pending[id] = waiter
        val msg = JSONObject().put("type", "request").put("id", id).put("method", method)
        if (payload != null) msg.put("payload", payload)
        if (!ws.send(msg.toString())) {
            pending.remove(id)
            // A connection that went away under a call is a drop, which the app says in its own
            // words; a socket that never opened is the failure that kept it from opening.
            throw closedError("The connection went away before the request was sent", null)
        }
        try {
            val response = withTimeoutOrNull(timeoutMs) { waiter.await() }
                ?: throw HylaTimeoutException("$method got no answer within ${timeoutMs / 1000}s")
            if (response.has("error") && !response.isNull("error")) {
                val err = response.getJSONObject("error")
                throw HylaCallException(
                    code = err.optString("code", "internal"),
                    status = err.optInt("status", 500),
                    message = err.optString("message", "request failed"),
                )
            }
            return response.optJSONObject("payload") ?: JSONObject()
        } finally {
            pending.remove(id)
        }
    }

    /**
     * Sends a request without waiting for the reply. Input uses this: the order of keystrokes
     * matters but their result does not, and awaiting each one would serialize a whole gesture
     * behind the network round trip.
     */
    fun send(method: String, payload: JSONObject? = null): Boolean {
        val ws = socket ?: return false
        // Ahead of the open this would be buffered by the WebSocket, rather than answered.
        if (!opened.isCompleted || opened.isCancelled) return false
        val id = ids.incrementAndGet().toString()
        val msg = JSONObject().put("type", "request").put("id", id).put("method", method)
        if (payload != null) msg.put("payload", payload)
        return ws.send(msg.toString())
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket?.close(1000, "bye")
        failPending(ConnectionDroppedException("Connection closed", null))
        if (!opened.isCompleted) opened.completeExceptionally(IllegalStateException("Connection closed"))
        finish(null)
    }

    private fun listener(): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val hello = JSONObject()
                .put("type", "hello")
                .put("api_revision", apiRevision)
                .put("api_min_revision", 0)
                .put(
                    "client",
                    JSONObject()
                        .put("kind", "app")
                        .put("platform", "other")
                        .put("name", "HylaPocket")
                        .put("version", "0.1.0"),
                )
            webSocket.send(hello.toString())
            if (!opened.isCompleted) opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = try {
                JSONObject(text)
            } catch (_: Exception) {
                return
            }
            when (obj.optString("type")) {
                "response" -> {
                    val id = obj.optString("id")
                    pending.remove(id)?.complete(obj)
                }
                "event" -> {
                    val payload = obj.optJSONObject("payload") ?: return
                    _events.tryEmit(payload)
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val err = closedError(reason.ifBlank { "Connection closed ($code)" }, null)
            failPending(err)
            if (!opened.isCompleted) opened.completeExceptionally(err)
            finish(err)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val err = closedError(response?.message?.takeIf { it.isNotBlank() } ?: t.message ?: "Connection failed", t)
            failPending(err)
            if (!opened.isCompleted) opened.completeExceptionally(err)
            finish(err)
        }
    }

    /** A connection that was up dropped; one that never opened failed for [cause]'s reason. */
    private fun closedError(message: String, cause: Throwable?): Exception =
        if (opened.isCompleted) ConnectionDroppedException(message, cause) else IllegalStateException(message, cause)

    private fun finish(err: Throwable?) {
        if (!finished.isCompleted) finished.complete(err)
    }

    private fun failPending(err: Throwable) {
        val left = pending.values.toList()
        pending.clear()
        left.forEach { if (!it.isCompleted) it.completeExceptionally(err) }
    }

    companion object {
        /** The control handshake: the bearer token, for a listener that takes one. */
        fun controlRequest(endpoint: Endpoint, token: String): Request = Request.Builder()
            .url(endpoint.ws("/api/control"))
            .header("Sec-WebSocket-Protocol", "rex.control.v1")
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .build()

        /**
         * A client whose sockets live as long as the connection: a ping every 30 seconds tells a
         * socket that died quietly from one that is idle.
         */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
}
