package dev.minios.ocremote.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.minios.ocremote.domain.model.SseEvent
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

    @Test
    fun readsV2EventDataAndKeepsLegacyPropertiesCompatibility() {
        val v2 = buildJsonObject {
            put("data", buildJsonObject { put("sessionID", "v2") })
        }
        val legacy = buildJsonObject {
            put("properties", buildJsonObject { put("sessionID", "legacy") })
        }

        assertEquals("v2", sseEventData(v2)["sessionID"]?.toString()?.trim('"'))
        assertEquals("legacy", sseEventData(legacy)["sessionID"]?.toString()?.trim('"'))
    }

    @Test
    fun suppressesOnlyHighFrequencyEventsFromDebugLog() {
        assertEquals(true, isHighFrequencySseEvent(SseEvent.MessagePartDelta("session", "message", "part", "text", "x")))
        assertEquals(true, isHighFrequencySseEvent(SseEvent.ServerHeartbeat))
        assertEquals(false, isHighFrequencySseEvent(SseEvent.SessionCompacted("session")))
        assertEquals(false, isHighFrequencySseEvent(SseEvent.SessionIdle("session")))
    }
}
