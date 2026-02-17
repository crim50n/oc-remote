package dev.minios.ocremote.domain.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Custom serializer for Part that dispatches on the "type" field.
 */
object PartSerializer : JsonContentPolymorphicSerializer<Part>(Part::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<Part> {
        return when (element.jsonObject["type"]?.jsonPrimitive?.content) {
            "text" -> Part.Text.serializer()
            "reasoning" -> Part.Reasoning.serializer()
            "tool" -> Part.Tool.serializer()
            "step-start" -> Part.StepStart.serializer()
            "step-finish" -> Part.StepFinish.serializer()
            "file" -> Part.File.serializer()
            "snapshot" -> Part.Snapshot.serializer()
            "patch" -> Part.Patch.serializer()
            "subtask" -> Part.Subtask.serializer()
            "compaction" -> Part.Compaction.serializer()
            "retry" -> Part.Retry.serializer()
            "agent" -> Part.Agent.serializer()
            else -> Part.Unknown.serializer()
        }
    }
}

/**
 * Message Part - different types of content in a message.
 * Field names use @SerialName to match the OpenCode API convention (uppercase ID suffixes).
 */
@Serializable(with = PartSerializer::class)
sealed class Part {
    abstract val id: String
    abstract val sessionId: String
    abstract val messageId: String

    @Serializable
    data class Text(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val text: String = "",
        val synthetic: Boolean? = null,
        val ignored: Boolean? = null,
        val time: Time? = null,
        val metadata: Map<String, JsonElement>? = null
    ) : Part() {
        @Serializable
        data class Time(val start: Long, val end: Long? = null)
    }

    @Serializable
    data class Reasoning(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val text: String = "",
        val time: Time? = null,
        val metadata: Map<String, JsonElement>? = null
    ) : Part() {
        @Serializable
        data class Time(val start: Long, val end: Long? = null)
    }

    @Serializable
    data class Tool(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        @SerialName("callID") val callId: String,
        val tool: String,
        val state: ToolState,
        val metadata: Map<String, JsonElement>? = null
    ) : Part()

    @Serializable
    data class StepStart(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val snapshot: String? = null
    ) : Part()

    @Serializable
    data class StepFinish(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val reason: String = "",
        val snapshot: String? = null,
        val cost: Double? = null,
        val tokens: Tokens? = null
    ) : Part() {
        @Serializable
        data class Tokens(
            val input: Int = 0,
            val output: Int = 0,
            val total: Int? = null,
            val reasoning: Int = 0,
            val cache: Cache? = null
        )

        @Serializable
        data class Cache(
            val read: Int = 0,
            val write: Int = 0
        )
    }

    @Serializable
    data class File(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val mime: String,
        val filename: String? = null,
        val url: String? = null,
        val source: JsonElement? = null
    ) : Part()

    @Serializable
    data class Snapshot(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val snapshot: String = ""
    ) : Part()

    @Serializable
    data class Patch(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val hash: String = "",
        val files: List<String> = emptyList()
    ) : Part()

    @Serializable
    data class Subtask(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val prompt: String = "",
        val description: String? = null,
        val agent: String? = null,
        val model: Model? = null,
        val command: String? = null
    ) : Part() {
        @Serializable
        data class Model(
            @SerialName("providerID") val providerId: String,
            @SerialName("modelID") val modelId: String
        )
    }

    @Serializable
    data class Compaction(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val auto: Boolean = false
    ) : Part()

    @Serializable
    data class Retry(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val attempt: Int = 0,
        val error: JsonElement? = null,
        val time: Time? = null
    ) : Part() {
        @Serializable
        data class Time(val created: Long)

        val errorMessage: String
            get() = error?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
    }

    @Serializable
    data class Agent(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val name: String = "",
        val source: JsonElement? = null
    ) : Part()

    @Serializable
    data class Permission(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val message: String = ""
    ) : Part()

    @Serializable
    data class Question(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val question: String = ""
    ) : Part()

    @Serializable
    data class Abort(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String,
        val reason: String = ""
    ) : Part()

    @Serializable
    data class SessionTurn(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String
    ) : Part()

    @Serializable
    data class Unknown(
        override val id: String,
        @SerialName("sessionID") override val sessionId: String,
        @SerialName("messageID") override val messageId: String
    ) : Part()
}
