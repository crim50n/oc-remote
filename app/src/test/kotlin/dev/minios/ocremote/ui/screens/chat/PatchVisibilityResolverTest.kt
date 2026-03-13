package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchVisibilityResolverTest {

    @Test
    fun suppressRepeatedPatchCards_hidesRepeatedNonBlankHashAcrossAssistantMessages() {
        val firstPatch = patchPart(messageId = "assistant-1", partId = "patch-1", hash = "same-hash")
        val secondPatch = patchPart(messageId = "assistant-2", partId = "patch-2", hash = "same-hash")

        val messages = listOf(
            assistantMessage(id = "assistant-1", parts = listOf(firstPatch)),
            assistantMessage(id = "assistant-2", parts = listOf(secondPatch)),
        )

        val visible = suppressRepeatedPatchCards(messages)

        assertEquals(listOf(firstPatch), visible[0].parts.filterIsInstance<Part.Patch>())
        assertTrue(visible[1].parts.filterIsInstance<Part.Patch>().isEmpty())
    }

    @Test
    fun suppressRepeatedPatchCards_keepsPatchWhenHashChanges() {
        val firstPatch = patchPart(messageId = "assistant-1", partId = "patch-1", hash = "hash-1")
        val secondPatch = patchPart(messageId = "assistant-2", partId = "patch-2", hash = "hash-2")

        val messages = listOf(
            assistantMessage(id = "assistant-1", parts = listOf(firstPatch)),
            assistantMessage(id = "assistant-2", parts = listOf(secondPatch)),
        )

        val visible = suppressRepeatedPatchCards(messages)

        assertEquals(listOf(firstPatch), visible[0].parts.filterIsInstance<Part.Patch>())
        assertEquals(listOf(secondPatch), visible[1].parts.filterIsInstance<Part.Patch>())
    }

    @Test
    fun suppressRepeatedPatchCards_doesNotResetForNonPatchAssistantParts() {
        val firstPatch = patchPart(messageId = "assistant-1", partId = "patch-1", hash = "same-hash")
        val textOnly = textPart(messageId = "assistant-2", partId = "text-1", text = "daily report")
        val repeatedPatch = patchPart(messageId = "assistant-3", partId = "patch-2", hash = "same-hash")

        val messages = listOf(
            assistantMessage(id = "assistant-1", parts = listOf(firstPatch)),
            assistantMessage(id = "assistant-2", parts = listOf(textOnly)),
            assistantMessage(id = "assistant-3", parts = listOf(repeatedPatch)),
        )

        val visible = suppressRepeatedPatchCards(messages)

        assertEquals(listOf(firstPatch), visible[0].parts.filterIsInstance<Part.Patch>())
        assertEquals(listOf(textOnly), visible[1].parts.filterIsInstance<Part.Text>())
        assertTrue(visible[2].parts.filterIsInstance<Part.Patch>().isEmpty())
    }

    @Test
    fun suppressRepeatedPatchCards_keepsRepeatedHashAfterDifferentHash() {
        val firstPatch = patchPart(messageId = "assistant-1", partId = "patch-1", hash = "hash-a")
        val secondPatch = patchPart(messageId = "assistant-2", partId = "patch-2", hash = "hash-b")
        val thirdPatch = patchPart(messageId = "assistant-3", partId = "patch-3", hash = "hash-a")

        val messages = listOf(
            assistantMessage(id = "assistant-1", parts = listOf(firstPatch)),
            assistantMessage(id = "assistant-2", parts = listOf(secondPatch)),
            assistantMessage(id = "assistant-3", parts = listOf(thirdPatch)),
        )

        val visible = suppressRepeatedPatchCards(messages)

        assertEquals(listOf(firstPatch), visible[0].parts.filterIsInstance<Part.Patch>())
        assertEquals(listOf(secondPatch), visible[1].parts.filterIsInstance<Part.Patch>())
        assertEquals(listOf(thirdPatch), visible[2].parts.filterIsInstance<Part.Patch>())
    }

    @Test
    fun suppressRepeatedPatchCards_keepsBlankHashPatchVisible() {
        val firstPatch = patchPart(messageId = "assistant-1", partId = "patch-1", hash = "")
        val secondPatch = patchPart(messageId = "assistant-2", partId = "patch-2", hash = "")

        val messages = listOf(
            assistantMessage(id = "assistant-1", parts = listOf(firstPatch)),
            assistantMessage(id = "assistant-2", parts = listOf(secondPatch)),
        )

        val visible = suppressRepeatedPatchCards(messages)

        assertEquals(listOf(firstPatch), visible[0].parts.filterIsInstance<Part.Patch>())
        assertEquals(listOf(secondPatch), visible[1].parts.filterIsInstance<Part.Patch>())
    }

    private fun assistantMessage(id: String, parts: List<Part>): ChatMessage {
        return ChatMessage(
            message = Message.Assistant(
                id = id,
                sessionId = "session-1",
                time = TimeInfo(created = 1L),
                parentId = "parent-$id",
            ),
            parts = parts,
        )
    }

    private fun patchPart(messageId: String, partId: String, hash: String): Part.Patch {
        return Part.Patch(
            id = partId,
            sessionId = "session-1",
            messageId = messageId,
            hash = hash,
            files = listOf("README.md"),
        )
    }

    private fun textPart(messageId: String, partId: String, text: String): Part.Text {
        return Part.Text(
            id = partId,
            sessionId = "session-1",
            messageId = messageId,
            text = text,
        )
    }
}
