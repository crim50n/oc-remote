package dev.minios.ocremote.data.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.basicAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent

class WebDavSyncTransport(
    private val client: HttpClient,
    private val fileUrl: String,
    private val username: String,
    private val password: String,
) : SyncTransport {
    override suspend fun read(): RemoteSyncFile? {
        val response = client.get(fileUrl) { basicAuth(username, password) }
        if (response.status.value == 404) return null
        requireWebDavSuccess(response.status.value, "read WebDAV sync file")
        return RemoteSyncFile(response.bodyAsText(), response.headers[HttpHeaders.ETag])
    }

    override suspend fun write(content: String, expectedRevision: String?, create: Boolean): String? {
        if (!create && expectedRevision == null) {
            throw SyncHttpException("WebDAV server must provide an ETag for safe synchronization")
        }
        val response = client.put(fileUrl) {
            basicAuth(username, password)
            if (create) {
                header(HttpHeaders.IfNoneMatch, "*")
            } else {
                header(HttpHeaders.IfMatch, expectedRevision!!)
            }
            setBody(TextContent(content, io.ktor.http.ContentType.Application.Json))
        }
        requireWebDavSuccess(response.status.value, "write WebDAV sync file")
        return response.headers[HttpHeaders.ETag]
    }
}

private fun requireWebDavSuccess(status: Int, operation: String) {
    if (status == 412) throw SyncHttpException("Remote WebDAV file changed during synchronization")
    if (status !in 200..299) throw SyncHttpException("Unable to $operation (HTTP $status)")
}
