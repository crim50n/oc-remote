package dev.minios.ocremote.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import dev.minios.ocremote.R
import dev.minios.ocremote.data.repository.SessionTab
import dev.minios.ocremote.data.repository.WorkspaceTab
import dev.minios.ocremote.data.repository.PageTab
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.ui.components.isAmoledTheme
import dev.minios.ocremote.ui.components.AppDropdownMenu
import dev.minios.ocremote.ui.components.AppDropdownMenuItem
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

internal fun adjacentSessionTab(tabs: List<WorkspaceTab>, activeKey: String?, backwards: Boolean): WorkspaceTab? {
    if (tabs.isEmpty()) return null
    val index = tabs.indexOfFirst { it.key == activeKey }
    if (index < 0) return if (backwards) tabs.last() else tabs.first()
    return tabs[Math.floorMod(index + if (backwards) -1 else 1, tabs.size)]
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopSessionTabs(
    tabs: List<WorkspaceTab>, servers: List<ServerConfig>, statuses: Map<String, SessionStatus>,
    attention: Set<String>, connected: Set<String>, activeKey: String?,
    onSelect: (WorkspaceTab) -> Unit, onClose: (WorkspaceTab) -> Unit,
    onOrder: (List<String>, List<String>) -> Unit, onAdd: () -> Unit, onHistory: () -> Unit = {},
    onMenu: () -> Unit = {}, canRestore: Boolean = false,
    onTabAction: (WorkspaceTab, SessionTabAction) -> Unit = { _, _ -> },
    sessions: List<Session> = emptyList(),
    tabsEnabled: Boolean = true, sessionActive: Boolean = true, onSettings: () -> Unit = {},
    onAbout: () -> Unit = {}, onServers: () -> Unit = {}, onAddServer: () -> Unit = {},
) {
    var globalMenu by remember { mutableStateOf(false) }
    val stripFocus = remember { FocusRequester() }
    LaunchedEffect(sessionActive) { globalMenu = false }
    val state = rememberLazyListState()
    val latestTabs by rememberUpdatedState(tabs)
    var dragged by remember { mutableStateOf<List<WorkspaceTab>?>(null) }
    var base by remember { mutableStateOf<List<String>>(emptyList()) }
    val reorder = rememberReorderableLazyListState(state) { from, to ->
        val list = dragged ?: return@rememberReorderableLazyListState
        val source = list.indexOfFirst { it.key == from.key }
        val target = list.indexOfFirst { it.key == to.key }
        if (source >= 0 && target >= 0) dragged = list.toMutableList().apply { add(target, removeAt(source)) }
    }
    LaunchedEffect(activeKey, tabs.size) {
        val index = tabs.indexOfFirst { it.key == activeKey }
        if (index >= 0 && dragged == null) state.animateScrollToItem(index)
    }
    val amoled = isAmoledTheme()
    Surface(modifier = Modifier.focusRequester(stripFocus).focusable(), color = MaterialTheme.colorScheme.surface) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (tabsEnabled) DesktopTabSearch(tabs, servers, sessions, activeKey, canRestore, onSelect, onClose, onHistory)
                BoxWithConstraints(Modifier.weight(1f)) {
                val pinnedCount = tabs.count { it.pinned }
                val widths = desktopTabWidths(maxWidth.value, pinnedCount, tabs.size - pinnedCount)
                val naturalWidth = (pinnedCount * widths.pinned + (tabs.size - pinnedCount) * widths.normal +
                    16f + (tabs.size - 1).coerceAtLeast(0) * 4f).dp
                val stripWidth = minOf(naturalWidth, (maxWidth - if (tabsEnabled) 48.dp else 0.dp).coerceAtLeast(0.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(state = state, modifier = Modifier.width(stripWidth), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(dragged ?: tabs, key = { it.key }) { tab ->
                        val server = servers.firstOrNull { it.id == tab.serverId }
                        val active = tab.key == activeKey
                        val compactPinned = tab.pinned && widths.pinned < 80f
                        val working = statuses[tab.sessionId] is SessionStatus.Busy || statuses[tab.sessionId] is SessionStatus.Retry
                        ReorderableItem(reorder, key = tab.key) { dragging ->
                            var menu by remember { mutableStateOf(false) }
                            Box {
                            TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                state = rememberTooltipState(), tooltip = {
                                    PlainTooltip {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(tab.title.ifBlank { tab.key }, style = MaterialTheme.typography.titleSmall)
                                            if (server != null) Text(server.displayName)
                                            sessions.firstOrNull { it.id == tab.sessionId }?.directory?.let { Text(it) }
                                            if (tab is PageTab && tab.directory.isNotBlank()) Text(tab.directory)
                                            if (working) Text(stringResource(R.string.session_tabs_working))
                                            else if (tab.serverId != null && tab.serverId !in connected) Text(stringResource(R.string.session_tabs_offline))
                                        }
                                    }
                                }) {
                                Surface(
                                    modifier = Modifier.width((if (tab.pinned) widths.pinned else widths.normal).dp).height(44.dp)
                                        .then(tabMouseActions(tab.key, { menu = true }, { onClose(tab) })).draggableHandle(
                                        onDragStarted = { base = latestTabs.map { it.key }; dragged = latestTabs },
                                        onDragStopped = { dragged?.let { onOrder(base, it.map(WorkspaceTab::key)) }; dragged = null },
                                    ),
                                    shape = MaterialTheme.shapes.small,
                                    color = if (amoled) Color.Black else if (active) MaterialTheme.colorScheme.surfaceContainerHigh
                                        else MaterialTheme.colorScheme.surface,
                                    border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                    shadowElevation = if (dragging && !amoled) 6.dp else 0.dp,
                                ) {
                                    Row(Modifier.fillMaxWidth().clickable(enabled = (tab.serverId == null || server != null) && !dragging) { stripFocus.requestFocus(); onSelect(tab) }
                                        .padding(start = if (compactPinned) 0.dp else 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = if (compactPinned) Arrangement.Center else Arrangement.spacedBy(6.dp)) {
                                        if (working) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                                        else if (tab.serverId != null && tab.serverId !in connected) Icon(Lucide.WifiOff, stringResource(R.string.session_tabs_offline), Modifier.size(14.dp))
                                        else if (tab.sessionId in attention) Icon(Lucide.CircleAlert, stringResource(R.string.session_tabs_attention), Modifier.size(14.dp))
                                        else if (tab.pinned) Icon(Lucide.Pin, stringResource(R.string.tabs_pin), Modifier.size(14.dp))
                                        else if (tab is PageTab) Icon(workspacePageIcon(tab.page), null, Modifier.size(16.dp))
                                        if (!compactPinned) Column(Modifier.weight(1f)) {
                                            Text(tab.title.ifBlank { tab.key }, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                            if (tabs.count { it.title == tab.title } > 1) Text(server?.displayName.orEmpty(),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                                        }
                                        if (!tab.pinned) IconButton(onClick = { onClose(tab) }) { Icon(Lucide.X, stringResource(R.string.session_tabs_close), Modifier.size(18.dp)) }
                                    }
                                }
                            }
                            SessionTabContextMenu(tab, menu, tab.serverId in connected, canRestore,
                                onDismiss = { menu = false }, onAction = { menu = false; onTabAction(tab, it) })
                            }
                        }
                    }
                }
                if (tabsEnabled) IconButton(onClick = onAdd) { Icon(Lucide.Plus, stringResource(R.string.session_tabs_add)) }
                Spacer(Modifier.weight(1f))
                }
                }
                Box {
                    IconButton(onClick = { stripFocus.requestFocus(); if (sessionActive) onMenu() else globalMenu = true }) {
                        Icon(Lucide.EllipsisVertical, stringResource(R.string.more_options))
                    }
                    AppDropdownMenu(globalMenu, { globalMenu = false }) {
                        AppDropdownMenuItem(text = { Text(stringResource(R.string.menu_servers_sessions)) },
                            leadingIcon = { Icon(Lucide.Server, null) }, onClick = { globalMenu = false; onServers() })
                        AppDropdownMenuItem(text = { Text(stringResource(R.string.home_add_server)) },
                            leadingIcon = { Icon(Lucide.Plus, null) }, onClick = { globalMenu = false; onAddServer() })
                        AppDropdownMenuItem(text = { Text(stringResource(R.string.settings_title)) },
                            leadingIcon = { Icon(Lucide.Settings, null) }, onClick = { globalMenu = false; onSettings() })
                        AppDropdownMenuItem(text = { Text(stringResource(R.string.about_title)) },
                            leadingIcon = { Icon(Lucide.Info, null) }, onClick = { globalMenu = false; onAbout() })
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
