package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.data.api.PromptPart
import dev.minios.ocremote.data.repository.PendingPromptRecord
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.MessageWithParts
import dev.minios.ocremote.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingPromptReconciliationTest {
    @Test
    fun `missing pending prompt expires when authoritative window covers its id`() {
        val pending = pending("msg_0200", createdAt = 1_000)
        val authoritative = listOf(message("msg_0100"), message("msg_0300"))

        assertEquals(
            setOf(pending.messageId),
            missingPendingPromptIds(
                pending = listOf(pending),
                authoritative = authoritative,
                now = 20_000,
                minimumAgeMs = 10_000,
            ),
        )
    }

    @Test
    fun `pending prompt remains when history window does not reach its id`() {
        val pending = pending("msg_0100", createdAt = 1_000)

        assertTrue(
            missingPendingPromptIds(
                pending = listOf(pending),
                authoritative = listOf(message("msg_0200"), message("msg_0300")),
                now = 20_000,
                minimumAgeMs = 10_000,
            ).isEmpty(),
        )
    }

    @Test
    fun `confirmed pending prompt never expires`() {
        val pending = pending("msg_0200", createdAt = 1_000)

        assertTrue(
            missingPendingPromptIds(
                pending = listOf(pending),
                authoritative = listOf(message(pending.messageId)),
                now = 20_000,
                minimumAgeMs = 0,
            ).isEmpty(),
        )
    }

    private fun pending(id: String, createdAt: Long) = PendingPromptRecord(
        messageId = id,
        sessionId = "session",
        parts = listOf(PromptPart(type = "text", text = "prompt")),
        createdAt = createdAt,
    )

    private fun message(id: String) = MessageWithParts(
        info = Message.User(
            id = id,
            sessionId = "session",
            time = TimeInfo(created = 1_000),
        ),
        parts = emptyList(),
    )
}
