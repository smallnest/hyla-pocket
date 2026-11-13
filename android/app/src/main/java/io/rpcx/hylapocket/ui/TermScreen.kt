package io.rpcx.hylapocket.ui

import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.rpcx.hylapocket.R
import io.rpcx.hylapocket.core.BlockInfo
import io.rpcx.hylapocket.core.HylaSession
import io.rpcx.hylapocket.core.HylaState
import io.rpcx.hylapocket.core.KeyModifiers
import io.rpcx.hylapocket.core.KeyPress
import io.rpcx.hylapocket.core.Phase
import io.rpcx.hylapocket.core.ProgramRecord
import io.rpcx.hylapocket.core.TerminalFeed
import io.rpcx.hylapocket.core.keyPressFor
import io.rpcx.hylapocket.core.toProblem
import io.rpcx.hylapocket.terminal.TerminalSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

// The terminal: a floating top bar that says what this is, the pane edge to edge on its own
// paper, and a floating dock under the thumb.

/**
 * The line under the header. A connection that is down stays there with a way to retry; an action
 * that failed shows for a few seconds and goes, or goes when tapped, so it never outlives its cause.
 */
@Composable
internal fun TermBanner(state: HylaState, onRetry: () -> Unit, onDismiss: (Long) -> Unit) {
    val problem = rememberSettledProblem(state.phase, state.problem.takeIf { state.phase != Phase.Live })
    val notice = state.notice
    LaunchedEffect(notice?.id) {
        if (notice != null) {
            delay(NOTICE_MS)
            onDismiss(notice.id)
        }
    }
    val (text, action) = when {
        problem != null -> problemText(problem, state.endpointLabel) to onRetry
        notice != null -> problemText(notice.problem, state.endpointLabel) to { onDismiss(notice.id) }
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = action).padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Colors.danger, fontSize = TextStyles.label, modifier = Modifier.weight(1f))
        Text(
            stringResource(if (problem != null) R.string.action_retry_now else R.string.action_dismiss),
            color = if (problem != null) Colors.accent else Colors.muted,
            fontSize = TextStyles.label,
        )
    }
}

/** Something picked from the menu that needs a name or a confirmation. */
internal data class Rename(val kind: Level, val id: String, val current: String)

internal data class Confirm(val kind: Level, val id: String, val name: String)

internal enum class Level { Session, Pane }

/** The bottom sheet open over the terminal. */
internal sealed interface Sheet {
    data object Sessions : Sheet
    data object Actions : Sheet
    data object Navigator : Sheet
}

