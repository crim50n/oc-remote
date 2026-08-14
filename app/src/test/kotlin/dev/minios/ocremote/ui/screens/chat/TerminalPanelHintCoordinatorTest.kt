package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPanelHintCoordinatorTest {
    @Test
    fun `hint is consumed once per process state`() {
        val hint = OncePerProcessHint()

        assertTrue(hint.tryShow())
        assertFalse(hint.tryShow())
    }
}
