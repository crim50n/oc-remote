package dev.minios.ocremote.data.api

import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ServerProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val conn = ServerConnection("https://opencode.example", null)

    @Before fun resetRegistry() { ServerProtocolRegistry.clear() }
    @After fun clearRegistry() { ServerProtocolRegistry.clear() }

    @Test fun `unresponsive protocol probe can be cancelled and retried`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val engine = FixtureEngine {
            if (!entered.isCompleted) {
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            FixtureResponse("""{"version":"2.0.30"}""")
        }
        val client = fixtureClient(engine, json)
        try {
            withTimeout(5_000) {
                val attempt = launch { ServerProtocolRegistry.resolve(client, json, conn) }
                entered.await()
                attempt.cancelAndJoin()
                cancelled.await()
                assertTrue(attempt.isCancelled)
                assertNull(ServerProtocolRegistry.knownProtocol(conn))
                assertEquals(ServerProtocol.V2, ServerProtocolRegistry.resolve(client, json, conn))
            }
        } finally { client.close() }
    }

    @Test fun `false health is rejected and never cached`() = runBlocking {
        val engine = FixtureEngine { request ->
            if (request.url.encodedPath == "/api/info") FixtureResponse("{}", 404)
            else FixtureResponse("""{"healthy":false,"version":"1.2.0"}""")
        }
        val client = fixtureClient(engine, json)
        try {
            repeat(2) { assertTrue(runCatching { ServerProtocolRegistry.resolve(client, json, conn) }.isFailure) }
            assertNull(ServerProtocolRegistry.knownProtocol(conn))
            assertEquals(4, engine.requests.size)
        } finally { client.close() }
    }

    @Test fun `dev version requires pinned ServerInfo structure`() = runBlocking {
        for (info in listOf(
            """{"version":"dev","pid":0,"urls":[],"paths":{"tmp":"/tmp"}}""",
            """{"version":"dev"}""",
            """{"version":"dev","pid":-1,"urls":[],"paths":{"tmp":"/tmp"}}""",
            """{"version":"dev","pid":1,"urls":[42],"paths":{"tmp":"/tmp"}}""",
        )) {
            ServerProtocolRegistry.clear()
            val engine = FixtureEngine { request ->
                if (request.url.encodedPath == "/api/info") FixtureResponse(info)
                else FixtureResponse("""{"healthy":true,"version":"1.2.0"}""")
            }
            val client = fixtureClient(engine, json)
            try {
                val expected = if (info.contains("\"pid\":0")) ServerProtocol.V2 else ServerProtocol.V1
                assertEquals(expected, ServerProtocolRegistry.resolve(client, json, conn))
            } finally { client.close() }
        }
    }

    @Test fun `API info version selects v2 and caches result`() = runBlocking {
        val engine = FixtureEngine { request ->
            assertEquals("/api/info", request.url.encodedPath)
            FixtureResponse("""{"version":"2.0.30"}""")
        }
        val client = fixtureClient(engine, json)
        try {
            repeat(2) { assertEquals(ServerProtocol.V2, ServerProtocolRegistry.resolve(client, json, conn)) }
            assertEquals(1, engine.requests.size)
        } finally { client.close() }
    }

    @Test fun `404 and web UI response fall back only after v1 health confirmation`() = runBlocking {
        for (info in listOf(FixtureResponse("{}", 404), FixtureResponse("<html>OpenCode UI</html>"))) {
            ServerProtocolRegistry.clear()
            val engine = FixtureEngine { request ->
                when (request.url.encodedPath) {
                    "/api/info" -> info
                    "/global/health" -> FixtureResponse("""{"healthy":true,"version":"1.2.0"}""")
                    else -> error("Unexpected route")
                }
            }
            val client = fixtureClient(engine, json)
            try {
                assertEquals(ServerProtocol.V1, ServerProtocolRegistry.resolve(client, json, conn))
                assertEquals(listOf("/api/info", "/global/health"), engine.requests.map { it.url.encodedPath })
            } finally { client.close() }
        }
    }

    @Test fun `authorization failure does not fall back to v1 or enter cache`() = runBlocking {
        for (status in listOf(401, 403)) {
            ServerProtocolRegistry.clear()
            val engine = FixtureEngine { FixtureResponse("{}", status) }
            val client = fixtureClient(engine, json)
            try {
                repeat(2) {
                    val error = runCatching { ServerProtocolRegistry.resolve(client, json, conn) }.exceptionOrNull()
                    assertTrue(error is ServerAuthenticationException)
                    assertEquals(status, (error as ServerAuthenticationException).statusCode)
                }
                assertEquals(listOf("/api/info", "/api/info"), engine.requests.map { it.url.encodedPath })
            } finally { client.close() }
        }
    }

    @Test fun `service outage does not fall back to v1`() = runBlocking {
        val engine = FixtureEngine { FixtureResponse("{}", 503) }
        val client = fixtureClient(engine, json)
        try {
            val error = runCatching { ServerProtocolRegistry.resolve(client, json, conn) }.exceptionOrNull()
            assertTrue(error is ServerHealthHttpException)
            assertEquals(503, (error as ServerHealthHttpException).statusCode)
            assertEquals(1, engine.requests.size)
        } finally { client.close() }
    }

    @Test fun `fallback rejects a page without v1 health contract`() = runBlocking {
        val engine = FixtureEngine { FixtureResponse("<html>Login</html>") }
        val client = fixtureClient(engine, json)
        try {
            assertNotNull(runCatching { ServerProtocolRegistry.resolve(client, json, conn) }.exceptionOrNull())
            assertEquals(listOf("/api/info", "/global/health"), engine.requests.map { it.url.encodedPath })
        } finally { client.close() }
    }

    @Test fun `protocol cache isolates server URL and credentials`() = runBlocking {
        val authenticated = ServerConnection(conn.baseUrl, "Bearer YOUR_API_KEY_HERE")
        val otherServer = ServerConnection("https://second.example", null)
        val engine = FixtureEngine { request ->
            when {
                request.url.host == "second.example" -> FixtureResponse("""{"version":"2.0.30"}""")
                request.headers[HttpHeaders.Authorization] == authenticated.authHeader -> FixtureResponse("{}", 401)
                request.url.encodedPath == "/api/info" -> FixtureResponse("{}", 404)
                else -> FixtureResponse("""{"healthy":true,"version":"1.2.0"}""")
            }
        }
        val client = fixtureClient(engine, json)
        try {
            assertEquals(ServerProtocol.V1, ServerProtocolRegistry.resolve(client, json, conn))
            val error = runCatching { ServerProtocolRegistry.resolve(client, json, authenticated) }.exceptionOrNull()
            assertTrue(error is ServerAuthenticationException)
            assertEquals(ServerProtocol.V2, ServerProtocolRegistry.resolve(client, json, otherServer))
            assertEquals(ServerProtocol.V1, ServerProtocolRegistry.resolve(client, json, conn))
            assertEquals(4, engine.requests.size)
        } finally { client.close() }
    }
}
