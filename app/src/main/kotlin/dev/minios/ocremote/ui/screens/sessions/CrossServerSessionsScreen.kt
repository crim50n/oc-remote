package dev.minios.ocremote.ui.screens.sessions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.minios.ocremote.R
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.ui.components.sessionCategoryColor
import dev.minios.ocremote.ui.components.sessionCategoryIcon
import dev.minios.ocremote.ui.components.appPopupBorder
import dev.minios.ocremote.ui.components.appPopupContainerColor
import dev.minios.ocremote.ui.components.isAmoledTheme
import dev.minios.ocremote.ui.components.SessionCardContent
import dev.minios.ocremote.ui.components.AppCardShape
import dev.minios.ocremote.ui.components.AppPrimaryButton
import dev.minios.ocremote.ui.components.AppSecondaryButton
import dev.minios.ocremote.ui.components.AppDialog
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrossServerSessionsScreen(
    onNavigateBack: () -> Unit,
    onOpenSession: (CrossServerSessionItem) -> Unit,
    onConnectServer: (ServerConfig) -> Unit,
    viewModel: CrossServerSessionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val isAmoled = isAmoledTheme()
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var offlinePromptItem by remember { mutableStateOf<CrossServerSessionItem?>(null) }
    var categoryPickerItem by remember { mutableStateOf<CrossServerSessionItem?>(null) }
    val filteredItems = remember(state.items, selectedCategoryId) {
        filterCrossServerFavorites(state.items, selectedCategoryId)
    }
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    var draggedItems by remember { mutableStateOf<List<CrossServerSessionItem>?>(null) }
    var originalDragItems by remember { mutableStateOf<List<CrossServerSessionItem>>(emptyList()) }
    val visibleItems = draggedItems ?: filteredItems
    val reorderableState = rememberReorderableLazyListState(
        lazyListState = listState,
        scrollThreshold = 72.dp,
    ) { from, to ->
        val currentItems = draggedItems ?: return@rememberReorderableLazyListState
        if (from.index !in currentItems.indices || to.index !in currentItems.indices) {
            return@rememberReorderableLazyListState
        }
        draggedItems = currentItems.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.cross_sessions_favorites),
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.items.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = { selectedCategoryId = null },
                        label = { Text(stringResource(R.string.cross_sessions_all)) },
                        leadingIcon = if (selectedCategoryId == null) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
                        colors = if (selectedCategoryId == null) {
                            if (isAmoled) {
                                AssistChipDefaults.assistChipColors(
                                    containerColor = Color.Black,
                                    labelColor = MaterialTheme.colorScheme.primary,
                                    leadingIconContentColor = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        } else AssistChipDefaults.assistChipColors(),
                        border = if (selectedCategoryId == null && isAmoled) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            AssistChipDefaults.assistChipBorder(enabled = true)
                        },
                    )
                    state.filterCategories.forEach { category ->
                        val selected = selectedCategoryId == category.id
                        AssistChip(
                            onClick = { selectedCategoryId = category.id },
                            label = { Text(category.name) },
                            leadingIcon = {
                                Icon(
                                    if (selected) Icons.Default.Check else sessionCategoryIcon(category.icon),
                                    contentDescription = null,
                                    tint = sessionCategoryColor(category.color),
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            colors = if (selected) {
                                if (isAmoled) {
                                    AssistChipDefaults.assistChipColors(
                                        containerColor = Color.Black,
                                        labelColor = MaterialTheme.colorScheme.primary,
                                        leadingIconContentColor = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    AssistChipDefaults.assistChipColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            } else AssistChipDefaults.assistChipColors(),
                            border = if (selected && isAmoled) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                AssistChipDefaults.assistChipBorder(enabled = true)
                            },
                        )
                    }
                }
            }

            if (visibleItems.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.StarBorder, contentDescription = null, modifier = Modifier.size(40.dp))
                    Text(
                        text = stringResource(
                            if (selectedCategoryId == null) R.string.cross_sessions_empty_favorites_any_server
                            else R.string.cross_sessions_empty_favorite_category_any_server,
                        ),
                        modifier = Modifier.padding(top = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(
                        visibleItems,
                        key = { _, item -> "${item.server.id}:${item.session.id}" },
                    ) { index, item ->
                        val itemKey = item.favoriteKey()
                        ReorderableItem(reorderableState, key = itemKey) { isDragged ->
                            val interactionSource = remember(itemKey) { MutableInteractionSource() }
                            CrossServerSessionCard(
                                modifier = Modifier
                                    .longPressDraggableHandle(
                                        interactionSource = interactionSource,
                                        onDragStarted = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            originalDragItems = filteredItems
                                            draggedItems = filteredItems
                                        },
                                        onDragStopped = {
                                            val original = originalDragItems
                                            val reordered = draggedItems.orEmpty()
                                            val from = original.indexOfFirst { it.favoriteKey() == itemKey }
                                            val to = reordered.indexOfFirst { it.favoriteKey() == itemKey }
                                            if (from >= 0 && to >= 0 && from != to) {
                                                viewModel.moveFavorite(item, original, to - from)
                                            }
                                            draggedItems = null
                                            originalDragItems = emptyList()
                                        },
                                    )
                                    .graphicsLayer {
                                        if (isDragged) {
                                            scaleX = 1.015f
                                            scaleY = 1.015f
                                            shadowElevation = 8.dp.toPx()
                                        }
                                    },
                                item = item,
                                interactionSource = interactionSource,
                                favoritePosition = index,
                                favoriteCount = visibleItems.size,
                                onClick = {
                                    if (item.isConnected) onOpenSession(item) else offlinePromptItem = item
                                },
                                onToggleFavorite = { viewModel.toggleFavorite(item) },
                                onMoveFavorite = { offset -> viewModel.moveFavorite(item, visibleItems, offset) },
                                onChooseCategory = { categoryPickerItem = item },
                            )
                        }
                    }
                }
            }
        }
    }

    offlinePromptItem?.let { item ->
        AppDialog(
            onDismissRequest = { offlinePromptItem = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    text = stringResource(R.string.cross_sessions_connect_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.cross_sessions_connect_message, item.server.displayName),
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppSecondaryButton(onClick = { offlinePromptItem = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                    AppPrimaryButton(
                        modifier = Modifier.padding(start = 8.dp),
                    onClick = {
                        offlinePromptItem = null
                        onConnectServer(item.server)
                    },
                ) {
                    Text(stringResource(R.string.connect))
                }
                }
            }
        }
    }

    categoryPickerItem?.let { item ->
        SessionCategoryPickerDialog(
            categories = state.categories,
            selectedCategoryId = item.category?.id,
            onSelect = { categoryId ->
                viewModel.setSessionCategory(item, categoryId)
                categoryPickerItem = null
            },
            onSaveCategory = viewModel::saveSessionCategory,
            onDeleteCategory = viewModel::deleteSessionCategory,
            onDismiss = { categoryPickerItem = null },
        )
    }
}

