package dev.minios.ocremote.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * SSE Event - events from Server-Sent Events stream.
 * All events that the client receives from GET /global/event or GET /event.
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
        val permission: String,
        val patterns: List<String> = emptyList(),
        val metadata: Map<String, JsonElement>? = null,
        val always: List<String> = emptyList(),
        val tool: ToolRef? = null
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
        val questions: List<Question>,
        val tool: ToolRef? = null
    ) : SseEvent() {
        @Serializable
        data class Question(
            val header: String,
            val question: String,
            val multiple: Boolean = false,
            val custom: Boolean = true,
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
            val status: String,
            val priority: String
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
 * Reference to a tool call (used in permission/question events).
 */
@Serializable
data class ToolRef(
    @SerialName("messageID") val messageId: String,
    @SerialName("callID") val callId: String
)

/**
 * File Diff - represents changes to a file.
 * Matches Snapshot.FileDiff from the server.
 */
@Serializable
data class FileDiff(
    val file: String,
    val before: String = "",
    val after: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String? = null // "added", "deleted", "modified"
)

/**
 * Project - represents an OpenCode project.
 * Server returns: id, worktree, vcs, name, icon, commands, time, sandboxes
 */
@Serializable
data class Project(
    val id: String = "",
    val worktree: String = "",
    val name: String? = null,
    val path: String = "", // legacy, may be absent
    val vcs: String? = null,
    val directory: String? = null
) {
    /** Display name: explicit name, or last path segment of worktree, or id */
    val displayName: String
        get() = name?.takeIf { it.isNotEmpty() }
            ?: worktree.takeIf { it.isNotEmpty() }?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
            ?: path.takeIf { it.isNotEmpty() }?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
            ?: id.take(8)
}


