package dev.minios.ocremote.ui.screens.sessions

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.LabelOff
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.BackHandler
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import dev.minios.ocremote.R
import dev.minios.ocremote.data.api.FileNode
import dev.minios.ocremote.domain.model.Project
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SessionCategory
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.animation.core.*
import androidx.compose.ui.graphics.graphicsLayer
import dev.minios.ocremote.ui.components.AppDialog
import dev.minios.ocremote.ui.components.AppDialogShape
import dev.minios.ocremote.ui.components.AppPrimaryButton
import dev.minios.ocremote.ui.components.AppSearchShape
import dev.minios.ocremote.ui.components.AppSecondaryButton
import dev.minios.ocremote.ui.components.SessionCardContent
import dev.minios.ocremote.ui.components.AppCardShape
import dev.minios.ocremote.ui.components.appAmoledBorder
import dev.minios.ocremote.ui.components.appDialogContainerColor
import dev.minios.ocremote.ui.components.appDialogElevation
import dev.minios.ocremote.ui.components.appPopupBorder
import dev.minios.ocremote.ui.components.appPopupContainerColor
import dev.minios.ocremote.ui.components.isAmoledTheme
import dev.minios.ocremote.ui.components.AppLoadingEdge
import dev.minios.ocremote.ui.components.sessionCategoryColor
import dev.minios.ocremote.ui.components.sessionCategoryIcon
import dev.minios.ocremote.ui.screens.settings.SessionCategoriesDialog
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState

internal fun shouldRevealPromotedSession(
    previousTopSessionId: String?,
    currentTopSessionId: String?,
    firstVisibleItemIndex: Int,
    searchActive: Boolean,
): Boolean = previousTopSessionId != null &&
    currentTopSessionId != null &&
    previousTopSessionId != currentTopSessionId &&
    firstVisibleItemIndex <= 2 &&
    !searchActive

internal data class RecentSessionDirectory(
    val directory: String,
    val name: String,
    val count: Int,
    val lastUsed: Long,
)

internal fun recentSessionDirectories(
    sessions: List<SessionItem>,
    limit: Int = 20,
): List<RecentSessionDirectory> = sessions
    .groupBy { it.session.directory.trimEnd('/') }
    .map { (directory, items) ->
        RecentSessionDirectory(
            directory = items.first().session.directory,
            name = directory.substringAfterLast('/').ifEmpty { directory },
            count = items.size,
            lastUsed = items.maxOf { it.session.time.updated },
        )
    }
    .sortedByDescending(RecentSessionDirectory::lastUsed)
    .take(limit)