@Composable
internal fun TermScreen(
    state: HylaState,
    fontSize: Float,
    terminalTypeface: android.graphics.Typeface,
    onFontSize: (Float) -> Unit,
    onFontSettings: () -> Unit,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    watchPane: (String) -> Flow<TerminalFeed>,
    onRetry: () -> Unit,
    onDismissNotice: (Long) -> Unit,
    onAct: (suspend HylaSession.() -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val ime = LocalSoftwareKeyboardController.current
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var mods by remember { mutableStateOf(KeyModifiers()) }
    var link by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<Rename?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var termSession by remember { mutableStateOf<TerminalSession?>(null) }
    // The pane's editor: what the keyboard key raises, and what Back and the session list drop.
    var keyboard by remember { mutableStateOf<TerminalImeView?>(null) }
    val view = state.view
    val tab = view?.activeWindow()
    val focused = view?.focusedBlock()
    val status = focused?.let { state.blockStatus[it.id] }
    val panes = tab?.blocks.orEmpty()

    /** Sends a key with the modifiers the key row holds; the ones on for one key are then used up. */
    fun press(key: KeyPress, own: String = "") {
        val e = mods.event(key, own)
        onAct { sendKey(e.code, mods = e.mods, text = e.text, codepoint = e.codepoint, consumedMods = e.consumedMods) }
        mods = mods.consume()
    }

    /**
     * A character from the phone keyboard while a modifier is on becomes that key with the modifier,
     * so C-r or Alt-b can be typed with the keyboard's letters. True when it was taken here.
     */
    fun typed(bytes: ByteArray, flushFirst: () -> Unit): Boolean {
        if (!mods.any) return false
        val key = keyPressFor(String(bytes, Charsets.UTF_8)) ?: return false
        flushFirst()
        press(key)
        return true
    }

    // Typing goes straight to the terminal, as it does on the desktop: the keyboard key raises the
    // IME for the pane, and the key row stays above it. There is no composer between them.
    fun toggleKeyboard() {
        val target = keyboard ?: return
        val imeManager = context.getSystemService(InputMethodManager::class.java)
        // Whether the keyboard is up, not whether the pane has focus: the IME can be showing while
        // focus sits in its own window, and then the same key must put it away.
        val showing = ViewCompat.getRootWindowInsets(target)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (showing) {
            imeManager?.hideSoftInputFromWindow(target.windowToken, 0)
            target.clearFocus()
        } else {
            target.showIme()
        }
    }

    BackHandler(onBack = onBack)

    // The keyboard belongs to the pane that is on screen: leaving the terminal drops it, so the
    // session list does not come back with a keyboard over it.
    DisposableEffect(Unit) {
        onDispose {
            keyboard?.clearFocus()
            ime?.hide()
        }
    }

    // One paper for the whole screen, the terminal's: the top bar and the dock float on it instead
    // of sitting on bands of a second grey.
    Column(Modifier.fillMaxSize().background(Colors.terminal).statusBarsPadding().navigationBarsPadding().imePadding()) {
        SessionTopBar(
            state = state,
            view = view,
            status = status,
            onBack = onBack,
            onNavigate = { if (view != null) sheet = Sheet.Navigator },
            onMore = { sheet = Sheet.Actions },
        )
        TermBanner(state, onRetry = onRetry, onDismiss = onDismissNotice)
        // The pane sits on its own paper: white in the day, the page at night, edge to edge, the
        // way a terminal fills the screen.
        Box(Modifier.weight(1f).fillMaxWidth().background(Colors.terminal)) {
            val shown = focused?.id
            key(shown) {
                TerminalPane(
                    blockId = shown.orEmpty(),
                    feeds = watchPane,
                    fontSize = fontSize,
                    terminalTypeface = terminalTypeface,
                    onFontSize = onFontSize,
                    onInput = { bytes -> onAct { writeBytes(bytes) } },
                    onTyped = ::typed,
                    onLink = { link = it },
                    onResize = { cols, rows, cw, ch -> onAct { setSize(cols, rows, cw, ch) } },
                    onSession = { termSession = it },
                    onKeyboard = { keyboard = it },
                    // A horizontal swipe on the pane moves to the pane beside it, as the dots
                    // under it say there is one.
                    onSwipe = { forward ->
                        if (panes.size > 1) {
                            val index = panes.indexOfFirst { it.id == focused?.id }
                            val next = (index + if (forward) 1 else -1).coerceIn(0, panes.lastIndex)
                            if (next != index) onAct { focusBlock(panes[next].id) }
                        }
                    },
                    onError = { onAct { note(it.toProblem()) } },
                    modifier = Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 2.dp),
                )
            }
        }
        if (panes.size > 1) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp), contentAlignment = Alignment.Center) {
                PaneBar(
                    tab = tab,
                    focused = focused,
                    status = state.blockStatus,
                    programs = state.programs,
                    onSelect = { block -> onAct { focusBlock(block.id) } },
                )
            }
        }
        KeyBar(
            mods = mods,
            busy = status?.state == "working" || status?.state == "blocked",
            imeUp = imeShown(),
            onMod = { mods = mods.tap(it, SystemClock.uptimeMillis()) },
            onKey = { key, own -> press(key, own) },
            onNewLine = { press(KeyPress("enter", 0), "shift") },
            onPaste = {
                val text = clipboard(context)
                if (text.isNullOrEmpty()) toast(context, context.getString(R.string.toast_clipboard_empty)) else onAct { paste(text) }
            },
            onKeyboard = { toggleKeyboard() },
        )
    }

    when (val open = sheet) {
        null -> Unit
        Sheet.Sessions -> SessionSheet(
            state = state,
            onDismiss = { sheet = null },
            onOpen = { id -> if (id != state.activeSessionId) onOpenSession(id) },
            onCreate = { onAct { createShell(null) } },
        )
        Sheet.Actions -> ActionSheet(onDismiss = { sheet = null }) {
            Group(stringResource(R.string.level_pane)) {
                if (focused != null) {
                    SheetItem(stringResource(R.string.menu_rename_pane), icon = R.drawable.ic_edit) { rename = Rename(Level.Pane, focused.id, focused.label) }
                    SheetItem(stringResource(R.string.menu_close_pane), Colors.danger, icon = R.drawable.ic_close) {
                        confirm = Confirm(Level.Pane, focused.id, focused.label)
                    }
                }
            }
            Group {
                SheetItem(stringResource(R.string.terminal_font_title), icon = R.drawable.ic_settings) { onFontSettings() }
            }
            Group(stringResource(R.string.level_session)) {
                SheetItem(stringResource(R.string.header_sessions), icon = R.drawable.ic_sessions) { sheet = Sheet.Sessions }
                if (view != null) {
                    SheetItem(stringResource(R.string.menu_rename_session), icon = R.drawable.ic_edit) {
                        rename = Rename(Level.Session, view.sessionId, view.label)
                    }
                    SheetItem(stringResource(R.string.menu_kill_session), Colors.danger, icon = R.drawable.ic_delete) {
                        confirm = Confirm(Level.Session, view.sessionId, view.label)
                    }
                }
            }
        }
        Sheet.Navigator -> if (view != null) {
            ActionSheet(onDismiss = { sheet = null }, title = view.label) {
                Group {
                    SheetItem(stringResource(R.string.header_sessions), icon = R.drawable.ic_sessions) { sheet = Sheet.Sessions }
                }
                if (view.windows.size > 1) {
                    Group(label = stringResource(R.string.group_tabs), icons = false) {
                        view.windows.forEach { window ->
                            SheetItem(
                                label = window.label,
                                detail = window.blocks.size.takeIf { it > 1 }?.toString(),
                                marked = window.id == tab?.id,
                            ) {
                                window.focusedBlock()?.let { block -> if (block.id != focused?.id) onAct { focusBlock(block.id) } }
                            }
                        }
                    }
                }
                if (panes.size > 1) {
                    Group(label = stringResource(R.string.group_panes), icons = false) {
                        panes.forEachIndexed { index, block ->
                            SheetItem(
                                label = "${index + 1}  ${paneTitle(block, state.blockStatus[block.id], state.programs[block.id])}",
                                detail = state.blockStatus[block.id]?.let { statusWord(it) },
                                marked = block.id == focused?.id,
                            ) {
                                if (block.id != focused?.id) onAct { focusBlock(block.id) }
                            }
                        }
                    }
                }
            }
        }
    }

    link?.let { url ->
        LinkSheet(
            url = url,
            onDismiss = { link = null },
            onOpen = { openLink(context, url, copied = R.string.toast_link_copied) },
            onCopy = {
                clipboardSet(context, url)
                toast(context, context.getString(R.string.toast_copied))
            },
            onShare = { shareText(context, url) },
        )
    }

    rename?.let { target ->
        var text by remember(target) { mutableStateOf(target.current) }
        AlertDialog(
            onDismissRequest = { rename = null },
            containerColor = Colors.surface,
            title = {
                Text(
                    stringResource(if (target.kind == Level.Pane) R.string.menu_rename_pane else R.string.menu_rename_session),
                    color = Colors.ink,
                )
            },
            text = { Field(stringResource(R.string.field_name), text, autoFocus = true) { text = it } },
            confirmButton = {
                TextButton(onClick = {
                    rename = null
                    val label = text.trim()
                    if (label.isEmpty() || label == target.current) return@TextButton
                    onAct {
                        if (target.kind == Level.Pane) renameBlock(label, target.id) else renameSession(label)
                    }
                }) { Text(stringResource(R.string.action_save), color = Colors.accent) }
            },
            dismissButton = {
                TextButton(onClick = { rename = null }) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
            },
        )
    }

    confirm?.let { target ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = Colors.surface,
            title = {
                Text(
                    stringResource(if (target.kind == Level.Pane) R.string.confirm_close_pane else R.string.confirm_kill_session, target.name),
                    color = Colors.ink,
                )
            },
            text = {
                Text(
                    stringResource(if (target.kind == Level.Pane) R.string.confirm_close_pane_body else R.string.confirm_kill_session_body),
                    color = Colors.muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    onAct { if (target.kind == Level.Pane) closeBlock(target.id) else destroySession() }
                }) { Text(stringResource(R.string.action_close), color = Colors.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel), color = Colors.muted) }
            },
        )
    }
}

