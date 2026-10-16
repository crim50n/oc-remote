package dev.minios.ocremote.data.api

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
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
            val (status, info) = probe(httpClient, json, conn, "/api/info")
            if (status.value == 401 || status.value == 403) {
                throw ServerAuthenticationException(status.value)
            }
            val version = (info?.get("version") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val major = version?.removePrefix("v")?.substringBefore('.')?.toIntOrNull()
            // Dev builds need the released ServerInfo shape, not just an arbitrary version string.
            val structuralV2 = info != null && version != null && major == null &&
                (info["pid"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.let { it >= 0 } == true &&
                (info["urls"] as? JsonArray)?.all { it is JsonPrimitive && it.isString } == true &&
                ((info["paths"] as? JsonObject)?.get("tmp") as? JsonPrimitive)?.isString == true
            val protocol = if (major == 2 || structuralV2) {
                ServerProtocol.V2
            } else {
                // V1 may serve its web UI with HTTP 200 for an unknown route. Confirm its health
                // contract instead of treating HTML, authorization errors, or outages as V1.
                if (!status.isSuccess() && status.value !in listOf(404, 405)) {
                    throw ServerHealthHttpException(status.value)
                }
                val (healthStatus, payload) = probe(httpClient, json, conn, "/global/health")
                healthStatusException(healthStatus.value)?.let { throw it }
                require((payload?.get("healthy") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true) {
                    "OpenCode server is unhealthy or has an unrecognized API"
                }
                ServerProtocol.V1
            }
            entries[conn] = Entry(protocol, System.currentTimeMillis() + CACHE_TTL_MS)
            protocol
        }
    }

    private suspend fun probe(
        client: HttpClient,
        json: Json,
        conn: ServerConnection,
        path: String,
    ): Pair<HttpStatusCode, JsonObject?> = client.prepareGet("${conn.baseUrl}$path") {
        conn.authHeader?.let { header("Authorization", it) }
    }.execute { response ->
        val value = if (response.status.isSuccess()) withContext(Dispatchers.IO) {
            V2LimitedInputStream(response.bodyAsChannel().toInputStream(), 65_536).reader(Charsets.UTF_8).use { input ->
                try {
                    readV2Response(json, input, path, V2ResponseLimits(
                        maxNodes = 4096, maxRetainedChars = 65_536, maxDepth = 32, maxLiteralChars = 65_536,
                    )) as? JsonObject
                } catch (e: V2ResponseLimitException) {
                    throw e
                } catch (_: IOException) {
                    null
                }
            }
        } else null
        response.status to value
    }

    internal fun clear() {
        entries.clear()
        locks.clear()
    }
}
