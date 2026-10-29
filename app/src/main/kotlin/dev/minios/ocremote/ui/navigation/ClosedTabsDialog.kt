package dev.minios.ocremote.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import dev.minios.ocremote.R
import dev.minios.ocremote.data.repository.ClosedWorkspaceTab
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.ui.components.AppDialog

@Composable
internal fun ClosedTabsDialog(entries: List<ClosedWorkspaceTab>, servers: List<ServerConfig>,
    onRestore: (String) -> Unit, onDismiss: () -> Unit) {
    AppDialog(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringResource(R.string.tabs_recently_closed), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onDismiss) { Icon(Lucide.X, stringResource(R.string.close)) }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).weight(1f, fill = false)) {
            items(entries.asReversed(), key = { it.tab.key }) { entry ->
                val server = servers.firstOrNull { it.id == entry.tab.serverId }
                ListItem(
                    headlineContent = { Text(entry.tab.title.ifBlank { entry.tab.key }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(server?.displayName ?: stringResource(if (entry.tab.serverId == null) R.string.app_name else R.string.session_tabs_server_removed)) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    modifier = Modifier.clickable(enabled = entry.tab.serverId == null || server != null) { onRestore(entry.tab.key) },
                )
            }
        }
    }
}
