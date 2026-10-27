package dev.minios.ocremote.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckIntervalTest {
    private val day = 24 * 60 * 60 * 1000L

    @Test fun firstResumeCanCheckImmediately() {
        assertTrue(UpdatePolicy.shouldCheckAutomatically(1000L, 0L))
    }

    @Test fun repeatedResumesDoNotCheckUntilIntervalExpires() {
        val completed = 10 * day
        assertFalse(UpdatePolicy.shouldCheckAutomatically(completed, completed))
        assertFalse(UpdatePolicy.shouldCheckAutomatically(completed + day - 1, completed))
        assertTrue(UpdatePolicy.shouldCheckAutomatically(completed + day, completed))
    }

    @Test fun largeClockChangesDoNotBlockChecksIndefinitely() {
        val completed = 10 * day
        assertFalse(UpdatePolicy.shouldCheckAutomatically(completed - 1000L, completed))
        assertTrue(UpdatePolicy.shouldCheckAutomatically(completed - day, completed))
    }
}
