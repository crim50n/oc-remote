package dev.minios.ocremote.data.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptRequestTest {

    @Test
    fun serializesCorrelatedMessageId() {
        val encoded = Json.encodeToString(
            PromptRequest(
                messageId = "msg_000000000001abcdefghijklmn",
                parts = listOf(PromptPart(type = "text", text = "hello")),
            ),
        )

        assertTrue(encoded.contains("\"messageID\":\"msg_000000000001abcdefghijklmn\""))
    }
}
