package dev.minios.ocremote.ui.screens.sessions

import dev.minios.ocremote.logging.AppLogger as Log
import androidx.lifecycle.SavedStateHandle
import dev.minios.ocremote.BuildConfig
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.api.FileNode
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.data.repository.DirectoryScope
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.domain.model.Project
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SessionCategory
import dev.minios.ocremote.domain.model.FavoriteSessionSnapshot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "SessionListViewModel"

data class SessionListUiState(
    val sessionGroups: List<ProjectSessionGroup> = emptyList(),
    val projects: List<Project> = emptyList(),
    val serverName: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val categories: List<SessionCategory> = emptyList(),
)

/** A group of sessions belonging to a project. */
data class ProjectSessionGroup(
    val projectId: String,
    val projectName: String,
    val directory: String,
    val sessions: List<SessionItem>,
    val branch: String? = null,
    /** Per-session tilde-path labels (sessionId -> tildePath) for flat display. */
    val sessionDirLabels: Map<String, String> = emptyMap()
)

internal fun buildProjectSessionGroups(
    sessions: List<SessionItem>,
    projects: List<Project>,
    homeDir: String?,
    branches: Map<DirectoryScope, String?>,
    serverId: String,
): List<ProjectSessionGroup> {
    fun normalized(path: String) = path.trimEnd('/').ifEmpty { "/" }
    fun displayPath(path: String): String {
        val dir = normalized(path)
        return if (!homeDir.isNullOrBlank() && (dir == homeDir || dir.startsWith("$homeDir/"))) {
            "~" + dir.removePrefix(homeDir)
        } else {
            dir
        }
    }
    fun projectFor(session: Session): Project? {
        projects.firstOrNull { it.id.isNotBlank() && it.id == session.projectId }?.let { return it }
        val directory = normalized(session.directory)
        return projects
            .filter { project ->
                val root = normalized(project.worktree.ifBlank { project.path })
                directory == root || directory.startsWith("$root/")
            }
            .maxByOrNull { normalized(it.worktree.ifBlank { it.path }).length }
    }

    return sessions
        .groupBy { item ->
            val project = projectFor(item.session)
            project?.id?.takeIf { it.isNotBlank() }
                ?: "directory:${normalized(item.session.directory)}"
        }
        .map { (key, items) ->
            val sorted = sortSessionItems(items)
            val project = projectFor(sorted.first().session)
            val directory = normalized(
                project?.worktree?.takeIf { it.isNotBlank() }
                    ?: project?.path?.takeIf { it.isNotBlank() }
                    ?: sorted.first().session.directory,
            )
            val branch = branches.entries.firstOrNull { (scope, _) ->
                scope.serverId == serverId && normalized(scope.directory) == directory
            }?.value
            ProjectSessionGroup(
                projectId = project?.id ?: key,
                projectName = project?.displayName
                    ?: directory.substringAfterLast('/').ifEmpty { "/" },
                directory = directory,
                sessions = sorted,
                branch = branch,
                sessionDirLabels = sorted.associate { it.session.id to displayPath(it.session.directory) },
            )
        }
        .sortedWith(compareByDescending<ProjectSessionGroup> { group ->
            group.sessions.any { it.isFavorite }
        }.thenBy { group ->
            group.sessions.mapNotNull { it.favoriteIndex }.minOrNull() ?: Int.MAX_VALUE
        }.thenByDescending { group ->
            group.sessions.maxOfOrNull { it.session.time.updated } ?: 0
        }.thenBy { it.projectName.lowercase() })
}

data class SessionItem(
    val session: Session,
    val status: SessionStatus = SessionStatus.Idle,
    val favoriteIndex: Int? = null,
    val category: SessionCategory? = null,
) {
    val isFavorite: Boolean get() = favoriteIndex != null
}

internal fun sortSessionItems(items: List<SessionItem>): List<SessionItem> = items.sortedWith(
    compareByDescending<SessionItem> { it.isFavorite }
        .thenBy { it.favoriteIndex ?: Int.MAX_VALUE }
        .thenByDescending { it.session.time.updated }
)

internal data class DirectoryPathQuery(val parent: String, val segment: String)

internal fun parseDirectoryPathQuery(query: String, homeDirectory: String): DirectoryPathQuery? {
    val trimmed = query.trim()
    val expanded = when {
        trimmed == "~" -> homeDirectory
        trimmed.startsWith("~/") -> homeDirectory.trimEnd('/') + trimmed.removePrefix("~")
        trimmed.startsWith('/') -> trimmed
        else -> return null
    }
    if (expanded == "/") return DirectoryPathQuery("/", "")
    if (expanded.endsWith('/')) return DirectoryPathQuery(expanded.trimEnd('/').ifEmpty { "/" }, "")
    val parent = expanded.substringBeforeLast('/', missingDelimiterValue = "")
        .ifEmpty { "/" }
    return DirectoryPathQuery(parent, expanded.substringAfterLast('/'))
}

