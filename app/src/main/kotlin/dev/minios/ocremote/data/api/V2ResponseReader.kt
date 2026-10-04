package dev.minios.ocremote.data.api

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.io.Reader
import kotlinx.coroutines.CancellationException

internal data class V2ResponseLimits(
    val maxNodes: Int = 250_000,
    val maxRetainedChars: Int = 8 * 1024 * 1024,
    val maxDepth: Int = 128,
    val maxLiteralChars: Int = 8 * 1024 * 1024,
)

/** Deliberately excludes response contents and server-supplied field names. */
internal class V2ResponseLimitException : IOException("OpenCode v2 response exceeds safe parsing limits")

/** Reads large catalogs without constructing ignored configuration or provider metadata. */
internal fun readV2Response(
    json: Json,
    input: Reader,
    endpoint: String,
    limits: V2ResponseLimits = V2ResponseLimits(),
): JsonElement {
    require(limits.maxNodes > 0 && limits.maxRetainedChars > 0 && limits.maxDepth > 0 && limits.maxLiteralChars > 0)
    val reader = JsonReader(JsonStructureGuard(input, limits)).apply { strictness = Strictness.STRICT }
    val budget = ResponseBudget(limits)
    try {
        val result = readValue(json, reader, projection(endpoint), budget)
        if (reader.peek() != JsonToken.END_DOCUMENT) throw IOException("Invalid OpenCode v2 JSON response")
        return result
    } catch (error: V2ResponseLimitException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (_: IOException) {
        throw IOException("Invalid OpenCode v2 JSON response")
    } catch (_: IllegalStateException) {
        throw IOException("Invalid OpenCode v2 JSON response")
    } catch (_: IllegalArgumentException) {
        throw IOException("Invalid OpenCode v2 JSON response")
    }
}

private class ResponseBudget(private val limits: V2ResponseLimits) {
    private var nodes = 0
    private var chars = 0L

    fun node() {
        if (++nodes > limits.maxNodes) throw V2ResponseLimitException()
    }

    fun text(value: String): String {
        chars += value.length
        if (chars > limits.maxRetainedChars) throw V2ResponseLimitException()
        return value
    }
}

private class Projection(
    val fields: Map<String, Projection>? = null,
    val item: Projection? = null,
    val maxItems: Int = Int.MAX_VALUE,
)
private val fullValue = Projection()
private fun fields(vararg names: String) = Projection(names.associateWith { fullValue })
private fun shape(vararg entries: Pair<String, Projection>) = Projection(mapOf(*entries))

private val locationShape = fields("directory")
private val modelShape = Projection(
    fields("id", "name", "providerID", "family", "status", "enabled").fields!! + mapOf(
        "capabilities" to fields("tools", "input", "output"),
        "limit" to fields("context", "input", "output"),
        "cost" to Projection(shape("input" to fullValue, "output" to fullValue, "cache" to fields("read", "write")).fields, maxItems = 1),
        "variants" to fields("id"),
    ),
)
private val providerShape = Projection(
    fields("id", "name", "canonical", "integrationID", "activation", "package").fields!! +
        ("settings" to fields("baseURL")),
)
private val agentShape = fields("id", "name", "description", "mode", "hidden", "color")
private val configShape = shape("info" to shape(
    "model" to fields("providerID", "model", "variant"), "default_agent" to fullValue,
))
private val projectShape = fields("id", "canonical", "directory", "vcs", "name", "sandboxes")
private val sessionShape = Projection(
    fields("id", "sessionID", "parentID", "projectID", "title", "slug", "version", "created", "directory").fields!! + mapOf(
        "time" to fields("created", "updated", "idle", "viewed", "archived"),
        "location" to locationShape,
        "permissions" to fields("action", "resource", "effect"),
        "revert" to fields("messageID", "partID", "snapshot"),
    ),
)

private fun envelope(item: Projection) = Projection(mapOf(
    "data" to item,
    "cursor" to fields("next", "previous"),
    "location" to locationShape,
), item)

private fun projection(endpoint: String): Projection {
    val path = endpoint.substringBefore('?').trimEnd('/')
    return when {
        path == "/api/model" -> envelope(modelShape)
        path == "/api/model/default" -> envelope(fields("id", "providerID"))
        path == "/api/provider" || path.startsWith("/api/provider/") && path.removePrefix("/api/provider/").let { '/' !in it } -> envelope(providerShape)
        path == "/api/agent" -> envelope(agentShape)
        path == "/api/config" -> configShape
        path == "/api/project" -> projectShape
        path == "/api/session" -> envelope(sessionShape)
        path.startsWith("/api/session/") && path != "/api/session/active" && path.removePrefix("/api/session/").let { '/' !in it } -> envelope(sessionShape)
        else -> fullValue
    }
}

internal fun isProjectedV2Endpoint(endpoint: String): Boolean = projection(endpoint) !== fullValue

private fun readValue(json: Json, reader: JsonReader, projection: Projection, budget: ResponseBudget): JsonElement {
    budget.node()
    return when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            reader.beginObject()
            val result = linkedMapOf<String, JsonElement>()
            while (reader.hasNext()) {
                val name = reader.nextName()
                val child = projection.fields?.get(name) ?: if (projection.fields == null) fullValue else null
                if (child == null) {
                    reader.skipValue()
                } else {
                    budget.text(name)
                    result[name] = readValue(json, reader, child, budget)
                }
            }
            reader.endObject()
            JsonObject(result)
        }
        JsonToken.BEGIN_ARRAY -> {
            reader.beginArray()
            val result = ArrayList<JsonElement>()
            while (reader.hasNext()) {
                if (result.size < projection.maxItems) result += readValue(json, reader, projection.item ?: projection, budget)
                else reader.skipValue()
            }
            reader.endArray()
            JsonArray(result)
        }
        JsonToken.STRING -> JsonPrimitive(budget.text(reader.nextString()))
        JsonToken.NUMBER -> json.parseToJsonElement(budget.text(reader.nextString()))
        JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
        JsonToken.NULL -> { reader.nextNull(); JsonNull }
        else -> throw IOException("Invalid OpenCode v2 JSON response")
    }
}

/** Also bounds skipped nesting and single literals before JsonReader can allocate them. */
private class JsonStructureGuard(private val input: Reader, private val limits: V2ResponseLimits) : Reader() {
    private var depth = 0
    private var inString = false
    private var escaped = false
    private var literalChars = 0

    override fun read(buffer: CharArray, offset: Int, length: Int): Int {
        val count = input.read(buffer, offset, length)
        if (count <= 0) return count
        for (index in offset until offset + count) {
            val char = buffer[index]
            if (inString) {
                if (!escaped && char == '"') {
                    inString = false
                    continue
                }
                if (++literalChars > limits.maxLiteralChars) throw V2ResponseLimitException()
                escaped = if (escaped) false else char == '\\'
            } else {
                when (char) {
                    '"' -> { inString = true; escaped = false; literalChars = 0 }
                    '{', '[' -> if (++depth > limits.maxDepth) throw V2ResponseLimitException()
                    '}', ']' -> depth--
                }
            }
        }
        return count
    }

    override fun close() = input.close()
}
