package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.domain.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRetryStatusTest {
    @Test
    fun `retry remains an active stoppable session state`() {
        assertTrue(isWorkingSessionStatus(SessionStatus.Busy))
        assertTrue(isWorkingSessionStatus(SessionStatus.Retry(1, "rate limited", 10_000)))
        assertFalse(isWorkingSessionStatus(SessionStatus.Idle))
    }

    @Test
    fun `retry countdown rounds partial seconds up and stops at zero`() {
        assertEquals(3, retryDelaySeconds(nextAtMillis = 3_001, nowMillis = 1_000))
        assertEquals(2, retryDelaySeconds(nextAtMillis = 3_000, nowMillis = 1_000))
        assertEquals(0, retryDelaySeconds(nextAtMillis = 999, nowMillis = 1_000))
    }
}
