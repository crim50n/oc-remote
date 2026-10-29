package dev.minios.ocremote.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.AppDropdownMenu
import dev.minios.ocremote.ui.components.AppDropdownMenuItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopTerminalToolbar(
    tabs: List<TerminalTabUi>, activeId: String?, connected: Boolean, canDock: Boolean, docked: Boolean,
    pickerOpen: Boolean, onPicker: (Boolean) -> Unit, extraKeys: Boolean, onExtraKeys: () -> Unit,
    onSelect: (String) -> Unit, onNew: () -> Unit, onRecover: (String) -> Unit, onClose: (String) -> Unit,
    onDock: () -> Unit, onHide: () -> Unit, onPaste: () -> Unit, onKeyboard: () -> Unit, onResetFont: () -> Unit,
    amoled: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val active = tabs.firstOrNull { it.id == activeId }
    Surface(color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints {
        val showDockButton = canDock && maxWidth >= 360.dp
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(1f)) {
                val scroll = rememberScrollState()
                val overflow = 96.dp * tabs.size > (maxWidth - 48.dp) && maxWidth >= 184.dp
                val available = (maxWidth - 48.dp - if (overflow) 40.dp else 0.dp).coerceAtLeast(0.dp)
                val tabWidth = (available / tabs.size.coerceAtLeast(1)).coerceIn(96.dp, 112.dp)
                val density = androidx.compose.ui.platform.LocalDensity.current
                LaunchedEffect(activeId, tabs.map { it.id }, tabWidth, available) {
                    val index = tabs.indexOfFirst { it.id == activeId }
                    if (index >= 0) with(density) {
                        val start = (tabWidth.toPx() * index).toInt()
                        val end = start + tabWidth.roundToPx()
                        when {
                            start < scroll.value -> scroll.animateScrollTo(start)
                            end > scroll.value + available.roundToPx() -> scroll.animateScrollTo(end - available.roundToPx())
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.width((tabWidth * tabs.size).coerceAtMost(available))
                        .horizontalScroll(scroll).selectableGroup()) {
                        tabs.forEach { tab ->
                            val selected = tab.id == activeId
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = { PlainTooltip { Text("${tab.title} · ${stringResource(terminalTabStateLabel(tab.state))}") } },
                                state = rememberTooltipState(),
                            ) {
                                Surface(
                                    modifier = Modifier.width(tabWidth).height(48.dp).padding(horizontal = 2.dp, vertical = 4.dp),
                                    shape = MaterialTheme.shapes.small,
                                    color = if (amoled) Color.Black else if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
                                        else MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Row(Modifier.fillMaxSize()
                                        .selectable(selected, role = Role.Tab, onClick = { focus.clearFocus(); onSelect(tab.id) })
                                        .padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (tab.state == TerminalTabState.Starting || tab.state == TerminalTabState.Reconnecting) {
                                            CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
                                        } else if (!tab.connected) Icon(Lucide.WifiOff, stringResource(terminalTabStateLabel(tab.state)),
                                            Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                                        if (!tab.connected) Spacer(Modifier.width(6.dp))
                                        Text(tab.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                                        IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(40.dp)) {
                                            Icon(Lucide.X, stringResource(R.string.chat_terminal_close_tab), Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    IconButton(onClick = onNew, enabled = connected) { Icon(Lucide.Plus, stringResource(R.string.chat_terminal_new_tab)) }
                    if (overflow) IconButton(onClick = { focus.clearFocus(); onPicker(true) }, modifier = Modifier.size(40.dp)) {
                        Icon(Lucide.ChevronDown, stringResource(R.string.tool_terminal), Modifier.size(18.dp))
                    }
                }
                AppDropdownMenu(pickerOpen, { onPicker(false) }) {
                    tabs.forEach { tab ->
                        AppDropdownMenuItem(text = {
                            Column {
                                Text(tab.title)
                                Text(stringResource(terminalTabStateLabel(tab.state)), style = MaterialTheme.typography.labelSmall)
                            }
                        }, leadingIcon = if (tab.id == activeId) { { Icon(Lucide.Check, null) } } else null,
                            onClick = { onSelect(tab.id) })
                    }
                }
            }
            if (showDockButton) IconButton(onClick = onDock) {
                Icon(if (docked) Lucide.Maximize2 else Lucide.Columns2,
                    stringResource(if (docked) R.string.terminal_expand else R.string.terminal_dock_right))
            }
            Box {
                IconButton(onClick = { focus.clearFocus(); menu = true }) { Icon(Lucide.EllipsisVertical, stringResource(R.string.more_options)) }
                AppDropdownMenu(menu, { menu = false }) {
                    if (canDock && !showDockButton) AppDropdownMenuItem(
                        text = { Text(stringResource(if (docked) R.string.terminal_expand else R.string.terminal_dock_right)) },
                        onClick = onDock,
                    )
                    AppDropdownMenuItem(text = { Text(stringResource(R.string.terminal_paste)) }, enabled = connected, onClick = onPaste)
                    AppDropdownMenuItem(text = { Text(stringResource(R.string.chat_terminal_keyboard)) }, onClick = onKeyboard)
                    AppDropdownMenuItem(text = { Text(stringResource(R.string.terminal_extra_keys)) },
                        trailingIcon = if (extraKeys) { { Icon(Lucide.Check, null) } } else null, onClick = onExtraKeys)
                    AppDropdownMenuItem(text = { Text(stringResource(R.string.terminal_zoom_reset)) }, onClick = onResetFont)
                    if (active != null && active.recoveryAction != TerminalRecoveryAction.None) {
                        AppDropdownMenuItem(text = { Text(stringResource(terminalRecoveryLabel(active.recoveryAction))) },
                            enabled = connected, onClick = { onRecover(active.id) })
                    }
                    HorizontalDivider()
                    AppDropdownMenuItem(text = { Text(stringResource(R.string.terminal_hide)) }, onClick = onHide)
                    if (active != null) AppDropdownMenuItem(text = { Text(stringResource(R.string.chat_terminal_close_tab)) },
                        onClick = { onClose(active.id) })
                }
            }
        }
        }
    }
}