/** Pulsing dots loading indicator — 3 dots that scale up/down in sequence. */
@Composable
private fun PulsingDotsIndicator(
    modifier: Modifier = Modifier,
    dotSize: androidx.compose.ui.unit.Dp = 10.dp,
    dotSpacing: androidx.compose.ui.unit.Dp = 8.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val transition = rememberInfiniteTransition(label = "pulsing_dots")
    val scales2 = (0..2).map { index ->
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1200
                    val offset = index * 150
                    0.4f at 0 + offset
                    1.0f at 300 + offset
                    0.4f at 600 + offset
                    0.4f at 1200
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "dot_scale_$index"
        )
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(dotSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        scales2.forEach { scale ->
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        alpha = 0.3f + 0.7f * ((scale.value - 0.4f) / 0.6f)
                    }
                .background(color, CircleShape)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerRefreshEdge(
    isRefreshing: Boolean,
    state: PullToRefreshState,
    modifier: Modifier = Modifier,
) {
    AppLoadingEdge(
        active = isRefreshing,
        progress = state.distanceFraction,
        modifier = modifier
    )
}

/**
 * Session List Screen - shows all sessions for a connected server,
 * grouped by project. Tapping a session navigates to the chat screen.
 */
@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    onNavigateToChat: (sessionId: String, openTerminal: Boolean) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: SessionListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val groupByProject by viewModel.groupSessionsByProject.collectAsState()
    val recentDirectoryCount by viewModel.recentDirectoryCount.collectAsState()
    val isAmoled = isAmoledTheme()
    // Navigate to newly created session
    LaunchedEffect(viewModel) {
        viewModel.navigateToSession
            .onEach { sessionId ->
                onNavigateToChat(sessionId, false)
            }
            .launchIn(this)
    }

    // Rename dialog state
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameSessionId by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }

    // Delete confirmation dialog state
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteSessionId by remember { mutableStateOf("") }
    var deleteSessionTitle by remember { mutableStateOf("") }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }

    // Project picker dialog state
    var showOpenProject by remember { mutableStateOf(false) }
    var showQuickNewSession by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var collapsedProjects by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val sessionListState = rememberLazyListState()
    val topSessionId = uiState.sessionGroups.firstOrNull()?.sessions?.firstOrNull()?.session?.id
    var previousTopSessionId by remember { mutableStateOf<String?>(null) }
    val visibleGroups = remember(uiState.sessionGroups, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            uiState.sessionGroups
        } else {
            uiState.sessionGroups.mapNotNull { group ->
                val projectMatches = group.projectName.contains(query, ignoreCase = true) ||
                    group.directory.contains(query, ignoreCase = true) ||
                    group.branch?.contains(query, ignoreCase = true) == true
                val sessions = if (projectMatches) group.sessions else group.sessions.filter { item ->
                    item.session.title?.contains(query, ignoreCase = true) == true ||
                        item.session.id.contains(query, ignoreCase = true) ||
                        item.session.directory.contains(query, ignoreCase = true)
                }
                group.copy(sessions = sessions).takeIf { sessions.isNotEmpty() }
            }
        }
    }

    LaunchedEffect(topSessionId, searchQuery.isNotBlank()) {
        if (shouldRevealPromotedSession(
                previousTopSessionId = previousTopSessionId,
                currentTopSessionId = topSessionId,
                firstVisibleItemIndex = sessionListState.firstVisibleItemIndex,
                searchActive = searchQuery.isNotBlank(),
            )
        ) {
            sessionListState.animateScrollToItem(0)
        }
        previousTopSessionId = topSessionId
    }

    BackHandler(enabled = uiState.isSelectionMode) {
        viewModel.clearSelection()
    }

    val allSessions = uiState.sessionGroups.flatMap { it.sessions }
    val pullToRefreshState = rememberPullToRefreshState()
    val isRefreshing = uiState.isLoading && allSessions.isNotEmpty()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Box {
                if (uiState.isSelectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.sessions_selected_count, uiState.selectedIds.size),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                        }
                    },
                    actions = {
                        TextButton(onClick = { viewModel.selectAll() }) {
                            Text(stringResource(R.string.sessions_select_all))
                        }
                        IconButton(onClick = { showDeleteSelectedDialog = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.sessions_delete_selected),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    scrollBehavior = scrollBehavior
                )
                } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = uiState.serverName.ifEmpty { stringResource(R.string.sessions_title) },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.setGroupSessionsByProject(!groupByProject) }) {
                            Icon(
                                imageVector = if (groupByProject) Icons.AutoMirrored.Filled.ViewList else Icons.Default.Folder,
                                contentDescription = stringResource(
                                    if (groupByProject) R.string.sessions_view_recent
                                    else R.string.sessions_view_projects,
                                ),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    scrollBehavior = scrollBehavior
                    )
                }
                ServerRefreshEdge(
                    isRefreshing = isRefreshing,
                    state = pullToRefreshState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        },
        floatingActionButton = {
            if (!uiState.isSelectionMode) {
                FloatingActionButton(
                    onClick = {
                        // If there are known projects, show the quick dialog first;
                        // otherwise go straight to the full directory browser.
                        if (uiState.sessionGroups.isNotEmpty()) {
                            showQuickNewSession = true
                        } else {
                            showOpenProject = true
                        }
                    },
                    containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (isAmoled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer,
                    elevation = if (isAmoled) {
                        FloatingActionButtonDefaults.elevation(
                            defaultElevation = 0.dp,
                            pressedElevation = 0.dp,
                            focusedElevation = 0.dp,
                            hoveredElevation = 0.dp
                        )
                    } else {
                        FloatingActionButtonDefaults.elevation()
                    },
                    modifier = if (isAmoled) {
                        Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            shape = FloatingActionButtonDefaults.shape
                        )
                    } else {
                        Modifier
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.sessions_new))
                }
            }
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = viewModel::loadSessions,
            state = pullToRefreshState,
            modifier = Modifier.fillMaxSize().padding(padding),
            indicator = { },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .graphicsLayer { translationY = pullToRefreshState.distanceFraction * 80f },
            ) {
                when {
                uiState.isLoading && allSessions.isEmpty() -> {
                    PulsingDotsIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        dotSize = 12.dp,
                        dotSpacing = 8.dp
                    )
                }
                uiState.error != null && allSessions.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = uiState.error ?: stringResource(R.string.session_unknown_error),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                        AppPrimaryButton(onClick = { viewModel.loadSessions() }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                allSessions.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Chat,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.sessions_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.sessions_tap_plus),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = sessionListState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item(key = "session-search") {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 4.dp),
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                                        }
                                    }
                                },
                                placeholder = { Text(stringResource(R.string.search_sessions)) },
                                singleLine = true,
                                shape = AppSearchShape,
                            )
                        }
                        if (visibleGroups.isEmpty()) {
                            item(key = "no-search-results") {
                                Text(
                                    text = stringResource(R.string.sessions_no_search_results),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 40.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (!groupByProject) {
                            val recentSessions = visibleGroups
                                .flatMap { group -> group.sessions.map { group to it } }
                                .sortedWith(
                                    compareByDescending<Pair<ProjectSessionGroup, SessionItem>> { (_, item) -> item.isFavorite }
                                        .thenBy { (_, item) -> item.favoriteIndex ?: Int.MAX_VALUE }
                                        .thenByDescending { (_, item) -> item.session.time.updated }
                                )
                            items(recentSessions, key = { (_, item) -> item.session.id }) { (group, item) ->
                                val untitledLabel = stringResource(R.string.session_untitled)
                                SessionRow(
                                    item = item,
                                    projectName = group.sessionDirLabels[item.session.id] ?: group.directory,
                                    isSelectionMode = uiState.isSelectionMode,
                                    isSelected = item.session.id in uiState.selectedIds,
                                    favoriteCount = allSessions.count { it.isFavorite },
                                    categories = uiState.categories,
                                    onClick = {
                                        if (uiState.isSelectionMode) viewModel.toggleSelection(item.session.id)
                                        else onNavigateToChat(item.session.id, false)
                                    },
                                    onLongClick = { viewModel.toggleSelection(item.session.id) },
                                    onToggleFavorite = { viewModel.toggleFavorite(item.session.id) },
                                    onMoveFavorite = { offset -> viewModel.moveFavorite(item.session.id, offset) },
                                    onSetCategory = { categoryId ->
                                        viewModel.setSessionCategory(item.session.id, categoryId)
                                    },
                                    onSaveCategory = viewModel::saveSessionCategory,
                                    onDeleteCategory = viewModel::deleteSessionCategory,
                                    onRename = {
                                        renameSessionId = item.session.id
                                        renameText = item.session.title ?: ""
                                        showRenameDialog = true
                                    },
                                    onDelete = {
                                        deleteSessionId = item.session.id
                                        deleteSessionTitle = item.session.title ?: untitledLabel
                                        showDeleteDialog = true
                                    },
                                )
                            }
                        } else {
                            for (group in visibleGroups) {
                                val expanded = searchQuery.isNotBlank() || group.projectId !in collapsedProjects
                                item(key = "project-${group.projectId}") {
                                ProjectHeader(
                                    name = group.projectName,
                                    directory = group.directory,
                                    branch = group.branch,
                                    sessionCount = group.sessions.size,
                                    expanded = expanded,
                                    onToggle = {
                                        collapsedProjects = if (group.projectId in collapsedProjects) {
                                            collapsedProjects - group.projectId
                                        } else {
                                            collapsedProjects + group.projectId
                                        }
                                    },
                                    onNewSession = { viewModel.createNewSession(group.directory) },
                                )
                            }
                                if (expanded) items(group.sessions, key = { it.session.id }) { item ->
                                val untitledLabel = stringResource(R.string.session_untitled)
                                val dirLabel = group.sessionDirLabels[item.session.id]
                                    ?.takeIf { item.session.directory.trimEnd('/') != group.directory.trimEnd('/') }
                                SessionRow(
                                    item = item,
                                    projectName = dirLabel,
                                    isSelectionMode = uiState.isSelectionMode,
                                    isSelected = item.session.id in uiState.selectedIds,
                                    favoriteCount = allSessions.count { it.isFavorite },
                                    categories = uiState.categories,
                                    onClick = {
                                        if (uiState.isSelectionMode) {
                                            viewModel.toggleSelection(item.session.id)
                                        } else {
                                            onNavigateToChat(item.session.id, false)
                                        }
                                    },
                                    onLongClick = { viewModel.toggleSelection(item.session.id) },
                                    onToggleFavorite = { viewModel.toggleFavorite(item.session.id) },
                                    onMoveFavorite = { offset -> viewModel.moveFavorite(item.session.id, offset) },
                                    onSetCategory = { categoryId ->
                                        viewModel.setSessionCategory(item.session.id, categoryId)
                                    },
                                    onSaveCategory = viewModel::saveSessionCategory,
                                    onDeleteCategory = viewModel::deleteSessionCategory,
                                    onRename = {
                                        renameSessionId = item.session.id
                                        renameText = item.session.title ?: ""
                                        showRenameDialog = true
                                    },
                                    onDelete = {
                                        deleteSessionId = item.session.id
                                        deleteSessionTitle = item.session.title ?: untitledLabel
                                        showDeleteDialog = true
                                    }
                                )
                                }
                            }
                        }
                        item(key = "session-list-fab-spacer") {
                            Spacer(modifier = Modifier.height(84.dp))
                        }
                    }
                }
                }
            }
        }
    }

    // Quick new session dialog (recent projects)
    if (showQuickNewSession) {
        val allSessions = uiState.sessionGroups.flatMap { it.sessions }
        NewSessionQuickDialog(
            sessions = allSessions,
            limit = recentDirectoryCount,
            onSelectDirectory = { directory ->
                showQuickNewSession = false
                viewModel.createNewSession(directory = directory)
            },
            onBrowse = {
                showQuickNewSession = false
                showOpenProject = true
            },
            onDismiss = { showQuickNewSession = false }
        )
    }

    // Open Project directory browser dialog
    if (showOpenProject) {
        OpenProjectDialog(
            viewModel = viewModel,
            projects = uiState.projects,
            onSelect = { directory ->
                showOpenProject = false
                viewModel.createNewSession(directory = directory)
            },
            onDismiss = { showOpenProject = false }
        )
    }

    if (showDeleteSelectedDialog) {
        AppDialog(onDismissRequest = { showDeleteSelectedDialog = false }, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.sessions_delete_selected),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(stringResource(R.string.sessions_delete_selected_confirm, uiState.selectedIds.size))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        AppSecondaryButton(onClick = { showDeleteSelectedDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        AppPrimaryButton(
                            onClick = {
                                viewModel.deleteSelected()
                                showDeleteSelectedDialog = false
                            },
                            destructive = true,
                        ) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
        }
    }

    // Rename dialog
    if (showRenameDialog) {
        AppDialog(onDismissRequest = { showRenameDialog = false }, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.session_rename),
                        style = MaterialTheme.typography.titleMedium
                    )
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text(stringResource(R.string.session_rename_title)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        AppSecondaryButton(onClick = { showRenameDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        AppPrimaryButton(
                            onClick = {
                                viewModel.renameSession(renameSessionId, renameText)
                                showRenameDialog = false
                            },
                            enabled = renameText.isNotBlank()
                        ) {
                            Text(stringResource(R.string.session_rename_button))
                        }
                    }
                }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AppDialog(onDismissRequest = { showDeleteDialog = false }, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.session_delete),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(stringResource(R.string.session_delete_confirm, deleteSessionTitle))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        AppSecondaryButton(onClick = { showDeleteDialog = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        AppPrimaryButton(
                            onClick = {
                                viewModel.deleteSession(deleteSessionId)
                                showDeleteDialog = false
                            },
                            destructive = true,
                        ) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
        }
    }
}

@Composable
private fun ProjectHeader(
    name: String,
    directory: String,
    branch: String?,
    sessionCount: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onNewSession: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle)
            .padding(top = 12.dp, bottom = 8.dp, start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!branch.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = branch,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.sessions_project_summary, directory, sessionCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onNewSession) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.sessions_new_in_project))
        }
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Directory browser dialog for opening a project.
 * Shows: known projects at top, then browsable server filesystem.
 * Supports search and tap-to-navigate into subdirectories.
 */
