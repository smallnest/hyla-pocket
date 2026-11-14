package io.rpcx.hylapocket.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.rpcx.hylapocket.R
import io.rpcx.hylapocket.core.BlockInfo
import io.rpcx.hylapocket.core.Endpoint
import io.rpcx.hylapocket.core.HostCodec
import io.rpcx.hylapocket.core.addressText
import io.rpcx.hylapocket.core.HylaState
import io.rpcx.hylapocket.core.ListedSession
import io.rpcx.hylapocket.core.Phase
import io.rpcx.hylapocket.core.ProgramRecord
import io.rpcx.hylapocket.core.SessionView
import io.rpcx.hylapocket.core.parseHostEntry
import io.rpcx.hylapocket.data.HostStore
import io.rpcx.hylapocket.data.SavedHost
import io.rpcx.hylapocket.data.ThemeMode

/** One session under its host: what the list shows of it. */
private data class HomeSession(
    val session: ListedSession,
    val view: SessionView?,
    /** True while a pane of it is working or waiting: the dot the eye keeps. */
    val activity: Boolean,
)

/** The screens' edges, and a host row's mark and text, in the steps the list uses. */
private val PAGE = 16.dp
private val KEY_MARK = 16.dp
private val KEY_TEXT = 64.dp
private val TILE = 36.dp

/**
 * The list: every host, its sessions, and under each session the panes it has. A pane a program
 * in is working or waiting on carries its dot; the eye keeps only those. Search narrows by host,
 * session or pane name.
 */
