package dev.minios.ocremote.domain.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class TimeInfo(
    val created: Long,
    val completed: Long? = null
)

/**
 * Custom serializer for Message that dispatches on the "role" field.
 */
object MessageSerializer : JsonContentPolymorphicSerializer<Message>(Message::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Message> {
        return when (element.jsonObject["role"]?.jsonPrimitive?.content) {
            "user" -> Message.User.serializer()
            "assistant" -> Message.Assistant.serializer()
            else -> Message.User.serializer() // fallback
        }
    }
}

/**
 * Message - user or assistant message in a session.
 * Field names use @SerialName to match the OpenCode API convention (uppercase ID suffixes).
 */
@Serializable(with = MessageSerializer::class)
sealed class Message {
    abstract val id: String
    abstract val sessionId: String
    abstract val role: String
    abstract val time: TimeInfo

    @Serializable
    data class User(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        override val role: String = "user",
        override val time: TimeInfo,
        val agent: String? = null,
        val model: Model? = null,
        val format: OutputFormat? = null,
        val summary: UserSummary? = null,
        val system: String? = null,
        val tools: Map<String, Boolean>? = null,
        val variant: String? = null
    ) : Message() {
        @Serializable
        data class Model(
            @SerialName("providerID") val providerId: String,
            @SerialName("modelID") val modelId: String
        )

        @Serializable
        data class OutputFormat(
            val type: String,
            val schema: JsonElement? = null,
            val retryCount: Int? = null
        )

        @Serializable
        data class UserSummary(
            val title: String? = null,
            val body: String? = null,
            val diffs: List<FileDiff>? = null
        )
    }

    @Serializable
    data class Assistant(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        override val role: String = "assistant",
        override val time: TimeInfo,
        @SerialName("parentID") val parentId: String,
        @SerialName("modelID") val modelId: String? = null,
        @SerialName("providerID") val providerId: String? = null,
        val agent: String? = null,
        val mode: String? = null,
        val path: PathInfo? = null,
        val cost: Double? = null,
        val tokens: Tokens? = null,
        val finish: String? = null,
        val error: ErrorInfo? = null,
        val structured: JsonElement? = null,
        val variant: String? = null,
        val summary: Boolean? = null
    ) : Message() {
        @Serializable
        data class PathInfo(
            val cwd: String,
            val root: String
        )

        @Serializable
        data class Tokens(
            val input: Int = 0,
            val output: Int = 0,
            val total: Int? = null,
            val reasoning: Int = 0,
            val cache: Cache = Cache()
        ) {
            @Serializable
            data class Cache(
                val read: Int = 0,
                val write: Int = 0
            )
        }

        @Serializable
        data class ErrorInfo(
            val name: String = "",
            val data: JsonElement? = null
        ) {
            val message: String
                get() = data?.jsonObject?.get("message")?.jsonPrimitive?.content ?: name
        }
    }
}

@Serializable
data class MessageWithParts(
    val info: Message,
    val parts: List<Part>
)