@Composable
private fun OpenProjectDialog(
    viewModel: SessionListViewModel,
    projects: List<Project>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var currentDir by remember { mutableStateOf<String?>(null) }
    var homeDir by remember { mutableStateOf<String?>(null) }
    var directories by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    var searchResults by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var isCreatingFolder by remember { mutableStateOf(false) }
    var createFolderError by remember { mutableStateOf<String?>(null) }
    val focusRequester = remember { FocusRequester() }

    val isSearching = searchQuery.isNotBlank()

    // Load home directory and initial listing
    LaunchedEffect(Unit) {
        val home = viewModel.getHomeDirectory()
        homeDir = home
        currentDir = home
        isLoading = true
        directories = viewModel.listDirectories(home)
        isLoading = false
    }

    // Re-list when currentDir changes
    LaunchedEffect(currentDir) {
        val dir = currentDir ?: return@LaunchedEffect
        if (searchQuery.isBlank()) {
            isLoading = true
            directories = viewModel.listDirectories(dir)
            isLoading = false
        }
    }

    // Search debounce
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank()) {
            searchResults = emptyList()
            // Re-list current dir
            currentDir?.let {
                isLoading = true
                directories = viewModel.listDirectories(it)
                isLoading = false
            }
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(300)
        isLoading = true
        val baseDir = homeDir ?: "/"
        searchResults = viewModel.searchDirectories(searchQuery, baseDir)
        isLoading = false
    }

    // Focus the search field
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200)
        try { focusRequester.requestFocus() } catch (_: Exception) {}
    }

    /** Shorten an absolute path by replacing home prefix with ~ */
    fun tildeReplace(path: String): String {
        val home = homeDir ?: return path
        return if (path.startsWith(home)) "~" + path.removePrefix(home) else path
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.75f),
            shape = AppDialogShape,
            color = appDialogContainerColor(),
            border = appAmoledBorder(),
            tonalElevation = appDialogElevation(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.sessions_open_project),
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                }

                // Search field
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(AppSearchShape)
                        .background(
                            if (isAmoled) {
                                Color.Black
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            }
                        )
                        .then(
                            if (isAmoled) {
                                Modifier.border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                                    shape = AppSearchShape
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.sessions_search_folders),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                            }
                            innerTextField()
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.chat_clear),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }

                // Breadcrumb / current path (when not searching)
                if (!isSearching && currentDir != null) {
                    val canGoUp = currentDir != "/" && currentDir != homeDir
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .padding(horizontal = 20.dp, vertical = 4.dp)
                            .then(
                                if (canGoUp) Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .clickable {
                                        // Navigate up
                                        val parent = currentDir!!.trimEnd('/').substringBeforeLast('/')
                                        currentDir = parent.ifEmpty { "/" }
                                    } else Modifier
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (canGoUp) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                            )
                        }
                        Text(
                            text = tildeReplace(currentDir ?: "/"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    // Content
                    when {
                        isLoading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                PulsingDotsIndicator(dotSize = 10.dp, dotSpacing = 6.dp)
                            }
                        }
                        isSearching -> {
                            // Search results
                            if (searchResults.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.sessions_no_folders),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    items(searchResults) { path ->
                                        val absolutePath = path.trimEnd('/').ifEmpty { "/" }
                                        DirectoryRow(
                                            displayPath = tildeReplace(absolutePath) + "/",
                                            onClick = { onSelect(absolutePath) },
                                            onNavigate = {
                                                // Navigate into this directory for further browsing
                                                searchQuery = ""
                                                currentDir = absolutePath
                                            }
                                        )
                                    }
                                    item(key = "dir-search-fab-spacer") {
                                        Spacer(modifier = Modifier.height(84.dp))
                                    }
                                }
                            }
                        }
                        else -> {
                            // Directory listing
                            val showKnownProjects = currentDir == homeDir && projects.isNotEmpty()

                            if (directories.isEmpty() && !showKnownProjects) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.sessions_empty_directory),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    items(directories, key = { it.name }) { node ->
                                        val absPath = node.absolute ?: "${currentDir?.trimEnd('/')}/${node.name}"
                                        DirectoryRow(
                                            displayPath = tildeReplace(absPath) + "/",
                                            onNavigate = {
                                                // Navigate into this directory
                                                currentDir = absPath
                                            },
                                            onClick = { onSelect(absPath) }
                                        )
                                    }
                                    item(key = "dir-list-fab-spacer") {
                                        Spacer(modifier = Modifier.height(84.dp))
                                    }
                                }
                            }
                        }
                    }

                    FloatingActionButton(
                        onClick = {
                            showCreateFolderDialog = true
                            createFolderError = null
                            if (newFolderName.isBlank()) newFolderName = ""
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(16.dp)
                            .then(if (isAmoled) Modifier.appPopupBorder(FloatingActionButtonDefaults.shape) else Modifier),
                        containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.primaryContainer,
                        contentColor = if (isAmoled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer,
                        elevation = if (isAmoled) {
                            FloatingActionButtonDefaults.elevation(
                                defaultElevation = 0.dp,
                                pressedElevation = 0.dp,
                                focusedElevation = 0.dp,
                                hoveredElevation = 0.dp,
                            )
                        } else {
                            FloatingActionButtonDefaults.elevation()
                        },
                    ) {
                        Icon(Icons.Default.CreateNewFolder, contentDescription = stringResource(R.string.sessions_create_folder))
                    }
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        AppDialog(
            onDismissRequest = {
                if (!isCreatingFolder) showCreateFolderDialog = false
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.sessions_create_folder_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = {
                            newFolderName = it
                            createFolderError = null
                        },
                        singleLine = true,
                        enabled = !isCreatingFolder,
                        label = { Text(stringResource(R.string.sessions_create_folder_name_label)) },
                        placeholder = { Text(stringResource(R.string.sessions_create_folder_name_placeholder)) },
                        isError = createFolderError != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (createFolderError != null) {
                        Text(
                            text = createFolderError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    AppSecondaryButton(
                        onClick = { showCreateFolderDialog = false },
                        enabled = !isCreatingFolder,
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    AppPrimaryButton(
                        onClick = {
                            val parent = currentDir ?: homeDir ?: "/"
                            val name = newFolderName.trim()
                            if (name.isBlank()) {
                                createFolderError = context.getString(R.string.sessions_create_folder_invalid_name)
                                return@AppPrimaryButton
                            }

                            isCreatingFolder = true
                            scope.launch {
                                val result = viewModel.createDirectory(parent, name)
                                isCreatingFolder = false
                                result.onSuccess { createdPath ->
                                    showCreateFolderDialog = false
                                    newFolderName = ""
                                    createFolderError = null
                                    searchQuery = ""
                                    currentDir = parent
                                    directories = viewModel.listDirectories(parent)
                                    Toast
                                        .makeText(
                                            context,
                                            context.getString(R.string.sessions_create_folder_success, tildeReplace(createdPath)),
                                            Toast.LENGTH_SHORT,
                                        )
                                        .show()
                                }.onFailure { error ->
                                    createFolderError = error.message ?: context.getString(R.string.sessions_create_folder_failed)
                                }
                            }
                        },
                        enabled = !isCreatingFolder,
                    ) {
                        if (isCreatingFolder) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.sessions_create_folder_create))
                        }
                    }
                }
            }
        }
    }
}

