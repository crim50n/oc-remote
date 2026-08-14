package dev.minios.ocremote.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter

class OversizedMessageSanitizerTest {
    @Test
    fun preservesStructureAndIdentifiersButOmitsPayloadStrings() {
        val input = """[{"info":{"id":"msg-1","sessionID":"ses-1","role":"assistant","tokens":{"input":10,"output":42}},"parts":[{"id":"part-1","sessionID":"ses-1","messageID":"msg-1","type":"tool","tool":"task","state":{"status":"completed","output":"huge output","title":"Custom agent","metadata":{"sessionId":"child-1","description":"Short description","diff":"huge diff"}}}]}]"""
        val output = StringWriter()

        sanitizeOversizedMessageJson(StringReader(input), output)

        val root = Json.parseToJsonElement(output.toString()).jsonArray.single().jsonObject
        assertEquals("msg-1", root["info"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
        assertEquals(42, root["info"]?.jsonObject?.get("tokens")?.jsonObject?.get("output")?.jsonPrimitive?.int)
        val part = root["parts"]?.jsonArray?.single()?.jsonObject
        assertEquals("task", part?.get("tool")?.jsonPrimitive?.content)
        val state = part?.get("state")?.jsonObject
        assertFalse(state?.get("output")?.jsonPrimitive?.content.orEmpty().contains("huge output"))
        assertEquals("Custom agent", state?.get("title")?.jsonPrimitive?.content)
        assertEquals(
            "child-1",
            state?.get("metadata")?.jsonObject
                ?.get("sessionId")?.jsonPrimitive?.content,
        )
        assertEquals(
            "Short description",
            state?.get("metadata")?.jsonObject?.get("description")?.jsonPrimitive?.content,
        )
        assertFalse(
            state?.get("metadata")?.jsonObject?.get("diff")?.jsonPrimitive?.content.orEmpty().contains("huge diff"),
        )
    }

    @Test
    fun extractsImageDataUrlsWhilePreservingRegularContent() {
        val input = """[{"info":{"id":"msg-1"},"parts":[{"type":"file","url":"data:image/png;base64,YWJj"},{"type":"text","text":"kept"}]}]"""
        val output = StringWriter()

        transformMessageJson(
            input = StringReader(input),
            output = output,
            omitPayloadFields = false,
            cacheImageDataUrl = { value ->
                if (value.startsWith("data:image/")) "file:/cache/image.png" else null
            },
        )

        val parts = Json.parseToJsonElement(output.toString()).jsonArray.single().jsonObject["parts"]?.jsonArray
        assertEquals("file:/cache/image.png", parts?.get(0)?.jsonObject?.get("url")?.jsonPrimitive?.content)
        assertEquals("kept", parts?.get(1)?.jsonObject?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun omitsOversizedImageUrlWithoutPassingItToCache() {
        val input = """[{"parts":[{"type":"file","url":"data:image/png;base64,YWJj"}]}]"""
        val output = StringWriter()
        var cacheCalled = false

        transformMessageJson(
            input = StringReader(input),
            output = output,
            omitPayloadFields = true,
            cacheImageDataUrl = {
                cacheCalled = true
                "file:/cache/image.png"
            },
        )

        val url = Json.parseToJsonElement(output.toString()).jsonArray.single().jsonObject["parts"]
            ?.jsonArray?.single()?.jsonObject?.get("url")?.jsonPrimitive?.content
        assertEquals("", url)
        assertTrue(!cacheCalled)
    }
}
