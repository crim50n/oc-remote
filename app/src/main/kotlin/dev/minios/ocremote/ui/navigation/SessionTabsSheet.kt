package dev.minios.ocremote.ui.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import dev.minios.ocremote.ui.components.AppDialog
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
import dev.minios.ocremote.ui.components.DialogSystemBars
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.activity.compose.BackHandler
import dev.minios.ocremote.ui.components.backIcon
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.composables.icons.lucide.*
import dev.minios.ocremote.R
import dev.minios.ocremote.data.repository.SessionTab
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.ui.components.isAmoledTheme
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionTabsSheet(
    tabs: List<SessionTab>, servers: List<ServerConfig>, sessions: List<Session>,
    statuses: Map<String, SessionStatus>, attentionSessionIds: Set<String>, connectedServerIds: Set<String>,
    activeKey: String?, canUndo: Boolean,
    onSelect: (SessionTab) -> Unit, onClose: (SessionTab) -> Unit,
    onUndo: () -> Unit, onMove: (SessionTab, Int) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit,
    onOrder: (List<String>, List<String>) -> Unit = { _, _ -> },
    serverSessions: Map<String, Set<String>> = emptyMap(),
) {
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val currentTabs by rememberUpdatedState(tabs)
    var draggedTabs by remember { mutableStateOf<List<SessionTab>?>(null) }
    var dragBase by remember { mutableStateOf<List<String>>(emptyList()) }
    val shownTabs = draggedTabs ?: tabs
    val haptic = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val undo by rememberUpdatedState(onUndo)
    var undoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var adding by remember { mutableStateOf(false) }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val current = draggedTabs ?: return@rememberReorderableLazyListState
        val source = current.indexOfFirst { it.key == from.key }
        val target = current.indexOfFirst { it.key == to.key }
        if (source >= 0 && target >= 0 && source != target) {
            draggedTabs = current.toMutableList().apply { add(target, removeAt(source)) }
        }
    }
    val amoled = isAmoledTheme()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(
        usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
    )) {
        BackHandler(enabled = searching) { searching = false; query = "" }
        DialogSystemBars(MaterialTheme.colorScheme.background)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (searching) { searching = false; query = "" } else onDismiss() }) {
                    Icon(backIcon(), stringResource(R.string.back))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { searching = !searching; query = "" }) {
                    Icon(Lucide.Search, stringResource(R.string.session_tabs_search))
                }
            }
            if (searching) OutlinedTextField(query, { query = it }, singleLine = true,
                label = { Text(stringResource(R.string.session_tabs_search)) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp))
            LazyColumn(state = listState, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(shownTabs.filter { tab -> query.isBlank() || listOf(tab.title,
                    servers.firstOrNull { it.id == tab.serverId }?.displayName.orEmpty(),
                    sessions.firstOrNull { it.id == tab.sessionId }?.directory.orEmpty())
                    .any { it.contains(query, ignoreCase = true) } }, key = { it.key }) { tab ->
                    val server = servers.firstOrNull { it.id == tab.serverId }
                    val session = sessions.firstOrNull { it.id == tab.sessionId }
                    val status = when {
                        tab.serverId !in connectedServerIds -> R.string.session_tabs_offline
                        tab.sessionId in attentionSessionIds -> R.string.session_tabs_attention
                        statuses[tab.sessionId] is SessionStatus.Busy || statuses[tab.sessionId] is SessionStatus.Retry -> R.string.session_tabs_working
                        tab.hasDraft -> R.string.session_tabs_draft
                        tab.key != activeKey && (session?.time?.updated ?: 0) > tab.lastActive -> R.string.session_tabs_updated
                        else -> null
                    }
                    ReorderableItem(reorderState, key = tab.key) { dragging ->
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
                                else MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(if (tab.key == activeKey) 2.dp else 1.dp, if (tab.key == activeKey) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Row(Modifier.fillMaxWidth().clickable(enabled = server != null && !dragging) { onSelect(tab) }
                                .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(session?.title ?: tab.title.ifBlank { tab.sessionId },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(server?.displayName ?: stringResource(R.string.session_tabs_server_removed),
                                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(status?.let { stringResource(it) }.orEmpty(), style = MaterialTheme.typography.labelSmall,
                                        minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                                }, modifier = Modifier.align(Alignment.Top)) {
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
            AppDialog(onDismissRequest = { adding = false },
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
                        TextButton(onClick = { adding = false }) { Text(stringResource(R.string.cancel)) }
                    }
                }
        }
        }
        }
    }
}