/**
 * A single directory row in the browser.
 * Tap to select. Has a chevron to navigate into the directory.
 */
@Composable
private fun DirectoryRow(
    displayPath: String,
    onClick: () -> Unit,
    onNavigate: (() -> Unit)? = null
) {
    // Split into parent + leaf for styling
    val trimmed = displayPath.trimEnd('/')
    val lastSlash = trimmed.lastIndexOf('/')
    val parent = if (lastSlash > 0) trimmed.substring(0, lastSlash + 1) else ""
    val leaf = if (lastSlash >= 0) trimmed.substring(lastSlash + 1) else trimmed
    val trailing = if (displayPath.endsWith("/")) "/" else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Text(
            text = buildAnnotatedString {
                if (parent.isNotEmpty()) {
                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))) {
                        append(parent)
                    }
                }
                withStyle(SpanStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )) {
                    append(leaf)
                }
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))) {
                    append(trailing)
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
            if (onNavigate != null) {
                IconButton(
                    onClick = onNavigate,
                ) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = stringResource(R.string.open),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
    }
}

/**
 * Quick-start dialog for creating a new session.
 * Groups sessions by their directory to show unique project folders,
 * sorted by most recently used. One tap creates a session in that folder.
 * A "Browse..." row at the bottom opens the full directory picker.
 */
