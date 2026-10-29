package dev.minios.ocremote.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import dev.minios.ocremote.R
import dev.minios.ocremote.data.repository.PageTab
import dev.minios.ocremote.data.repository.WorkspaceTab
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.ui.components.AppDropdownMenu
import dev.minios.ocremote.ui.components.AppDropdownMenuItem

internal fun filterWorkspaceTabs(tabs: List<WorkspaceTab>, servers: List<ServerConfig>, sessions: List<Session>, query: String): List<WorkspaceTab> {
    val needle = query.trim()
    if (needle.isEmpty()) return tabs
    val names = servers.associate { it.id to it.displayName }
    val directories = sessions.associate { it.id to it.directory }
    return tabs.filter { tab ->
        listOf(tab.title, names[tab.serverId].orEmpty(), directories[tab.sessionId].orEmpty(), (tab as? PageTab)?.directory.orEmpty())
            .any { it.contains(needle, ignoreCase = true) }
    }
}

/** An anchored, scrollable popup; it never navigates to the mobile fullscreen switcher. */
@Composable
internal fun DesktopTabSearch(
    tabs: List<WorkspaceTab>, servers: List<ServerConfig>, sessions: List<Session>, activeKey: String?,
    canRestore: Boolean, onSelect: (WorkspaceTab) -> Unit, onClose: (WorkspaceTab) -> Unit, onHistory: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(tabs, servers, sessions, query) { filterWorkspaceTabs(tabs, servers, sessions, query) }
    Box {
        IconButton(onClick = { expanded = !expanded; query = "" }) {
            Icon(Lucide.ChevronDown, stringResource(R.string.session_tabs_count, tabs.size))
        }
        AppDropdownMenu(expanded, { expanded = false }, modifier = Modifier.width(360.dp)) {
            val searchHint = stringResource(R.string.tabs_search_hint)
            Surface(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Lucide.Search, null, Modifier.size(20.dp))
                    BasicTextField(query, { query = it }, singleLine = true,
                        modifier = Modifier.weight(1f).semantics { contentDescription = searchHint },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { field ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) Text(searchHint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                field()
                            }
                        })
                }
            }
            filtered.forEach { tab ->
                val server = servers.firstOrNull { it.id == tab.serverId }
                AppDropdownMenuItem(
                    modifier = Modifier.testTag("desktopTabSearchResult:${tab.key}"),
                    text = {
                        Column {
                            Text(tab.title.ifBlank { tab.key }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (tab.serverId != null) Text(server?.displayName ?: stringResource(R.string.session_tabs_server_removed),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(if (tab.key == activeKey) Lucide.Check else if (tab is PageTab) workspacePageIcon(tab.page) else Lucide.MessageSquare, null) },
                    trailingIcon = { IconButton(onClick = { onClose(tab) }) { Icon(Lucide.X, stringResource(R.string.session_tabs_close), Modifier.size(18.dp)) } },
                    enabled = workspaceTabAvailable(tab, servers),
                    onClick = { onSelect(tab) },
                )
            }
            if (filtered.isEmpty()) Text(stringResource(R.string.tabs_search_empty), Modifier.padding(16.dp))
            HorizontalDivider()
            AppDropdownMenuItem(text = { Text(stringResource(R.string.tabs_recently_closed)) },
                leadingIcon = { Icon(Lucide.History, null) }, enabled = canRestore, onClick = onHistory)
        }
    }
}
