package dev.minios.ocremote.ui.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.data.repository.ServerConnectionStateRepository
import dev.minios.ocremote.data.repository.ServerRepository
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.FavoriteSessionSnapshot
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionCategory
import dev.minios.ocremote.domain.model.SessionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CrossServerSessionItem(
    val server: ServerConfig,
    val session: Session,
    val status: SessionStatus,
    val category: SessionCategory?,
    val isFavorite: Boolean,
    val favoriteIndex: Int?,
    val isConnected: Boolean,
)

data class CrossServerSessionsUiState(
    val items: List<CrossServerSessionItem> = emptyList(),
    val categories: List<SessionCategory> = emptyList(),
    val filterCategories: List<SessionCategory> = emptyList(),
    val connectedServerCount: Int = 0,
)

private data class ServerSessionPreferences(
    val favoriteIds: List<String>,
    val categoryAssignments: Map<String, String>,
)

@HiltViewModel
class CrossServerSessionsViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val settingsRepository: SettingsRepository,
    private val eventReducer: EventReducer,
    private val connectionStateRepository: ServerConnectionStateRepository,
) : ViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val preferencesByServer: Flow<Map<String, ServerSessionPreferences>> =
        serverRepository.servers.flatMapLatest { servers ->
            if (servers.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    servers.map { server ->
                        combine(
                            settingsRepository.favoriteSessionIds(server.id),
                            settingsRepository.sessionCategoryAssignments(server.id),
                        ) { favoriteIds, assignments ->
                            server.id to ServerSessionPreferences(favoriteIds, assignments)
                        }
                    },
                ) { values -> values.toMap() }
            }
        }

    private val sourceState = combine(
        serverRepository.servers,
        eventReducer.sessions,
        eventReducer.serverSessions,
        eventReducer.sessionStatuses,
        settingsRepository.sessionCategories,
    ) { servers, sessions, serverSessions, statuses, categories ->
        SourceState(servers, sessions, serverSessions, statuses, categories)
    }

    private val favoriteOrder = settingsRepository.crossServerFavoriteOrder.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val favoriteSnapshots = settingsRepository.favoriteSessionSnapshots.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyMap(),
    )

    val uiState = combine(
        sourceState,
        preferencesByServer,
        connectionStateRepository.connectedServerIds,
        favoriteOrder,
        favoriteSnapshots,
    ) { source, preferences, connectedIds, favoriteOrder, snapshots ->
        buildCrossServerSessionsState(source, preferences, connectedIds, favoriteOrder, snapshots)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        CrossServerSessionsUiState(),
    )

    init {
        viewModelScope.launch {
            uiState.map { state ->
                state.items
                    .filter(CrossServerSessionItem::isConnected)
                    .associate { it.favoriteKey() to FavoriteSessionSnapshot.from(it.session) }
            }.distinctUntilChanged().collect(settingsRepository::cacheFavoriteSessionSnapshots)
        }
    }


    fun toggleFavorite(item: CrossServerSessionItem) {
        viewModelScope.launch {
            settingsRepository.setSessionFavorite(
                serverId = item.server.id,
                sessionId = item.session.id,
                favorite = !item.isFavorite,
                snapshot = FavoriteSessionSnapshot.from(item.session),
            )
            settingsRepository.setCrossServerFavoriteOrderItem(item.favoriteKey(), !item.isFavorite)
        }
    }

    fun moveFavorite(item: CrossServerSessionItem, visibleItems: List<CrossServerSessionItem>, offset: Int) {
        if (offset == 0) return
        val mergedOrder = moveCrossServerFavoriteOrder(
            currentOrder = favoriteOrder.value,
            visibleOrder = visibleItems.map(CrossServerSessionItem::favoriteKey),
            itemKey = item.favoriteKey(),
            offset = offset,
        )
        if (mergedOrder == favoriteOrder.value) return
        viewModelScope.launch {
            settingsRepository.setCrossServerFavoriteOrder(mergedOrder)
        }
    }

    fun setSessionCategory(item: CrossServerSessionItem, categoryId: String?) {
        viewModelScope.launch {
            settingsRepository.setSessionCategory(item.server.id, item.session.id, categoryId)
        }
    }

    fun saveSessionCategory(id: String?, name: String, color: String, icon: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            settingsRepository.saveSessionCategory(
                SessionCategory(
                    id = id ?: java.util.UUID.randomUUID().toString(),
                    name = trimmed,
                    color = color,
                    icon = icon,
                ),
            )
        }
    }

    fun deleteSessionCategory(categoryId: String) {
        viewModelScope.launch { settingsRepository.deleteSessionCategory(categoryId) }
    }
}