@Composable
internal fun HomeScreen(
    hosts: List<SavedHost>,
    connected: SavedHost?,
    state: HylaState,
    opening: Opening?,
    onConnect: (SavedHost) -> Unit,
    onOpenSession: (SavedHost, String) -> Unit,
    onOpenPane: (SavedHost, String, String) -> Unit,
    onNewSession: (SavedHost, String?) -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
    onSave: (SavedHost) -> Unit,
    onDelete: (String) -> Unit,
    onFontSettings: () -> Unit,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
    projectOrder: (String) -> List<String>,
    onProjectOrder: (String, List<String>) -> Unit,
    loadCollapsed: () -> Set<String>,
    saveCollapsed: (Set<String>) -> Unit,
    onKillSession: (SavedHost, String) -> Unit,
) {
    var editing by remember { mutableStateOf<SavedHost?>(null) }
    var creating by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var menuHost by remember { mutableStateOf<SavedHost?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var activeOnly by remember { mutableStateOf(false) }
    var collapsedHosts by remember { mutableStateOf(emptySet<String>()) }
    var collapsedProjects by remember { mutableStateOf(loadCollapsed()) }
    var killing by remember { mutableStateOf<Pair<SavedHost, HomeSession>?>(null) }
    // Every fold is written down, so a project stays folded the next time the app opens.
    fun foldProjects(change: (Set<String>) -> Set<String>) {
        val next = change(collapsedProjects)
        collapsedProjects = next
        saveCollapsed(next)
    }
    // The projects of each host, in the order dragged; the stored one seeds it.
    val projectOrders = remember { mutableStateMapOf<String, List<String>>() }
    fun orderOf(hostId: String): List<String> = projectOrders.getOrPut(hostId) { projectOrder(hostId) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val closeSearch: () -> Unit = {
        searchOpen = false
        query = ""
        keyboard?.hide()
    }

    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            focus.requestFocus()
            keyboard?.show()
        }
    }
    // Back closes the search first, as the platform's search does.
    BackHandler(enabled = searchOpen, onBack = closeSearch)

    if (hosts.isEmpty()) {
        EmptyHome(onAdd = { creating = true })
    } else {
        val q = query.trim().lowercase()
        val rows = hosts.mapNotNull { host ->
            val live = connected?.id == host.id
            // A host that is not connected has no sessions on this screen: the list is what the
            // machine says it has now, not what it had when the app last spoke to it.
            val allSessions = if (live && state.phase == Phase.Live) state.sessions else emptyList()
            val hostMatch = q.isEmpty() || host.label.lowercase().contains(q) || host.host.contains(q)
            val sessions = allSessions.mapNotNull { session ->
                val view = if (live) state.views[session.id] else null
                val panes = view?.allBlocks().orEmpty()
                val sessionActivity = panes.any { state.isBusy(it.id) }
                if (activeOnly && !sessionActivity) return@mapNotNull null
                val labelMatch = hostMatch || session.label.lowercase().contains(q)
                if (!labelMatch && panes.none { paneTitle(it, state.blockStatus[it.id], state.programs[it.id]).lowercase().contains(q) }) {
                    return@mapNotNull null
                }
                HomeSession(session, view, sessionActivity)
            }
            if (sessions.isEmpty() && (activeOnly || !hostMatch)) return@mapNotNull null
            host to sessions
        }
        val projectKeys = rows.flatMap { (h, sessions) ->
            sessions.mapNotNull { it.session.project?.let { p -> "${h.id}/$p" } }
        }.toSet()
        val allCollapsed = projectKeys.isNotEmpty() && collapsedProjects.containsAll(projectKeys)
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Column(Modifier.fillMaxSize().background(Colors.bg).statusBarsPadding().imePadding()) {
            if (searchOpen) {
                SearchBar(query = query, focus = focus, onQuery = { query = it }, onClose = closeSearch)
            }
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = PAGE,
                        end = PAGE,
                        top = if (searchOpen) 0.dp else 8.dp,
                        // Clear of the floating controls, so the last row can be scrolled above them.
                        bottom = if (searchOpen) 16.dp else navBottom + 104.dp,
                    ),
                ) {
                    rows.forEachIndexed { index, (host, sessions) ->
                        val live = connected?.id == host.id
                        val hostExpanded = host.id !in collapsedHosts || searchOpen
                        item(key = "host-${host.id}") {
                            if (index > 0) Spacer(Modifier.height(12.dp))
                            HostHeader(
                                host = host,
                                live = live,
                                status = if (live) phaseText(state) else null,
                                phase = if (live) state.phase else null,
                                sessionCount = sessions.size,
                                expanded = hostExpanded,
                                onToggle = {
                                    if (live) {
                                        collapsedHosts = if (hostExpanded) collapsedHosts + host.id else collapsedHosts - host.id
                                        if (!hostExpanded) onRetry()
                                    } else {
                                        onConnect(host)
                                    }
                                },
                                onMenu = { menuHost = host },
                            )
                            if (live) HostStatus(state, onRetry)
                        }
                        if (hostExpanded) {
                            // Sessions under the project they belong to, as the host's own sidebar
                            // lists them; a session of no project comes first, unlabeled.
                            var project = ""
                            val order = orderOf(host.id)
                            val rank = order.withIndex().associate { (index, name) -> name to index }
                            fun moveProject(name: String, delta: Int) {
                                // The grip's gesture outlives a recomposition, so the list it
                                // moves in is read from the store at each step, not captured.
                                val current = projectOrders[host.id]?.takeIf { it.isNotEmpty() }?.toMutableList()
                                    ?: sessions.mapNotNull { it.session.project?.takeIf { p -> p.isNotBlank() } }.distinct().toMutableList()
                                val from = current.indexOf(name)
                                if (from < 0) return
                                val to = (from + delta).coerceIn(0, current.lastIndex)
                                if (to == from) return
                                val next = current.toMutableList().apply { add(to, removeAt(from)) }
                                projectOrders[host.id] = next
                                onProjectOrder(host.id, next)
                            }
                            sessions
                                .sortedWith(compareBy({ rank[it.session.project.orEmpty()] ?: Int.MAX_VALUE }, { it.session.project.orEmpty() }))
                                .forEach { row ->
                                val rowProject = row.session.project.orEmpty()
                                val projectKey = "${host.id}/$rowProject"
                                if (rowProject != project) {
                                    project = rowProject
                                    if (rowProject.isNotEmpty()) {
                                        // The name, not the loop's variable: the item's content is
                                        // read later, when every group has passed.
                                        item(key = "project-$projectKey") {
                                            ProjectHeader(
                                                name = rowProject,
                                                collapsed = projectKey in collapsedProjects,
                                                onMove = { delta -> moveProject(rowProject, delta) },
                                                onToggle = {
                                                    foldProjects { keys -> if (projectKey in keys) keys - projectKey else keys + projectKey }
                                                },
                                                onNewSession = { onNewSession(host, rowProject) },
                                            )
                                        }
                                    }
                                }
                                if (rowProject.isNotEmpty() && projectKey in collapsedProjects) return@forEach
                                val openingThis = opening == Opening.Existing(row.session.id)
                                item(key = "session-${row.session.id}") {
                                    SessionHeader(
                                        row = row,
                                        state = state,
                                        opening = openingThis,
                                        onToggle = { onOpenSession(host, row.session.id) },
                                        onOpenPane = { block -> onOpenPane(host, row.session.id, block) },
                                        onKill = if (live) {
                                            { killing = host to row }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                    }
                    if (rows.isEmpty()) {
                        item { NotFound(activeOnly = activeOnly) }
                    }
                }
                if (!searchOpen) {
                    FloatingControls(
                        activeOnly = activeOnly,
                        allCollapsed = allCollapsed,
                        onSettings = { settings = true },
                        onEye = { activeOnly = !activeOnly },
                        onFold = {
                            foldProjects { if (allCollapsed) emptySet() else projectKeys }
                        },
                        onSearch = { searchOpen = true },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }

    killing?.let { (host, row) ->
        AlertDialog(
            onDismissRequest = { killing = null },
            containerColor = Colors.surface,
            title = { Text(stringResource(R.string.confirm_kill_session, row.session.label), color = Colors.ink) },
            text = { Text(stringResource(R.string.confirm_kill_session_body), color = Colors.muted) },
            confirmButton = {
                TextButton(onClick = {
                    killing = null
                    onKillSession(host, row.session.id)
                }) { Text(stringResource(R.string.action_delete), color = Colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { killing = null }) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
            },
        )
    }

    if (creating || editing != null) {
        HostDialog(
            initial = editing,
            onDismiss = { creating = false; editing = null },
            onDelete = editing?.let { host -> { onDelete(host.id); creating = false; editing = null } },
            onSave = {
                onSave(it)
                creating = false
                editing = null
            },
        )
    }

    menuHost?.let { host ->
        ActionSheet(onDismiss = { menuHost = null }, title = host.label) {
            Group {
                SheetItem(stringResource(R.string.session_new), icon = R.drawable.ic_add) {
                    menuHost = null
                    onNewSession(host, null)
                }
                SheetItem(stringResource(R.string.action_edit_host), icon = R.drawable.ic_edit) {
                    menuHost = null
                    editing = host
                }
                if (connected?.id == host.id) {
                    SheetItem(stringResource(R.string.action_disconnect), Colors.danger, icon = R.drawable.ic_disconnect) {
                        menuHost = null
                        onDisconnect()
                    }
                } else {
                    SheetItem(stringResource(R.string.action_connect), icon = R.drawable.ic_host) {
                        menuHost = null
                        onConnect(host)
                    }
                }
            }
        }
    }

    if (settings) {
        SettingsSheet(
            onDismiss = { settings = false },
            onAddHost = {
                settings = false
                creating = true
            },
            themeMode = themeMode,
            onThemeMode = onThemeMode,
            onFontSettings = {
                settings = false
                onFontSettings()
            },
        )
    }
}

/** Whether the program in [this] pane is working or waiting on the person. */
private fun HylaState.isBusy(blockId: String): Boolean {
    val record = blockStatus[blockId] ?: return false
    return record.state == "working" || record.state == "blocked"
}

/** The fold's chevron: down while open, turned to point right while folded. */
@Composable
private fun FoldChevron(expanded: Boolean) {
    val turn by animateFloatAsState(if (expanded) 0f else -90f, label = "fold")
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Glyph(R.drawable.ic_expand, Colors.muted, Modifier.rotate(turn))
    }
}

/**
 * One host: its mark, its name with the connection's dot, and what it is doing. Tapping it folds
 * its sessions, or connects it when it is not the one open; the menu edits it.
 */
@Composable
private fun HostHeader(
    host: SavedHost,
    live: Boolean,
    status: String?,
    phase: Phase?,
    sessionCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onMenu: () -> Unit,
) {
    val folded = stringResource(R.string.state_folded)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(Radius.card))
            .clickable(onClick = onToggle)
            .semantics { if (live && !expanded) stateDescription = folded }
            .padding(start = KEY_MARK),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(R.drawable.ic_host, if (live) Colors.ink else Colors.muted, size = 24.dp)
        Spacer(Modifier.width(KEY_TEXT - KEY_MARK - 24.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    host.label,
                    color = Colors.ink,
                    fontSize = TextStyles.title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (live) {
                    Spacer(Modifier.width(8.dp))
                    Dot(phaseTint(phase ?: Phase.Idle), 8.dp)
                }
            }
            Text(
                (if (live) status.orEmpty() else stringResource(R.string.host_not_connected)) +
                    if (sessionCount > 0) " · " + pluralStringResource(R.plurals.session_count, sessionCount, sessionCount) else "",
                color = Colors.muted,
                fontSize = TextStyles.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        RoundButton(R.drawable.ic_more, stringResource(R.string.action_more), onMenu, tint = Colors.muted, size = 48.dp)
    }
}

/** The line under a connected host: why it is not live, and the tap that tries again. */
@Composable
private fun HostStatus(state: HylaState, onRetry: () -> Unit) {
    val problem = rememberSettledProblem(state.phase, state.problem.takeIf { state.phase != Phase.Live })
    if (problem == null) return
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(Radius.chip)).clickable(onClick = onRetry)
            .padding(start = KEY_TEXT, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(problemText(problem, state.endpointLabel), color = Colors.danger, fontSize = TextStyles.label, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.action_retry_now), color = Colors.accent, fontSize = TextStyles.label, fontWeight = FontWeight.Medium)
    }
}

/**
 * The project a host puts its sessions in: its name, which folds them, and the plus that starts a
 * session in it.
 */
@Composable
private fun ProjectHeader(
    name: String,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onNewSession: () -> Unit,
    onMove: ((Int) -> Unit)? = null,
) {
    var drag by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    // A step is short enough that a small drag moves a project, and a long one moves several.
    val step = with(LocalDensity.current) { 44.dp.toPx() }
    Row(
        Modifier.fillMaxWidth()
            .padding(start = KEY_MARK, top = 6.dp, bottom = 0.dp)
            .clip(RoundedCornerShape(Radius.chip))
            .background(if (dragging) Colors.keyOn else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The grip is the handle: dragging it moves the project, and the row itself still folds.
        if (onMove != null) {
            Box(
                // The grip takes the pointer on its own: a drag on it moves the project, and the
                // list under it does not scroll. Consuming each move at once is what keeps the
                // LazyColumn from taking the gesture for its own.
                Modifier.size(40.dp).pointerInput(name) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var travelled = 0f
                            dragging = true
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) break
                                val dy = change.positionChange().y
                                if (dy != 0f) {
                                    change.consume()
                                    travelled += dy
                                    if (travelled > step) {
                                        onMove(1)
                                        travelled -= step
                                    } else if (travelled < -step) {
                                        onMove(-1)
                                        travelled += step
                                    }
                                }
                            }
                            drag = 0f
                            dragging = false
                        }
                    }
                },
                contentAlignment = Alignment.Center,
            ) {
                Glyph(R.drawable.ic_drag_handle, Colors.muted, size = 20.dp)
            }
        }
        Row(
            Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(Radius.chip))
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                color = Colors.ink,
                fontSize = TextStyles.subhead,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            FoldChevron(!collapsed)
        }
        RoundButton(
            R.drawable.ic_add_circle,
            stringResource(R.string.session_new),
            onNewSession,
            tint = Colors.muted,
            size = 44.dp,
        )
    }
}

