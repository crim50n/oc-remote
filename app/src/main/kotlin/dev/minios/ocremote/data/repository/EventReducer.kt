package dev.minios.ocremote.data.repository

import dev.minios.ocremote.logging.AppLogger as Log
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.domain.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "EventReducer"
private const val MAX_PENDING_DELTA_KEYS = 128
private const val MAX_PENDING_DELTA_CHARS = 65_536

internal fun compactSessionForCache(session: Session): Session = session.copy(
    summary = session.summary?.copy(diffs = null),
    permission = null,
)

private data class PendingDeltaKey(
    val sessionId: String,
    val messageId: String,
    val partId: String,
)

data class DirectoryScope(val serverId: String, val directory: String, val workspaceId: String? = null)

enum class PromptDeliveryState { ADMITTED, PROMOTED }
data class PromptDeliveryInfo(val sessionId: String, val state: PromptDeliveryState)
data class WorkspaceScope(val serverId: String, val workspaceId: String)

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
    
    private val _pendingInteractions = MutableStateFlow<List<PendingInteraction>>(emptyList())
    val pendingInteractions: StateFlow<List<PendingInteraction>> = _pendingInteractions.asStateFlow()
    
    private val _todos = MutableStateFlow<Map<String, List<SseEvent.TodoUpdated.Todo>>>(emptyMap())
    val todos: StateFlow<Map<String, List<SseEvent.TodoUpdated.Todo>>> = _todos.asStateFlow()
    
    private val _vcsBranches = MutableStateFlow<Map<DirectoryScope, String?>>(emptyMap())
    val vcsBranches: StateFlow<Map<DirectoryScope, String?>> = _vcsBranches.asStateFlow()
    
    private val _projectInfo = MutableStateFlow<Map<DirectoryScope, Project>>(emptyMap())
    val projectInfo: StateFlow<Map<DirectoryScope, Project>> = _projectInfo.asStateFlow()

    private val _promptDeliveries = MutableStateFlow<Map<String, PromptDeliveryInfo>>(emptyMap())
    val promptDeliveries: StateFlow<Map<String, PromptDeliveryInfo>> = _promptDeliveries.asStateFlow()

    private val _workspaceStatuses = MutableStateFlow<Map<WorkspaceScope, String>>(emptyMap())
    val workspaceStatuses: StateFlow<Map<WorkspaceScope, String>> = _workspaceStatuses.asStateFlow()
    
    // ============ Event Processing ============
    
    /**
     * Process an SSE event and update state.
     * @param event The SSE event to process
     * @param serverId The server this event came from (used for session tracking)
     */
    fun processEvent(event: SseEvent, serverId: String, directory: String? = null, workspaceId: String? = null) {
        when (event) {
            is SseEvent.ServerConnected -> handleServerConnected()
            is SseEvent.ServerHeartbeat -> { /* No-op */ }
            is SseEvent.ServerInstanceDisposed -> handleServerInstanceDisposed(event, serverId)
            is SseEvent.GlobalDisposed -> clearTransientForServer(serverId)
            is SseEvent.WorkspaceStatus -> _workspaceStatuses.update {
                it + (WorkspaceScope(serverId, event.workspaceId) to event.status)
            }
            is SseEvent.WorkspaceReady -> event.workspaceId?.let { workspaceId ->
                _workspaceStatuses.update { it + (WorkspaceScope(serverId, workspaceId) to "connected") }
            }
            is SseEvent.WorkspaceFailed -> event.workspaceId?.let { workspaceId ->
                _workspaceStatuses.update { it + (WorkspaceScope(serverId, workspaceId) to "error") }
            }
            is SseEvent.WorktreeReady, is SseEvent.WorktreeFailed -> Unit
            
            is SseEvent.SessionCreated -> handleSessionCreated(event, serverId)
            is SseEvent.SessionUpdated -> handleSessionUpdated(event, serverId)
            is SseEvent.SessionDeleted -> handleSessionDeleted(event)
            is SseEvent.SessionStatus -> handleSessionStatus(event, serverId)
            is SseEvent.SessionIdle -> handleSessionIdle(event, serverId)
            is SseEvent.SessionCompacted -> Unit
            is SseEvent.SessionDiff -> handleSessionDiff(event)
            is SseEvent.SessionError -> handleSessionError(event)
            is SseEvent.PromptAdmitted -> _promptDeliveries.update {
                it + (event.messageId to PromptDeliveryInfo(event.sessionId, PromptDeliveryState.ADMITTED))
            }
            is SseEvent.Prompted -> handleNextPrompted(event, serverId)
            is SseEvent.NextStepStarted -> handleNextStepStarted(event, serverId)
            is SseEvent.NextStepEnded -> handleNextStepEnded(event)
            is SseEvent.NextStepFailed -> handleNextStepFailed(event)
            is SseEvent.NextAgentSwitched -> updateMessage(event.sessionId, event.messageId) { message ->
                when (message) {
                    is Message.User -> message.copy(agent = event.agent)
                    is Message.Assistant -> message.copy(agent = event.agent)
                }
            }
            is SseEvent.NextModelSwitched -> updateMessage(event.sessionId, event.messageId) { message ->
                val model = event.model.jsonObject
                when (message) {
                    is Message.User -> message.copy(
                        model = Message.User.Model(
                            model["providerID"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            model["modelID"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        ),
                        variant = model["variant"]?.jsonPrimitive?.contentOrNull,
                    )
                    is Message.Assistant -> message.copy(
                        providerId = model["providerID"]?.jsonPrimitive?.contentOrNull,
                        modelId = model["modelID"]?.jsonPrimitive?.contentOrNull,
                        variant = model["variant"]?.jsonPrimitive?.contentOrNull,
                    )
                }
            }
            is SseEvent.NextContextUpdated -> handleMessagePartUpdated(SseEvent.MessagePartUpdated(Part.Text(
                "${event.messageId}-context", event.sessionId, event.messageId, event.text,
                time = Part.Text.Time(event.timestamp, event.timestamp),
            )))
            is SseEvent.NextSynthetic -> handleMessagePartUpdated(SseEvent.MessagePartUpdated(Part.Text(
                "${event.messageId}-synthetic", event.sessionId, event.messageId, event.text, synthetic = true,
                time = Part.Text.Time(event.timestamp, event.timestamp),
            )))
            is SseEvent.NextShellStarted -> handleNextShellStarted(event)
            is SseEvent.NextShellEnded -> handleNextShellEnded(event)
            is SseEvent.NextTextStarted -> handleMessagePartUpdated(SseEvent.MessagePartUpdated(
                Part.Text(event.textId, event.sessionId, event.messageId, time = Part.Text.Time(event.timestamp)),
            ))
            is SseEvent.NextTextDelta -> handleMessagePartDelta(SseEvent.MessagePartDelta(
                event.sessionId, event.messageId, event.textId, "text", event.delta,
            ))
            is SseEvent.NextTextEnded -> handleMessagePartUpdated(SseEvent.MessagePartUpdated(
                Part.Text(event.textId, event.sessionId, event.messageId, event.text, time = Part.Text.Time(event.timestamp, event.timestamp)),
            ))
            is SseEvent.NextReasoningStarted -> handleMessagePartUpdated(SseEvent.MessagePartUpdated(
                Part.Reasoning(event.reasoningId, event.sessionId, event.messageId, time = Part.Reasoning.Time(event.timestamp)),
            ))
            is SseEvent.NextReasoningDelta -> handleMessagePartDelta(SseEvent.MessagePartDelta(
                event.sessionId, event.messageId, event.reasoningId, "text", event.delta,
            ))
            is SseEvent.NextReasoningEnded -> handleMessagePartUpdated(SseEvent.MessagePartUpdated(
                Part.Reasoning(event.reasoningId, event.sessionId, event.messageId, event.text, time = Part.Reasoning.Time(event.timestamp, event.timestamp)),
            ))
            is SseEvent.NextToolInputStarted -> handleMessagePartUpdated(SseEvent.MessagePartUpdated(Part.Tool(
                id = event.callId,
                sessionId = event.sessionId,
                messageId = event.messageId,
                callId = event.callId,
                tool = event.name,
                state = ToolState.Pending(),
            )))
            is SseEvent.NextToolInputDelta -> handleNextToolInputDelta(event)
            is SseEvent.NextToolInputEnded -> handleNextToolInputEnded(event)
            is SseEvent.NextToolCalled -> handleNextToolCalled(event)
            is SseEvent.NextToolProgress -> handleNextToolProgress(event)
            is SseEvent.NextToolSuccess -> handleNextToolSuccess(event)
            is SseEvent.NextToolFailed -> handleNextToolFailed(event)
            
            is SseEvent.MessageUpdated -> handleMessageUpdated(event)
            is SseEvent.MessageRemoved -> handleMessageRemoved(event)
            
            is SseEvent.MessagePartUpdated -> handleMessagePartUpdated(event)
            is SseEvent.MessagePartDelta -> handleMessagePartDelta(event)
            is SseEvent.MessagePartRemoved -> handleMessagePartRemoved(event)
            
            is SseEvent.PermissionAsked -> handlePermissionAsked(event, serverId)
            is SseEvent.PermissionReplied -> handlePermissionReplied(event)
            
            is SseEvent.QuestionAsked -> handleQuestionAsked(event, serverId)
            is SseEvent.QuestionReplied -> handleQuestionReplied(event)
            is SseEvent.QuestionRejected -> handleQuestionRejected(event)
            
            is SseEvent.TodoUpdated -> handleTodoUpdated(event)
            is SseEvent.VcsBranchUpdated -> handleVcsBranchUpdated(event, serverId, directory, workspaceId)
            is SseEvent.LspUpdated -> { /* LSP events not needed in mobile */ }
            is SseEvent.ProjectUpdated -> handleProjectUpdated(event, serverId, directory, workspaceId)
        }
    }
    
    // ============ Server Events ============
    
    private fun handleServerConnected() {
        if (BuildConfig.DEBUG) Log.d(TAG, "Server connected")
    }
    
    private fun handleServerInstanceDisposed(event: SseEvent.ServerInstanceDisposed, serverId: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Server instance disposed")
        _vcsBranches.update { current -> current.filterKeys { it.serverId != serverId || it.directory != event.directory } }
        _projectInfo.update { current -> current.filterKeys { it.serverId != serverId || it.directory != event.directory } }
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

    private fun handleNextPrompted(event: SseEvent.Prompted, serverId: String) {
        trackSession(serverId, event.sessionId)
        _promptDeliveries.update {
            it + (event.messageId to PromptDeliveryInfo(event.sessionId, PromptDeliveryState.PROMOTED))
        }
        val timestamp = event.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
        handleMessageUpdated(SseEvent.MessageUpdated(Message.User(
            id = event.messageId,
            sessionId = event.sessionId,
            time = TimeInfo(timestamp),
        )))
        val prompt = event.prompt?.jsonObject ?: return
        prompt["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { text ->
            handleMessagePartUpdated(SseEvent.MessagePartUpdated(Part.Text(
                id = "${event.messageId}-prompt",
                sessionId = event.sessionId,
                messageId = event.messageId,
                text = text,
                time = Part.Text.Time(timestamp, timestamp),
            )))
        }
        prompt["files"]?.jsonArray?.forEachIndexed { index, element ->
            val file = element.jsonObject
            handleMessagePartUpdated(SseEvent.MessagePartUpdated(Part.File(
                id = "${event.messageId}-file-$index",
                sessionId = event.sessionId,
                messageId = event.messageId,
                mime = "application/octet-stream",
                filename = file["name"]?.jsonPrimitive?.contentOrNull,
                url = file["uri"]?.jsonPrimitive?.contentOrNull,
                source = file["source"],
            )))
        }
    }

    private fun handleNextStepStarted(event: SseEvent.NextStepStarted, serverId: String) {
        trackSession(serverId, event.sessionId)
        val model = event.model.jsonObject
        val parentId = _messages.value[event.sessionId]
            ?.filterIsInstance<Message.User>()
            ?.maxByOrNull { it.time.created }
            ?.id
            ?: ""
        handleMessageUpdated(SseEvent.MessageUpdated(Message.Assistant(
            id = event.assistantMessageId,
            sessionId = event.sessionId,
            time = TimeInfo(event.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()),
            parentId = parentId,
            providerId = model["providerID"]?.jsonPrimitive?.contentOrNull,
            modelId = model["modelID"]?.jsonPrimitive?.contentOrNull,
            variant = model["variant"]?.jsonPrimitive?.contentOrNull,
            agent = event.agent,
        )))
        _sessionStatuses.update { it + (event.sessionId to SessionStatus.Busy) }
    }

    private fun handleNextStepEnded(event: SseEvent.NextStepEnded) {
        val existing = _messages.value[event.sessionId]
            ?.filterIsInstance<Message.Assistant>()
            ?.firstOrNull { it.id == event.assistantMessageId }
            ?: return
        val tokens = event.tokens.jsonObject
        val cache = tokens["cache"]?.jsonObject
        handleMessageUpdated(SseEvent.MessageUpdated(existing.copy(
            time = existing.time.copy(completed = event.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()),
            finish = event.finish,
            cost = event.cost,
            tokens = Message.Assistant.Tokens(
                input = tokens["input"]?.jsonPrimitive?.intOrNull ?: 0,
                output = tokens["output"]?.jsonPrimitive?.intOrNull ?: 0,
                reasoning = tokens["reasoning"]?.jsonPrimitive?.intOrNull ?: 0,
                cache = Message.Assistant.Tokens.Cache(
                    read = cache?.get("read")?.jsonPrimitive?.intOrNull ?: 0,
                    write = cache?.get("write")?.jsonPrimitive?.intOrNull ?: 0,
                ),
            ),
        )))
    }

    private fun updateMessage(sessionId: String, messageId: String, transform: (Message) -> Message) {
        _messages.update { current ->
            val messages = current[sessionId].orEmpty()
            if (messages.none { it.id == messageId }) current
            else current + (sessionId to messages.map { if (it.id == messageId) transform(it) else it })
        }
    }

    private fun handleNextStepFailed(event: SseEvent.NextStepFailed) {
        val error = event.error.jsonObject
        updateMessage(event.sessionId, event.assistantMessageId) { message ->
            val assistant = message as? Message.Assistant ?: return@updateMessage message
            assistant.copy(
                time = assistant.time.copy(completed = event.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()),
                error = Message.Assistant.ErrorInfo(
                    name = error["name"]?.jsonPrimitive?.contentOrNull ?: "Error",
                    data = error["data"] ?: event.error,
                ),
            )
        }
    }

    private fun handleNextShellStarted(event: SseEvent.NextShellStarted) {
        handleMessagePartUpdated(SseEvent.MessagePartUpdated(Part.Tool(
            id = event.callId,
            sessionId = event.sessionId,
            messageId = event.messageId,
            callId = event.callId,
            tool = "bash",
            state = ToolState.Running(
                input = mapOf("command" to JsonPrimitive(event.command)),
                time = ToolState.Running.Time(event.timestamp),
            ),
        )))
    }

    private fun handleNextShellEnded(event: SseEvent.NextShellEnded) {
        val existing = _parts.value.values.asSequence().flatten()
            .filterIsInstance<Part.Tool>()
            .firstOrNull { it.sessionId == event.sessionId && it.callId == event.callId }
            ?: return
        val running = existing.state as? ToolState.Running ?: return
        handleMessagePartUpdated(SseEvent.MessagePartUpdated(existing.copy(
            state = ToolState.Completed(
                input = running.input,
                output = event.output,
                time = ToolState.Completed.Time(running.time?.start ?: event.timestamp, event.timestamp),
            ),
        )))
    }

    private fun findToolPart(messageId: String, callId: String): Part.Tool? =
        _parts.value[messageId]?.filterIsInstance<Part.Tool>()?.firstOrNull { it.callId == callId }

    private fun handleNextToolInputDelta(event: SseEvent.NextToolInputDelta) {
        val existing = findToolPart(event.messageId, event.callId) ?: return
        val pending = existing.state as? ToolState.Pending ?: return
        handleMessagePartUpdated(SseEvent.MessagePartUpdated(existing.copy(
            state = pending.copy(raw = pending.raw.orEmpty() + event.delta),
        )))
    }

    private fun handleNextToolInputEnded(event: SseEvent.NextToolInputEnded) {
        val existing = findToolPart(event.messageId, event.callId) ?: return
        val input = runCatching { Json.parseToJsonElement(event.text).jsonObject }.getOrDefault(emptyMap())
        handleMessagePartUpdated(SseEvent.MessagePartUpdated(existing.copy(
            state = ToolState.Pending(input = input, raw = event.text),
        )))
    }

    private fun handleNextToolCalled(event: SseEvent.NextToolCalled) {
        val existing = findToolPart(event.messageId, event.callId)
        handleMessagePartUpdated(SseEvent.MessagePartUpdated(Part.Tool(
            id = existing?.id ?: event.callId,
            sessionId = event.sessionId,
            messageId = event.messageId,
            callId = event.callId,
            tool = event.tool,
            state = ToolState.Running(
                input = event.input.jsonObject,
                time = ToolState.Running.Time(event.timestamp),
            ),
        )))
    }

    private fun toolContentText(content: kotlinx.serialization.json.JsonElement): String =
        content.jsonArray.mapNotNull { item ->
            item.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                ?.get("text")?.jsonPrimitive?.contentOrNull
        }.joinToString("\n")

    private fun toolMetadata(structured: kotlinx.serialization.json.JsonElement, output: String): Map<String, kotlinx.serialization.json.JsonElement> =
        structured.jsonObject + if (output.isNotBlank()) mapOf("output" to JsonPrimitive(output)) else emptyMap()

    private fun handleNextToolProgress(event: SseEvent.NextToolProgress) {
        val existing = findToolPart(event.messageId, event.callId) ?: return
        val input = when (val state = existing.state) {
            is ToolState.Pending -> state.input
            is ToolState.Running -> state.input
            is ToolState.Completed -> state.input
            is ToolState.Error -> state.input
        }
        val output = toolContentText(event.content)
        val start = (existing.state as? ToolState.Running)?.time?.start ?: event.timestamp
        handleMessagePartUpdated(SseEvent.MessagePartUpdated(existing.copy(
            state = ToolState.Running(input, metadata = toolMetadata(event.structured, output), time = ToolState.Running.Time(start)),
        )))
    }

    private fun handleNextToolSuccess(event: SseEvent.NextToolSuccess) {
        val existing = findToolPart(event.messageId, event.callId) ?: return
        val running = existing.state as? ToolState.Running
        val input = running?.input ?: (existing.state as? ToolState.Pending)?.input.orEmpty()
        val output = toolContentText(event.content)
        val attachments = event.content.jsonArray.mapIndexedNotNull { index, item ->
            val file = item.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "file" } ?: return@mapIndexedNotNull null
            ToolState.Completed.Attachment(
                id = "${event.callId}-file-$index",
                sessionId = event.sessionId,
                messageId = event.messageId,
                mime = file["mime"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream",
                filename = file["name"]?.jsonPrimitive?.contentOrNull,
                url = file["uri"]?.jsonPrimitive?.contentOrNull,
            )
        }
        handleMessagePartUpdated(SseEvent.MessagePartUpdated(existing.copy(
            state = ToolState.Completed(
                input = input,
                output = output,
                metadata = event.structured.jsonObject,
                time = ToolState.Completed.Time(running?.time?.start ?: event.timestamp, event.timestamp),
                attachments = attachments,
            ),
        )))
    }

    private fun handleNextToolFailed(event: SseEvent.NextToolFailed) {
        val existing = findToolPart(event.messageId, event.callId) ?: return
        val running = existing.state as? ToolState.Running
        val input = running?.input ?: (existing.state as? ToolState.Pending)?.input.orEmpty()
        val errorObject = event.error.jsonObject
        val error = errorObject["message"]?.jsonPrimitive?.contentOrNull
            ?: errorObject["data"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?: event.error.toString()
        handleMessagePartUpdated(SseEvent.MessagePartUpdated(existing.copy(
            state = ToolState.Error(
                input = input,
                error = error,
                time = ToolState.Error.Time(running?.time?.start ?: event.timestamp, event.timestamp),
            ),
        )))
    }
    
    private fun handleSessionUpdated(event: SseEvent.SessionUpdated, serverId: String) {
        trackSession(serverId, event.info.id)
        _sessions.update { current ->
            val existingIndex = current.indexOfFirst { it.id == event.info.id }
            val updated = if (existingIndex >= 0) {
                // Update existing
                current.toMutableList().apply { set(existingIndex, event.info) }
            } else {
                // Upsert: session wasn't in list (no session.created received), add it
                if (BuildConfig.DEBUG) Log.d(TAG, "Session ${event.info.id} not found, upserting (title=${event.info.title})")
                current + event.info
            }
            updated.sortedByDescending { it.time.updated }
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
        removePendingForSessions(setOf(sessionId))
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
    
    private fun handlePermissionAsked(event: SseEvent.PermissionAsked, serverId: String) {
        trackSession(serverId, event.sessionId)
        upsertPending(PendingInteraction.Permission(event))
    }
    
    private fun handlePermissionReplied(event: SseEvent.PermissionReplied) {
        synchronized(pendingLock) {
            removePending(PendingInteraction.Permission::class.java, event.sessionId, event.requestId)
            pendingRevision++
        }
    }
    
    // ============ Question Events ============
    
    private fun handleQuestionAsked(event: SseEvent.QuestionAsked, serverId: String) {
        trackSession(serverId, event.sessionId)
        upsertPending(PendingInteraction.Question(event))
    }
    
    private fun handleQuestionReplied(event: SseEvent.QuestionReplied) {
        synchronized(pendingLock) {
            removePending(PendingInteraction.Question::class.java, event.sessionId, event.requestId)
            pendingRevision++
        }
    }
    
    private fun handleQuestionRejected(event: SseEvent.QuestionRejected) {
        synchronized(pendingLock) {
            removePending(PendingInteraction.Question::class.java, event.sessionId, event.requestId)
            pendingRevision++
        }
    }

    /**
     * Optimistically remove a question from the pending list.
     * Called after a successful API reply/reject, in case the SSE event doesn't arrive.
     */
    fun removeQuestion(sessionId: String, questionId: String) {
        synchronized(pendingLock) {
            removePending(PendingInteraction.Question::class.java, sessionId, questionId)
            pendingRevision++
        }
    }

    fun removePermission(sessionId: String, permissionId: String) {
        synchronized(pendingLock) {
            removePending(PendingInteraction.Permission::class.java, sessionId, permissionId)
            pendingRevision++
        }
    }

    private fun upsertPending(interaction: PendingInteraction) {
        synchronized(pendingLock) {
            _pendingInteractions.update { current ->
                val index = current.indexOfFirst { it.sameIdentity(interaction) }
                if (index >= 0) current.toMutableList().apply { set(index, interaction) } else current + interaction
            }
            pendingRevision++
        }
    }

    private fun removePending(type: Class<out PendingInteraction>, sessionId: String, requestId: String) {
        _pendingInteractions.update { current ->
            current.filterNot { type.isInstance(it) && it.sessionId == sessionId && it.id == requestId }
        }
    }

    private fun removePendingForSessions(sessionIds: Set<String>) {
        synchronized(pendingLock) {
            _pendingInteractions.update { current -> current.filterNot { it.sessionId in sessionIds } }
            pendingRevision++
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
        val sessionIds = _serverSessions.value[serverId].orEmpty() +
            permissions.map { it.sessionId } + questions.map { it.sessionId }
        sessionIds.forEach { trackSession(serverId, it) }
        replacePendingForSessionsLocked(sessionIds, permissions, questions)
        pendingRevision++
        true
    }

    fun replacePendingRequestsForSessions(
        sessionIds: Set<String>,
        permissions: List<SseEvent.PermissionAsked>,
        questions: List<SseEvent.QuestionAsked>,
        expectedRevision: Long,
    ): Boolean = synchronized(pendingLock) {
        if (pendingRevision != expectedRevision) return@synchronized false
        replacePendingForSessionsLocked(sessionIds, permissions, questions)
        pendingRevision++
        true
    }

    private fun replacePendingForSessionsLocked(
        sessionIds: Set<String>,
        permissions: List<SseEvent.PermissionAsked>,
        questions: List<SseEvent.QuestionAsked>,
    ) {
        val snapshot = (permissions.map { PendingInteraction.Permission(it) } +
            questions.map { PendingInteraction.Question(it) })
            .filter { it.sessionId in sessionIds }
            .associateBy { it.identityKey() }
        val retained = _pendingInteractions.value.mapNotNull { current ->
            if (current.sessionId !in sessionIds) current else snapshot[current.identityKey()]
        }
        val retainedKeys = retained.asSequence().map { it.identityKey() }.toSet()
        val additions = snapshot.values
            .filterNot { it.identityKey() in retainedKeys }
            .sortedWith(compareBy<PendingInteraction>({ it.sessionId }, { it.typeRank() }, { it.id }))
        _pendingInteractions.value = retained + additions
    }

    private fun PendingInteraction.sameIdentity(other: PendingInteraction): Boolean =
        identityKey() == other.identityKey()

    private fun PendingInteraction.identityKey(): Triple<String, Int, String> =
        Triple(sessionId, typeRank(), id)

    private fun PendingInteraction.typeRank(): Int = when (this) {
        is PendingInteraction.Permission -> 0
        is PendingInteraction.Question -> 1
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
        val compactedSessions = sessions.map(::compactSessionForCache)
        val sessionIds = compactedSessions.map { it.id }.toSet()
        _serverSessions.update { current ->
            val existing = current[serverId] ?: emptySet()
            current + (serverId to (existing + sessionIds))
        }
        _sessions.update { current ->
            // Merge: replace existing sessions by ID, add new ones
            val updated = current.toMutableList()
            for (session in compactedSessions) {
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
                mergeLoadedParts(current[messageId].orEmpty(), loadedParts)
            }
        }
    }

    /** Remove only locally cached history so the session can be fetched again without affecting server state. */
    fun clearSessionHistory(sessionId: String) {
        val messageIds = _messages.value[sessionId].orEmpty().mapTo(mutableSetOf()) { it.id }
        _messages.update { it - sessionId }
        if (messageIds.isNotEmpty()) {
            _parts.update { current -> current - messageIds }
        }
    }

    private fun mergeLoadedParts(current: List<Part>, loaded: List<Part>): List<Part> {
        val currentById = current.associateBy { it.id }
        val merged = loaded.map { loadedPart ->
            when (val currentPart = currentById[loadedPart.id]) {
                is Part.Text -> if (loadedPart is Part.Text && currentPart.text.length > loadedPart.text.length) currentPart else loadedPart
                is Part.Reasoning -> if (loadedPart is Part.Reasoning && currentPart.text.length > loadedPart.text.length) currentPart else loadedPart
                else -> loadedPart
            }
        }
        val loadedIds = loaded.asSequence().map { it.id }.toSet()
        return merged + current.filterNot { it.id in loadedIds }
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
        synchronized(pendingLock) {
            _pendingInteractions.value = emptyList()
            pendingRevision++
        }
        synchronized(deltaLock) { pendingDeltas.clear() }
        _todos.value = emptyMap()
        _vcsBranches.value = emptyMap()
        _projectInfo.value = emptyMap()
        _promptDeliveries.value = emptyMap()
        _workspaceStatuses.value = emptyMap()
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
        removePendingForSessions(sessionIds)
        _todos.update { it - sessionIds }
        _promptDeliveries.update { current -> current.filterValues { it.sessionId !in sessionIds } }
        _vcsBranches.update { current -> current.filterKeys { it.serverId != serverId } }
        _projectInfo.update { current -> current.filterKeys { it.serverId != serverId } }
        _workspaceStatuses.update { current -> current.filterKeys { it.serverId != serverId } }
        
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
    }

    /** Clear connection-scoped state without erasing persisted session history. */
    fun clearTransientForServer(serverId: String) {
        val sessionIds = _serverSessions.value[serverId].orEmpty()
        _pendingInteractions.update { interactions -> interactions.filterNot { it.sessionId in sessionIds } }
        _sessionStatuses.update { statuses ->
            statuses.mapValues { (sessionId, status) ->
                if (sessionId in sessionIds && status !is SessionStatus.Idle) SessionStatus.Idle else status
            }
        }
        _vcsBranches.update { current -> current.filterKeys { it.serverId != serverId } }
        _projectInfo.update { current -> current.filterKeys { it.serverId != serverId } }
        _workspaceStatuses.update { current -> current.filterKeys { it.serverId != serverId } }
    }
    
    // ============ Todo Events ============
    
    private fun handleTodoUpdated(event: SseEvent.TodoUpdated) {
        _todos.update { it + (event.sessionId to event.todos) }
    }
    
    // ============ VCS Events ============
    
    private fun handleVcsBranchUpdated(event: SseEvent.VcsBranchUpdated, serverId: String, directory: String?, workspaceId: String?) {
        val scope = DirectoryScope(serverId, directory ?: return, workspaceId)
        _vcsBranches.update { it + (scope to event.branch) }
    }
    
    // ============ Project Events ============
    
    private fun handleProjectUpdated(event: SseEvent.ProjectUpdated, serverId: String, directory: String?, workspaceId: String?) {
        val scope = DirectoryScope(serverId, directory ?: return, workspaceId)
        _projectInfo.update { it + (scope to event.info) }
    }
}
