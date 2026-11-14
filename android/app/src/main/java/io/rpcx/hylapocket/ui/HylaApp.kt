package io.rpcx.hylapocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel

// The app root: the screen for the route, and the dialogs that float over both.

@Composable
fun HylaPocketApp(model: HylaViewModel = viewModel()) {
    val route by model.route.collectAsState()
    val hosts by model.hosts.collectAsState()
    val state by model.hyla.collectAsState()
    val fontSize by model.fontSize.collectAsState()
    val themeMode by model.themeMode.collectAsState()
    val terminalTypeface by model.terminalTypeface.collectAsState()
    val terminalFont by model.terminalFont.collectAsState()
    val opening by model.opening.collectAsState()
    var fontSettingsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { model.restore() }
    LifecycleStartEffect(model) {
        model.setForeground(true)
        onStopOrDispose { model.setForeground(false) }
    }
    HylaTheme(mode = themeMode) {
        Box(Modifier.fillMaxSize().background(Colors.bg)) {
            when (route) {
                Route.Home -> HomeScreen(
                    hosts = hosts,
                    connected = model.host,
                    state = state,
                    opening = opening,
                    onConnect = { model.connect(it) },
                    onOpenSession = model::openSessionOn,
                    onOpenPane = model::openPaneOn,
                    onNewSession = model::createSessionOn,
                    onDisconnect = model::disconnect,
                    onRetry = model::retryNow,
                    onSave = model::saveHost,
                    onDelete = model::deleteHost,
                    onFontSettings = { fontSettingsOpen = true },
                    themeMode = themeMode,
                    onThemeMode = model::setThemeMode,
                    projectOrder = model::projectOrder,
                    onProjectOrder = model::setProjectOrder,
                    loadCollapsed = model::collapsedProjects,
                    onKillSession = model::killSession,
                    saveCollapsed = model::setCollapsedProjects,
                )
                Route.Term -> TermScreen(
                    state = state,
                    fontSize = fontSize,
                    terminalTypeface = terminalTypeface,
                    onFontSize = model::setFontSize,
                    onFontSettings = { fontSettingsOpen = true },
                    themeMode = themeMode,
                    onThemeMode = model::setThemeMode,
                    onBack = model::showHome,
                    onOpenSession = model::openSession,
                    watchPane = model::watchPane,
                    onRetry = model::retryNow,
                    onDismissNotice = model::dismissNotice,
                    onAct = model::act,
                )
            }
        }
        if (fontSettingsOpen) {
            FontSettingsDialog(
                state = terminalFont,
                size = fontSize,
                onSelect = model::selectTerminalFont,
                onSize = model::setFontSize,
                onDismiss = { fontSettingsOpen = false },
            )
        }
    }
}
