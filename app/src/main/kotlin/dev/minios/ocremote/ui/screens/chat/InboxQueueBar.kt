package dev.minios.ocremote.ui.screens.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.AppDropdownMenu
import dev.minios.ocremote.ui.components.AppDropdownMenuItem

data class InboxQueueItem(val id: String, val text: String, val delivery: String)

@Composable
internal fun InboxQueueBar(
    items: List<InboxQueueItem>, working: Set<String>, connected: Boolean,
    onAction: (String, String?) -> Unit,
) {
    if (items.isEmpty()) return
    val window = dev.minios.ocremote.ui.components.rememberVisibleWindowSize()
    val compact = window.height < 480.dp
    var expanded by rememberSaveable(compact) { mutableStateOf(!compact) }
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(stringResource(R.string.chat_inbox_title, items.size), Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        IconButton(onClick = { expanded = !expanded }) {
            Icon(if (expanded) Lucide.ChevronUp else Lucide.ChevronDown,
                stringResource(if (expanded) R.string.chat_collapse else R.string.chat_expand))
        }
        }
        if (expanded) LazyColumn(Modifier.fillMaxWidth().heightIn(max =
            dev.minios.ocremote.ui.components.composerExtraHeight(window.height))) {
            items(items, key = { it.id }) { item ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = {
                        Text(item.text.ifBlank { stringResource(R.string.chat_inbox_attachments) }, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    },
                    supportingContent = {
                        Text(stringResource(if (item.delivery == "steer") R.string.chat_inbox_steer else R.string.chat_inbox_queue),
                            style = MaterialTheme.typography.labelSmall)
                    },
                    trailingContent = {
                        if (item.id in working) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Box {
                            IconButton(onClick = { menu = true }, enabled = connected) {
                                Icon(Lucide.EllipsisVertical, stringResource(R.string.more_options))
                            }
                            AppDropdownMenu(menu, { menu = false }) {
                                AppDropdownMenuItem(
                                    text = { Text(stringResource(if (item.delivery == "steer") R.string.chat_inbox_set_queue else R.string.chat_inbox_set_steer)) },
                                    leadingIcon = { Icon(Lucide.ArrowRight, null) },
                                    enabled = connected,
                                    onClick = { menu = false; onAction(item.id, if (item.delivery == "steer") "queue" else "steer") },
                                )
                                AppDropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_inbox_cancel)) },
                                    leadingIcon = { Icon(Lucide.X, null) }, enabled = connected,
                                    onClick = { menu = false; onAction(item.id, null) },
                                )
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                )
            }
        }
    }
}
