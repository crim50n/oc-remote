package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InitialMessageVisibilityTest {
    @Test
    fun suppressesPartialHistoryUntilInitialLoadCompletes() {
        assertTrue(shouldSuppressPartialMessages(loading = true, messageCount = 2, sentDuringInitialLoad = false))
        assertFalse(shouldSuppressPartialMessages(loading = false, messageCount = 2, sentDuringInitialLoad = false))
    }

    @Test
    fun showsMessagesAfterLocalSendDuringInitialLoad() {
        assertFalse(shouldSuppressPartialMessages(loading = true, messageCount = 1, sentDuringInitialLoad = true))
    }
}