/**
 * A session under its host: a quiet group label with its pane count, the plus that makes a new
 * session, and the fold. A blue dot says a program in it is working.
 */
@Composable
private fun SessionHeader(
    row: HomeSession,
    state: HylaState,
    opening: Boolean,
    onToggle: () -> Unit,
    onOpenPane: (String) -> Unit,
    onKill: (() -> Unit)? = null,
) {
    val view = row.view
    val shown = view?.let { state.places[it.sessionId] }
    val blocks = view?.allBlocks()?.let { all -> all.sortedBy { if (it.id == shown) 0 else 1 } }.orEmpty()
    // One pane is the row: the session's name and its pane's mark, as the desktop shows a tab.
    // A session of several keeps its header and its panes on a card.
    if (blocks.size == 1) {
        val block = blocks[0]
        SinglePaneRow(row = row, state = state, block = block, opening = opening, onKill = onKill) { onOpenPane(block.id) }
        return
    }
    val panes = blocks.size
    val detail = when {
        opening -> stringResource(R.string.session_opening)
        row.view != null -> pluralStringResource(R.plurals.pane_count, panes, panes)
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(Radius.chip)).clickable(onClick = onToggle)
            .padding(start = KEY_MARK),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.session.label,
                color = Colors.muted,
                fontSize = TextStyles.subhead,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (row.activity) {
                Spacer(Modifier.width(8.dp))
                Dot(Colors.accent, 6.dp)
            }
            if (detail != null) {
                Spacer(Modifier.width(8.dp))
                Text(detail, color = if (opening) Colors.accent else Colors.muted, fontSize = TextStyles.label, maxLines = 1)
            }
        }
        if (onKill != null) KillButton(onKill)
    }
    // The session's panes, on one card, the pane the phone last showed first.
    if (blocks.isEmpty()) return
    Column(
        Modifier.fillMaxWidth().padding(start = KEY_TEXT - KEY_MARK).clip(RoundedCornerShape(Radius.card)).background(Colors.surface),
    ) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Colors.line))
            }
            PaneRow(
                block = block,
                status = state.blockStatus[block.id],
                program = state.programs[block.id],
                title = state.titles[block.id],
                onClick = { onOpenPane(block.id) },
            )
        }
    }
}

