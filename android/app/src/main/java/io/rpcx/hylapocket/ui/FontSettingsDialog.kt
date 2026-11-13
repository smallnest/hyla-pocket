package io.rpcx.hylapocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.rpcx.hylapocket.R
import io.rpcx.hylapocket.data.MAX_TERMINAL_FONT_SP
import io.rpcx.hylapocket.data.MIN_TERMINAL_FONT_SP
import io.rpcx.hylapocket.data.TerminalFontState

/**
 * The font the terminal draws with (the system's monospace, or JetBrains Mono fetched once) and the
 * size it draws it at: the pinch on a pane lands on the same size.
 */
@Composable
internal fun FontSettingsDialog(
    state: TerminalFontState,
    size: Float,
    onSelect: (Boolean) -> Unit,
    onSize: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Colors.surface,
        title = { Text(stringResource(R.string.terminal_font_title)) },
        text = {
            Column {
                SizeRow(size, onSize)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.terminal_font_hint), color = Colors.muted, fontSize = TextStyles.label)
                Spacer(Modifier.height(16.dp))
                FontOption(
                    stringResource(R.string.terminal_font_system),
                    stringResource(R.string.terminal_font_system_detail),
                    selected = !state.selected,
                    enabled = !state.downloading,
                ) { onSelect(false) }
                FontOption(
                    stringResource(R.string.terminal_font_download),
                    stringResource(if (state.available) R.string.terminal_font_saved else R.string.terminal_font_download),
                    selected = state.selected,
                    enabled = !state.downloading && !state.initializing,
                ) { onSelect(true) }
                if (state.initializing) Text(stringResource(R.string.terminal_font_loading), color = Colors.muted)
                if (state.downloading) {
                    Text(stringResource(R.string.terminal_font_downloading, (state.progress * 100).toInt()), color = Colors.muted)
                }
                if (state.failed) Text(stringResource(R.string.terminal_font_failed), color = Colors.danger)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close), color = Colors.accent) }
        },
    )
}

/** The size a pane draws its text at: a step at a time, in a sample of the font itself. */
@Composable
private fun SizeRow(size: Float, onSize: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onSize(size - 1f) }, enabled = size > MIN_TERMINAL_FONT_SP) {
            Text(stringResource(R.string.terminal_size_smaller), color = Colors.ink, fontSize = 18.sp)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.terminal_size_value, size.toInt()),
                color = Colors.ink,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(R.string.terminal_size_sample),
                color = Colors.muted,
                fontSize = (size / 2f).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = { onSize(size + 1f) }, enabled = size < MAX_TERMINAL_FONT_SP) {
            Text(stringResource(R.string.terminal_size_larger), color = Colors.ink, fontSize = 18.sp)
        }
    }
}

@Composable
private fun FontOption(title: String, detail: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) Colors.ink else Colors.muted, fontWeight = FontWeight.Medium)
            Text(detail, color = Colors.muted, fontSize = TextStyles.label)
        }
        if (selected) Box(Modifier.width(8.dp).height(8.dp).background(Colors.accent, CircleShape))
    }
}
