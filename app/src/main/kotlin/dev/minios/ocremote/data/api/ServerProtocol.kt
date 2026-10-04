package dev.minios.ocremote.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap

enum class ServerProtocol { V1, V2 }

/** Negotiate per connection, including credentials, without persisting authentication data. */
object ServerProtocolRegistry {
    private data class Entry(val protocol: ServerProtocol, val expiresAt: Long)
    private val entries = ConcurrentHashMap<ServerConnection, Entry>()
    private val locks = ConcurrentHashMap<ServerConnection, Mutex>()
    private const val CACHE_TTL_MS = 60_000L

    fun knownProtocol(conn: ServerConnection): ServerProtocol? = entries[conn]?.protocol

    suspend fun resolve(httpClient: HttpClient, json: Json, conn: ServerConnection): ServerProtocol {
        entries[conn]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return it.protocol }
        return locks.getOrPut(conn) { Mutex() }.withLock {
            entries[conn]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return@withLock it.protocol }
            val response = httpClient.get("${conn.baseUrl}/api/info") {
                conn.authHeader?.let { header("Authorization", it) }
            }
            if (response.status.value == 401 || response.status.value == 403) {
                throw ServerAuthenticationException(response.status.value)
            }
            val info = if (response.status.isSuccess()) {
                runCatching { json.parseToJsonElement(response.bodyAsText()) as? JsonObject }.getOrNull()
            } else null
            val version = info?.get("version")?.jsonPrimitive?.contentOrNull
            val protocol = if (version?.substringBefore('.')?.removePrefix("v") == "2") {
                ServerProtocol.V2
            } else {
                // V1 may serve its web UI with HTTP 200 for an unknown route. Confirm its health
                // contract instead of treating HTML, authorization errors, or outages as V1.
                if (!response.status.isSuccess() && response.status.value !in listOf(404, 405)) {
                    throw ServerHealthHttpException(response.status.value)
                }
                val health = httpClient.get("${conn.baseUrl}/global/health") {
                    conn.authHeader?.let { header("Authorization", it) }
                }
                healthStatusException(health.status.value)?.let { throw it }
                val payload = json.parseToJsonElement(health.bodyAsText()).jsonObject
                require(payload["healthy"]?.jsonPrimitive?.booleanOrNull != null) { "Unrecognized OpenCode server API" }
                ServerProtocol.V1
            }
            entries[conn] = Entry(protocol, System.currentTimeMillis() + CACHE_TTL_MS)
            protocol
        }
    }

    internal fun clear() {
        entries.clear()
        locks.clear()
    }
}