/** A session of one pane, on one line: its mark, its name, and what the pane is doing. */
@Composable
private fun SinglePaneRow(
    row: HomeSession,
    state: HylaState,
    block: BlockInfo,
    opening: Boolean,
    onKill: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val status = state.blockStatus[block.id]
    val program = state.programs[block.id]
    val title = state.titles[block.id]
    val busy = status?.state == "working" || status?.state == "blocked"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(Radius.card))
            .background(Colors.surface)
            .clickable(onClick = onClick)
            .padding(start = KEY_MARK, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProgramTile(status?.app ?: program, size = TILE)
        Spacer(Modifier.width(KEY_TEXT - KEY_MARK - TILE))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                row.session.label,
                color = Colors.ink,
                fontSize = TextStyles.body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (opening) stringResource(R.string.session_opening)
                else title?.takeIf { it != row.session.label } ?: block.label,
                color = Colors.muted,
                fontSize = TextStyles.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (busy) {
            Dot(statusTint(status!!), 8.dp, ring = Colors.surface)
            Spacer(Modifier.width(8.dp))
        }
        if (onKill != null) KillButton(onKill)
    }
}

/** The cross that closes a session from its row, before it asks. */
@Composable
private fun KillButton(onKill: () -> Unit) {
    RoundButton(
        R.drawable.ic_close,
        stringResource(R.string.menu_kill_session),
        onKill,
        tint = Colors.muted,
        size = 40.dp,
        iconSize = 20.dp,
    )
}

