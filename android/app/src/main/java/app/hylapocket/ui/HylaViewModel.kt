package app.hylapocket.ui

import android.app.Application
import android.graphics.Typeface
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.hylapocket.core.Endpoint
import app.hylapocket.core.HylaClient
import app.hylapocket.core.addressText
import app.hylapocket.core.parseHostAddress
import app.hylapocket.core.HylaSession
import app.hylapocket.core.HylaState
import app.hylapocket.core.ListedSession
import app.hylapocket.core.Phase
import app.hylapocket.core.TerminalFeed
import app.hylapocket.BuildConfig
import app.hylapocket.core.toProblem
import app.hylapocket.data.FontFiles
import app.hylapocket.data.HostStore
import app.hylapocket.data.SavedHost
import app.hylapocket.data.TerminalFontState
import app.hylapocket.data.downloadedTypeface
import app.hylapocket.tailcat.tailcatHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// The app's state: the saved hosts, the one connection the screens share, and the font.

/** Which screen is on: the list of hosts, or a terminal. */
sealed interface Route {
    data object Home : Route
    data object Term : Route
}

/** What the list is waiting on: a session being opened, or one being made. */
sealed interface Opening {
    data class Existing(val id: String) : Opening
    data object New : Opening
}

class HylaViewModel(app: Application) : AndroidViewModel(app) {
    private val store = HostStore(app)

    val hosts = MutableStateFlow(store.list())
    val route = MutableStateFlow<Route>(Route.Home)
    private val _hyla = MutableStateFlow(HylaState())
    val hyla: StateFlow<HylaState> = _hyla
    val fontScale = MutableStateFlow(store.fontScale())

    private val fontFiles = FontFiles(File(app.filesDir, "terminal-fonts"))
    val terminalTypeface = MutableStateFlow(SystemTypeface)
    internal val terminalFont = MutableStateFlow(TerminalFontState())

    /** The host the connection is for, while there is one. */
    var host: SavedHost? = null
        private set

    private var session: HylaSession? = null
    private var foreground = true
    private var connectJob: Job? = null
    private var watch: Job? = null
    private var sessionJob: Job? = null

    private val _opening = MutableStateFlow<Opening?>(null)
    val opening: StateFlow<Opening?> = _opening

    /** Becomes the connection's session once it exists, so a tap before that can wait for it. */
    private var sessionReady = CompletableDeferred<HylaSession>()

    private val network = NetworkWatcher(app) {
        viewModelScope.launch {
            session?.reconnectNow()
            // The host's listener may have come back on a new address: a dial that went nowhere
            // waits out its timeout, so a fresh one starts now.
            _hyla.value.phase.takeIf { it != Phase.Live }?.let { retryNow() }
        }
    }.also { it.start() }

    init {
        viewModelScope.launch {
            val face = withContext(Dispatchers.IO) {
                runCatching { if (fontFiles.available()) downloadedTypeface(fontFiles.directory) else null }.getOrNull()
            }
            val selected = store.downloadedFont() && face != null
            if (selected) terminalTypeface.value = face
            store.setDownloadedFont(selected)
            terminalFont.value = TerminalFontState(selected = selected, available = face != null, initializing = false)
        }
        viewModelScope.launch {
            hyla.map { it.phase to it.problem }.distinctUntilChanged().collect { (phase, problem) ->
                Log.i(CONN_TAG, "phase $phase" + (problem?.let { " · ${it.trouble}${it.detail?.let { d -> ": $d" } ?: ""}" } ?: ""))
            }
            hyla.mapNotNull { it.notice }.distinctUntilChanged().collect { notice ->
                Log.i(CONN_TAG, "action failed: ${notice.problem.trouble}: ${notice.problem.detail}")
            }
        }
    }

    override fun onCleared() {
        network.stop()
        disconnectQuiet()
        super.onCleared()
    }

