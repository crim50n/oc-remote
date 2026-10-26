package dev.minios.ocremote.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.StringReader

class V2ResponseReaderLimitsTest {
    @Test
    fun numericLiteralLimitsApplyBeforeReadingWholeRetainedOrIgnoredValues() {
        for (field in listOf("id", "ignored")) {
            var consumed = 0
            val input = object : StringReader("""{"data":[{"$field":""" + "9".repeat(32 * 1024) + "}]}") {
                override fun read(buffer: CharArray, offset: Int, length: Int): Int =
                    super.read(buffer, offset, length).also { if (it > 0) consumed += it }
            }
            expectLimit {
                readV2Response(Json, input, "/api/model", V2ResponseLimits(maxLiteralChars = 64))
            }
            assertTrue("Must reject at the stream buffer, not after allocating the literal", consumed <= 1024)
        }
    }

    @Test
    fun unquotedLiteralLimitIncludesSignsDecimalPointsAndExponentsAndResetsAtDelimiters() {
        val limits = V2ResponseLimits(maxLiteralChars = 4)
        val raw = """[1234,-123,1.23,1e+2,true,null,"1234",{"1234":1234}]"""
        assertEquals(Json.parseToJsonElement(raw), readV2Response(Json, raw.reader(), "/api/command", limits))
        for (number in listOf("12345", "-1234", "1.234", "1e+23")) {
            expectLimit { readV2Response(Json, "[$number]".reader(), "/api/command", limits) }
        }
    }

    @Test
    fun quotedLiteralAndIgnoredNestingLimitsRemainEnforced() {
        expectLimit {
            readV2Response(Json, """{"data":[],"ignored":"123456789"}""".reader(),
                "/api/model", V2ResponseLimits(maxLiteralChars = 8))
        }
        expectLimit {
            readV2Response(Json, """{"data":[],"ignored":[[[[0]]]]}""".reader(),
                "/api/model", V2ResponseLimits(maxDepth = 4))
        }
    }

    private fun expectLimit(action: () -> Unit) {
        try {
            action()
            fail("Expected bounded response rejection")
        } catch (error: V2ResponseLimitException) {
            assertEquals("OpenCode response exceeds safe parsing limits", error.message)
            assertNull(error.cause)
        }
    }
}
