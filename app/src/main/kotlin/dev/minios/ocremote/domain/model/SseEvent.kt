package dev.minios.ocremote.domain.model

import kotlinx.serialization.Serializable

/**
 * SSE Event - events from Server-Sent Events stream
 * All events that the client receives from GET /global/event or GET /event
 */
@Serializable
sealed class SseEvent {
    // Server events
    @Serializable
    data object ServerConnected : SseEvent()
    
    @Serializable
    data object ServerHeartbeat : SseEvent()
    
    @Serializable
    data class ServerInstanceDisposed(val directory: String) : SseEvent()
    
    // Session lifecycle
    @Serializable
    data class SessionCreated(val info: Session) : SseEvent()
    
    @Serializable
    data class SessionUpdated(val info: Session) : SseEvent()
    
    @Serializable
    data class SessionDeleted(val info: Session) : SseEvent()
    
    @Serializable
    data class SessionDiff(
        val sessionId: String,
        val diff: List<FileDiff>
    ) : SseEvent()
    
    @Serializable
    data class SessionStatus(
        val sessionId: String,
        val status: dev.minios.ocremote.domain.model.SessionStatus
    ) : SseEvent()
    
    @Serializable
    data class SessionIdle(val sessionId: String) : SseEvent()
    
    @Serializable
    data class SessionError(
        val sessionId: String?,
        val error: String
    ) : SseEvent()
    
    // Message events
    @Serializable
    data class MessageUpdated(val info: Message) : SseEvent()
    
    @Serializable
    data class MessageRemoved(
        val sessionId: String,
        val messageId: String
    ) : SseEvent()
    
    // Part events - the streaming content
    @Serializable
    data class MessagePartUpdated(val part: Part) : SseEvent()
    
    @Serializable
    data class MessagePartDelta(
        val sessionId: String,
        val messageId: String,
        val partId: String,
        val field: String,  // Usually "text"
        val delta: String   // The new chunk to append
    ) : SseEvent()
    
    @Serializable
    data class MessagePartRemoved(
        val sessionId: String,
        val messageId: String,
        val partId: String
    ) : SseEvent()
    
    // Permission events
    @Serializable
    data class PermissionAsked(
        val id: String,
        val sessionId: String,
        val tool: String,
        val permission: String,
        val metadata: Map<String, String>? = null,
        val patterns: List<String>? = null,
        val always: Boolean = false
    ) : SseEvent()
    
    @Serializable
    data class PermissionReplied(
        val sessionId: String,
        val requestId: String
    ) : SseEvent()
    
    // Question events
    @Serializable
    data class QuestionAsked(
        val id: String,
        val sessionId: String,
        val questions: List<Question>
    ) : SseEvent() {
        @Serializable
        data class Question(
            val header: String,
            val question: String,
            val multiple: Boolean = false,
            val options: List<Option>
        )
        
        @Serializable
        data class Option(
            val label: String,
            val description: String
        )
    }
    
    @Serializable
    data class QuestionReplied(
        val sessionId: String,
        val requestId: String
    ) : SseEvent()
    
    @Serializable
    data class QuestionRejected(
        val sessionId: String,
        val requestId: String
    ) : SseEvent()
    
    // Todo events
    @Serializable
    data class TodoUpdated(
        val sessionId: String,
        val todos: List<Todo>
    ) : SseEvent() {
        @Serializable
        data class Todo(
            val content: String,
            val status: String, // pending, in_progress, completed, cancelled
            val priority: String // high, medium, low
        )
    }
    
    // VCS events
    @Serializable
    data class VcsBranchUpdated(val branch: String) : SseEvent()
    
    // LSP events
    @Serializable
    data object LspUpdated : SseEvent()
    
    // Project events
    @Serializable
    data class ProjectUpdated(val info: Project) : SseEvent()
}

/**
 * File Diff - represents changes to a file
 */
@Serializable
data class FileDiff(
    val path: String,
    val type: String, // add, update, delete, move
    val diff: String? = null,
    val oldPath: String? = null
)

/**
 * Project - represents an OpenCode project
 */
@Serializable
data class Project(
    val name: String,
    val path: String,
    val vcs: String? = null
)