    /** Downloads JetBrains Mono, or goes back to the system's monospace. */
    internal fun selectTerminalFont(downloaded: Boolean) {
        if (terminalFont.value.initializing || terminalFont.value.downloading) return
        if (!downloaded) {
            terminalTypeface.value = SystemTypeface
            store.setDownloadedFont(false)
            terminalFont.value = terminalFont.value.copy(selected = false, failed = false)
            return
        }
        terminalFont.value = terminalFont.value.copy(downloading = true, failed = false, progress = 0f)
        viewModelScope.launch {
            try {
                val face = withContext(Dispatchers.IO) {
                    val files = fontFiles.install(progress = { progress ->
                        terminalFont.value = terminalFont.value.copy(progress = progress)
                    })
                    downloadedTypeface(files)
                }
                terminalTypeface.value = face
                store.setDownloadedFont(true)
                terminalFont.value = terminalFont.value.copy(selected = true, available = true, downloading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                terminalFont.value = terminalFont.value.copy(downloading = false, failed = true)
            }
        }
    }

    fun saveHost(host: SavedHost) {
        store.upsert(host)
        hosts.value = store.list()
    }

    fun deleteHost(id: String) {
        store.delete(id)
        hosts.value = store.list()
        if (host?.id == id) disconnect()
    }

    /**
     * Connects to [target], and with [resume] goes back to the session and pane that were on screen.
     * A plain connect stops at the session list.
     */
    fun connect(target: SavedHost, resume: Boolean = false) {
        disconnectQuiet()
        host = target
        store.setLastHost(target.id)
        val previous = store.lastSession(target.id)
        route.value = Route.Home
        val cached = if (resume) emptyList() else store.cachedSessions(target.id)
        _hyla.value = HylaState(
            phase = Phase.Connecting,
            endpointLabel = target.endpoint().addressText(),
            sessions = cached,
        )
        connectJob = viewModelScope.launch {
            val s = HylaSession(
                target.endpoint(),
                target.token,
                cachedSessions = cached,
                trace = { if (BuildConfig.DEBUG) Log.i(CONN_TAG, it) },
                // A tailcat host is reached through its transport, which speaks tailcat under the
                // sockets OkHttp asks for; a network host's sockets go straight out.
                clientFactory = { endpoint, token ->
                    HylaClient(endpoint, token, http = if (endpoint.tailcat) tailcatHttp(endpoint.pairCode!!) else HylaClient.defaultHttp())
                },
                streamHttp = { if (it.tailcat) tailcatHttp(it.pairCode!!) else HylaClient.defaultHttp() },
            )
            session = s
            sessionReady.complete(s)
            s.setActive(foreground)
            sessionJob = s.start(viewModelScope, previous)
            watch = viewModelScope.launch { follow(s, target.id, if (resume) previous else null) }
        }
    }

    /**
     * Everything that follows the connection's state, one flow per job: the screen shows every
     * state, and the store only hears about what changed. Ends with the connection.
     */
    private suspend fun follow(s: HylaSession, hostId: String, resumeTo: String?) = kotlinx.coroutines.coroutineScope {
        launch { s.state.collect { _hyla.value = it } }
        launch {
            s.state.mapNotNull { it.activeSessionId }.distinctUntilChanged()
                .collect { store.setLastSession(hostId, it) }
        }
        launch {
            s.state.map { it.sessions }.filter { it.isNotEmpty() }.distinctUntilChanged()
                .collect { store.setCachedSessions(hostId, it) }
        }
        // Only a pane that was on the terminal screen counts. Connecting already selects a session,
        // and its focus must not replace the pane remembered for it.
        launch {
            combine(s.state, route) { st, r -> if (r == Route.Term) shownPane(st) else null }
                .filterNotNull().distinctUntilChanged()
                .collect { (sid, block) -> store.setLastPane(hostId, sid, block, s.state.value.sessions.map { it.id }) }
        }
        // A session no longer on the host, such as after hylad restarted, leaves nothing to show:
        // back to the list rather than an empty terminal.
        launch {
            s.state.map { it.phase to it.activeSessionId }.distinctUntilChanged().collect { (phase, active) ->
                if (phase == Phase.Live && active == null && route.value == Route.Term) route.value = Route.Home
            }
        }
        if (resumeTo != null) {
            launch {
                s.state.first { it.phase == Phase.Live }
                resumeInto(s, resumeTo)
            }
        }
    }

    private fun shownPane(st: HylaState): Pair<String, String>? {
        val sid = st.activeSessionId ?: return null
        val block = st.view?.takeIf { it.sessionId == sid }?.focusedBlock()?.id ?: return null
        return sid to block
    }

    /** Reopens the remembered session on its remembered pane; stays on the list if it is gone. */
    private suspend fun resumeInto(s: HylaSession, sessionId: String?) {
        val target = sessionId?.takeIf { id -> s.state.value.sessions.any { it.id == id } } ?: return
        try {
            s.openSession(target, rememberedPane(target))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        route.value = Route.Term
    }

    private fun rememberedPane(sessionId: String): String? = host?.let { store.lastPane(it.id, sessionId) }

    /** Opens a session where the phone left it, or where the desktop is. */
    fun openSession(id: String) = enterSession(Opening.Existing(id)) { openSession(id, rememberedPane(id)) }

    /** Opens a session on a host, connecting to it first when it is not the one open. */
    fun openSessionOn(target: SavedHost, id: String) {
        if (host?.id != target.id) connect(target)
        openSession(id)
    }

    /** Makes a session on a host, connecting to it first when it is not the open one. */
    fun createSessionOn(target: SavedHost, label: String? = null) {
        if (host?.id != target.id) connect(target)
        createSession(label)
    }

    /** The sessions this host had last time, for a host that is not open. */
    fun cachedSessions(hostId: String): List<ListedSession> = store.cachedSessions(hostId)

    /** Makes a new session and shows it once hylad has it, never the session before it. */
    fun createSession(label: String? = null) = enterSession(Opening.New) { createShell(label) }

    private fun enterSession(what: Opening, open: suspend HylaSession.() -> Unit) {
        if (_opening.value != null) return
        if (_hyla.value.phase == Phase.Failed) return
        val waiting = sessionReady
        _opening.value = what
        viewModelScope.launch {
            try {
                val s = waiting.await()
                s.awaitReady()
                s.open()
                if (session === s) route.value = Route.Term
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session?.note(e.toProblem())
            } finally {
                if (_opening.value == what) _opening.value = null
            }
        }
    }

    /**
     * After the process is killed in the background, reopen the host and session that were on
     * screen. hylad keeps the sessions, so this is a reconnect rather than a new one.
     */
    fun restore() {
        if (route.value != Route.Home || session != null) return
        val target = store.lastHost()?.let { store.find(it) } ?: return
        connect(target, resume = true)
    }

    /** The shown pane's bytes for as long as the screen collects them. */
    fun watchPane(blockId: String): Flow<TerminalFeed> =
        session?.watchPane(blockId) ?: kotlinx.coroutines.flow.emptyFlow()

    fun retryNow() {
        session?.retryNow()
    }

    fun dismissNotice(id: Long) {
        session?.dismissNotice(id)
    }

    /** Coming back, the session checks a connection that may have died while the app was away. */
    fun setForeground(visible: Boolean) {
        foreground = visible
        session?.setActive(visible)
    }

    fun disconnect() {
        disconnectQuiet()
        store.setLastHost(null)
        route.value = Route.Home
    }

    fun showHome() {
        route.value = Route.Home
    }

    fun setFont(scale: Float) {
        val next = scale.coerceIn(0.7f, 1.8f)
        fontScale.value = next
        store.setFontScale(next)
    }

    fun act(block: suspend HylaSession.() -> Unit) {
        val s = session ?: return
        viewModelScope.launch {
            try {
                s.block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                s.note(e.toProblem())
            }
        }
    }

    private fun disconnectQuiet() {
        connectJob?.cancel()
        connectJob = null
        sessionReady.cancel()
        sessionReady = CompletableDeferred()
        _opening.value = null
        watch?.cancel()
        watch = null
        val closing = session
        session = null
        sessionJob?.cancel()
        _hyla.value = HylaState()
        if (closing != null) teardown.launch { runCatching { closing.stop() } }
    }
}

/**
 * Runs a session's teardown after whatever started it is gone. The disown it waits for is one
 * round trip with a time limit, so nothing here outlives the process by more than that.
 */
private val teardown = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

/** Connection diagnostics: `adb logcat -s HylaPocket`. */
internal const val CONN_TAG = "HylaPocket"

/** The monospace a phone has, until JetBrains Mono is downloaded. */
private val SystemTypeface: Typeface = Typeface.MONOSPACE

/** Reads an address the way its host field is written, or a tailcat pair code; null when it is neither. */
internal fun parseAddress(text: String): Endpoint? = parseHostAddress(text)