/**
 * One pane of a session: the tile of what runs in it - an agent's mark, or the terminal for a
 * shell - badged while it works, its name, and what its program is doing.
 */
@Composable
private fun PaneRow(
    block: BlockInfo,
    status: ProgramRecord?,
    program: String?,
    title: String?,
    onClick: () -> Unit,
) {
    val busy = status?.state == "working" || status?.state == "blocked"
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick)
            .padding(start = KEY_MARK, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProgramTile(status?.app ?: program, size = TILE)
        Spacer(Modifier.width(KEY_TEXT - KEY_MARK - TILE))
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                title ?: paneTitle(block, status, program),
                color = Colors.ink,
                fontSize = TextStyles.body,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            DetailLine(status, block)
        }
        if (busy) {
            Dot(statusTint(status!!), 8.dp, ring = Colors.surface)
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun DetailLine(status: ProgramRecord?, block: BlockInfo) {
    val word = status?.let { statusWord(it) }
    val text = word ?: block.label
    Text(
        text,
        color = if (status?.state == "blocked") Colors.amber else Colors.muted,
        fontSize = TextStyles.label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun NotFound(activeOnly: Boolean) {
    Text(
        stringResource(if (activeOnly) R.string.no_activity else R.string.search_none),
        color = Colors.muted,
        fontSize = TextStyles.subhead,
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        textAlign = TextAlign.Center,
    )
}

/**
 * The controls floating over the list: a pill with settings and the eye at the bottom left, search
 * on its own at the bottom right. Both are the height of the platform's floating action button and
 * stand on the page margin.
 */
@Composable
private fun FloatingControls(
    activeOnly: Boolean,
    allCollapsed: Boolean,
    onSettings: () -> Unit,
    onEye: () -> Unit,
    onFold: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val on = stringResource(R.string.filter_on)
    val off = stringResource(R.string.filter_off)
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = PAGE, vertical = 16.dp)) {
        Row(
            Modifier.align(Alignment.BottomStart).floating(RoundedCornerShape(50)).padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(R.drawable.ic_settings, stringResource(R.string.settings), onSettings, size = 48.dp, iconSize = 24.dp)
            RoundButton(
                if (activeOnly) R.drawable.ic_eye_filled else R.drawable.ic_eye,
                stringResource(R.string.action_show_active),
                onEye,
                modifier = Modifier.semantics { stateDescription = if (activeOnly) on else off },
                tint = if (activeOnly) Colors.accent else Colors.ink,
                plate = if (activeOnly) Colors.keyOn else null,
                size = 48.dp,
                iconSize = 24.dp,
            )
            // Folds every project at once, and opens them all again: the chevron points the way
            // the sessions go.
            RoundButton(
                R.drawable.ic_expand,
                stringResource(if (allCollapsed) R.string.action_expand_all else R.string.action_collapse_all),
                onFold,
                modifier = Modifier.rotate(if (allCollapsed) 0f else 180f),
                size = 48.dp,
                iconSize = 24.dp,
            )
        }
        RoundButton(
            R.drawable.ic_search,
            stringResource(R.string.action_search),
            onSearch,
            modifier = Modifier.align(Alignment.BottomEnd).floating(),
            size = 56.dp,
            iconSize = 24.dp,
        )
    }
}

/** The platform's search bar: a 56 dp pill, back on the left, clear on the right. */
@Composable
private fun SearchBar(
    query: String,
    focus: FocusRequester,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = PAGE, vertical = 8.dp).height(56.dp)
            .clip(RoundedCornerShape(50)).background(Colors.surface).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(R.drawable.ic_back, stringResource(R.string.action_back), onClose, size = 48.dp)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.search_hint), color = Colors.muted, fontSize = TextStyles.body, maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = Colors.ink, fontSize = TextStyles.body),
                cursorBrush = SolidColor(Colors.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        if (query.isNotEmpty()) {
            RoundButton(R.drawable.ic_close, stringResource(R.string.action_clear), { onQuery("") }, tint = Colors.muted, size = 48.dp)
        }
    }
}

