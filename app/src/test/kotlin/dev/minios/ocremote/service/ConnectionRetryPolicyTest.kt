package dev.minios.ocremote.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRetryPolicyTest {
    @Test
    fun `failed connection stops after fifteen minutes`() {
        val startedAt = 1_000L

        assertFalse(hasFailedConnectionTimedOut(startedAt, startedAt + FAILED_CONNECTION_TIMEOUT_MS - 1))
        assertTrue(hasFailedConnectionTimedOut(startedAt, startedAt + FAILED_CONNECTION_TIMEOUT_MS))
    }

    @Test
    fun `connection endpoint comparison ignores whitespace and trailing slash`() {
        assertTrue(sameServerEndpoint("https://example.com/", " https://example.com "))
        assertFalse(sameServerEndpoint("https://example.com", "https://other.example"))
    }
}
