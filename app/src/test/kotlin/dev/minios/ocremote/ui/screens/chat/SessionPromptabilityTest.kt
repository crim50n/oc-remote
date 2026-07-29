package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.domain.model.Session
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPromptabilityTest {
    @Test
    fun rootSessionAcceptsPrompts() {
        assertTrue(sessionAcceptsPrompts(session(parentId = null)))
    }

    @Test
    fun childSessionRejectsPrompts() {
        assertFalse(sessionAcceptsPrompts(session(parentId = "parent")))
    }

    @Test
    fun unknownSessionRejectsPrompts() {
        assertFalse(sessionAcceptsPrompts(null))
    }

    private fun session(parentId: String?) = Session(
        id = "session",
        parentId = parentId,
        time = Session.Time(created = 1, updated = 1),
    )
}
