package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.domain.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRetryStatusTest {
    @Test
    fun `retry remains an active stoppable session state`() {
        val retry = SessionStatus.Retry(1, "rate limited", 10_000)

        assertTrue(isWorkingSessionStatus(SessionStatus.Busy))
        assertTrue(isWorkingSessionStatus(retry))
        assertFalse(isWorkingSessionStatus(SessionStatus.Idle))
        assertEquals(
            ComposerAction.STOP,
            composerAction(
                isBusy = isWorkingSessionStatus(retry),
                isSending = false,
                hasDraft = false,
                isShellMode = false,
            ),
        )
    }

    @Test
    fun `retry countdown rounds partial seconds up and stops at zero`() {
        assertEquals(3, retryDelaySeconds(nextAtMillis = 3_001, nowMillis = 1_000))
        assertEquals(2, retryDelaySeconds(nextAtMillis = 3_000, nowMillis = 1_000))
        assertEquals(0, retryDelaySeconds(nextAtMillis = 999, nowMillis = 1_000))
    }
}
