package app.hylapocket.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

enum class Phase { Idle, Connecting, Live, Reconnecting, Failed }

data class TermSize(val cols: Int, val rows: Int, val cellWidthPx: Int, val cellHeightPx: Int)

data class HylaState(
    val phase: Phase = Phase.Idle,
    /** Where the server this state belongs to is, shown while connecting: host:port, or a tailcat pair code. */
    val endpointLabel: String = "",
    /** Why the connection is not live right now; kept while the next attempt runs, cleared once live. */
    val problem: Problem? = null,
    /** The last action that failed while the connection was fine; the UI shows it for a moment. */
    val notice: Notice? = null,
    val sessions: List<ListedSession> = emptyList(),
    val activeSessionId: String? = null,
    val view: SessionView? = null,
    val views: Map<String, SessionView> = emptyMap(),
    /** What each pane's program says it is doing, summarized; kept current by hylad's events. */
    val blockStatus: Map<String, ProgramRecord> = emptyMap(),
    /** The shown pane's working directory. */
    val pwd: String? = null,
    /** What each pane runs, by name - an agent such as `pi`, an editor, or a shell. */
    val programs: Map<String, String> = emptyMap(),
    /** The title each pane's program set, such as `π - rexpocket`; absent until one is set. */
    val titles: Map<String, String> = emptyMap(),
    /** True while hylad has the shown pane at this phone's size: this phone is the one controlling it. */
    val owning: Boolean = false,
    /** The pane the phone shows in each session, for the list. */
    val places: Map<String, String> = emptyMap(),
)

/**
 * Phone-side session: bootstrap, sidebar model, and the shown pane's VT.
 * Owning the shown pane makes hylad use this phone's size, the same way a
 * terminal client takes the pane while it is on screen. Leaving disowns it.
 */
