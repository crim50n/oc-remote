package dev.minios.ocremote.domain.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Custom serializer for ToolState that dispatches on the "status" field.
 * The API uses "status" (not "type") as discriminator.
 */
object ToolStateSerializer : JsonContentPolymorphicSerializer<ToolState>(ToolState::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ToolState> {
        return when (element.jsonObject["status"]?.jsonPrimitive?.content) {
            "pending" -> ToolState.Pending.serializer()
            "running" -> ToolState.Running.serializer()
            "completed" -> ToolState.Completed.serializer()
            "error" -> ToolState.Error.serializer()
            else -> ToolState.Pending.serializer() // fallback
        }
    }
}

/**
 * Tool State - lifecycle of a tool call.
 * Discriminated by "status" field in the API JSON.
 */
@Serializable(with = ToolStateSerializer::class)
sealed class ToolState {
    @Serializable
    data class Pending(
        val input: Map<String, JsonElement> = emptyMap(),
        val raw: String? = null
    ) : ToolState()

    @Serializable
    data class Running(
        val input: Map<String, JsonElement> = emptyMap(),
        val title: String? = null,
        val metadata: Map<String, JsonElement>? = null,
        val time: Time? = null
    ) : ToolState() {
        @Serializable
        data class Time(val start: Long)
    }

    @Serializable
    data class Completed(
        val input: Map<String, JsonElement> = emptyMap(),
        val output: String = "",
        val title: String? = null,
        val metadata: Map<String, JsonElement>? = null,
        val time: Time? = null,
        val attachments: List<Attachment>? = null
    ) : ToolState() {
        @Serializable
        data class Time(val start: Long, val end: Long, val compacted: Long? = null)

        @Serializable
        data class Attachment(
            val type: String,
            val data: String? = null
        )
    }

    @Serializable
    data class Error(
        val input: Map<String, JsonElement> = emptyMap(),
        val error: String = "",
        val metadata: Map<String, JsonElement>? = null,
        val time: Time? = null
    ) : ToolState() {
        @Serializable
        data class Time(val start: Long, val end: Long)
    }
}
