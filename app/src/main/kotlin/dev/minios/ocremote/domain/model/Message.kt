package dev.minios.ocremote.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class TimeInfo(
    val created: Long,
    val completed: Long? = null
)

/**
 * Message - user or assistant message in a session
 */
@Serializable
sealed class Message {
    abstract val id: String
    abstract val sessionId: String
    abstract val role: String
    abstract val time: TimeInfo
    
    @Serializable
    data class User(
        override val id: String,
        override val sessionId: String,
        override val role: String = "user",
        override val time: TimeInfo,
        val agent: String? = null,
        val model: Model? = null,
        val format: OutputFormat? = null,
        val summary: String? = null,
        val system: String? = null,
        val tools: List<String>? = null,
        val variant: String? = null
    ) : Message() {
        @Serializable
        data class Model(
            val providerId: String,
            val modelId: String
        )
        
        @Serializable
        data class OutputFormat(
            val type: String,
            val schema: String? = null, // JSON string instead of Map
            val retryCount: Int? = null
        )
    }
    
    @Serializable
    data class Assistant(
        override val id: String,
        override val sessionId: String,
        override val role: String = "assistant",
        override val time: TimeInfo,
        val parentId: String,
        val modelId: String? = null,
        val providerId: String? = null,
        val agent: String? = null,
        val mode: String? = null,
        val path: String? = null,
        val cost: Double? = null,
        val tokens: Tokens? = null,
        val finish: String? = null, // stop, end-turn, tool-calls, unknown, length
        val error: ErrorInfo? = null,
        val structured: String? = null, // JSON string instead of Map
        val variant: String? = null,
        val summary: String? = null
    ) : Message() {
        @Serializable
        data class Tokens(
            val input: Int,
            val output: Int
        )
        
        @Serializable
        data class ErrorInfo(
            val name: String,
            val message: String,
            val retries: Int? = null
        )
    }
}

@Serializable
data class MessageWithParts(
    val info: Message,
    val parts: List<Part>
)
