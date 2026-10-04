package dev.minios.ocremote.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** In-process HTTP transport: exercises Ktor request serialization without sockets. */
internal data class FixtureResponse(val body: String = "{}", val status: Int = 200)

@OptIn(io.ktor.util.InternalAPI::class)
internal class FixtureEngine(private val handler: suspend (HttpRequestData) -> FixtureResponse) : HttpClientEngineBase("fixture") {
    override val config = HttpClientEngineConfig()
    val requests = mutableListOf<HttpRequestData>()

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        requests += data
        val response = handler(data)
        return HttpResponseData(HttpStatusCode.fromValue(response.status), GMTDate(),
            headersOf(HttpHeaders.ContentType, "application/json"), HttpProtocolVersion.HTTP_1_1,
            ByteReadChannel(response.body), callContext())
    }
}

internal fun fixtureClient(engine: FixtureEngine, json: Json): HttpClient = HttpClient(engine) {
    install(ContentNegotiation) { json(json) }
}

internal fun HttpRequestData.jsonBody(json: Json): JsonObject =
    json.parseToJsonElement(String((body as OutgoingContent.ByteArrayContent).bytes(), Charsets.UTF_8)).jsonObject

class OpenCodeV2ApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val conn = ServerConnection("https://opencode.example", "Bearer YOUR_API_KEY_HERE")

    @Test fun `catalog loads are shared and authentication cannot repopulate stale cache`() = runBlocking {
        val mutationStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val finishMutation = kotlinx.coroutines.CompletableDeferred<Unit>()
        val concurrentRead = kotlinx.coroutines.CompletableDeferred<Unit>()
        var connected = false
        var catalogLoads = 0
        val engine = FixtureEngine { request ->
            when (request.url.encodedPath) {
                "/api/provider" -> FixtureResponse("""{"data":[{"id":"p","name":"Provider","activation":"enabled"}]}""")
                "/api/model" -> {
                    catalogLoads++
                    if (catalogLoads > 1 && !connected) concurrentRead.complete(Unit)
                    val model = if (connected) "new-model" else "old-model"
                    FixtureResponse("""{"data":[{"id":"$model","providerID":"p","name":"Model","enabled":true}]}""")
                }
                "/api/model/default" -> FixtureResponse("""{"data":null}""")
                "/api/provider/p" -> FixtureResponse("""{"data":{"integrationID":"integration"}}""")
                "/api/integration/integration" -> FixtureResponse("""{"data":{"id":"integration"}}""")
                "/api/integration/integration/connect/key" -> {
                    mutationStarted.complete(Unit)
                    finishMutation.await()
                    connected = true
                    FixtureResponse()
                }
                else -> error("Unexpected fixture route")
            }
        }
        val client = fixtureClient(engine, json)
        try {
            val api = OpenCodeV2Api(client, json)
            val first = api.providers(conn)
            assertSame(first, api.providers(conn))
            assertEquals(1, catalogLoads)
            val mutation = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                api.setApiKey(conn, "p", "YOUR_API_KEY_HERE")
            }
            mutationStarted.await()
            val reload = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { api.providers(conn) }
            assertNull(kotlinx.coroutines.withTimeoutOrNull(200) { concurrentRead.await() })
            assertFalse(reload.isCompleted)
            finishMutation.complete(Unit)
            assertTrue(mutation.await())
            val refreshed = reload.await()
            assertEquals(setOf("new-model"), refreshed.all.single().models.keys)
            assertSame(refreshed, api.providers(conn))
            assertEquals(2, catalogLoads)
        } finally {
            finishMutation.complete(Unit)
            client.close()
        }
    }

    @Test fun `export preserves importable payload without HTTP envelope`() = runBlocking {
        val data = """{"info":{"id":"ses_1","location":{"directory":"/workspace"},"metadata":{"nullable":null}},"messages":[{"id":"msg_1","type":"user","text":"Unicode: ação; quoted brace: \"}\""}]}"""
        val engine = FixtureEngine { request ->
            assertEquals("/api/experimental/session/ses_1/export", request.url.encodedPath)
            FixtureResponse("""{"data":$data}""")
        }
        val client = fixtureClient(engine, json)
        try {
            val output = java.io.ByteArrayOutputStream()
            var progress = 0L
            OpenCodeV2Api(client, json).exportToStream(conn, "ses_1", output) { progress = it }
            assertEquals(json.parseToJsonElement(data), json.parseToJsonElement(output.toString("UTF-8")))
            assertEquals(output.size().toLong(), progress)
        } finally { client.close() }
    }

    @Test fun `prompt switches agent and model before sending flattened content`() = runBlocking {
        val engine = FixtureEngine { FixtureResponse() }
        val client = fixtureClient(engine, json)
        try {
            OpenCodeV2Api(client, json).prompt(conn, "session_1", "message_1", listOf(
                PromptPart("text", text = "first"), PromptPart("text", text = "second"),
                PromptPart("file", path = "/workspace/example.kt", filename = "example.kt"),
                PromptPart("agent", text = "review"),
            ), ModelSelection("provider_1", "model_1"), "build", "fast")
            assertEquals(listOf("/api/session/session_1/agent", "/api/session/session_1/model", "/api/session/session_1/prompt"),
                engine.requests.map { it.url.encodedPath })
            assertTrue(engine.requests.all { it.method == HttpMethod.Post })
            assertTrue(engine.requests.all { it.headers[HttpHeaders.Authorization] == conn.authHeader })
            assertEquals(json.parseToJsonElement("""{"agent":"build"}"""), engine.requests[0].jsonBody(json))
            assertEquals(json.parseToJsonElement("""{"model":{"providerID":"provider_1","id":"model_1","variant":"fast"}}"""),
                engine.requests[1].jsonBody(json))
            assertEquals(json.parseToJsonElement("""{"id":"message_1","text":"first\nsecond","delivery":"steer","resume":true,"files":[{"uri":"file:///workspace/example.kt","name":"example.kt"}],"agents":[{"name":"review"}]}"""),
                engine.requests[2].jsonBody(json))
        } finally { client.close() }
    }

    @Test fun `session pagination keeps root filter and follows next cursor`() = runBlocking {
        val engine = FixtureEngine { request ->
            assertEquals("/api/session", request.url.encodedPath)
            assertEquals("null", request.url.parameters["parentID"])
            assertEquals("/workspace", request.url.parameters["directory"])
            assertEquals("desc", request.url.parameters["order"])
            assertEquals("100", request.url.parameters["limit"])
            when (request.url.parameters["cursor"]) {
                null -> FixtureResponse("""{"data":[{"id":"s2","location":{"directory":"/workspace"},"time":{"created":2}}],"cursor":{"next":"older-page","previous":"newer-page"}}""")
                "older-page" -> FixtureResponse("""{"data":[{"id":"s1","time":{"created":1}}],"cursor":{"next":null}}""")
                else -> error("Unexpected pagination cursor")
            }
        }
        val client = fixtureClient(engine, json)
        try {
            val sessions = OpenCodeV2Api(client, json).sessions(conn, "/workspace")
            assertEquals(listOf("s2", "s1"), sessions.map { it.id })
            assertEquals("/workspace", sessions.first().directory)
            assertEquals(2, engine.requests.size)
        } finally { client.close() }
    }

    @Test fun `session pagination fails when server repeats cursor`() = runBlocking {
        val engine = FixtureEngine { FixtureResponse("""{"data":[],"cursor":{"next":"repeated"}}""") }
        val client = fixtureClient(engine, json)
        try {
            val error = runCatching { OpenCodeV2Api(client, json).sessions(conn) }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals(2, engine.requests.size)
        } finally { client.close() }
    }

    @Test fun `message page sorts chat content and returns older next cursor`() = runBlocking {
        val engine = FixtureEngine { request ->
            assertEquals("/api/session/session_1/message", request.url.encodedPath)
            assertEquals("desc", request.url.parameters["order"])
            assertEquals("20", request.url.parameters["limit"])
            assertEquals("requested-page", request.url.parameters["cursor"])
            FixtureResponse("""{"data":[{"id":"m2","type":"user","text":"later","time":{"created":20}},{"id":"switch","type":"model-switched","time":{"created":15}},{"id":"m1","type":"user","text":"earlier","time":{"created":10}}],"cursor":{"next":"older-page","previous":"newer-page"}}""")
        }
        val client = fixtureClient(engine, json)
        try {
            val page = OpenCodeV2Api(client, json).messagesPage(conn, "session_1", 20, "requested-page")
            assertEquals(listOf("m1", "m2"), page.messages.map { it.info.id })
            assertTrue(page.messages.all { it.info.sessionId == "session_1" })
            assertEquals("older-page", page.nextCursor)
        } finally { client.close() }
    }

    @Test fun `permission reply resolves session and sends decision`() = runBlocking {
        val engine = FixtureEngine { request ->
            when (request.url.encodedPath) {
                "/api/permission/request" -> {
                    assertEquals("/workspace", request.url.parameters["location[directory]"])
                    FixtureResponse("""{"data":[{"id":"permission_1","sessionID":"session_1","action":"write","resources":["example.kt"]}]}""")
                }
                "/api/session/session_1/permission/permission_1/reply" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals(json.parseToJsonElement("""{"decision":"once","message":"Approved example"}"""), request.jsonBody(json))
                    FixtureResponse()
                }
                else -> error("Unexpected route")
            }
        }
        val client = fixtureClient(engine, json)
        try {
            assertTrue(OpenCodeV2Api(client, json).replyPermission(conn, "permission_1", "once", "Approved example", "/workspace"))
            assertEquals(2, engine.requests.size)
        } finally { client.close() }
    }

    @Test fun `form replies use field keys option values and typed booleans and numbers`() = runBlocking {
        val engine = FixtureEngine { request ->
            when (request.url.encodedPath) {
                "/api/form" -> FixtureResponse("""{"data":[{"id":"form_1","sessionID":"session_1","fields":[{"key":"choice","type":"string","options":[{"label":"Visible option","value":"internal-option"}]},{"key":"enabled","type":"boolean"},{"key":"count","type":"integer"},{"key":"ratio","type":"number"},{"key":"tags","type":"multiselect","options":[{"label":"Tag A","value":"a"},{"label":"Tag B","value":"b"}]}]}]}""")
                "/api/session/session_1/form/form_1/reply" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals(json.parseToJsonElement("""{"answer":{"choice":"internal-option","enabled":true,"count":3,"ratio":1.5,"tags":["a","b"]}}"""), request.jsonBody(json))
                    FixtureResponse()
                }
                else -> error("Unexpected route")
            }
        }
        val client = fixtureClient(engine, json)
        try {
            assertTrue(OpenCodeV2Api(client, json).replyQuestion(conn, "form_1",
                listOf(listOf("Visible option"), listOf("true"), listOf("3"), listOf("1.5"), listOf("Tag A", "Tag B")), null))
            assertEquals(2, engine.requests.size)
        } finally { client.close() }
    }

    @Test fun `PTY creation sends scoped body and decodes data envelope`() = runBlocking {
        val engine = FixtureEngine { request ->
            assertEquals("/api/pty", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/workspace", request.url.parameters["location[directory]"])
            assertEquals(json.parseToJsonElement("""{"title":"Terminal","cwd":"/workspace"}"""), request.jsonBody(json))
            FixtureResponse("""{"location":{"directory":"/workspace"},"data":{"id":"pty_1","title":"Terminal","command":"bash","args":[],"cwd":"/workspace","status":"running","pid":42}}""")
        }
        val client = fixtureClient(engine, json)
        try {
            val result = OpenCodeV2Api(client, json).createPty(conn, "Terminal", "/workspace", "/workspace")
            assertEquals("pty_1", result.id)
            assertEquals("running", result.status)
            assertEquals(42, result.pid)
        } finally { client.close() }
    }
}
