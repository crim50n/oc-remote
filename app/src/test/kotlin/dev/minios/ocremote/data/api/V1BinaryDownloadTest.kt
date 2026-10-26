package dev.minios.ocremote.data.api

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.util.Collections
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class V1BinaryDownloadTest {
    @Test fun decodesEscapesWithMetadataAfterContent() {
        val output = ByteArrayOutputStream()
        val mime = downloadV1Binary(
            """{"content":"\u002fw==","encoding":"base64","mimeType":"application/octet-stream"}""".byteInputStream(),
            output,
        )
        assertArrayEquals(byteArrayOf(-1), output.toByteArray())
        assertEquals("application/octet-stream", mime)
    }

    @Test fun streamsBeyondPreviewLimitWithoutRetainingPayload() {
        val encodedSize = 48 * 1024 * 1024
        val content = object : InputStream() {
            var remaining = encodedSize
            override fun read(): Int = if (remaining-- > 0) 'A'.code else -1
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(length, remaining)
                bytes.fill('A'.code.toByte(), offset, offset + count)
                remaining -= count
                return count
            }
        }
        val input = SequenceInputStream(Collections.enumeration(listOf(
            """{"encoding":"base64","content":"""".byteInputStream(),
            content,
            "\"}".byteInputStream(),
        )))
        var size = 0L
        downloadV1Binary(input, object : OutputStream() {
            override fun write(value: Int) { assertEquals(0, value); size++ }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                for (i in offset until offset + length) assertEquals(0, bytes[i].toInt())
                size += length
            }
        })
        assertEquals(36L * 1024 * 1024, size)
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsMissingEncoding() {
        downloadV1Binary("""{"type":"binary","content":""}""".byteInputStream(), ByteArrayOutputStream())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedBase64() {
        downloadV1Binary("""{"encoding":"base64","content":"???"}""".byteInputStream(), ByteArrayOutputStream())
    }

    @Test(expected = CancellationException::class)
    fun propagatesCancellationFromDestination() {
        downloadV1Binary("""{"encoding":"base64","content":"AAAA"}""".byteInputStream(), object : OutputStream() {
            override fun write(value: Int) { throw CancellationException("Cancelled") }
        })
    }
}
