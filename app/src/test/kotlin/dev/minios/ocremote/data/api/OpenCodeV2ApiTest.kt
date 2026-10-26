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
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import io.ktor.utils.io.core.readBytes
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** In-process HTTP transport: exercises Ktor request serialization without sockets. */
internal data class FixtureResponse(
    val body: String = "{}", val status: Int = 200,
    val headers: Headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

@OptIn(io.ktor.util.InternalAPI::class)
internal class FixtureEngine(private val handler: suspend (HttpRequestData) -> FixtureResponse) : HttpClientEngineBase("fixture") {
    override val config = HttpClientEngineConfig()
    val requests = mutableListOf<HttpRequestData>()

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        requests += data
        val response = handler(data)
        return HttpResponseData(HttpStatusCode.fromValue(response.status), GMTDate(),
            response.headers, HttpProtocolVersion.HTTP_1_1,
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

    @Test fun `upload streams arbitrary binary content to write endpoint`() = runBlocking {
        val bytes = ByteArray(150_000) { (it % 251).toByte() }
        val engine = FixtureEngine { request ->
            assertEquals("/api/experimental/fs/write", request.url.encodedPath)
            assertEquals("/tmp/new.apk", request.url.parameters["path"])
            coroutineScope {
                val channel = io.ktor.utils.io.ByteChannel(autoFlush = true)
                val writer = launch {
                    try { (request.body as OutgoingContent.WriteChannelContent).writeTo(channel) }
                    finally { channel.close(null) }
                }
                assertArrayEquals(bytes, channel.readRemaining().readBytes())
                writer.join()
            }
            FixtureResponse("""{"data":{"path":"/tmp/new.apk"}}""")
        }
        val client = fixtureClient(engine, json)
        try {
            var progress = 0L
            bytes.inputStream().use { input ->
                OpenCodeV2Api(client, json).uploadFile(conn, "/tmp/new.apk", "/repo", input) { progress = it }
            }
            assertEquals(bytes.size.toLong(), progress)
        } finally { client.close() }
    }

    @Test fun `download streams raw bytes and reports progress`() = runBlocking {
        val body = "download-data".repeat(100_000)
        val engine = FixtureEngine { request ->
            assertEquals("/api/fs/read/app.apk", request.url.encodedPath)
            assertEquals("/tmp", request.headers["x-opencode-directory"])
            FixtureResponse(body, headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"))
        }
        val client = fixtureClient(engine, json)
        try {
            val output = java.io.ByteArrayOutputStream()
            var progress = 0L
            val mime = OpenCodeV2Api(client, json).downloadFile(conn, "/tmp/app.apk", "/repo", output) { bytes, _ -> progress = bytes }
            assertEquals(body, output.toString("UTF-8"))
            assertEquals(body.length.toLong(), progress)
            assertEquals("application/octet-stream", mime)
        } finally { client.close() }
    }

    @Test fun `write sends raw bytes and explicit target path`() = runBlocking {
        val bytes = "saved text".toByteArray()
        val engine = FixtureEngine { request ->
            assertEquals("/api/experimental/fs/write", request.url.encodedPath)
            assertEquals("new.txt", request.url.parameters["path"])
            assertEquals("/repo", request.headers["x-opencode-directory"])
            assertArrayEquals(bytes, (request.body as OutgoingContent.ByteArrayContent).bytes())
            FixtureResponse("""{"data":{"path":"/repo/new.txt"}}""")
        }
        val client = fixtureClient(engine, json)
        try { OpenCodeV2Api(client, json).writeFile(conn, "new.txt", "/repo", bytes) }
        finally { client.close() }
    }

    @Test fun `filesystem find accepts entry objects from released v2 API`() = runBlocking {
        val engine = FixtureEngine {
            FixtureResponse("""{"data":[{"path":"src/main.kt","type":"file"},{"path":"docs/","type":"directory"}]}""")
        }
        val client = fixtureClient(engine, json)
        try {
            assertEquals(listOf("src/main.kt", "docs/"), OpenCodeV2Api(client, json).findFiles(conn, "", null, "/repo", 20))
        } finally { client.close() }
    }

    @Test fun `outside project file reads use parent location and encoded basename`() = runBlocking {
        val engine = FixtureEngine { request ->
            assertEquals("/api/fs/read/report%20one.txt", request.url.encodedPath)
            assertEquals("/tmp", request.headers["x-opencode-directory"])
            assertEquals(conn.authHeader, request.headers[HttpHeaders.Authorization])
            FixtureResponse("report", headers = headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val client = fixtureClient(engine, json)
        try {
            assertEquals("report", OpenCodeV2Api(client, json).readFile(conn, "/tmp/report one.txt", "/repo").content)
        } finally { client.close() }
    }

    @Test fun `project catalogs have independent locks cache entries and location headers`() = runBlocking {
        val blocked = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val engine = FixtureEngine { request ->
            val directory = request.headers["x-opencode-directory"]
            assertNull(request.url.parameters["location[directory]"])
            when (request.url.encodedPath) {
                "/api/provider" -> {
                    if (directory == "/one") { blocked.complete(Unit); release.await() }
                    FixtureResponse("""{"data":[{"id":"p","name":"Provider","activation":"enabled"}]}""")
                }
                "/api/model" -> FixtureResponse("""{"data":[{"id":"${directory?.removePrefix("/")}","providerID":"p","name":"Model","enabled":true}]}""")
                "/api/model/default" -> FixtureResponse("""{"data":null}""")
                "/api/session/s/inbox" -> FixtureResponse("""{"data":[{"id":"pending"}]}""")
                else -> error("Unexpected route")
            }
        }
        val client = fixtureClient(engine, json)
        try {
            val api = OpenCodeV2Api(client, json)
            val one = async { api.providers(conn, "/one") }
            blocked.await()
            val two = kotlinx.coroutines.withTimeout(2_000) { api.providers(conn, "/two") }
            assertEquals(setOf("two"), two.all.single().models.keys)
            assertSame(two, api.providers(conn, "/two"))
            assertEquals("pending", kotlinx.coroutines.withTimeout(2_000) { api.inbox(conn, "s") }.single().jsonObject["id"]!!.jsonPrimitive.content)
            release.complete(Unit)
            assertEquals(setOf("one"), one.await().all.single().models.keys)
        } finally { release.complete(Unit); client.close() }
    }

    @Test fun `config and agents use project context and model variant is separate`() = runBlocking {
        val engine = FixtureEngine { request ->
            assertEquals(directoryHeaderValue("/project/ação"), request.headers["x-opencode-directory"])
            when (request.url.encodedPath) {
                "/api/config" -> FixtureResponse("""[{"info":{"model":{"providerID":"p","model":"model/with/slash","variant":"fast"}}}]""")
                "/api/provider" -> FixtureResponse("""{"data":[]}""")
                "/api/agent" -> FixtureResponse("""{"data":[]}""")
                "/api/integration" -> FixtureResponse("""{"data":[]}""")
                else -> error("Unexpected route")
            }
        }
        val client = fixtureClient(engine, json)
        try {
            val api = OpenCodeV2Api(client, json)
            val config = api.config(conn, "/project/ação")
            assertEquals("p/model/with/slash", config.model)
            assertEquals("fast", config.modelVariant)
            assertTrue(api.agents(conn, "/project/ação").isEmpty())
            assertTrue(api.authMethods(conn, "/project/ação").isEmpty())
        } finally { client.close() }
    }

    @Test fun `file previews reject oversized chunked bodies and accept exact byte limit`() = runBlocking {
        for (size in listOf(8 * 1024 * 1024, 8 * 1024 * 1024 + 1)) {
            val engine = FixtureEngine {
                FixtureResponse("a".repeat(size), headers = headersOf(
                    HttpHeaders.ContentType to listOf("text/plain"),
                    HttpHeaders.TransferEncoding to listOf("chunked"),
                ))
            }
            val client = fixtureClient(engine, json)
            try {
                val result = runCatching { OpenCodeV2Api(client, json).readFile(conn, "large.txt", null) }
                if (size == 8 * 1024 * 1024) assertEquals(size, result.getOrThrow().content.length)
                else assertTrue(result.exceptionOrNull() is V2ResponseLimitException)
            } finally { client.close() }
        }
    }

    @Test fun `declared oversized preview fails before body consumption`() = runBlocking {
        val engine = FixtureEngine {
            FixtureResponse("small", headers = headersOf(HttpHeaders.ContentLength, "${8L * 1024 * 1024 + 1}"))
        }
        val client = fixtureClient(engine, json)
        try {
            assertTrue(runCatching { OpenCodeV2Api(client, json).readFile(conn, "large", null) }
                .exceptionOrNull() is V2ResponseLimitException)
        } finally { client.close() }
    }

    @Test fun `optional empty form fields are omitted including implicit optional fields`() {
        val form = json.parseToJsonElement("""{"fields":[{"key":"text","type":"string"},{"key":"flag","type":"boolean","required":false},{"key":"number","type":"number","required":false},{"key":"tags","type":"multiselect","required":false},{"key":"required","type":"string","required":true}]}""").jsonObject
        assertEquals(json.parseToJsonElement("""{"required":"value"}"""),
            v2FormAnswer(form, listOf(listOf(""), emptyList(), emptyList(), emptyList(), listOf("value"))))
    }

    @Test fun `shared provider integration cannot silently delete credentials`() = runBlocking {
        val engine = FixtureEngine { request ->
            when (request.url.encodedPath) {
                "/api/provider/p" -> FixtureResponse("""{"data":{"integrationID":"shared"}}""")
                "/api/integration/shared" -> FixtureResponse("""{"data":{"id":"shared"}}""")
                "/api/provider" -> FixtureResponse("""{"data":[{"id":"p","integrationID":"shared"},{"id":"other","integrationID":"shared"}]}""")
                else -> error("Must not access or delete shared credentials")
            }
        }
        val client = fixtureClient(engine, json)
        try {
            val error = runCatching { OpenCodeV2Api(client, json).removeAuth(conn, "p") }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertTrue(error!!.message!!.contains("shared by multiple providers"))
            assertTrue(engine.requests.none { it.method == HttpMethod.Delete })
        } finally { client.close() }
    }

    @Test fun `paths use directory as home fallback`() = runBlocking {
        val engine = FixtureEngine { FixtureResponse("""{"directory":"/workspace","project":{"canonical":"/repo"}}""") }
        val client = fixtureClient(engine, json)
        try {
            val paths = OpenCodeV2Api(client, json).paths(conn)
            assertEquals("/workspace", paths.home)
            assertEquals("/repo", paths.worktree)
        } finally { client.close() }
    }

    @Test fun `first message page sets descending order without cursor`() = runBlocking {
        val engine = FixtureEngine { request ->
            assertEquals("desc", request.url.parameters["order"])
            assertNull(request.url.parameters["cursor"])
            FixtureResponse("""{"data":[],"cursor":{}}""")
        }
        val client = fixtureClient(engine, json)
        try { assertNull(OpenCodeV2Api(client, json).messagesPage(conn, "s", 10, null).nextCursor) }
        finally { client.close() }
    }

    @Test fun `chunked history rejects oversized ordinary text before sanitizer allocation`() = runBlocking {
        for (type in listOf("user", "assistant")) {
            val content = if (type == "user") "\"text\":\"${"x".repeat(257)}\""
                else "\"content\":[{\"type\":\"text\",\"text\":\"${"x".repeat(257)}\"}]"
            val engine = FixtureEngine {
                FixtureResponse("""{"data":[{"id":"m","sessionID":"s","type":"$type",$content}]}""",
                    headers = headersOf(HttpHeaders.TransferEncoding, "chunked"))
            }
            val client = fixtureClient(engine, json)
            try {
                val api = OpenCodeV2Api(client, json, historyLimits = V2ResponseLimits(maxLiteralChars = 256))
                assertTrue(runCatching { api.messagesPage(conn, "s", 1, null, maxResponseBytes = 64) }
                    .exceptionOrNull() is V2ResponseLimitException)
            } finally { client.close() }
        }
    }

    @Test fun `history bounds retained tree after transformation even with individually small strings`() = runBlocking {
        val engine = FixtureEngine {
            FixtureResponse("""{"data":[{"id":"m","sessionID":"s","type":"user","text":"${"x".repeat(100)}","metadata":{"extra":"${"y".repeat(100)}"}}]}""")
        }
        val client = fixtureClient(engine, json)
        try {
            val api = OpenCodeV2Api(client, json, historyLimits = V2ResponseLimits(maxRetainedChars = 160))
            assertTrue(runCatching { api.messagesPage(conn, "s", 1, null) }
                .exceptionOrNull() is V2ResponseLimitException)
        } finally { client.close() }
    }

    @Test fun `oversized v2 tool content is omitted without losing assistant text or cursor`() = runBlocking {
        val engine = FixtureEngine {
            FixtureResponse("""{"data":[{"id":"m","sessionID":"s","type":"assistant","content":[{"type":"text","text":"kept"},{"type":"tool","id":"call","name":"read","state":{"status":"completed","input":{},"content":[{"type":"text","text":"${"x".repeat(512)}"}]}}]}],"cursor":{"next":"older"}}""")
        }
        val client = fixtureClient(engine, json)
        try {
            val api = OpenCodeV2Api(client, json, historyLimits = V2ResponseLimits(maxRetainedChars = 512))
            val page = api.messagesPage(conn, "s", 1, null, maxResponseBytes = 128)
            assertEquals("older", page.nextCursor)
            val parts = page.messages.single().parts
            assertEquals("kept", (parts.first() as dev.minios.ocremote.domain.model.Part.Text).text)
            val tool = parts.last() as dev.minios.ocremote.domain.model.Part.Tool
            assertEquals("", (tool.state as dev.minios.ocremote.domain.model.ToolState.Completed).output)
        } finally { client.close() }
    }

    @Test fun `inline base64 history is bounded before image conversion`() = runBlocking {
        val engine = FixtureEngine {
            FixtureResponse("""{"data":[{"id":"m","sessionID":"s","type":"user","files":[{"mime":"image/png","data":"${"A".repeat(257)}"}]}]}""")
        }
        val client = fixtureClient(engine, json)
        try {
            val api = OpenCodeV2Api(client, json, historyLimits = V2ResponseLimits(maxLiteralChars = 256))
            assertTrue(runCatching { api.messagesPage(conn, "s", 1, null) }
                .exceptionOrNull() is V2ResponseLimitException)
        } finally { client.close() }
    }

    @Test fun `history disk cap rejects declared and chunked bodies but not streaming export`() = runBlocking {
        for (headers in listOf(headersOf(HttpHeaders.ContentLength, "65"),
            headersOf(HttpHeaders.TransferEncoding, "chunked"))) {
            val engine = FixtureEngine { FixtureResponse(" ".repeat(65), headers = headers) }
            val client = fixtureClient(engine, json)
            try {
                val api = OpenCodeV2Api(client, json, maxHistoryBytes = 64)
                assertTrue(runCatching { api.messagesPage(conn, "s", 1, null) }
                    .exceptionOrNull() is V2ResponseLimitException)
            } finally { client.close() }
        }
        val engine = FixtureEngine { FixtureResponse("""{"data":{"text":"${"x".repeat(128)}"}}""") }
        val client = fixtureClient(engine, json)
        try {
            val output = java.io.ByteArrayOutputStream()
            OpenCodeV2Api(client, json, maxHistoryBytes = 64).exportToStream(conn, "s", output) {}
            assertEquals(128, json.parseToJsonElement(output.toString("UTF-8")).jsonObject["text"]!!.jsonPrimitive.content.length)
        } finally { client.close() }
    }

    @Test fun `history accepts exact disk limit`() = runBlocking {
        val engine = FixtureEngine { FixtureResponse("""{"data":[]}""".padEnd(64)) }
        val client = fixtureClient(engine, json)
        try {
            assertTrue(OpenCodeV2Api(client, json, maxHistoryBytes = 64)
                .messagesPage(conn, "s", 1, null).messages.isEmpty())
        } finally { client.close() }
    }

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

    @Test fun `session pagination sends filters only on first page and follows next cursor`() = runBlocking {
        val engine = FixtureEngine { request ->
            assertEquals("/api/session", request.url.encodedPath)
            if (request.url.parameters["cursor"] == null) {
                assertEquals("null", request.url.parameters["parentID"])
                assertEquals("/workspace", request.url.parameters["directory"])
                assertEquals("desc", request.url.parameters["order"])
            } else {
                assertEquals(setOf("cursor", "limit"), request.url.parameters.names())
            }
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
            assertNull(request.url.parameters["order"])
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
                    assertEquals(directoryHeaderValue("/workspace"), request.headers["x-opencode-directory"])
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
            assertEquals(directoryHeaderValue("/workspace"), request.headers["x-opencode-directory"])
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
