package dev.minios.ocremote.data.api

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import java.io.InputStream
import java.io.OutputStream

private val binaryFileJson = JsonFactory.builder()
    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(65_536).build())
    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
    .build()

/** Streams the content string directly through a Base64 decoder. Metadata may occur in any order.
 * The caller owns the output and must discard partial output on failure or cancellation.
 */
internal fun downloadV1Binary(input: InputStream, output: OutputStream): String? {
    binaryFileJson.createParser(input).use { parser ->
        check(parser.nextToken() == JsonToken.START_OBJECT) { "Invalid V1 file response" }
        var contentRead = false
        var encoding: String? = null
        var mime: String? = null
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            check(parser.currentToken() == JsonToken.FIELD_NAME) { "Invalid V1 file response" }
            val name = parser.currentName()
            val token = parser.nextToken()
            when (name) {
                "content" -> {
                    check(token == JsonToken.VALUE_STRING) { "Invalid V1 file content" }
                    // Unlike getBinaryValue(), this does not materialize the Base64 string or byte array.
                    parser.readBinaryValue(output)
                    contentRead = true
                }
                "encoding", "mimeType" -> {
                    check(token == JsonToken.VALUE_STRING || token == JsonToken.VALUE_NULL) { "Invalid V1 file metadata" }
                    val value = if (token == JsonToken.VALUE_NULL) null else parser.text
                    if (name == "encoding") encoding = value else mime = value
                }
                else -> parser.skipChildren()
            }
        }
        check(contentRead && encoding == "base64") { "This V1 server does not expose Base64 file contents" }
        check(parser.nextToken() == null) { "Invalid V1 file response" }
        return mime
    }
}
