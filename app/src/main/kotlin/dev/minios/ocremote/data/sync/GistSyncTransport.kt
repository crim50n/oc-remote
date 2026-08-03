package dev.minios.ocremote.data.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GistSyncTransport(
    private val client: HttpClient,
    private val json: Json,
    private val token: String,
    gistReference: String?,
) : SyncTransport {
    private var gistId = parseGistId(gistReference)
    val currentGistId: String? get() = gistId

    override suspend fun read(): RemoteSyncFile? {
        val id = gistId ?: return null
        val response = client.get("https://api.github.com/gists/$id") { bearer() }
        if (response.status.value == 404) return null
        requireSuccess(response.status.value, "read GitHub Gist")
        val gist = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val file = gist["files"]?.jsonObject?.get(FILE_NAME)?.jsonObject
            ?: throw SyncHttpException("GitHub Gist does not contain $FILE_NAME")
        val content = file["content"]?.jsonPrimitive?.content.orEmpty()
        val resolved = if (file["truncated"]?.jsonPrimitive?.booleanOrNull == true) {
            val rawUrl = file["raw_url"]?.jsonPrimitive?.content
                ?: throw SyncHttpException("Truncated Gist file has no raw_url")
            val raw = client.get(rawUrl) { bearer() }
            requireSuccess(raw.status.value, "read truncated GitHub Gist")
            raw.bodyAsText()
        } else content
        val revision = response.headers[HttpHeaders.ETag]
            ?: gist["history"]?.jsonArray?.firstOrNull()?.jsonObject?.get("version")?.jsonPrimitive?.content
        return RemoteSyncFile(resolved, revision, gistId)
    }

    override suspend fun write(content: String, expectedRevision: String?, create: Boolean): String? {
        val files = "\"files\":{\"$FILE_NAME\":{\"content\":${json.encodeToString(String.serializer(), content)}}}"
        val response = if (gistId == null) {
            client.post("https://api.github.com/gists") {
                bearer()
                setBody(TextContent("{\"public\":false,\"description\":\"OC Remote sync\",$files}", ContentType.Application.Json))
            }
        } else {
            client.patch("https://api.github.com/gists/$gistId") {
                bearer()
                expectedRevision?.let { header(HttpHeaders.IfMatch, it) }
                setBody(TextContent("{$files}", ContentType.Application.Json))
            }
        }
        requireSuccess(response.status.value, "write GitHub Gist")
        val gist = json.parseToJsonElement(response.bodyAsText()).jsonObject
        gistId = gist["id"]?.jsonPrimitive?.content ?: gistId
        return response.headers[HttpHeaders.ETag]
            ?: gist["history"]?.jsonArray?.firstOrNull()?.jsonObject?.get("version")?.jsonPrimitive?.content
    }

    private fun io.ktor.client.request.HttpRequestBuilder.bearer() {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.Accept, "application/vnd.github+json")
    }

    companion object {
        const val FILE_NAME = "OCRemote.json"

        fun parseGistId(reference: String?): String? {
            val value = reference?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank) ?: return null
            val candidate = value.substringAfterLast('/')
            return candidate.takeIf { it.matches(Regex("[0-9a-fA-F]{5,}")) }
        }
    }
}

private fun requireSuccess(status: Int, operation: String) {
    if (status == 412) throw SyncHttpException("Remote Gist changed during synchronization")
    if (status !in 200..299) throw SyncHttpException("Unable to $operation (HTTP $status)")
}
