package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part

internal fun suppressRepeatedPatchCards(messages: List<ChatMessage>): List<ChatMessage> {
    var lastVisiblePatchHash: String? = null

    return messages.map { chatMessage ->
        val assistantMessage = chatMessage.message as? Message.Assistant
        if (assistantMessage == null) {
            chatMessage
        } else {
            val filteredParts = buildList {
                for (part in chatMessage.parts) {
                    if (part is Part.Patch) {
                        val normalizedHash = part.hash.trim()
                        val isRepeatedPatch = normalizedHash.isNotEmpty() && normalizedHash == lastVisiblePatchHash
                        if (!isRepeatedPatch) {
                            add(part)
                            if (normalizedHash.isNotEmpty()) {
                                lastVisiblePatchHash = normalizedHash
                            }
                        }
                    } else {
                        add(part)
                    }
                }
            }
            chatMessage.copy(parts = filteredParts)
        }
    }
}
