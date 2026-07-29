package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.TimeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTurnGroupingTest {

    @Test
    fun consecutiveAssistantMessagesShareOneTurn() {
        val turns = groupChatTurns(
            listOf(
                user("user-1"),
                assistant("assistant-1", "user-1"),
                assistant("assistant-2", "user-1"),
                user("user-2"),
                assistant("assistant-3", "user-2"),
            ),
        )

        assertEquals(listOf(1, 2, 1, 1), turns.map { it.messages.size })
        assertTrue(turns[0].isUser)
        assertFalse(turns[1].isUser)
        assertEquals("t_assistant-1", turns[1].key)
    }

    private fun user(id: String) = ChatMessage(
        message = Message.User(id, "session", time = TimeInfo(1)),
        parts = emptyList(),
    )

    private fun assistant(id: String, parentId: String) = ChatMessage(
        message = Message.Assistant(id, "session", time = TimeInfo(1), parentId = parentId),
        parts = emptyList(),
    )
}