@HiltViewModel
class SessionListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val eventReducer: EventReducer,
    private val api: OpenCodeApi,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val serverUrl: String = savedStateHandle.get<String>("serverUrl").orEmpty()
    private val username: String = savedStateHandle.get<String>("username").orEmpty()
    private val password: String = savedStateHandle.get<String>("password").orEmpty()
    val serverName: String = savedStateHandle.get<String>("serverName").orEmpty()
    val serverId: String = savedStateHandle.get<String>("serverId").orEmpty()

    private val conn = ServerConnection.from(serverUrl, username, password.ifEmpty { null })

    val groupSessionsByProject: StateFlow<Boolean> = settingsRepository.groupSessionsByProject.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        false,
    )

    private val favoriteSessionIds: StateFlow<List<String>> = settingsRepository.favoriteSessionIds(serverId).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val sessionCategories: StateFlow<List<SessionCategory>> = settingsRepository.sessionCategories.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList(),
    )

    private val categoryAssignments: StateFlow<Map<String, String>> =
        settingsRepository.sessionCategoryAssignments(serverId).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            emptyMap(),
        )

    private val _error = MutableStateFlow<String?>(null)
    private val _isLoading = MutableStateFlow(true)
    private val _projects = MutableStateFlow<List<Project>>(emptyList())
    private val _homeDir = MutableStateFlow<String?>(null)
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val _navigateToSession = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val navigateToSession: SharedFlow<String> = _navigateToSession.asSharedFlow()

    @Suppress("UNCHECKED_CAST")
    val uiState: StateFlow<SessionListUiState> = combine(
        listOf(
            eventReducer.sessions,
            eventReducer.sessionStatuses,
            eventReducer.serverSessions,
            _isLoading,
            _error,
            _projects,
            _homeDir,
            _selectedIds,
            eventReducer.vcsBranches,
            favoriteSessionIds,
            sessionCategories,
            categoryAssignments,
        )
    ) { values ->
        val allSessions = values[0] as List<Session>
        val statuses = values[1] as Map<String, SessionStatus>
        val serverSessions = values[2] as Map<String, Set<String>>
        val loading = values[3] as Boolean
        val error = values[4] as String?
        val projects = values[5] as List<Project>
        val homeDir = values[6] as String?
        val selectedIds = values[7] as Set<String>
        val branches = values[8] as Map<DirectoryScope, String?>
        val favoriteIds = values[9] as List<String>
        val categories = values[10] as List<SessionCategory>
        val assignments = values[11] as Map<String, String>
        val favoriteOrder = favoriteIds.withIndex().associate { (index, id) -> id to index }
        val categoriesById = categories.associateBy { it.id }

        // Filter sessions belonging to this server
        val serverSessionIds = serverSessions[serverId] ?: emptySet()
        val sessions = allSessions
            .filter { it.id in serverSessionIds && !it.isArchived && it.parentId == null }
            .sortedByDescending { it.time.updated }
            .map { session ->
                SessionItem(
                    session = session,
                    status = statuses[session.id] ?: SessionStatus.Idle,
                    favoriteIndex = favoriteOrder[session.id],
                    category = assignments[session.id]?.let(categoriesById::get),
                )
            }

        val groups = buildProjectSessionGroups(sessions, projects, homeDir, branches, serverId)

        val visibleSessionIds = sessions.map { it.session.id }.toSet()
        val validSelectedIds = selectedIds.intersect(visibleSessionIds)
        if (validSelectedIds != selectedIds) {
            _selectedIds.value = validSelectedIds
        }

        SessionListUiState(
            sessionGroups = groups,
            projects = projects,
            serverName = serverName,
            isLoading = loading,
            error = error,
            selectedIds = validSelectedIds,
            isSelectionMode = validSelectedIds.isNotEmpty(),
            categories = categories,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        SessionListUiState(serverName = serverName)
    )

    init {
        loadHomeDir()
        loadSessions()
        viewModelScope.launch {
            while (true) {
                delay(5_000)
                refreshSessionStatuses()
            }
        }
    }

    fun setGroupSessionsByProject(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setGroupSessionsByProject(enabled) }
    }

    fun toggleFavorite(sessionId: String) {
        val favorites = favoriteSessionIds.value
        val session = uiState.value.sessionGroups
            .asSequence()
            .flatMap { it.sessions.asSequence() }
            .firstOrNull { it.session.id == sessionId }
            ?.session
        viewModelScope.launch {
            settingsRepository.setSessionFavorite(
                serverId = serverId,
                sessionId = sessionId,
                favorite = sessionId !in favorites,
                snapshot = session?.let(FavoriteSessionSnapshot::from),
            )
        }
    }

    fun moveFavorite(sessionId: String, offset: Int) {
        viewModelScope.launch {
            settingsRepository.moveFavoriteSession(serverId, sessionId, offset)
        }
    }

    fun setSessionCategory(sessionId: String, categoryId: String?) {
        viewModelScope.launch {
            settingsRepository.setSessionCategory(serverId, sessionId, categoryId)
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

    fun loadSessions() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // Load all projects first
                val projects = api.listProjects(conn)
                _projects.value = projects
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${projects.size} projects for multi-project session fetch")

                if (projects.isEmpty()) {
                    // Fallback: load sessions without directory header (server CWD only)
                    val sessions = api.listSessions(conn)
                    eventReducer.setSessions(serverId, sessions)
                    if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${sessions.size} sessions (no projects)")
                } else {
                    // Load sessions for each project using its worktree as directory
                    var totalSessions = 0
                    for (project in projects) {
                        try {
                            val sessions = api.listSessions(conn, directory = project.worktree)
                            eventReducer.setSessions(serverId, sessions)
                            totalSessions += sessions.size
                            if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${sessions.size} sessions for project ${project.displayName}")
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.w(TAG, "Failed to load sessions for project ${project.displayName}: ${e.message}")
                        }
                    }
                    if (BuildConfig.DEBUG) Log.d(TAG, "Total: loaded $totalSessions sessions across ${projects.size} projects for server $serverId")
                }
                refreshSessionStatuses(projects)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to load sessions", e)
                _error.value = e.message ?: "Failed to load sessions"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun refreshSessionStatuses(projects: List<Project> = _projects.value) {
        val directories: List<String?> = projects.map { it.worktree.takeIf(String::isNotBlank) }
            .ifEmpty { listOf(null) }
        val serverSessionIds = eventReducer.serverSessions.value[serverId].orEmpty()
        for (directory in directories) {
            try {
                val sessionIds = eventReducer.sessions.value.asSequence()
                    .filter { it.id in serverSessionIds }
                    .filter { directory == null || it.directory == directory }
                    .map { it.id }
                    .toSet()
                val statuses = api.listSessionStatuses(conn, directory)
                eventReducer.replaceSessionStatuses(serverId, sessionIds, statuses)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (BuildConfig.DEBUG) Log.d(TAG, "Failed to refresh project statuses: ${e::class.java.simpleName}")
            }
        }
    }

    private fun loadProjects() {
        viewModelScope.launch {
            try {
                val projects = api.listProjects(conn)
                _projects.value = projects
                if (BuildConfig.DEBUG) Log.d(TAG, "Loaded ${projects.size} projects")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to load projects", e)
            }
        }
    }

    private fun loadHomeDir() {
        viewModelScope.launch {
            getHomeDirectory()
        }
    }

    fun createNewSession(directory: String? = null) {
        viewModelScope.launch {
            try {
                val session = api.createSession(conn, directory = directory)
                // The SSE stream should pick up the new session, but also add directly
                eventReducer.setSessions(serverId, listOf(session))
                if (BuildConfig.DEBUG) Log.d(TAG, "Created new session: ${session.id}")
                _navigateToSession.tryEmit(session.id)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to create session", e)
                _error.value = e.message ?: "Failed to create session"
            }
        }
    }

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            try {
                val success = api.deleteSession(conn, sessionId)
                if (success) {
                    settingsRepository.setSessionFavorite(serverId, sessionId, false)
                    settingsRepository.setSessionCategory(serverId, sessionId, null)
                    if (BuildConfig.DEBUG) Log.d(TAG, "Deleted session $sessionId")
                    loadSessions()
                } else {
                    _error.value = "Failed to delete session"
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to delete session", e)
                _error.value = e.message ?: "Failed to delete session"
            }
        }
    }

    fun toggleSelection(sessionId: String) {
        _selectedIds.update { selected ->
            if (sessionId in selected) selected - sessionId else selected + sessionId
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun selectAll() {
        val allIds = uiState.value.sessionGroups
            .flatMap { group -> group.sessions.map { it.session.id } }
            .toSet()
        _selectedIds.value = allIds
    }

    fun deleteSelected() {
        viewModelScope.launch {
            val ids = _selectedIds.value
            if (ids.isEmpty()) return@launch
            try {
                val results = coroutineScope {
                    ids.map { id ->
                        async {
                            id to api.deleteSession(conn, id)
                        }
                    }.awaitAll()
                }
                val failed = results.filterNot { it.second }
                results.filter { it.second }.forEach { (id, _) ->
                    settingsRepository.setSessionFavorite(serverId, id, false)
                    settingsRepository.setSessionCategory(serverId, id, null)
                }
                if (failed.isNotEmpty()) {
                    _error.value = "Failed to delete ${failed.size} session(s)"
                }
                clearSelection()
                loadSessions()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to delete selected sessions", e)
                _error.value = e.message ?: "Failed to delete selected sessions"
            }
        }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            try {
                api.updateSession(conn, sessionId, newTitle)
                if (BuildConfig.DEBUG) Log.d(TAG, "Renamed session $sessionId to '$newTitle'")
                loadSessions()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Failed to rename session", e)
                _error.value = e.message ?: "Failed to rename session"
            }
        }
    }

    // ============ Directory browsing for Open Project ============

    /** Get the server's home directory (cached). */
    suspend fun getHomeDirectory(): String {
        _homeDir.value?.let { return it }
        return try {
            val paths = api.getServerPaths(conn)
            val home = paths.home
            _homeDir.value = home
            if (BuildConfig.DEBUG) Log.d(TAG, "Server home directory resolved")
            home
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to get server paths", e)
            "/"
        }
    }

    /** List directories in a given path on the server. */
    suspend fun listDirectories(directory: String): List<FileNode> {
        return try {
            val nodes = api.listDirectory(conn, path = "", directory = directory)
            nodes.filter { it.type == "directory" }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to list directory", e)
            emptyList()
        }
    }

    /** Search names fuzzily, but resolve typed absolute and home-relative paths like the WebUI. */
    suspend fun searchDirectories(query: String, directory: String): List<String> {
        val pathQuery = parseDirectoryPathQuery(query, directory)
        if (pathQuery != null) {
            val parentNodes = listDirectories(pathQuery.parent)
            if (pathQuery.segment.isEmpty()) {
                return (listOf(pathQuery.parent) + parentNodes.map { node ->
                    node.absolute ?: joinDirectory(pathQuery.parent, node.name)
                }).distinct()
            }

            val matches = parentNodes.filter { node ->
                node.name.contains(pathQuery.segment, ignoreCase = true)
            }
            val exact = matches.firstOrNull { it.name.equals(pathQuery.segment, ignoreCase = true) }
            if (exact != null) {
                val exactPath = exact.absolute ?: joinDirectory(pathQuery.parent, exact.name)
                val children = listDirectories(exactPath).map { node ->
                    node.absolute ?: joinDirectory(exactPath, node.name)
                }
                return (listOf(exactPath) + children).distinct()
            }
            return matches.map { it.absolute ?: joinDirectory(pathQuery.parent, it.name) }.distinct()
        }

        return try {
            api.findFiles(conn, query = query, type = "directory", directory = directory, limit = 50)
                .map { result ->
                    if (result.startsWith('/')) result.trimEnd('/')
                    else joinDirectory(directory, result)
                }
                .distinct()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to search directories", e)
            emptyList()
        }
    }

    private fun joinDirectory(parent: String, child: String): String =
        if (parent == "/") "/${child.trim('/')}" else "${parent.trimEnd('/')}/${child.trim('/')}"

    /** Create a directory inside the currently browsed path. */
    suspend fun createDirectory(parentDirectory: String, folderName: String): Result<String> {
        val sanitized = folderName.trim().trim('/').replace(Regex("/+"), "/")
        if (sanitized.isBlank() || sanitized == "." || sanitized == "..") {
            return Result.failure(IllegalArgumentException("Invalid folder name"))
        }

        return runCatching {
            val targetDirectory = if (parentDirectory == "/") {
                "/$sanitized"
            } else {
                "${parentDirectory.trimEnd('/')}/$sanitized"
            }

            val tempSession = api.createSession(
                conn = conn,
                title = "mkdir",
                directory = parentDirectory,
            )

            try {
                val escaped = sanitized.replace("'", "'\"'\"'")
                val command = "mkdir -p -- '$escaped'"

                val runShellOk = runCatching {
                    api.runShellCommand(
                        conn = conn,
                        sessionId = tempSession.id,
                        command = command,
                        agent = "build",
                        directory = parentDirectory,
                    )
                }.getOrElse { false }

                if (!runShellOk) {
                    val executeOk = api.executeCommand(
                        conn = conn,
                        sessionId = tempSession.id,
                        command = "bash",
                        arguments = "-lc \"$command\"",
                        directory = parentDirectory,
                    )
                    if (!executeOk) {
                        throw IllegalStateException("Failed to create directory")
                    }
                }
            } finally {
                runCatching { api.deleteSession(conn, tempSession.id) }
            }

            repeat(6) {
                if (directoryExists(targetDirectory)) {
                    return@runCatching targetDirectory
                }
                delay(200)
            }

            throw IllegalStateException("Directory was not created")
        }
    }

    private suspend fun directoryExists(directory: String): Boolean {
        return try {
            api.listDirectory(conn, path = "", directory = directory)
            true
        } catch (_: Exception) {
            false
        }
    }
}
