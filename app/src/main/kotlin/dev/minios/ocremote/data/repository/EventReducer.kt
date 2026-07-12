package dev.minios.ocremote.data.repository

import android.util.Log
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.domain.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EventReducer"
private const val MAX_PENDING_DELTA_KEYS = 128
private const val MAX_PENDING_DELTA_CHARS = 65_536

private data class PendingDeltaKey(
    val sessionId: String,
    val messageId: String,
    val partId: String,
)

/**
 * Event Reducer - processes SSE events and updates app state
 * 
 * This is the central state management for the app.
 * All SSE events flow through here and mutate the reactive state.
 * 
 * Supports multiple servers simultaneously. Session UUIDs are globally unique,
 * so all data maps are keyed by sessionId. A separate serverId→sessionIds map
 * tracks which sessions belong to which server for per-server cleanup.
 * 
 * Similar to the event-reducer.ts in the WebUI.
 */
@Singleton
class EventReducer @Inject constructor() {
    private val pendingLock = Any()
    private var pendingRevision = 0L
    private val deltaLock = Any()
    private val pendingDeltas = LinkedHashMap<PendingDeltaKey, StringBuilder>()
    
    // ============ State ============
    
    /** Maps serverId → set of sessionIds belonging to that server */
    private val _serverSessions = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val serverSessions: StateFlow<Map<String, Set<String>>> = _serverSessions.asStateFlow()
    
    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()
    