@Composable
private fun SettingsSheet(
    onDismiss: () -> Unit,
    onAddHost: () -> Unit,
    onFontSettings: () -> Unit,
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
) {
    ActionSheet(onDismiss = onDismiss, title = stringResource(R.string.settings)) {
        Group {
            SheetItem(stringResource(R.string.action_add_host), icon = R.drawable.ic_add, onClick = onAddHost)
            SheetItem(stringResource(R.string.terminal_font_title), icon = R.drawable.ic_settings, onClick = onFontSettings)
        }
        AppearanceRows(themeMode, onThemeMode)
    }
}

@Composable
private fun EmptyHome(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Colors.bg).statusBarsPadding().navigationBarsPadding().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Glyph(R.drawable.ic_host, Colors.muted, size = 48.dp)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.home_no_hosts), color = Colors.ink, fontSize = TextStyles.display, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.home_no_hosts_body),
            color = Colors.muted,
            fontSize = TextStyles.label,
            lineHeight = labelLeading,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        PrimaryButton(stringResource(R.string.action_add_host), onClick = onAdd)
    }
}

/**
 * Adding a host is a name and an address: `studio:7297` for a listener on the network, which
 * takes the password below it, or the pair code of hylad's tailcat listener, which is its own
 * password and so has none. The port and the scheme follow from the address, and the line under
 * it says what the app made of them.
 */
