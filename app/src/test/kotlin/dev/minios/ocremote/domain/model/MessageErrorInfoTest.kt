package dev.minios.ocremote.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageErrorInfoTest {
    @Test
    fun readsMessageFromObjectData() {
        val error = Message.Assistant.ErrorInfo(
            name = "ProviderError",
            data = JsonObject(mapOf("message" to JsonPrimitive("Request failed"))),
        )

        assertEquals("Request failed", error.message)
    }

    @Test
    fun readsMessageFromPrimitiveData() {
        val error = Message.Assistant.ErrorInfo(
            name = "ProviderError",
            data = JsonPrimitive("Plain server error"),
        )

        assertEquals("Plain server error", error.message)
    }

    @Test
    fun fallsBackToNameForUnsupportedDataShape() {
        val error = Message.Assistant.ErrorInfo(
            name = "ProviderError",
            data = JsonArray(listOf(JsonPrimitive("unexpected"))),
        )

        assertEquals("ProviderError", error.message)
    }
}
