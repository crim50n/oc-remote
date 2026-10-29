package dev.minios.ocremote.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/** A missing modern endpoint allows the caller to fall back on older V1 file status. */
internal suspend fun readWorkspaceVcsV1(
    client: HttpClient, json: Json, conn: ServerConnection, path: String, directory: String,
    query: Map<String, String> = emptyMap(),
): List<WorkspaceVcsChange>? = client.prepareGet("${conn.baseUrl}$path") {
    conn.authHeader?.let { header("Authorization", it) }
    directoryHeader(directory)
    parameter("directory", directory)
    query.forEach { (key, value) -> parameter(key, value) }
}.execute { response ->
    if (response.status.value in listOf(404, 405)) return@execute null
    check(response.status.isSuccess()) { "VCS request failed (HTTP ${response.status.value})" }
    withContext(Dispatchers.IO) {
        V2LimitedInputStream(response.bodyAsChannel().toInputStream(), 8L * 1024 * 1024)
            .reader(Charsets.UTF_8).use { input ->
                json.decodeFromJsonElement<List<WorkspaceVcsChange>>(readV2Response(json, input, path))
            }
    }
}