    private val _sessionStatuses = MutableStateFlow<Map<String, SessionStatus>>(emptyMap())
    val sessionStatuses: StateFlow<Map<String, SessionStatus>> = _sessionStatuses.asStateFlow()
    
    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap()) // sessionId -> messages
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()
    
    private val _parts = MutableStateFlow<Map<String, List<Part>>>(emptyMap()) // messageId -> parts
    val parts: StateFlow<Map<String, List<Part>>> = _parts.asStateFlow()
    
    private val _sessionDiffs = MutableStateFlow<Map<String, List<FileDiff>>>(emptyMap())
    val sessionDiffs: StateFlow<Map<String, List<FileDiff>>> = _sessionDiffs.asStateFlow()

    private val _sessionErrors = MutableStateFlow<Map<String, Message.Assistant.ErrorInfo>>(emptyMap())
    val sessionErrors: StateFlow<Map<String, Message.Assistant.ErrorInfo>> = _sessionErrors.asStateFlow()
    
    private val _permissions = MutableStateFlow<Map<String, List<SseEvent.PermissionAsked>>>(emptyMap())
    val permissions: StateFlow<Map<String, List<SseEvent.PermissionAsked>>> = _permissions.asStateFlow()
    
    private val _questions = MutableStateFlow<Map<String, List<SseEvent.QuestionAsked>>>(emptyMap())
    val questions: StateFlow<Map<String, List<SseEvent.QuestionAsked>>> = _questions.asStateFlow()
    
    private val _todos = MutableStateFlow<Map<String, List<SseEvent.TodoUpdated.Todo>>>(emptyMap())
    val todos: StateFlow<Map<String, List<SseEvent.TodoUpdated.Todo>>> = _todos.asStateFlow()
    
    private val _vcsBranch = MutableStateFlow<String?>(null)
    val vcsBranch: StateFlow<String?> = _vcsBranch.asStateFlow()
    
    private val _projectInfo = MutableStateFlow<Project?>(null)
    val projectInfo: StateFlow<Project?> = _projectInfo.asStateFlow()
    
    // ============ Event Processing ============
    
    /**
     * Process an SSE event and update state.
     * @param event The SSE event to process
     * @param serverId The server this event came from (used for session tracking)
     */
    fun processEvent(event: SseEvent, serverId: String) {
        when (event) {
            is SseEvent.ServerConnected -> handleServerConnected()
            is SseEvent.ServerHeartbeat -> { /* No-op */ }
            is SseEvent.ServerInstanceDisposed -> handleServerInstanceDisposed(event)
            
            is SseEvent.SessionCreated -> handleSessionCreated(event, serverId)
            is SseEvent.SessionUpdated -> handleSessionUpdated(event, serverId)
            is SseEvent.SessionDeleted -> handleSessionDeleted(event)
            is SseEvent.SessionStatus -> handleSessionStatus(event, serverId)
            is SseEvent.SessionIdle -> handleSessionIdle(event, serverId)
            is SseEvent.SessionDiff -> handleSessionDiff(event)
            is SseEvent.SessionError -> handleSessionError(event)
            
            is SseEvent.MessageUpdated -> handleMessageUpdated(event)
            is SseEvent.MessageRemoved -> handleMessageRemoved(event)
            
            is SseEvent.MessagePartUpdated -> handleMessagePartUpdated(event)
            is SseEvent.MessagePartDelta -> handleMessagePartDelta(event)
            is SseEvent.MessagePartRemoved -> handleMessagePartRemoved(event)
            
            is SseEvent.PermissionAsked -> handlePermissionAsked(event)
            is SseEvent.PermissionReplied -> handlePermissionReplied(event)
            
            is SseEvent.QuestionAsked -> handleQuestionAsked(event)
            is SseEvent.QuestionReplied -> handleQuestionReplied(event)
            is SseEvent.QuestionRejected -> handleQuestionRejected(event)
            
            is SseEvent.TodoUpdated -> handleTodoUpdated(event)
            is SseEvent.VcsBranchUpdated -> handleVcsBranchUpdated(event)
            is SseEvent.LspUpdated -> { /* LSP events not needed in mobile */ }
            is SseEvent.ProjectUpdated -> handleProjectUpdated(event)
        }
    }
    
    // ============ Server Events ============
    
    private fun handleServerConnected() {
        if (BuildConfig.DEBUG) Log.d(TAG, "Server connected")
    }
    
    private fun handleServerInstanceDisposed(event: SseEvent.ServerInstanceDisposed) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Server instance disposed: ${event.directory}")
        // State cleanup for the directory is handled by clearForServer() on disconnect
    }
    
    // ============ Session Events ============
    
    private fun handleSessionCreated(event: SseEvent.SessionCreated, serverId: String) {
        trackSession(serverId, event.info.id)
        _sessions.update { current ->
            (current.filterNot { it.id == event.info.id } + event.info)
                .sortedByDescending { it.time.updated }
        }
        _sessionStatuses.update { current ->
            if (event.info.id in current) current else current + (event.info.id to SessionStatus.Idle)
        }
    }
    
    private fun handleSessionUpdated(event: SseEvent.SessionUpdated, serverId: String) {
        trackSession(serverId, event.info.id)
        _sessions.update { current ->
            val existingIndex = current.indexOfFirst { it.id == event.info.id }
            if (existingIndex >= 0) {
                // Update existing
                current.toMutableList().apply { set(existingIndex, event.info) }
            } else {
                // Upsert: session wasn't in list (no session.created received), add it
                if (BuildConfig.DEBUG) Log.d(TAG, "Session ${event.info.id} not found, upserting (title=${event.info.title})")
                (current + event.info).sortedByDescending { it.time.updated }
            }
        }
    }
    
    /** Register a session as belonging to a server */
    private fun trackSession(serverId: String, sessionId: String) {
        _serverSessions.update { current ->
            val existing = current[serverId] ?: emptySet()
            current + (serverId to (existing + sessionId))
        }
    }
    
    private fun handleSessionDeleted(event: SseEvent.SessionDeleted) {
        val sessionId = event.info.id
        val messageIds = _messages.value[sessionId].orEmpty().map { it.id }.toSet()
        _serverSessions.update { current ->
            current.mapValues { (_, sessionIds) -> sessionIds - sessionId }
                .filterValues { it.isNotEmpty() }
        }
        _sessions.update { it.filter { session -> session.id != sessionId } }
        _sessionStatuses.update { it - sessionId }
        _messages.update { it - sessionId }
        _parts.update { current -> current.filterKeys { it !in messageIds } }
        _sessionDiffs.update { it - sessionId }
        _sessionErrors.update { it - sessionId }
        _permissions.update { it - sessionId }
        _questions.update { it - sessionId }
        _todos.update { it - sessionId }
        synchronized(deltaLock) {
            pendingDeltas.keys.removeAll { it.sessionId == sessionId }
        }
    }
    
    private fun handleSessionStatus(event: SseEvent.SessionStatus, serverId: String) {
        trackSession(serverId, event.sessionId)
        _sessionStatuses.update { it + (event.sessionId to event.status) }
        if (event.status is SessionStatus.Busy) {
            _sessionErrors.update { it - event.sessionId }
        }
    }
    
    private fun handleSessionIdle(event: SseEvent.SessionIdle, serverId: String) {
        trackSession(serverId, event.sessionId)
        _sessionStatuses.update { it + (event.sessionId to SessionStatus.Idle) }
    }
    
    private fun handleSessionDiff(event: SseEvent.SessionDiff) {
        _sessionDiffs.update { it + (event.sessionId to event.diff) }
    }
    
    private fun handleSessionError(event: SseEvent.SessionError) {
        Log.e(TAG, "Session ${event.sessionId} error: ${event.error.message}")
        event.sessionId?.let { sessionId ->
            _sessionErrors.update { it + (sessionId to event.error) }
            _sessionStatuses.update { it + (sessionId to SessionStatus.Idle) }
        }
    }
    
    // ============ Message Events ============
    
    private fun handleMessageUpdated(event: SseEvent.MessageUpdated) {
        val sessionId = event.info.sessionId
        _messages.update { current ->
            val sessionMessages = current[sessionId]?.toMutableList() ?: mutableListOf()
            val existingIndex = sessionMessages.indexOfFirst { it.id == event.info.id }
            
            if (existingIndex >= 0) {
                sessionMessages[existingIndex] = event.info
            } else {
                sessionMessages.add(event.info)
                sessionMessages.sortBy { it.time.created }
            }
            
            current + (sessionId to sessionMessages)
        }
    }
    
    private fun handleMessageRemoved(event: SseEvent.MessageRemoved) {
        _messages.update { current ->
            val sessionMessages = current[event.sessionId]?.filter { it.id != event.messageId }
            if (sessionMessages != null) {
                if (sessionMessages.isEmpty()) current - event.sessionId else current + (event.sessionId to sessionMessages)
            } else {
                current
            }
        }
        _parts.update { it - event.messageId }
        synchronized(deltaLock) {
            pendingDeltas.keys.removeAll { it.messageId == event.messageId }
        }
    }
    
    // ============ Part Events ============
    
    private fun handleMessagePartUpdated(event: SseEvent.MessagePartUpdated) {
        val messageId = event.part.messageId
        val key = PendingDeltaKey(event.part.sessionId, messageId, event.part.id)
        val buffered = synchronized(deltaLock) { pendingDeltas.remove(key)?.toString().orEmpty() }
        val updatedPart = if (buffered.isNotEmpty()) {
            applyTextDelta(event.part, buffered)
        } else {
            event.part
        }
        _parts.update { current ->
            val messageParts = current[messageId]?.toMutableList() ?: mutableListOf()
            val existingIndex = messageParts.indexOfFirst { it.id == updatedPart.id }
            
            if (existingIndex >= 0) {
                messageParts[existingIndex] = updatedPart
            } else {
                messageParts.add(updatedPart)
            }
            
            current + (messageId to messageParts)
        }
    }
    
    private fun handleMessagePartDelta(event: SseEvent.MessagePartDelta) {
        if (event.field != "text") {
            if (BuildConfig.DEBUG) Log.d(TAG, "Ignoring unsupported delta field=${event.field} part=${event.partId}")
            return
        }
        var applied = false
        _parts.update { current ->
            val messageParts = current[event.messageId]?.toMutableList() ?: return@update current
            val partIndex = messageParts.indexOfFirst { it.id == event.partId }
            
            if (partIndex < 0) return@update current
            
            val part = messageParts[partIndex]
            val updatedPart = applyTextDelta(part, event.delta)
            if (updatedPart === part) return@update current

            messageParts[partIndex] = updatedPart
            applied = true
            current + (event.messageId to messageParts)
        }
        if (!applied) bufferDelta(event)
    }
    
    private fun handleMessagePartRemoved(event: SseEvent.MessagePartRemoved) {
        _parts.update { current ->
            val messageParts = current[event.messageId]?.filter { it.id != event.partId }
            if (messageParts != null) {
                if (messageParts.isEmpty()) current - event.messageId else current + (event.messageId to messageParts)
            } else {
                current
            }
        }
        synchronized(deltaLock) {
            pendingDeltas.remove(PendingDeltaKey(event.sessionId, event.messageId, event.partId))
        }
    }

    private fun applyTextDelta(part: Part, delta: String): Part = when (part) {
        is Part.Text -> part.copy(text = part.text + delta)
        is Part.Reasoning -> part.copy(text = part.text + delta)
        else -> part
    }

    private fun bufferDelta(event: SseEvent.MessagePartDelta) {
        synchronized(deltaLock) {
            val key = PendingDeltaKey(event.sessionId, event.messageId, event.partId)
            if (key !in pendingDeltas && pendingDeltas.size >= MAX_PENDING_DELTA_KEYS) {
                pendingDeltas.remove(pendingDeltas.keys.first())
            }
            val buffer = pendingDeltas.getOrPut(key) { StringBuilder() }
            val available = MAX_PENDING_DELTA_CHARS - buffer.length
            if (available > 0) buffer.append(event.delta.take(available))
        }
    }
    
    // ============ Permission Events ============
    
    private fun handlePermissionAsked(event: SseEvent.PermissionAsked) {
        synchronized(pendingLock) {
            _permissions.update { current ->
                val existing = current[event.sessionId].orEmpty()
                val index = existing.indexOfFirst { it.id == event.id }
                val updated = if (index >= 0) {
                    existing.toMutableList().apply { set(index, event) }
                } else {
                    existing + event
                }
                current + (event.sessionId to updated)
            }
            pendingRevision++
        }
    }
    
    private fun handlePermissionReplied(event: SseEvent.PermissionReplied) {
        synchronized(pendingLock) {
            _permissions.update { current ->
                val remaining = current[event.sessionId]?.filter { it.id != event.requestId } ?: return@update current
                if (remaining.isEmpty()) current - event.sessionId else current + (event.sessionId to remaining)
            }
            pendingRevision++
        }
    }
    
    // ============ Question Events ============
    
    private fun handleQuestionAsked(event: SseEvent.QuestionAsked) {
        synchronized(pendingLock) {
            _questions.update { current ->
                val existing = current[event.sessionId].orEmpty()
                val index = existing.indexOfFirst { it.id == event.id }
                val updated = if (index >= 0) {
                    existing.toMutableList().apply { set(index, event) }
                } else {
                    existing + event
                }
                current + (event.sessionId to updated)
            }
            pendingRevision++
        }
    }
    
    private fun handleQuestionReplied(event: SseEvent.QuestionReplied) {
        synchronized(pendingLock) {
            _questions.update { current ->
                val remaining = current[event.sessionId]?.filter { it.id != event.requestId } ?: return@update current
                if (remaining.isEmpty()) current - event.sessionId else current + (event.sessionId to remaining)
            }
            pendingRevision++
        }
    }
    
    private fun handleQuestionRejected(event: SseEvent.QuestionRejected) {
        synchronized(pendingLock) {
            _questions.update { current ->
                val remaining = current[event.sessionId]?.filter { it.id != event.requestId } ?: return@update current
                if (remaining.isEmpty()) current - event.sessionId else current + (event.sessionId to remaining)
            }
            pendingRevision++
        }
    }

    /**
     * Optimistically remove a question from the pending list.
     * Called after a successful API reply/reject, in case the SSE event doesn't arrive.
     */
    fun removeQuestion(questionId: String) {
        _questions.update { current ->
            current.mapValues { (_, questions) ->
                questions.filter { it.id != questionId }
            }
        }
    }

    /**
     * Set pending questions for a session (loaded from REST API on session open).
     */
    fun setQuestions(sessionId: String, questions: List<SseEvent.QuestionAsked>) {
        _questions.update { current ->
            if (questions.isEmpty()) {
                current - sessionId
            } else {
                current + (sessionId to questions)
            }
        }
    }

    fun pendingSnapshotRevision(): Long = synchronized(pendingLock) { pendingRevision }

    fun replacePendingRequests(
        serverId: String,
        permissions: List<SseEvent.PermissionAsked>,
        questions: List<SseEvent.QuestionAsked>,
        expectedRevision: Long,
    ): Boolean = synchronized(pendingLock) {
        if (pendingRevision != expectedRevision) return@synchronized false
        val sessionIds = _serverSessions.value[serverId].orEmpty()
        _permissions.update { current ->
            current.filterKeys { it !in sessionIds } + permissions
                .distinctBy { it.sessionId to it.id }
                .groupBy { it.sessionId }
        }
        _questions.update { current ->
            current.filterKeys { it !in sessionIds } + questions
                .distinctBy { it.sessionId to it.id }
                .groupBy { it.sessionId }
        }
        pendingRevision++
        true
    }

    fun replaceSessionStatuses(
        serverId: String,
        sessionIds: Set<String>,
        statuses: Map<String, SessionStatus>,
    ) {
        val scope = sessionIds + statuses.keys
        scope.forEach { trackSession(serverId, it) }
        _sessionStatuses.update { current ->
            current + scope.associateWith { statuses[it] ?: SessionStatus.Idle }
        }
    }
    
    // ============ Batch Updates ============
    
    /**
     * Load initial session list for a server.
     * Registers all session IDs as belonging to the given serverId.
     */
    fun setSessions(serverId: String, sessions: List<Session>) {
        val sessionIds = sessions.map { it.id }.toSet()
        _serverSessions.update { current ->
            val existing = current[serverId] ?: emptySet()
            current + (serverId to (existing + sessionIds))
        }
        _sessions.update { current ->
            // Merge: replace existing sessions by ID, add new ones
            val updated = current.toMutableList()
            for (session in sessions) {
                val idx = updated.indexOfFirst { it.id == session.id }
                if (idx >= 0) {
                    updated[idx] = session
                } else {
                    updated.add(session)
                }
            }
            updated.sortedByDescending { it.time.updated }
        }
    }

    /**
     * Manually update the session status.
     * Useful for optimistic updates (e.g. aborting a session).
     */
    fun updateSessionStatus(sessionId: String, status: SessionStatus) {
        _sessionStatuses.update { it + (sessionId to status) }
        if (BuildConfig.DEBUG) Log.d(TAG, "Manually updated session $sessionId status to $status")
    }
    
    /**
     * Load messages for a session
     */
    fun mergeMessages(sessionId: String, messages: List<MessageWithParts>) {
        _messages.update { current ->
            val merged = (current[sessionId].orEmpty() + messages.map { it.info })
                .associateBy { it.id }
                .values
                .sortedBy { it.time.created }
            current + (sessionId to merged)
        }

        val partsMap = messages.associate { msg ->
            msg.info.id to msg.parts
        }
        _parts.update { current ->
            current + partsMap.mapValues { (messageId, loadedParts) ->
                (current[messageId].orEmpty() + loadedParts).associateBy { it.id }.values.toList()
            }
        }
    }
    
    /**
     * Clear all state (used when ALL servers disconnect)
     */
    fun clearAll() {
        _serverSessions.value = emptyMap()
        _sessions.value = emptyList()
        _sessionStatuses.value = emptyMap()
        _messages.value = emptyMap()
        _parts.value = emptyMap()
        _sessionDiffs.value = emptyMap()
        _sessionErrors.value = emptyMap()
        _permissions.value = emptyMap()
        _questions.value = emptyMap()
        synchronized(deltaLock) { pendingDeltas.clear() }
        _todos.value = emptyMap()
        _vcsBranch.value = null
        _projectInfo.value = null
    }
    
    /**
     * Clear state for a single server.
     * Removes sessions belonging to that server and all associated data.
     */
    fun clearForServer(serverId: String) {
        val sessionIds = _serverSessions.value[serverId] ?: emptySet()
        if (sessionIds.isEmpty()) {
            _serverSessions.update { it - serverId }
            return
        }
        
        // Remove the server's session tracking
        _serverSessions.update { it - serverId }
        
        // Remove sessions
        _sessions.update { it.filter { s -> s.id !in sessionIds } }
        _sessionStatuses.update { it - sessionIds }
        _sessionDiffs.update { it - sessionIds }
        _sessionErrors.update { it - sessionIds }
        _permissions.update { it - sessionIds }
        _questions.update { it - sessionIds }
        _todos.update { it - sessionIds }
        
        // Remove messages and their parts
        val messageIds = _messages.value
            .filterKeys { it in sessionIds }
            .values
            .flatten()
            .map { it.id }
            .toSet()
        _messages.update { it - sessionIds }
        _parts.update { it - messageIds }
        synchronized(deltaLock) {
            pendingDeltas.keys.removeAll { it.sessionId in sessionIds }
        }
        synchronized(pendingLock) {
            pendingRevision++
        }
    }
    
    // ============ Todo Events ============
    
    private fun handleTodoUpdated(event: SseEvent.TodoUpdated) {
        _todos.update { it + (event.sessionId to event.todos) }
    }
    
    // ============ VCS Events ============
    
    private fun handleVcsBranchUpdated(event: SseEvent.VcsBranchUpdated) {
        _vcsBranch.value = event.branch
    }
    
    // ============ Project Events ============
    
    private fun handleProjectUpdated(event: SseEvent.ProjectUpdated) {
        _projectInfo.value = event.info
    }
}
