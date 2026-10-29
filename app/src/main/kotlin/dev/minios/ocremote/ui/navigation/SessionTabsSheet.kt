package dev.minios.ocremote.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import dev.minios.ocremote.ui.components.AppDialog
import dev.minios.ocremote.ui.components.readableContentWidth
import dev.minios.ocremote.ui.components.AppPickerItemShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.activity.compose.BackHandler
import dev.minios.ocremote.ui.components.backIcon
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.composables.icons.lucide.*
import dev.minios.ocremote.R
import dev.minios.ocremote.data.repository.SessionTab
import dev.minios.ocremote.data.repository.WorkspaceTab
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.ui.components.isAmoledTheme
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionTabsSheet(
    tabs: List<WorkspaceTab>, servers: List<ServerConfig>, sessions: List<Session>,
    statuses: Map<String, SessionStatus>, attentionSessionIds: Set<String>, connectedServerIds: Set<String>,
    activeKey: String?, canUndo: Boolean,
    onSelect: (WorkspaceTab) -> Unit, onClose: (WorkspaceTab) -> Unit,
    onUndo: () -> Unit, onMove: (WorkspaceTab, Int) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit,
    onOrder: (List<String>, List<String>) -> Unit = { _, _ -> },
    serverSessions: Map<String, Set<String>> = emptyMap(),
    openSessionPicker: Boolean = false,
    onTabAction: (WorkspaceTab, SessionTabAction) -> Unit = { _, _ -> },
    onHistory: () -> Unit = {},
    onSessionPickerOpened: () -> Unit = {},
    onPickerDismissed: () -> Unit = {},
) {
    var searching by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(searching) {
        if (!searching) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val currentTabs by rememberUpdatedState(tabs)
    var draggedTabs by remember { mutableStateOf<List<WorkspaceTab>?>(null) }
    var dragBase by remember { mutableStateOf<List<String>>(emptyList()) }
    val shownTabs = draggedTabs ?: tabs
    val haptic = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val undo by rememberUpdatedState(onUndo)
    var undoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(openSessionPicker) {
        if (openSessionPicker) { adding = true; onSessionPickerOpened() }
    }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val current = draggedTabs ?: return@rememberReorderableLazyListState
        val source = current.indexOfFirst { it.key == from.key }
        val target = current.indexOfFirst { it.key == to.key }
        if (source >= 0 && target >= 0 && source != target) {
            draggedTabs = current.toMutableList().apply { add(target, removeAt(source)) }
        }
    }
    val amoled = isAmoledTheme()
    BackHandler {
        if (searching) { searching = false; query = "" } else onDismiss()
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().readableContentWidth()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (searching) { searching = false; query = "" } else onDismiss() }) {
                    Icon(backIcon(), stringResource(R.string.back))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onHistory, enabled = canUndo) {
                    Icon(Lucide.History, stringResource(R.string.tabs_recently_closed))
                }
                IconButton(onClick = { searching = !searching; query = "" }) {
                    Icon(Lucide.Search, stringResource(R.string.session_tabs_search))
                }
            }
            if (searching) OutlinedTextField(query, { query = it }, singleLine = true,
                label = { Text(stringResource(R.string.session_tabs_search)) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
            LazyColumn(state = listState, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shownTabs.filter { tab -> query.isBlank() || listOf(tab.title,
                    servers.firstOrNull { it.id == tab.serverId }?.displayName.orEmpty(),
                    sessions.firstOrNull { it.id == tab.sessionId }?.directory.orEmpty())
                    .any { it.contains(query, ignoreCase = true) } }, key = { it.key }) { tab ->
                    val server = servers.firstOrNull { it.id == tab.serverId }
                    val session = sessions.firstOrNull { it.id == tab.sessionId }
                    val status = when {
                        tab.serverId != null && tab.serverId !in connectedServerIds -> R.string.session_tabs_offline
                        tab.sessionId in attentionSessionIds -> R.string.session_tabs_attention
                        statuses[tab.sessionId] is SessionStatus.Busy || statuses[tab.sessionId] is SessionStatus.Retry -> R.string.session_tabs_working
                        tab.hasDraft -> R.string.session_tabs_draft
                        tab.key != activeKey && (session?.time?.updated ?: 0) > tab.lastActive -> R.string.session_tabs_updated
                        else -> null
                    }
                    ReorderableItem(reorderState, key = tab.key) { dragging ->
                        var tabMenu by remember { mutableStateOf(false) }
                        val earlierLabel = stringResource(R.string.session_tabs_earlier)
                        val laterLabel = stringResource(R.string.session_tabs_later)
                        Surface(
                            modifier = Modifier.longPressDraggableHandle(enabled = !searching,
                                onDragStarted = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    dragBase = currentTabs.map { it.key }
                                    draggedTabs = currentTabs
                                },
                                onDragStopped = {
                                    draggedTabs?.let { onOrder(dragBase, it.map { tab -> tab.key }) }
                                    draggedTabs = null
                                },
                            ).semantics {
                                customActions = buildList {
                                    if (tabs.firstOrNull()?.key != tab.key) add(CustomAccessibilityAction(earlierLabel) {
                                        onMove(tab, -1); true
                                    })
                                    if (tabs.lastOrNull()?.key != tab.key) add(CustomAccessibilityAction(laterLabel) {
                                        onMove(tab, 1); true
                                    })
                                }
                            },
                            shadowElevation = if (dragging && !amoled) 8.dp else 0.dp,
                            color = if (amoled) androidx.compose.ui.graphics.Color.Black
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = if (tab.key == activeKey || amoled) BorderStroke(1.dp,
                                if (tab.key == activeKey) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null,
                            shape = dev.minios.ocremote.ui.components.AppCardShape,
                        ) {
                            Row(Modifier.fillMaxWidth().clickable(enabled = (tab.serverId == null || server != null) && !dragging) { onSelect(tab) }
                                .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(server?.displayName ?: stringResource(if (tab.serverId == null) R.string.app_name else R.string.session_tabs_server_removed),
                                            modifier = Modifier.weight(1f, fill = false),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        session?.directory?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { directory ->
                                            Text(directory, modifier = Modifier.weight(1f),
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                                                style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (tab.pinned) Icon(Lucide.Pin, stringResource(R.string.tabs_pin), Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f))
                                    Text(session?.title ?: tab.title.ifBlank { tab.key }, modifier = Modifier.weight(1f),
                                         style = MaterialTheme.typography.bodyLarge,
                                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text(status?.let { stringResource(it) }.orEmpty(), style = MaterialTheme.typography.labelSmall,
                                        color = if (status == R.string.session_tabs_offline || status == R.string.session_tabs_attention)
                                            MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                        minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Box {
                                    IconButton(onClick = { tabMenu = true }) { Icon(Lucide.EllipsisVertical, stringResource(R.string.more_options)) }
                                    SessionTabContextMenu(tab, tabMenu, tab.serverId in connectedServerIds, canUndo,
                                        vertical = true,
                                        onDismiss = { tabMenu = false }, onAction = { tabMenu = false; onTabAction(tab, it) })
                                }
                                IconButton(onClick = {
                                    onClose(tab)
                                    undoJob?.cancel()
                                    snackbar.currentSnackbarData?.dismiss()
                                    undoJob = scope.launch {
                                        if (snackbar.showSnackbar(context.getString(R.string.session_tab_closed),
                                            context.getString(R.string.chat_restore), duration = SnackbarDuration.Short)
                                            == SnackbarResult.ActionPerformed) undo()
                                    }
                                }) {
                                    Icon(Lucide.X, stringResource(R.string.session_tabs_close))
                                }
                            }
                        }
                    }
                }
            }
        }
        dev.minios.ocremote.ui.components.AppSnackbarHost(snackbar,
            Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp).padding(bottom = 88.dp))
        FloatingActionButton(
            onClick = { adding = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).then(
                if (amoled) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, FloatingActionButtonDefaults.shape) else Modifier,
            ),
            containerColor = if (amoled) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (amoled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer,
            elevation = if (amoled) FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
                else FloatingActionButtonDefaults.elevation(),
        ) {
            Icon(Lucide.Plus, stringResource(R.string.session_tabs_add))
        }
        if (adding) {
            var sessionQuery by remember { mutableStateOf("") }
            val candidates = servers.flatMap { server ->
                sessions.filter { it.id in serverSessions[server.id].orEmpty() && !it.isArchived }
                    .map { server to it }
            }.sortedByDescending { it.second.time.updated }.filter { (server, session) ->
                listOf(server.displayName, session.title.orEmpty(), session.directory)
                    .any { it.contains(sessionQuery, ignoreCase = true) }
            }
            AppDialog(onDismissRequest = { adding = false; onPickerDismissed() },
                modifier = Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).height(48.dp),
                        shape = AppPickerItemShape,
                        color = if (amoled) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Lucide.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            BasicTextField(sessionQuery, { sessionQuery = it }, singleLine = true,
                                modifier = Modifier.weight(1f),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { field ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (sessionQuery.isEmpty()) Text(stringResource(R.string.session_tabs_search),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        field()
                                    }
                                })
                            if (sessionQuery.isNotEmpty()) IconButton(onClick = { sessionQuery = "" }) {
                                Icon(Lucide.X, stringResource(R.string.cancel), Modifier.size(18.dp))
                            }
                        }
                    }
                    LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        items(candidates, key = { "${it.first.id}:${it.second.id}" }) { (server, session) ->
                            ListItem(
                                headlineContent = { Text(session.title ?: session.id, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(server.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                                modifier = Modifier.clickable {
                                    adding = false
                                    onSelect(SessionTab(server.id, session.id, session.title.orEmpty()))
                                },
                            )
                        }
                        if (candidates.isEmpty()) item { Text(stringResource(R.string.sessions_empty), Modifier.padding(16.dp)) }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { adding = false; onPickerDismissed() }) { Text(stringResource(R.string.cancel)) }
                    }
                }
        }
        }
        }
}
