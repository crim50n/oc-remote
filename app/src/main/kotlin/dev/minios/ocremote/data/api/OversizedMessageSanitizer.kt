package dev.minios.ocremote.data.api

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.Reader
import java.io.Writer

private val OMITTED_FIELDS = setOf(
    "data",
    "diff",
    "output",
    "patch",
    "patchText",
    "raw",
    "reasoningEncryptedContent",
    "snapshot",
    "source",
    "url",
)

internal fun sanitizeOversizedMessageJson(input: Reader, output: Writer) {
    transformMessageJson(input, output, omitPayloadFields = true)
}

/** Remove the HTTP envelope while retaining the native, importable session format. */
internal fun unwrapV2DataJson(input: Reader, output: Writer) {
    val writer = JsonWriter(output)
    writer.setSerializeNulls(true)
    JsonReader(input).use { reader ->
        reader.beginObject()
        var found = false
        while (reader.hasNext()) {
            if (reader.nextName() == "data") {
                check(!found) { "Duplicate OpenCode export payload" }
                copyJsonValue(reader, writer, null, false, null, false, 0)
                found = true
            } else reader.skipValue()
        }
        reader.endObject()
        check(found) { "Missing OpenCode export payload" }
        writer.flush()
    }
}

internal fun transformMessageJson(
    input: Reader,
    output: Writer,
    omitPayloadFields: Boolean,
    cacheImageDataUrl: ((String) -> String?)? = null,
    preserveEnvelopeData: Boolean = false,
) {
    JsonReader(input).use { reader ->
        JsonWriter(output).use { writer ->
            reader.isLenient = true
            writer.setSerializeNulls(true)
            copyJsonValue(reader, writer, fieldName = null, omitPayloadFields, cacheImageDataUrl,
                preserveEnvelopeData, depth = 0)
        }
    }
}

private fun copyJsonValue(
    reader: JsonReader,
    writer: JsonWriter,
    fieldName: String?,
    omitPayloadFields: Boolean,
    cacheImageDataUrl: ((String) -> String?)?,
    preserveEnvelopeData: Boolean,
    depth: Int,
) {
    val token = reader.peek()
    if (!omitPayloadFields && fieldName == "url" && token == JsonToken.STRING && cacheImageDataUrl != null) {
        val value = reader.nextString()
        val cached = cacheImageDataUrl(value)
        writer.value(cached ?: if (omitPayloadFields) "" else value)
        return
    }
    val shouldOmit = omitPayloadFields && fieldName in OMITTED_FIELDS && token != JsonToken.NULL &&
        !(preserveEnvelopeData && fieldName == "data" && depth == 1) &&
        (fieldName != "output" || token == JsonToken.STRING || token == JsonToken.BEGIN_OBJECT || token == JsonToken.BEGIN_ARRAY)
    if (shouldOmit) {
        reader.skipValue()
        writer.value("")
        return
    }
    when (token) {
        JsonToken.BEGIN_ARRAY -> {
            reader.beginArray()
            writer.beginArray()
            while (reader.hasNext()) {
                copyJsonValue(reader, writer, fieldName = null, omitPayloadFields, cacheImageDataUrl, preserveEnvelopeData, depth + 1)
            }
            reader.endArray()
            writer.endArray()
        }
        JsonToken.BEGIN_OBJECT -> {
            reader.beginObject()
            writer.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                writer.name(name)
                copyJsonValue(reader, writer, name, omitPayloadFields, cacheImageDataUrl, preserveEnvelopeData, depth + 1)
            }
            reader.endObject()
            writer.endObject()
        }
        JsonToken.STRING -> writer.value(reader.nextString())
        JsonToken.NUMBER -> writer.jsonValue(reader.nextString())
        JsonToken.BOOLEAN -> writer.value(reader.nextBoolean())
        JsonToken.NULL -> {
            reader.nextNull()
            writer.nullValue()
        }
        JsonToken.END_ARRAY, JsonToken.END_OBJECT, JsonToken.NAME, JsonToken.END_DOCUMENT ->
            error("Unexpected JSON token ${reader.peek()}")
    }
}
