package dev.minios.ocremote.domain.model

import kotlinx.serialization.Serializable

/**
 * Message Part - different types of content in a message
 */
@Serializable
sealed class Part {
    abstract val id: String
    abstract val sessionId: String
    abstract val messageId: String
    
    @Serializable
    data class Text(
        override val id: String,
        override val sessionId: String,
        override val messageId: String,
        val text: String,
        val synthetic: Boolean? = null,
        val ignored: Boolean? = null,
        val time: Time? = null,
        val metadata: Map<String, String>? = null
    ) : Part() {
        @Serializable
        data class Time(val created: Long)
    }
    
    @Serializable
    data class Reasoning(
        override val id: String,
        override val sessionId: String,
        override val messageId: String,
        val text: String,
        val time: Time? = null,
        val metadata: Map<String, String>? = null
    ) : Part() {
        @Serializable
        data class Time(val start: Long, val end: Long? = null)
    }
    
    @Serializable
    data class Tool(
        override val id: String,
        override val sessionId: String,
        override val messageId: String,
        val callId: String,
        val tool: String, // Tool name (bash, read, edit, etc.)
        val state: ToolState
    ) : Part()
    
    @Serializable
    data class StepStart(
        override val id: String,
        override val sessionId: String,
        override val messageId: String,
        val snapshot: String? = null
    ) : Part()
    
    @Serializable
    data class StepFinish(
        override val id: String,
        override val sessionId: String,
        override val messageId: String,
        val reason: String,
        val snapshot: String? = null,
        val cost: Double? = null,
        val tokens: Tokens? = null
    ) : Part() {
        @Serializable
        data class Tokens(
            val input: Int,
            val output: Int
        )
    }
    
    @Serializable
    data class File(
        override val id: String,
        override val sessionId: String,
        override val messageId: String,
        val mime: String,
        val filename: String? = null,
        val url: String? = null,
        val source: String? = null
    ) : Part()
    
    @Serializable
    data class Snapshot(
        override val id: String,
        override val sessionId: String,
        override val messageId: String,
        val snapshot: String
    ) : Part()
    
    @Serializable
    data class Patch(
        override val id: String,
        override val sessionId: String,
        override val messageId: String,
        val hash: String,
        val files: List<FilePatch>
    ) : Part() {
        @Serializable
        data class FilePatch(
            val path: String,
            val type: String, // add, update, delete, move
            val diff: String? = null
        )
    }
    
    @Serializable
    data class Subtask(
        override val id: String,
        override val sessionId: String,
        override val messageId: String,
        val prompt: String,
        val description: String? = null,
        val agent: String? = null,
        val model: Model? = null,
        val command: String? = null
    ) : Part() {
        @Serializable
        data class Model(
            val providerId: String,
            val modelId: String
        )
    }
    
    @Serializable
    data class Compaction(
        override val id: String,
        override val sessionId: String,
        override val messageId: String,
        val auto: Boolean
    ) : Part()
    
    @Serializable
    data class Retry(
        override val id: String,
        override val sessionId: String,
        override val messageId: String,
        val attempt: Int,
        val error: String,
        val time: Time
    ) : Part() {
        @Serializable
        data class Time(val created: Long)
    }
    
    @Serializable
    data class Agent(
        override val id: String,
        override val sessionId: String,
        override val messageId: String,
        val name: String,
        val source: String? = null
    ) : Part()
    
    @Serializable
    data class Permission(
        override val id: String,
        override val sessionId: String,
        override val messageId: String,
        val message: String
    ) : Part()
    
    @Serializable
    data class Question(
        override val id: String,
        override val sessionId: String,
        override val messageId: String,
        val question: String
    ) : Part()
    
    @Serializable
    data class Abort(
        override val id: String,
        override val sessionId: String,
        override val messageId: String,
        val reason: String
    ) : Part()
    
    @Serializable
    data class SessionTurn(
        override val id: String,
        override val sessionId: String,
        override val messageId: String
    ) : Part()
    
    @Serializable
    data class Unknown(
        override val id: String,
        override val sessionId: String,
        override val messageId: String
    ) : Part()
}