@Composable
internal fun HostDialog(
    initial: SavedHost?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (SavedHost) -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(initial?.let { it.endpoint().addressText() } ?: "") }
    var token by remember { mutableStateOf(initial?.token ?: "") }
    var showToken by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    // A pair code is one long token, and a paste may carry the line breaks a terminal wrapped it
    // in, so the address is read without them.
    val typed = remember(address) { address.filterNot { it.isWhitespace() } }
    // A scanned or pasted code may carry the password too: tcp://host:port?token=….
    val entry = remember(typed) { if (typed.isBlank()) null else parseHostEntry(typed) }
    val parsed = entry?.endpoint
    val tailcat = parsed?.tailcat == true
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = {
            Text(
                stringResource(if (initial == null) R.string.action_add_host else R.string.action_edit_host),
                color = Colors.ink,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(stringResource(R.string.field_name), label) { label = it }
                Field(
                    stringResource(R.string.field_address),
                    address,
                    leading = { Glyph(R.drawable.ic_qr_scan, Colors.muted, size = 22.dp) },
                    onLeading = { scanning = true },
                    leadingLabel = stringResource(R.string.scan_title),
                ) { address = it }
                when {
                    tailcat -> Text(
                        listOfNotNull(
                            stringResource(R.string.host_transport_tailcat),
                            stringResource(R.string.host_pair_code, labelOrNull(parsed!!.pairCode) ?: ""),
                        ).joinToString(" · "),
                        color = Colors.muted,
                        fontSize = TextStyles.label,
                    )
                    parsed != null -> Text(
                        listOfNotNull(
                            "${parsed.hostName()}:${parsed.port}",
                            stringResource(if (parsed.plaintext) R.string.host_transport_plain else R.string.host_transport_tls),
                        ).joinToString(" · "),
                        color = Colors.muted,
                        fontSize = TextStyles.label,
                    )
                    address.isNotBlank() -> Text(stringResource(R.string.host_address_invalid), color = Colors.danger, fontSize = TextStyles.label)
                }
                // A pair code is its own password: only a network address takes one.
                if (parsed != null && !tailcat) {
                    Field(stringResource(R.string.field_token), token, visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation()) { token = it }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.field_show_token),
                            color = Colors.muted,
                            fontSize = TextStyles.label,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showToken = !showToken }) {
                            Text(
                                stringResource(if (showToken) R.string.action_hide else R.string.action_show),
                                color = Colors.accent,
                                fontSize = TextStyles.label,
                            )
                        }
                    }
                    Text(stringResource(R.string.field_token_hint), color = Colors.muted, fontSize = TextStyles.label, lineHeight = labelLeading)
                }
                TextButton(onClick = { scanning = true }) {
                    Text(stringResource(R.string.scan_title), color = Colors.accent, fontSize = TextStyles.label)
                }
            }
        },
        confirmButton = {
            // Nothing to save until the address says where to connect, and a network address's password is its key.
            TextButton(
                enabled = parsed != null && (tailcat || token.isNotBlank()),
                onClick = {
                    parsed?.let {
                        onSave(
                            SavedHost(
                                id = initial?.id ?: HostStore.newId(),
                                label = label.trim().ifBlank { it.pairCode ?: it.hostName() },
                                host = it.host,
                                port = it.port,
                                plaintext = it.plaintext,
                                token = if (it.tailcat) "" else token.trim().ifBlank { entry?.token.orEmpty() },
                            ),
                        )
                    }
                },
            ) { Text(stringResource(R.string.action_save), color = if (parsed != null && (tailcat || token.isNotBlank())) Colors.accent else Colors.muted) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.action_delete), color = Colors.danger) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
            }
        },
    )
    // The camera, or a picture of the code: nothing of the dialog before it is lost.
    if (scanning) {
        val done = remember { CompletableDeferred<Unit>() }
        val close = {
            scanning = false
            if (!done.isCompleted) done.complete(Unit)
        }
        ScanCodeDialog(
            done = done,
            onDismiss = close,
            onText = { text ->
                address = text
                parseHostEntry(text)?.takeIf { it.token.isNotEmpty() }?.let { token = it.token }
                close()
            },
        )
    }
    if (confirmDelete && onDelete != null && initial != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Colors.surface,
            title = { Text(stringResource(R.string.confirm_delete_host, initial.label), color = Colors.ink) },
            text = { Text(stringResource(R.string.confirm_delete_host_body), color = Colors.muted) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.action_delete), color = Colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
            },
        )
    }
}

/** The first part of a pair code, for a line that says what the host is without printing the secret. */
private fun labelOrNull(pair: String?): String? = pair?.take(10)?.plus("…")

/** The endpoint a host's address resolves to, or null when it does not resolve. */
internal fun endpointOf(text: String): Endpoint? = parseAddress(text)
