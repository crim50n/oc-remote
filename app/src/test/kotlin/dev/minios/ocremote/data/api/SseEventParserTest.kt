package dev.minios.ocremote.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseEventParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun parsesStructuredSessionError() {
        val event = parseSessionError(
            buildJsonObject {
                put("sessionID", "session")
                put("error", buildJsonObject {
                    put("name", "ProviderError")
                    put("data", buildJsonObject { put("message", "quota exceeded") })
                })
            },
            json,
        )

        assertEquals("session", event.sessionId)
        assertEquals("ProviderError", event.error.name)
        assertEquals("quota exceeded", event.error.message)
    }

    @Test
    fun keepsCompatibilityWithStringServerErrors() {
        val event = parseSessionError(buildJsonObject { put("error", "legacy error") }, json)

        assertNull(event.sessionId)
        assertEquals("legacy error", event.error.message)
    }
}