/**
 * The floating top bar: a round back button, the pane's tile, the title with the directory it is
 * in, and the session under it. No bar behind it; the paper is the background. The title block
 * opens the tabs and panes of the session.
 */
@Composable
private fun SessionTopBar(
    state: HylaState,
    view: io.rpcx.hylapocket.core.SessionView?,
    status: ProgramRecord?,
    onBack: () -> Unit,
    onNavigate: () -> Unit,
    onMore: () -> Unit,
) {
    // The title is what the program set, as a tab's is on the desktop; the session's name is under it.
    val title = view?.focusedBlock()?.id?.let { state.titles[it] }
        ?: view?.label?.takeIf { it.isNotBlank() }
        ?: state.endpointLabel.ifBlank { stringResource(R.string.app_name) }
    Row(
        Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(R.drawable.ic_back, stringResource(R.string.action_back), onBack, plate = Colors.key, size = 40.dp)
        Row(
            Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(Radius.card))
                .clickable(enabled = view != null, role = Role.Button, onClickLabel = stringResource(R.string.menu_navigate), onClick = onNavigate)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProgramTile(view?.focusedBlock()?.id?.let { state.programs[it] }, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        title,
                        color = Colors.ink,
                        fontSize = TextStyles.body,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(status?.let { statusTint(it) } ?: phaseTint(state.phase), 6.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        view?.focusedBlock()?.let { paneTitle(it, status, state.programs[it.id]) } ?: phaseText(state),
                        color = Colors.muted,
                        fontSize = TextStyles.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Glyph(R.drawable.ic_expand, Colors.muted, size = 18.dp)
                }
            }
        }
        RoundButton(R.drawable.ic_more, stringResource(R.string.action_more), onMore, plate = Colors.key, size = 40.dp)
    }
}

