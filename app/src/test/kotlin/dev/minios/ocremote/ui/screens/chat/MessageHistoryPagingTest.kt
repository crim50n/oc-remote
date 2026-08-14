package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageHistoryPagingTest {
    @Test
    fun initialPageShowsAtMostTenMessages() {
        assertEquals(5, fastInitialMessageLimit(5))
        assertEquals(10, fastInitialMessageLimit(25))
        assertEquals(10, fastInitialMessageLimit(200))
    }

    @Test
    fun backgroundPagesFillConfiguredLimitInBoundedChunks() {
        assertEquals(25, backgroundMessageLimit(10, 50))
        assertEquals(15, backgroundMessageLimit(35, 50))
        assertEquals(1, backgroundMessageLimit(49, 50))
    }
}