@Composable
private fun NewSessionQuickDialog(
    sessions: List<SessionItem>,
    limit: Int,
    onSelectDirectory: (String) -> Unit,
    onBrowse: () -> Unit,
    onDismiss: () -> Unit
) {
    val dirEntries = remember(sessions, limit) {
        recentSessionDirectories(sessions, limit)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .wrapContentHeight(),
            shape = AppDialogShape,
            color = appDialogContainerColor(),
            border = appAmoledBorder(),
            tonalElevation = appDialogElevation(),
        ) {
            Column(modifier = Modifier.padding(vertical = 16.dp)) {
                // Header
                Text(
                    text = stringResource(R.string.sessions_new_dialog_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    items(dirEntries, key = { it.directory }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectDirectory(entry.directory) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = entry.directory.trimEnd('/'),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = "${entry.count}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                // Divider
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                // "Open other project..." row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onBrowse() }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    Text(
                        text = stringResource(R.string.sessions_open_other_project),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    item: SessionItem,
    projectName: String? = null,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    favoriteCount: Int,
    categories: List<SessionCategory>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMoveFavorite: (Int) -> Unit,
    onSetCategory: (String?) -> Unit,
    onSaveCategory: (String?, String, String, String) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val clipboardManager = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var showActions by remember { mutableStateOf(false) }
    var showCategoryPicker by remember { mutableStateOf(false) }

    val cardContent: @Composable () -> Unit = {
        val containerColor = if (isSelected) {
            if (isAmoled) {
                Color.Black
            } else {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            }
        } else {
            if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerLow
        }

        val cardColors = CardDefaults.cardColors(containerColor = containerColor)

        val cardBorder = when {
            isSelected -> BorderStroke(
                1.5.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = if (isAmoled) 0.75f else 0.5f)
            )
            isAmoled -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
            else -> null
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            colors = cardColors,
            border = cardBorder,
            shape = AppCardShape,
        ) {
            Column {
                SessionCardContent(
                    session = item.session,
                    status = item.status,
                    isFavorite = item.isFavorite,
                    category = item.category,
                    contextLabel = projectName.orEmpty(),
                    leadingContent = {
                        AnimatedVisibility(
                            visible = isSelectionMode,
                            enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                            exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { onClick() },
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                        }
                    },
                    trailingContent = {
                        if (!isSelectionMode) {
                            Box {
                        IconButton(onClick = { showActions = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(
                            expanded = showActions,
                            onDismissRequest = { showActions = false },
                            modifier = Modifier.appPopupBorder(),
                            containerColor = appPopupContainerColor(),
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(if (item.isFavorite) R.string.session_favorite_remove else R.string.session_favorite_add))
                                },
                                leadingIcon = {
                                    Icon(
                                        if (item.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    showActions = false
                                    onToggleFavorite()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.session_category)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = item.category?.let { sessionCategoryIcon(it.icon) }
                                            ?: Icons.AutoMirrored.Filled.Label,
                                        contentDescription = null,
                                        tint = item.category?.let { sessionCategoryColor(it.color) }
                                            ?: LocalContentColor.current,
                                    )
                                },
                                onClick = {
                                    showActions = false
                                    showCategoryPicker = true
                                },
                            )
                            if (item.isFavorite) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.session_favorite_move_up)) },
                                    leadingIcon = { Icon(Icons.Default.ArrowUpward, contentDescription = null) },
                                    enabled = (item.favoriteIndex ?: 0) > 0,
                                    onClick = {
                                        showActions = false
                                        onMoveFavorite(-1)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.session_favorite_move_down)) },
                                    leadingIcon = { Icon(Icons.Default.ArrowDownward, contentDescription = null) },
                                    enabled = (item.favoriteIndex ?: Int.MAX_VALUE) < favoriteCount - 1,
                                    onClick = {
                                        showActions = false
                                        onMoveFavorite(1)
                                    },
                                )
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.session_copy_id)) },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                onClick = {
                                    showActions = false
                                    coroutineScope.launch {
                                        clipboardManager.setClipEntry(ClipEntry(ClipData.newPlainText("Session ID", item.session.id)))
                                    }
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.chat_copied_clipboard),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.session_rename)) },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    showActions = false
                                    onRename()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    showActions = false
                                    onDelete()
                                },
                            )
                        }
                            }
                        }
                    },
                )
            }
        }
    }

    cardContent()

    if (showCategoryPicker) {
        SessionCategoryPickerDialog(
            categories = categories,
            selectedCategoryId = item.category?.id,
            onSelect = { categoryId ->
                onSetCategory(categoryId)
                showCategoryPicker = false
            },
            onSaveCategory = onSaveCategory,
            onDeleteCategory = onDeleteCategory,
            onDismiss = { showCategoryPicker = false },
        )
    }
}

@Composable
internal fun SessionCategoryPickerDialog(
    categories: List<SessionCategory>,
    selectedCategoryId: String?,
    onSelect: (String?) -> Unit,
    onSaveCategory: (String?, String, String, String) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var showManager by remember { mutableStateOf(false) }
    AppDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.session_category),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(null) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.LabelOff, contentDescription = null)
                    Text(
                        text = stringResource(R.string.session_category_none),
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    if (selectedCategoryId == null) Icon(Icons.Default.Check, contentDescription = null)
                }
                categories.forEach { category ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(category.id) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = sessionCategoryIcon(category.icon),
                            contentDescription = null,
                            tint = sessionCategoryColor(category.color),
                        )
                        Text(
                            text = category.name,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        )
                        if (selectedCategoryId == category.id) Icon(Icons.Default.Check, contentDescription = null)
                    }
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showManager = true }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.settings_session_categories),
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            AppSecondaryButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    }
    if (showManager) {
        SessionCategoriesDialog(
            categories = categories,
            onSave = onSaveCategory,
            onDelete = onDeleteCategory,
            onDismiss = { showManager = false },
        )
    }
}