class HylaSession(
    private val endpoint: Endpoint,
    private val token: String,
    /** The list this host had last time, shown until the live one arrives with the bootstrap. */
    cachedSessions: List<ListedSession> = emptyList(),
    /** Diagnostics for the terminal stream and the calls a pane waits on; silent by default. */
    private val trace: (String) -> Unit = {},
    private val clientFactory: (Endpoint, String) -> HylaClient = { endpoint, token -> HylaClient(endpoint, token) },
    /** The client that opens each terminal stream: a tailcat host's sockets come from its transport. */
    private val streamHttp: (Endpoint) -> okhttp3.OkHttpClient = { HylaClient.defaultHttp() },
) {
    private val _state = MutableStateFlow(HylaState(sessions = cachedSessions))
    val state: StateFlow<HylaState> = _state

    private val clientRef = AtomicReference<HylaClient?>(null)
    private val sizeRef = AtomicReference(TermSize(80, 24, 8, 16))
    private var ownedBlock: String? = null
    /** Session [ownedBlock] lives in; it can differ from the active one right after a switch. */
    private var ownedSession: String? = null
    /** Serializes owning and disowning, so a release cannot be undone by a take that is in flight. */
    private val ownership = Mutex()

    /**
     * Each pane's status records and directory as hylad last reported them. hylad sends a change
     * as an event, so a pane is read once and then followed.
     */
    private val pwds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val foregrounds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val titleOf = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Each pane's status records, by pane, as hylad's follower last gave them. */
    private val statusRecords = java.util.concurrent.ConcurrentHashMap<String, Map<String, ProgramRecord>>()

    /** What each pane's program is, from its status or its foreground process; the status events carry one in their words. */
    private val statusPrograms = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The pane the phone shows in each session. hylad has one focus per session for the desktop
     * too, so following it would move the desktop onto the phone's pane. The phone's place is its
     * own; hylad's focus is left to the desktop.
     */
    private val places = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The size hylad was last given for the owned pane. */
    private var lastResize: TermSize? = null
    private var preferred: String? = null
    private val ready = CompletableDeferred<Unit>()
    private var runner: Job? = null
    @Volatile private var runnerScope: CoroutineScope? = null

    /** The pane streams being watched now, so coming back can hurry the ones that are redialing. */
    private val streams: MutableSet<TerminalStream> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * True while the control connection in use has told hylad which session this client is on, which
     * is what a data ticket and every pane call need. A pane that starts before that is refused,
     * and then waits out its backoff while the person looks at an empty pane.
     */
    private val attached = MutableStateFlow(false)

    /**
     * Every keystroke, paste and typed text, in the order it was typed. hylad runs `client.key` and
     * `block.event` in order but `terminal.write` whenever it arrives, so two writes sent without
     * waiting can swap places. One sender drains this queue and waits for each reply, which keeps
     * the order, and nothing else waits on it, so input never queues behind another call.
     */
    private val input = Channel<InputCall>(Channel.UNLIMITED)

    private class InputCall(val method: String, val payload: JSONObject) {
        val done = CompletableDeferred<Unit>()
    }

    /** Wakes the wait between connection attempts. */
    private val retry = Channel<Unit>(Channel.CONFLATED)
    private val noticeIds = java.util.concurrent.atomic.AtomicLong()

    /** False while nobody can see the screen; the loops that take and size panes wait for it. */
    private val active = MutableStateFlow(true)

    fun start(scope: CoroutineScope, preferredSessionId: String? = null): Job {
        preferred = preferredSessionId
        runnerScope = scope
        val job = scope.launch {
            var backoff = MIN_BACKOFF_MS
            while (isActive) {
                try {
                    runConnection()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!isActive) break
                    trace("connection ended: " + generateSequence(e as Throwable) { it.cause }.joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}" })
                    // A connection that was up starts over from the shortest wait.
                    if (_state.value.phase == Phase.Live) backoff = MIN_BACKOFF_MS
                    _state.update {
                        it.copy(
                            phase = if (it.phase == Phase.Live) Phase.Reconnecting else Phase.Connecting,
                            problem = e.toProblem(),
                        )
                    }
                    val woken = withTimeoutOrNull(backoff) { retry.receive() } != null
                    backoff = if (woken) MIN_BACKOFF_MS else (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
        runner = job
        return job
    }

    suspend fun awaitReady() {
        ready.await()
    }

    /**
     * Pauses the pane loops while the app is in the background, and checks the connection at once
     * when it comes back.
     *
     * Android cuts an app's network soon after it leaves the screen unless a foreground service
     * holds it, so the sockets are often dead on return - and a connection that died quietly looks
     * open until OkHttp's ping notices, half a minute later. Coming back therefore skips any
     * backoff, and checks a connection that still claims to be live with one quick call, replacing
     * it at once when that goes unanswered. That is the wait a pane used to sit through.
     */
    fun setActive(visible: Boolean) {
        val wasVisible = active.value
        active.value = visible
        if (!visible || wasVisible) return
        // A pane stream that is redialing may be stuck on a dial made while the network was cut.
        streams.forEach { it.retryNow() }
        if (_state.value.phase != Phase.Live) {
            // A dial in flight was made while the network may have been cut; start a fresh one.
            if (_state.value.phase == Phase.Connecting || _state.value.phase == Phase.Reconnecting) {
                clientRef.get()?.close()
            }
            retry.trySend(Unit)
            return
        }
        val client = clientRef.get() ?: return
        val scope = runnerScope ?: return
        scope.launch {
            val alive = runCatching { client.call("session.list", null, timeoutMs = LIVENESS_MS) }.isSuccess
            if (!alive && clientRef.get() === client) {
                trace("control connection did not answer after the app came back; reconnecting")
                reconnectNow()
            }
        }
    }

    /** Skips the rest of the wait before the next connection attempt. Nothing to skip while live. */
    fun retryNow() {
        if (_state.value.phase != Phase.Live) retry.trySend(Unit)
    }

    /**
     * Drops the connection and makes a new one at once: the network it ran on went away, and a
     * socket on a vanished network can take a minute of pings to notice. hylad starts a new one.
     */
    fun reconnectNow() {
        clientRef.get()?.close()
        // The pane streams ran on the same network, so they are as dead as the control connection.
        streams.forEach { it.reconnectNow() }
        retry.trySend(Unit)
    }

    fun note(problem: Problem) {
        val notice = Notice(noticeIds.incrementAndGet(), problem)
        _state.update { it.copy(notice = notice) }
    }

    fun dismissNotice(id: Long) {
        _state.update { if (it.notice?.id == id) it.copy(notice = null) else it }
    }

    /**
     * Tears the session down: cancel, then wait for the connection's NonCancellable finally to
     * disown its pane before the socket closes. Suspending, because that wait is a network round
     * trip and callers used to pay for it on the main thread.
     */
    suspend fun stop() {
        val job = runner
        runner = null
        job?.cancel()
        // Clearing clientRef first would skip the disown, so the join comes first.
        if (job != null) withTimeoutOrNull(4_000) { job.join() }
        clientRef.getAndSet(null)?.close()
        _state.update { it.copy(phase = Phase.Idle) }
    }

    /**
     * The size the screen fits. It is sent for the shown pane at once, rather than by the loop that
     * waits on it: after a stream reopens, the pane's size is the desktop's again, and only a new
     * resize makes hylad size the stream for this phone.
     */
    fun setSize(cols: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        val next = TermSize(
            cols.coerceIn(20, 400),
            rows.coerceIn(4, 200),
            cellWidthPx.coerceIn(1, 128),
            cellHeightPx.coerceIn(1, 128),
        )
        if (sizeRef.get() != next) sizeRef.set(next)
        if (ownedBlock != null) viewScope?.launch { resized(next) }
    }

    /**
     * Opens a session on [rememberedBlock], the pane the phone showed there last time, when it
     * still exists; otherwise on the pane hylad has focused. With [windowId] it opens on that tab
     * instead, on its remembered or focused pane. The pane is focused before the view is published,
     * so the terminal never shows another pane first, and one focus call moves the tab and the pane.
     */
    suspend fun openSession(sessionId: String, rememberedBlock: String? = null, windowId: String? = null) {
        preferred = sessionId
        // The pane left behind goes back to the desktop, or it stays at the phone's size.
        if (ownedSession != sessionId) disownQuietly()
        _state.update { it.copy(owning = false) }
        val payload = rpc("session.attach", JSONObject().put("session_id", sessionId))
        var view = parseView(payload.getJSONObject("view"))
        // The phone's own place: the asked-for tab, else where it was last time. hylad's focus is not
        // moved, so opening a tab here leaves the desktop on whatever it is showing.
        val pane = view.paneToShow(rememberedBlock ?: places[sessionId], windowId)
        if (pane != null) places[sessionId] = pane.id
        view = view.placedAt(places[sessionId])
        // applyView only replaces the shown view when this session is already active.
        // Opening another session has to force it, or the old view stays on screen.
        applyView(view)
        val shownPwd = view.focusedBlock()?.id?.let { pwds[it] }
        _state.update {
            it.copy(
                activeSessionId = sessionId,
                view = view,
                pwd = shownPwd,
                views = if (view.sessionId.isEmpty()) it.views else it.views + (view.sessionId to view),
                sessions = mergeSession(it.sessions, ListedSession(sessionId, view.label)),
            )
        }
    }

    suspend fun createShell(label: String?) {
        val body = JSONObject()
        if (!label.isNullOrBlank()) body.put("label", label.trim())
        val payload = rpc("session.create", body)
        val id = payload.getString("session_id")
        val createdLabel = label?.trim().takeUnless { it.isNullOrEmpty() } ?: id
        _state.update { it.copy(sessions = mergeSession(it.sessions, ListedSession(id, createdLabel))) }
        openSession(id)
    }

    /** Makes a tab and shows it on the phone; the desktop stays on the tab it has. */
    suspend fun newWindow() {
        val sid = requireSession()
        val made = rpc(
            "session.new_window",
            JSONObject().put("session_id", sid),
        )
        val block = made.optJSONArray("block_ids")?.optString(0)?.takeIf { it.isNotEmpty() } ?: return
        places[sid] = block
        // The view event that announces the tab may already have come; this one surely has it.
        refreshView(sid)
    }

    suspend fun split(vertical: Boolean) {
        val sid = requireSession()
        val anchor = _state.value.view?.focusedBlock()?.id
        val body = JSONObject()
            .put("session_id", sid)
            .put("direction", if (vertical) "vertical" else "horizontal")
            .put("side", "after")
            .put("ratio", 0.5)
        if (anchor != null) body.put("anchor_id", anchor)
        val made = rpc("session.new_split", body)
        // The new pane is shown here; the desktop keeps the focus it has.
        val block = made.optJSONArray("block_ids")?.optString(0)?.takeIf { it.isNotEmpty() } ?: return
        places[sid] = block
        refreshView(sid)
    }

    /**
     * Shows a pane, and so its tab, on the phone. hylad's focus is the desktop's and is not touched:
     * moving it made the desktop follow the phone onto the same pane, and the two then took that
     * pane from each other, resizing it every time.
     */
    suspend fun focusBlock(blockId: String) {
        val sid = requireSession()
        places[sid] = blockId
        showLocally { it.placedAt(blockId) }
    }

    /** Shows a tab on the phone, on the pane the phone last showed there or the tab's own focus. */
    suspend fun focusWindow(windowId: String) {
        val sid = requireSession()
        val view = _state.value.view ?: return
        val pane = view.paneToShow(places[sid], windowId) ?: return
        focusBlock(pane.id)
    }

    /** Closes a pane, the shown one by default. Closing a tab's last pane closes the tab. */
    suspend fun closeBlock(blockId: String? = null) {
        val sid = requireSession()
        val block = blockId ?: _state.value.view?.focusedBlock()?.id ?: return
        rpc("block.close", JSONObject().put("session_id", sid).put("block_id", block))
    }

    /** Closes a tab and every pane in it, the shown tab by default. */
    suspend fun closeWindow(windowId: String? = null) {
        val sid = requireSession()
        val window = windowId ?: _state.value.view?.activeWindow()?.id
        val body = JSONObject().put("session_id", sid)
        if (window != null) body.put("window_id", window)
        rpc("session.close_window", body)
    }

    suspend fun destroySession() {
        val sid = requireSession()
        rpc("session.destroy", JSONObject().put("session_id", sid))
        _state.update {
            val rest = it.sessions.filter { s -> s.id != sid }
            it.copy(
                sessions = rest,
                activeSessionId = rest.firstOrNull()?.id,
                view = null,
            )
        }
        // Its blocks are gone with it; there is nothing left to disown.
        ownedBlock = null
        ownedSession = null
        val next = _state.value.activeSessionId
        if (next != null) openSession(next)
    }

    suspend fun renameSession(label: String) {
        val sid = requireSession()
        rpc("session.set_label", JSONObject().put("session_id", sid).put("label", label))
    }

    suspend fun renameWindow(label: String, windowId: String? = null) {
        val sid = requireSession()
        val window = windowId ?: _state.value.view?.activeWindow()?.id ?: return
        rpc(
            "session.set_window_label",
            JSONObject().put("session_id", sid).put("window_id", window).put("label", label),
        )
    }

    suspend fun renameBlock(label: String, blockId: String? = null) {
        val sid = requireSession()
        val block = blockId ?: _state.value.view?.focusedBlock()?.id ?: return
        rpc(
            "session.set_block_label",
            JSONObject().put("session_id", sid).put("block_id", block).put("label", label),
        )
    }

    /** Types [text] into the shown pane, after everything typed before it. */
    suspend fun sendText(text: String) {
        if (text.isEmpty()) return
        input(write(text)).await()
    }

    /**
     * Keystrokes and mouse reports from the terminal view arrive as UTF-8 bytes. They join the
     * input queue without waiting, so a swipe is never throttled by the caller.
     */
    fun writeBytes(data: ByteArray) {
        if (data.isEmpty()) return
        input(write(String(data, Charsets.UTF_8)))
    }

    /** [writeBytes], with a way to wait until hylad has the bytes; for tests. */
    internal fun writeBytesAwaitable(data: ByteArray): CompletableDeferred<Unit> = input(write(String(data, Charsets.UTF_8)))

    /**
     * Pastes [text] into the shown pane. hylad takes it as the bytes a terminal reads, so it goes
     * through the same queue as typing; the paste itself is plain text, and every line's return
     * arrives with it, which is what a paste of several lines does.
     */
    suspend fun paste(text: String) {
        if (text.isEmpty()) return
        sendText(text)
    }

    suspend fun sendKey(
        code: String,
        mods: String = "",
        text: String = "",
        codepoint: Int = text.firstCodePoint(),
        consumedMods: String = "",
    ) {
        val (sid, block) = requireBlock()
        input(
            InputCall(
                "client.key",
                JSONObject()
                    .put("session_id", sid)
                    .put("block_id", block)
                    .put(
                        "key",
                        JSONObject()
                            .put("key", code)
                            .put("mods", mods)
                            .put("consumed_mods", consumedMods)
                            .put("text", text)
                            .put("unshifted_codepoint", codepoint)
                            .put("composing", false)
                            .put("action", "press"),
                    ),
            ),
        ).await()
    }

    private fun write(text: String): InputCall {
        val (sid, block) = requireBlock()
        return InputCall(
            WRITE_METHOD,
            JSONObject().put("session_id", sid).put("block_id", block).put("args", JSONObject().put("data", text)),
        )
    }

    /** Queues [call] behind everything typed before it; the result completes once hylad has it. */
    private fun input(call: InputCall): CompletableDeferred<Unit> {
        if (clientRef.get() == null) throw NotReadyException("Not connected yet")
        input.trySend(call)
        return call.done
    }

    /**
     * Sends queued input one call at a time, each after the reply to the one before. Writes to the
     * same pane that queued up while the last call was out go together as one write: a swipe over a
     * full-screen program makes a report per row, and one round trip each held a fling for seconds
     * over a tailnet. The bytes keep their order, so this changes nothing the program sees.
     */
    private suspend fun inputLoop(client: HylaClient) {
        var held: InputCall? = null
        while (true) {
            val first = held ?: input.receive()
            held = null
            val batch = arrayListOf(first)
            if (first.isWrite()) {
                while (true) {
                    val next = input.tryReceive().getOrNull() ?: break
                    if (next.isWrite() && next.target() == first.target() && batch.dataLength() < MAX_WRITE_BATCH) {
                        batch += next
                    } else {
                        // A key or a paste, or another pane: it goes after this batch, in its turn.
                        held = next
                        break
                    }
                }
            }
            val payload = if (batch.size == 1) first.payload else first.withData(batch.joinToString("") { it.data() })
            try {
                client.call(first.method, payload)
                batch.forEach { it.done.complete(Unit) }
            } catch (e: CancellationException) {
                batch.forEach { it.done.completeExceptionally(e) }
                held?.done?.completeExceptionally(e)
                throw e
            } catch (e: Exception) {
                batch.forEach { it.done.completeExceptionally(e) }
            }
        }
    }

    private fun InputCall.isWrite() = method == WRITE_METHOD

    private fun InputCall.target() = payload.optString("session_id") to payload.optString("block_id")

    private fun InputCall.data(): String = payload.optJSONObject("args")?.optString("data").orEmpty()

    private fun InputCall.withData(data: String): JSONObject =
        JSONObject(payload.toString()).put("args", JSONObject().put("data", data))

    private fun List<InputCall>.dataLength() = sumOf { it.data().length }

    /** The scope the connection's jobs run in, for calls that come from a click rather than a loop. */
    @Volatile private var viewScope: CoroutineScope? = null

    private suspend fun runConnection() {
        val client = clientFactory(endpoint, token)
        clientRef.set(client)
        // The last failure stays on screen while the next attempt runs: a picker that only says
        // "connecting" while a tunnel is down gives no way to tell retrying from hanging.
        _state.update {
            it.copy(phase = Phase.Connecting, endpointLabel = "${endpoint.host}:${endpoint.port}")
        }
        try {
            client.connect()
            coroutineScope {
                viewScope = this
                launch {
                    client.events.collect { event ->
                        try {
                            onEvent(event)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            note(e.toProblem())
                        }
                    }
                }
                // bootstrap is what attaches this connection to its session; only after it are pane
                // calls taken, and a stream that waits for a live connection waits for the session
                // too. Panes that came back meanwhile start watching at once.
                bootstrap()
                if (!ready.isCompleted) ready.complete(Unit)
                attached.value = true
                _state.update { it.copy(phase = Phase.Live, problem = null) }
                streams.forEach { it.retryNow() }
                launch { inputLoop(client) }
                launch { loadSessionViews(_state.value.sessions.map { it.id }, skip = _state.value.activeSessionId) }
                val cause = client.awaitClosed()
                throw ConnectionDroppedException(cause?.message ?: "Control connection dropped", cause)
            }
        } finally {
            attached.value = false
            viewScope = null
            withContext(NonCancellable) {
                try {
                    disownQuietly()
                } catch (_: Exception) {
                }
            }
            client.close()
            if (clientRef.get() === client) clientRef.set(null)
            // Input typed for this connection is not replayed on the next one: the pane may be gone.
            while (true) {
                val left = input.tryReceive().getOrNull() ?: break
                left.done.completeExceptionally(ConnectionDroppedException("Connection dropped before the input was sent", null))
            }
            ownedBlock = null
            lastResize = null
        }
    }

    private suspend fun bootstrap() {
        // 'all' adds block events - a program's status, the shell's directory - which the screen
        // would otherwise have to poll for, one round trip each.
        val body = JSONObject().put("event_scope", "all")
        val preferredId = preferred
        if (!preferredId.isNullOrBlank()) body.put("preferred_session_id", preferredId)
        val payload = rpc("session.bootstrap", body)
        val sessions = parseSessions(payload.optJSONObject("attach_all")?.optJSONArray("sessions"))
        val selected = payload.optJSONObject("selected_session")
        val view = selected?.optJSONObject("view")?.let { parseView(it) }?.let { it.placedAt(places[it.sessionId]) }
        val active = view?.sessionId ?: selected?.optJSONObject("session")?.optNullableString("session_id")
        _state.update {
            it.copy(
                sessions = sessions,
                activeSessionId = active,
                view = view,
                views = if (view != null && view.sessionId.isNotEmpty()) it.views + (view.sessionId to view) else it.views,
            )
        }
        preferred = active
    }

    /**
     * Fetches the views of [ids], so the home can name their tabs before they are opened. They are
     * asked for together and after the connection is live: one at a time in front of it, a tailnet
     * with a second of round trip held the whole app at *Connecting* for a second per session.
     * session.view neither attaches nor moves the desktop's focus.
     */
    private suspend fun loadSessionViews(ids: List<String>, skip: String? = null) = coroutineScope {
        for (id in ids) {
            if (id == skip) continue
            launch { runCatching { refreshView(id) } }
        }
    }

    private suspend fun onEvent(event: JSONObject) {
        when (event.optString("type")) {
            "session_created" -> {
                val session = event.optJSONObject("payload")?.optJSONObject("session") ?: return
                val id = session.optNullableString("session_id") ?: return
                val label = session.optNullableString("label") ?: shortId(id)
                _state.update { it.copy(sessions = mergeSession(it.sessions, ListedSession(id, label))) }
                runCatching { refreshView(id) }
            }
            "session_destroyed" -> {
                val id = event.optJSONObject("payload")?.optNullableString("session_id")
                    ?: event.optNullableString("session_id")
                    ?: return
                dropSession(id)
            }
            "session_moved" -> refreshSessionList()
            "session_event" -> {
                val payload = event.optJSONObject("payload") ?: return
                val sid = payload.optNullableString("session_id") ?: return
                val inner = payload.optJSONObject("event") ?: return
                onSessionEvent(sid, inner)
            }
        }
    }

    private suspend fun onSessionEvent(sid: String, event: JSONObject) {
        val payload = event.optJSONObject("payload") ?: JSONObject()
        when (event.optString("type")) {
            "session_view_changed" -> {
                val revision = payload.optLong("revision")
                val known = _state.value.views[sid]?.revision ?: -1
                if (revision > known) refreshView(sid)
            }
            "session_label_changed" -> {
                val label = payload.optNullableString("label") ?: return
                _state.update {
                    it.copy(
                        sessions = it.sessions.map { s -> if (s.id == sid) s.copy(label = label) else s },
                        view = if (it.view?.sessionId == sid) it.view.copy(label = label) else it.view,
                    )
                }
            }
            "session_destroyed" -> dropSession(sid)
            "block_event" -> onBlockEvent(payload)
            // Which client owns a pane decides its size, so a change here redraws it as owned or not.
            "block_owner_changed", "block_closed", "window_closed", "window_created", "active_window_changed" ->
                refreshView(sid)
        }
    }

    /** A pane's own news: a program's status, or the directory its shell moved to. */
    /**
     * What hylad's follower says of a pane: its status, summarized the way rexpocket reads one, and
     * the pane closing. hylad sends a pane's whole status after each change of it, so each event
     * replaces the last, rather than being a record to merge.
     */
    private fun onBlockEvent(event: JSONObject) {
        val block = event.optNullableString("block_id") ?: return
        when (event.optString("type")) {
            "block_status" -> {
                val state = event.optNullableString("state") ?: ""
                statusRecords[block] = when {
                    state.isEmpty() -> emptyMap()
                    else -> mapOf(
                        BLOCK_RECORD to ProgramRecord(
                            id = BLOCK_RECORD,
                            state = state,
                            kind = event.optNullableString("kind"),
                            app = statusPrograms[block] ?: foregrounds[block],
                            title = null,
                            msg = event.optNullableString("message"),
                            progress = event.optInt("progress").takeIf { it > 0 },
                        ),
                    )
                }
                publishStatus()
            }
            "pane_closed" -> {
                statusRecords -= block
                foregrounds -= block
                pwds -= block
                publishStatus()
            }
        }
    }

    private fun publishStatus() {
        _state.update { st ->
            val summary = LinkedHashMap<String, ProgramRecord>()
            for ((block, records) in statusRecords) summarizeStatus(records.values.toList())?.let { summary[block] = it }
            // A program that reported itself names the pane; otherwise the foreground process does.
            val programs = LinkedHashMap<String, String>()
            for (block in statusRecords.keys + foregrounds.keys) {
                val program = paneProgram(summary[block], foregrounds[block])
                if (program == null) statusPrograms -= block else statusPrograms[block] = program
                programs[block] = program ?: ""
                if (program == null) programs -= block
            }
            st.copy(
                blockStatus = summary,
                pwd = st.view?.focusedBlock()?.id?.let { pwds[it] },
                programs = programs,
                titles = HashMap(titleOf),
                places = HashMap(places),
            )
        }
    }

    private fun dropSession(id: String) {
        _state.update {
            val rest = it.sessions.filter { s -> s.id != id }
            val view = if (it.view?.sessionId == id) null else it.view
            it.copy(
                sessions = rest,
                views = it.views - id,
                view = view,
                activeSessionId = if (it.activeSessionId == id) rest.firstOrNull()?.id else it.activeSessionId,
            )
        }
    }

    private suspend fun refreshSessionList() {
        val payload = rpc("session.list", null)
        val sessions = parseSessions(payload.optJSONArray("sessions"))
        _state.update { it.copy(sessions = sessions) }
        val known = _state.value.views.keys
        loadSessionViews(sessions.map { it.id }.filter { it !in known }, skip = _state.value.activeSessionId)
    }

    private suspend fun refreshView(sid: String) {
        val payload = rpc("session.view", JSONObject().put("session_id", sid))
        applyView(parseView(payload))
    }

    /**
     * Mirrors a focus change the server just accepted. The change carries the revision the call
     * returned, so a view fetched before it cannot overwrite it, while any later change still does.
     */
    private fun showLocally(change: (SessionView) -> SessionView?) {
        _state.update { st ->
            val next = st.view?.let(change) ?: return@update st
            // The directory belongs to the pane on screen, so it moves with the focus.
            val pwd = next.focusedBlock()?.id?.let { pwds[it] }
            st.copy(view = next, views = st.views + (next.sessionId to next), pwd = pwd)
        }
    }

    private fun applyView(fromServer: SessionView) {
        if (fromServer.sessionId.isEmpty()) return
        // A pane the phone was on that has closed gives the place back to hylad's choice.
        val place = places[fromServer.sessionId]
        if (place != null && fromServer.allBlocks().none { it.id == place }) places.remove(fromServer.sessionId)
        val view = fromServer.placedAt(places[fromServer.sessionId])
        _state.update {
            val known = it.views[view.sessionId]?.revision ?: -1
            if (view.revision < known) return@update it
            val views = it.views + (view.sessionId to view)
            val active = it.activeSessionId == view.sessionId || it.activeSessionId == null
            it.copy(
                views = views,
                view = if (active) view else it.view,
                activeSessionId = it.activeSessionId ?: view.sessionId,
                sessions = mergeSession(it.sessions, ListedSession(view.sessionId, view.label)),
            )
        }
    }

    /**
     * The shown pane's bytes, for as long as this flow is collected: hylad's snapshot of it first,
     * then its output as it comes. Taking the pane, so hylad sizes it for this phone, and sizing it
     * happen here too, because all three belong to watching it. Ending the collection stops it.
     */
    fun watchPane(blockId: String): Flow<TerminalFeed> = flow {
        var backoff = MIN_BACKOFF_MS
        while (true) {
            // A data connection needs a ticket from the control connection: while that one is down,
            // asking for a pane only spins. This waits for it to come back.
            val live = _state.first { it.phase == Phase.Live }
            val sid = live.activeSessionId
            if (sid == null) {
                // Nothing to watch until a session is open; the connection may still be coming up.
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(PANE_RETRY_MAX_MS)
                continue
            }
            try {
                coroutineScope {
                    // The picture is what the person is waiting for, so its stream is asked for at
                    // once; taking the pane and sizing it run beside it rather than in front of it.
                    // All three wait for the connection to be attached, though: that is what a
                    // ticket and a pane call need, and a refusal here costs the stream its whole
                    // retry cycle - the wait a pane used to sit through after the app came back.
                    attached.first { it }
                    launch {
                        // A pane is taken on sight.
                        ensureOwned(sid, blockId)
                        launch { sizeLoop(sid, blockId) }
                        ownershipLoop(sid, blockId)
                    }
                    val stream = TerminalStream(endpoint, token, { streamHttp(endpoint) }, trace) { ticket(sid, blockId) }
                    streams += stream
                    try {
                        stream.feeds().collect { emit(it) }
                    } finally {
                        streams -= stream
                        stream.close()
                    }
                }
            } catch (e: CancellationException) {
                trace("pane $blockId: no longer watched")
                throw e
            } catch (e: HylaCallException) {
                trace("pane $blockId: ${e.code} ${e.message}")
                // The pane or its session is gone; watching it again cannot help.
                if (e.code == "not_found" || e.code == "session_not_found") {
                    note(e.toProblem())
                    return@flow
                }
                note(e.toProblem())
            } catch (e: Exception) {
                trace("pane $blockId: ${e.javaClass.simpleName}: ${e.message}")
                note(e.toProblem())
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(PANE_RETRY_MAX_MS)
        }
    }

    /**
     * Applies the phone's size to the pane while this connection owns it. The owned pane is the
     * size's target, because only the pane on screen has hylad's size follow this phone's.
     */
    private suspend fun sizeLoop(sid: String, blockId: String) {
        while (true) {
            while (ownedBlock != blockId || ownedSession != sid) delay(RESIZE_WAIT_MS)
            if (lastResize != sizeRef.get()) resized(sizeRef.get())
            delay(RESIZE_WAIT_MS)
        }
    }

    /**
     * Sends [size] for the owned pane. Nothing else starts the sender again, so a failure does not
     * end it: a call cut short by a dropped connection goes unsent, and the size is sent again once
     * the next connection owns the pane. A call hylad turned down is tried again in a moment.
     */
    private suspend fun resized(size: TermSize) {
        val block = ownedBlock ?: return
        val sid = ownedSession ?: return
        try {
            rpc(RESIZE_METHOD, resizePayload(sid, block, size))
            lastResize = size
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (attached.value) delay(RESIZE_RETRY_MS) else attached.first { it }
            resized(size)
        }
    }

    private fun resizePayload(sid: String, block: String, size: TermSize) = JSONObject()
        .put("session_id", sid)
        .put("block_id", block)
        .put(
            "args",
            JSONObject()
                .put("columns", size.cols)
                .put("rows", size.rows)
                .put("cell_width_px", size.cellWidthPx)
                .put("cell_height_px", size.cellHeightPx),
        )

    /**
     * A one-time ticket for a new stream on this pane, once the connection has attached: asked for
     * before that, hylad refuses it, and the stream then waits out its backoff while the pane stays
     * empty - the wait a pane used to sit through after the app came back.
     */
    private suspend fun ticket(sid: String, blockId: String): Triple<String, String, String> {
        attached.first { it }
        val payload = rpc(
            "data.connect",
            JSONObject()
                .put("session_id", sid)
                .put("block_id", blockId)
                .put("data_ref_id", "terminal")
                .put("subprotocol", TerminalStream.PROTOCOL),
        )
        return Triple(sid, blockId, payload.getString("ticket"))
    }

    private suspend fun ensureOwned(sid: String, block: String) {
        ownership.withLock {
            if (ownedBlock == block && ownedSession == sid) {
                markOwned()
                return@withLock
            }
            // The pane left behind goes back to the desktop before the new one is taken.
            val previous = ownedBlock
            val previousSession = ownedSession
            if (previous != null && previous != block) {
                rpc(
                    "session.disown_block",
                    JSONObject().put("session_id", previousSession ?: sid).put("block_ids", JSONArray().put(previous)),
                )
                ownedBlock = null
                ownedSession = null
            }
            takePaneLocked(sid, block)
        }
    }

    /** Takes the pane, after which hylad applies this phone's size to it instead of the old owner's. */
    private suspend fun takePane(sid: String, block: String) {
        ownership.withLock { takePaneLocked(sid, block) }
    }

    /** Caller holds [ownership]. */
    private suspend fun takePaneLocked(sid: String, block: String) {
        rpc(
            "session.own_block",
            JSONObject()
                .put("session_id", sid)
                .put("block_ids", JSONArray().put(block))
                .put("mode", "take"),
        )
        ownedBlock = block
        ownedSession = sid
        // A new take asks for its size again: hylad sizes the pane for the client that took it.
        lastResize = null
        markOwned()
    }

    private fun markOwned() {
        _state.update { it.copy(owning = true) }
    }

    /**
     * Keeps the pane's ownership honest. Hyla sizes a pane for the terminal that types in it: taking
     * it here is what makes the phone's size the pane's. hylad answers no question about an owner,
     * and it takes no pane back, so the phone holds the one it shows until it disowns it.
     */
    private suspend fun ownershipLoop(sid: String, blockId: String) {
        while (true) {
            active.first { it }
            if (ownedBlock != blockId || ownedSession != sid) {
                // A take that fails, such as while the connection is down, is tried again next time.
                runCatching { takePane(sid, blockId) }
            }
            delay(OWNERSHIP_CHECK_MS)
        }
    }

    private suspend fun disownQuietly() {
        val block = ownedBlock ?: return
        val sid = ownedSession ?: return
        val client = clientRef.get() ?: return
        try {
            client.call(
                "session.disown_block",
                JSONObject().put("session_id", sid).put("block_ids", JSONArray().put(block)),
            )
        } catch (_: Exception) {
        }
        ownedBlock = null
        ownedSession = null
    }

    /**
     * One call on the control connection. Calls are not queued behind each other: hylad runs them
     * side by side and matches each answer to its request, so over a tailnet with a second of round
     * trip, a pane opening no longer waits for whatever else the app was asking. A caller that
     * depends on an earlier answer awaits it, as every sequence here does; only typed input has an
     * order hylad must keep, and it has its own queue.
     */
    private suspend fun rpc(method: String, payload: JSONObject?): JSONObject {
        val client = clientRef.get() ?: throw NotReadyException("Not connected yet")
        val sent = System.nanoTime()
        try {
            return client.call(method, payload)
        } finally {
            trace("rpc $method took ${(System.nanoTime() - sent) / 1_000_000}ms")
        }
    }

    private fun requireSession(): String =
        _state.value.activeSessionId ?: throw NotReadyException("No open session")

    private fun requireBlock(): Pair<String, String> {
        val sid = requireSession()
        val block = _state.value.view?.focusedBlock()?.id ?: throw NotReadyException("No terminal in this window")
        return sid to block
    }

    private fun mergeSession(list: List<ListedSession>, item: ListedSession): List<ListedSession> {
        val idx = list.indexOfFirst { it.id == item.id }
        if (idx < 0) return list + item
        return list.map { if (it.id == item.id) item else it }
    }
}

/** First wait before a connection attempt is retried, and the wait after a connection that was up. */
private const val MIN_BACKOFF_MS = 400L

private const val MAX_BACKOFF_MS = 8_000L

/** How long to wait before taking a pane the first time, and the longest wait after retries. */
/** How often a watched pane checks that this connection still owns it. */
private const val OWNERSHIP_CHECK_MS = 3_000L

/** Wait before a resize hylad turned down is sent again. */
private const val RESIZE_RETRY_MS = 1_000L

/** How often the size of the owned pane is compared with the screen's. */
private const val RESIZE_WAIT_MS = 100L

/** Wait before the screen tries its pane again, after the connection or the stream let go. */
private const val PANE_RETRY_MAX_MS = 5_000L

/**
 * How long a call may take when the app comes back before its connection counts as dead. A tailnet
 * relay abroad answers in well under a second, so this leaves room for a slow path but not for the
 * half-minute a dead socket takes to be noticed otherwise.
 */
private const val LIVENESS_MS = 4_000L

private const val WRITE_METHOD = "com.superlogical.terminal.write"

private const val RESIZE_METHOD = "com.superlogical.terminal.resize"

/** hylad says a pane's status whole, so it is one record under this id. */
private const val BLOCK_RECORD = "status"

/**
 * Most characters one combined write carries. hylad takes a control message of up to 64 KiB, and a
 * character can take a few bytes of UTF-8 and JSON escaping, so this stays well under it.
 */
private const val MAX_WRITE_BATCH = 8_000

private fun String.firstCodePoint(): Int =
    if (isEmpty()) 0 else codePointAt(0)