@Composable
private fun CrossServerSessionCard(
    modifier: Modifier = Modifier,
    item: CrossServerSessionItem,
    interactionSource: MutableInteractionSource,
    favoritePosition: Int,
    favoriteCount: Int,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMoveFavorite: (Int) -> Unit,
    onChooseCategory: () -> Unit,
) {
    val isAmoled = isAmoledTheme()
    var showActions by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = if (isAmoled) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
        } else null,
        shape = AppCardShape,
    ) {
        SessionCardContent(
            session = item.session,
            status = item.status,
            isFavorite = item.isFavorite,
            category = item.category,
            contextLabel = item.server.displayName,
            contextDetail = item.session.directory.trimEnd('/').substringAfterLast('/').takeIf { directory ->
                directory.isNotBlank() &&
                    !item.server.displayName.trimEnd('/').substringAfterLast('/').equals(directory, ignoreCase = true) &&
                    !item.session.title.orEmpty().equals(directory, ignoreCase = true)
            },
            isOffline = !item.isConnected,
            trailingContent = {
                androidx.compose.foundation.layout.Box {
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
                            Icon(if (item.isFavorite) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = null)
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
                            onChooseCategory()
                        },
                    )
                    if (item.isFavorite) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.session_favorite_move_up)) },
                            leadingIcon = { Icon(Icons.Default.ArrowUpward, contentDescription = null) },
                            enabled = favoritePosition > 0,
                            onClick = {
                                showActions = false
                                onMoveFavorite(-1)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.session_favorite_move_down)) },
                            leadingIcon = { Icon(Icons.Default.ArrowDownward, contentDescription = null) },
                            enabled = favoritePosition < favoriteCount - 1,
                            onClick = {
                                showActions = false
                                onMoveFavorite(1)
                            },
                        )
                    }
                }
                }
            },
        )
    }
}
