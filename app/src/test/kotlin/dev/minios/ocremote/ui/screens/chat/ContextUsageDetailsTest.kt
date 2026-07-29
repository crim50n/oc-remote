package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextUsageDetailsTest {
    @Test
    fun separatesCurrentContextFromCumulativeSessionTotals() {
        val usage = ContextUsageDetails(
            input = 100,
            output = 20,
            reasoning = 10,
            cacheRead = 30,
            cacheWrite = 5,
            sessionInput = 500,
            sessionOutput = 100,
            sessionReasoning = 50,
            sessionCacheRead = 200,
            sessionCacheWrite = 10,
        )

        assertEquals(165, usage.currentTotal)
        assertEquals(860, usage.sessionTotal)
    }
}
