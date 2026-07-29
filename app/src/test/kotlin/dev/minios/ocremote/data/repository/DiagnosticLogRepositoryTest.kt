package dev.minios.ocremote.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticLogRepositoryTest {
    @Test
    fun redactsCredentialsBeforePersistence() {
        val sanitized = DiagnosticLogRepository.sanitize(
            "Authorization: Bearer secret-token password=hunter2 api_key=sk-secret https://example.test?state=oauth-state&code=oauth-code",
        )

        assertFalse(sanitized.contains("secret-token"))
        assertFalse(sanitized.contains("hunter2"))
        assertFalse(sanitized.contains("sk-secret"))
        assertFalse(sanitized.contains("oauth-state"))
        assertFalse(sanitized.contains("oauth-code"))
        assertTrue(sanitized.contains("[REDACTED]"))
    }

    @Test
    fun redactsHeadersCookiesOauthAndUrlCredentials() {
        val sanitized = DiagnosticLogRepository.sanitize(
            """
            Authorization: Digest private-value
            Cookie: session=private-cookie
            client_secret=private-secret code_verifier=private-verifier
            https://user:pass@example.test/callback?state=private-state&code=private-code
            """.trimIndent(),
        )

        listOf("private-value", "private-cookie", "private-secret", "private-verifier", "user:pass", "private-state", "private-code")
            .forEach { secret -> assertFalse(sanitized.contains(secret)) }
    }

    @Test
    fun redactsNetworkAddressesAndUserPaths() {
        val sanitized = DiagnosticLogRepository.sanitize(
            "hosts 192.168.10.20 and 2001:db8::1 paths /home/alice/private/project.kt /Users/bob/source C:\\Users\\carol\\secret",
        )

        assertFalse(sanitized.contains("192.168.10.20"))
        assertFalse(sanitized.contains("2001:db8::1"))
        assertFalse(sanitized.contains("alice"))
        assertFalse(sanitized.contains("bob"))
        assertFalse(sanitized.contains("carol"))
        assertTrue(sanitized.contains("[IP]"))
        assertTrue(sanitized.contains("[PATH]"))
    }

    @Test
    fun exportPerformsSecondSanitizationPass() {
        val exported = DiagnosticLogRepository.export(
            listOf(
                DiagnosticLogEntry(
                    timestamp = 0,
                    level = "ERROR",
                    category = "Auth",
                    message = "password=late-secret",
                    details = mapOf("Authorization" to "Bearer late-token"),
                ),
            ),
        )

        assertFalse(exported.contains("late-secret"))
        assertFalse(exported.contains("late-token"))
        assertTrue(exported.contains("[REDACTED]"))
    }

    @Test
    fun sanitizerBoundsEachField() {
        assertEquals(1000, DiagnosticLogRepository.sanitize("x".repeat(2000)).length)
    }

    @Test
    fun sanitizerBoundsDetailFieldCount() {
        val entry = DiagnosticLogEntry(
            timestamp = 0,
            level = "DEBUG",
            category = "Test",
            message = "bounded",
            details = (1..100).associate { "key-$it" to "value-$it" },
        )

        assertEquals(20, DiagnosticLogRepository.sanitizeEntry(entry).details.size)
    }
}
