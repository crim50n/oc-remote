package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.data.api.PromptPart
import dev.minios.ocremote.data.api.V2InboxItem
import dev.minios.ocremote.data.repository.PendingPromptRecord
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun V2InboxItem.toPendingPromptRecord(): PendingPromptRecord? {
    if (type != "user") return null

    // PromptPart cannot preserve skill semantics; never restore only part of such a prompt.
    val skills = payload["skills"]
    if (skills != null && skills != JsonNull && skills != JsonArray(emptyList())) return null

    fun JsonElement.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    val parts = mutableListOf<PromptPart>()
    val text = payload["text"]
    if (text != null && text != JsonNull) {
        val value = text.stringOrNull() ?: return null
        if (value.isNotBlank()) parts += PromptPart(type = "text", text = value)
    }
    val files = payload["files"]
    if (files != null && files != JsonNull) {
        if (files !is JsonArray) return null
        for (element in files) {
            val file = element as? JsonObject ?: return null
            val uri = file["uri"]?.stringOrNull()?.takeIf { it.isNotBlank() } ?: return null
            val name = file["name"]
            val filename = if (name == null || name == JsonNull) null else name.stringOrNull() ?: return null
            val mime = file["mime"]
            val mimeType = if (mime == null || mime == JsonNull) {
                "application/octet-stream"
            } else {
                mime.stringOrNull()?.takeIf { it.isNotBlank() } ?: return null
            }
            parts += PromptPart(type = "file", url = uri, filename = filename, mime = mimeType)
        }
    }
    val agents = payload["agents"]
    if (agents != null && agents != JsonNull) {
        if (agents !is JsonArray) return null
        for (element in agents) {
            val agent = element as? JsonObject ?: return null
            val name = agent["name"]?.stringOrNull()?.takeIf { it.isNotBlank() } ?: return null
            parts += PromptPart(type = "agent", text = name)
        }
    }
    if (parts.isEmpty()) return null

    return PendingPromptRecord(
        messageId = id,
        sessionId = sessionId,
        parts = parts,
        createdAt = time.created,
    )
}