/**
 * The panes of the shown tab, in one floating pill under the pane: a segment per pane with the
 * tile of what runs in it and its name, the one on screen on a soft plate in the action colour.
 * Tapping a segment goes there; a horizontal swipe on the pane does the same.
 */
@Composable
private fun PaneBar(
    tab: io.rpcx.hylapocket.core.WindowInfo?,
    focused: BlockInfo?,
    status: Map<String, ProgramRecord>,
    programs: Map<String, String>,
    onSelect: (BlockInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = tab?.blocks.orEmpty()
    val scroll = rememberScrollState()
    val selected = stringResource(R.string.state_selected)
    Row(
        modifier.floating(RoundedCornerShape(50)).horizontalScroll(scroll).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        blocks.forEach { block ->
            val on = block.id == focused?.id
            val record = status[block.id]
            val busy = record?.state == "working" || record?.state == "blocked"
            Box(
                Modifier.height(48.dp)
                    .clickable(role = Role.Tab) { if (!on) onSelect(block) }
                    .semantics { if (on) stateDescription = selected }
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    Modifier.fillMaxHeight().clip(RoundedCornerShape(50))
                        .then(if (on) Modifier.background(Colors.keyOn) else Modifier)
                        .padding(start = 6.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ProgramTile(programs[block.id], size = 24.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        paneTitle(block, record, programs[block.id]),
                        color = if (on) Colors.accent else Colors.ink,
                        fontSize = TextStyles.subhead,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 104.dp),
                    )
                    if (busy) {
                        Spacer(Modifier.width(6.dp))
                        Dot(statusTint(record), 6.dp)
                    }
                }
            }
        }
    }
}

/** Whether the phone keyboard is up, which decides which way the keyboard key points. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun imeShown(): Boolean = WindowInsets.isImeVisible

/** A pane left at the default "shell" label is named after the program running in it. */
internal fun paneTitle(block: BlockInfo, status: ProgramRecord?, program: String? = null): String {
    val app = status?.app?.takeIf { it.isNotBlank() } ?: program
    return if (block.label == "shell" && app != null) app else block.label
}

/** What the program in the pane is doing, in the app's words; null when hylad has not said. */
@Composable
internal fun statusWord(status: ProgramRecord): String? = statusWordRes(status)?.let { stringResource(it) }

internal fun statusWordRes(status: ProgramRecord): Int? = when (status.state) {
    "working" -> R.string.state_running
    "blocked" -> R.string.state_waiting_you
    "done" -> R.string.state_done
    "error" -> R.string.state_failed
    "idle" -> R.string.state_idle
    else -> null
}

/** Running is green, waiting on a person is amber, failure is red, the rest is quiet grey. */
internal fun statusTint(status: ProgramRecord): Color = when {
    status.state == "working" -> Colors.ok
    status.state == "blocked" -> Colors.amber
    status.state == "error" -> Colors.danger
    else -> Colors.muted
}

/** The connection's own state, for when no program has reported one. */
internal fun phaseTint(phase: Phase): Color = when (phase) {
    Phase.Live -> Colors.ok
    Phase.Failed -> Colors.danger
    Phase.Connecting, Phase.Reconnecting -> Colors.amber
    else -> Colors.muted
}

/** How long a failed action stays on the terminal screen. */
internal const val NOTICE_MS = 4_000L

/** A tab's name: the session's name, since a Hyla session is one tab here. */
internal fun tabName(block: BlockInfo, status: ProgramRecord?, program: String?): String = paneTitle(block, status, program)
