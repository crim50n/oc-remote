package dev.minios.ocremote.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderAuthErrorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun extractsOpenCodeUnknownErrorMessageWithoutStackTrace() {
        val body = """{"name":"UnknownError","data":{"message":"Error: Failed to initiate device authorization\n    at authorize"}}"""

        assertEquals(
            "Failed to initiate device authorization (HTTP 500)",
            providerAuthErrorMessage(json, 500, body, "Failed to start OAuth"),
        )
    }

    @Test
    fun fallsBackToStatusForHtmlResponses() {
        assertEquals(
            "Failed to start OAuth (HTTP 502)",
            providerAuthErrorMessage(json, 502, "<html>Bad gateway</html>", "Failed to start OAuth"),
        )
    }
}