internal fun CrossServerSessionItem.favoriteKey(): String = "${server.id}:${session.id}"

internal fun moveCrossServerFavoriteOrder(
    currentOrder: List<String>,
    visibleOrder: List<String>,
    itemKey: String,
    offset: Int,
): List<String> {
    val from = visibleOrder.indexOf(itemKey)
    if (from < 0 || offset == 0) return currentOrder
    val to = (from + offset).coerceIn(0, visibleOrder.lastIndex)
    if (from == to) return currentOrder
    val reordered = visibleOrder.toMutableList()
    reordered[from] = reordered[to]
    reordered[to] = itemKey
    val visibleKeys = reordered.toSet()
    val existingVisibleCount = currentOrder.count(visibleKeys::contains)
    val visibleIterator = reordered.iterator()
    return currentOrder.map { key ->
        if (key in visibleKeys) visibleIterator.next() else key
    } + reordered.drop(existingVisibleCount)
}

private data class SourceState(
    val servers: List<ServerConfig>,
    val sessions: List<Session>,
    val serverSessions: Map<String, Set<String>>,
    val statuses: Map<String, SessionStatus>,
    val categories: List<SessionCategory>,
)

private fun buildCrossServerSessionsState(
    source: SourceState,
    preferences: Map<String, ServerSessionPreferences>,
    connectedIds: Set<String>,
    favoriteOrder: List<String>,
    snapshots: Map<String, FavoriteSessionSnapshot>,
): CrossServerSessionsUiState {
    val sessionsById = source.sessions.associateBy(Session::id)
    val categoriesById = source.categories.associateBy(SessionCategory::id)
    val serverIndices = source.servers.withIndex().associate { it.value.id to it.index }
    val rawItems = source.servers
        .asSequence()
        .flatMap { server ->
            val serverPreferences = preferences[server.id] ?: ServerSessionPreferences(emptyList(), emptyMap())
            val isConnected = server.id in connectedIds
            val liveSessionIds = source.serverSessions[server.id].orEmpty()
            serverPreferences.favoriteIds.withIndex().asSequence().mapNotNull { (favoriteIndex, sessionId) ->
                val session = sessionsById[sessionId]
                    ?.takeIf { sessionId in liveSessionIds }
                    ?: snapshots["${server.id}:$sessionId"]?.toSession()
                    ?: Session(
                        id = sessionId,
                        time = Session.Time(created = 0, updated = 0),
                    )
                session
                    .takeUnless { it.isArchived || it.parentId != null }
                    ?: return@mapNotNull null
                val category = serverPreferences.categoryAssignments[sessionId]?.let(categoriesById::get)
                CrossServerSessionItem(
                    server = server,
                    session = session,
                    status = source.statuses[sessionId] ?: SessionStatus.Idle,
                    category = category,
                    isFavorite = true,
                    favoriteIndex = favoriteIndex,
                    isConnected = isConnected,
                )
            }
        }
        .sortedByDescending { it.session.time.updated }
        .toList()

    val storedIndices = favoriteOrder.withIndex().associate { it.value to it.index }
    val orderedFavorites = rawItems.sortedWith(
        compareBy<CrossServerSessionItem> { storedIndices[it.favoriteKey()] ?: Int.MAX_VALUE }
            .thenBy { serverIndices[it.server.id] ?: Int.MAX_VALUE }
            .thenBy { it.favoriteIndex ?: Int.MAX_VALUE },
    )
    val items = orderedFavorites.mapIndexed { index, item -> item.copy(favoriteIndex = index) }
    val visibleCategoryIds = items.mapNotNullTo(mutableSetOf()) { it.category?.id }

    return CrossServerSessionsUiState(
        items = items,
        categories = source.categories,
        filterCategories = source.categories.filter { it.id in visibleCategoryIds },
        connectedServerCount = connectedIds.size,
    )
}

internal fun sortCrossServerFavorites(items: List<CrossServerSessionItem>): List<CrossServerSessionItem> =
    items.filter(CrossServerSessionItem::isFavorite).sortedWith(
        compareBy<CrossServerSessionItem> { it.favoriteIndex ?: Int.MAX_VALUE }
        .thenByDescending { it.session.time.updated },
    )

internal fun filterCrossServerFavorites(
    items: List<CrossServerSessionItem>,
    categoryId: String?,
): List<CrossServerSessionItem> = sortCrossServerFavorites(items).filter { item ->
    categoryId == null || item.category?.id == categoryId
}
