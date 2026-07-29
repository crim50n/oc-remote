package dev.minios.ocremote.ui.screens.sessions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListScreenTest {
    @Test
    fun `initial or unchanged top session does not move the list`() {
        assertFalse(shouldRevealPromotedSession(null, "current", 0, searchActive = false))
        assertFalse(shouldRevealPromotedSession("current", "current", 0, searchActive = false))
        assertFalse(shouldRevealPromotedSession("current", null, 0, searchActive = false))
    }

    @Test
    fun `promoted session is revealed when viewport remains near top`() {
        assertTrue(shouldRevealPromotedSession("previous", "current", 0, searchActive = false))
        assertTrue(shouldRevealPromotedSession("previous", "current", 1, searchActive = false))
        assertTrue(shouldRevealPromotedSession("previous", "current", 2, searchActive = false))
    }

    @Test
    fun `promoted session preserves deep scroll and active search`() {
        assertFalse(shouldRevealPromotedSession("previous", "current", 3, searchActive = false))
        assertFalse(shouldRevealPromotedSession("previous", "current", 0, searchActive = true))
    }
}
