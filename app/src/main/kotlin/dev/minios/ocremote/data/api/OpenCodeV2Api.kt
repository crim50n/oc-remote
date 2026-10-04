package dev.minios.ocremote.data.api

import dev.minios.ocremote.domain.model.*
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.InputStream
import java.io.FilterInputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import dev.minios.ocremote.logging.AppLogger as Log

/** Adapter for the released @opencode/client 2.0.22 HTTP contract. */
internal class OpenCodeV2Api(private val client: HttpClient, private val json: Json,
    private val imageCache: MessageImageCache? = null,
) {
    private val forms = ConcurrentHashMap<Pair<ServerConnection, String>, JsonObject>()
    private val oauthAttempts = ConcurrentHashMap<Pair<ServerConnection, String>, Pair<String, String>>()
    private val payloadMutex = Mutex()
    private data class CatalogEntry(val value: ProviderCatalogResponse, val expiresAt: Long)
    private val catalogCache = ConcurrentHashMap<ServerConnection, CatalogEntry>()
    private val catalogLocks = ConcurrentHashMap<ServerConnection, Mutex>()

    suspend fun request(
        conn: ServerConnection, path: String, method: HttpMethod = HttpMethod.Get,
        body: JsonElement? = null, directory: String? = null,
        query: Map<String, String?> = emptyMap(), headers: Map<String, String> = emptyMap(),
    ): HttpResponse {
        val response = client.request("${conn.baseUrl}$path") {
            this.method = method
            conn.authHeader?.let { header(HttpHeaders.Authorization, it) }
            directory?.let { parameter("location[directory]", it) }
            query.forEach { (key, value) -> value?.let { parameter(key, it) } }
            headers.forEach { (key, value) -> header(key, value) }
            body?.let { contentType(ContentType.Application.Json); setBody(it) }
        }
        verifyResponse(response)
        return response
    }

    private fun verifyResponse(response: HttpResponse) {
        if (response.status.value in listOf(401, 403)) throw ServerAuthenticationException(response.status.value)
        if (!response.status.isSuccess()) throw OpenCodeHttpException(response.status.value)
    }

    private suspend fun copyResponse(response: HttpResponse, output: java.io.OutputStream, onProgress: (Long) -> Unit = {}) {
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var written = 0L
        while (!channel.isClosedForRead) {
            val count = channel.readAvailable(buffer)
            if (count < 0) break
            if (count > 0) {
                output.write(buffer, 0, count)
                written += count
                onProgress(written)
            }
        }
        output.flush()
    }

    suspend fun exportToStream(conn: ServerConnection, sessionId: String, output: java.io.OutputStream, onProgress: (Long) -> Unit) {
        client.prepareGet("${conn.baseUrl}/api/experimental/session/${id(sessionId)}/export") {
            conn.authHeader?.let { header(HttpHeaders.Authorization, it) }
        }.execute { response ->
            verifyResponse(response)
            withContext(Dispatchers.IO) {
                var written = 0L
                val progressOutput = object : java.io.FilterOutputStream(output) {
                    override fun write(value: Int) {
                        out.write(value); written++; onProgress(written)
                    }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        out.write(bytes, offset, length); written += length; onProgress(written)
                    }
                }
                InputStreamReader(response.bodyAsChannel().toInputStream(), Charsets.UTF_8).use { input ->
                    val writer = OutputStreamWriter(progressOutput, Charsets.UTF_8)
                    unwrapV2DataJson(input, writer)
                    writer.flush()
                }
            }
        }
    }

    suspend fun payload(conn: ServerConnection, path: String, method: HttpMethod = HttpMethod.Get,
        body: JsonElement? = null, directory: String? = null, query: Map<String, String?> = emptyMap(),
    ): JsonElement = payloadMutex.withLock {
        client.prepareRequest("${conn.baseUrl}$path") {
            this.method = method
            conn.authHeader?.let { header(HttpHeaders.Authorization, it) }
            directory?.let { parameter("location[directory]", it) }
            query.forEach { (key, value) -> value?.let { parameter(key, it) } }
            body?.let { contentType(ContentType.Application.Json); setBody(it) }
        }.execute { response ->
            verifyResponse(response)
            val resource = path.removePrefix("/api/").substringBefore('/')
            Log.d("OpenCodeApi", "V2 response resource=$resource bytes=${response.contentLength() ?: -1}")
            val maxBytes = if (isProjectedV2Endpoint(path)) 64L * 1024 * 1024 else 8L * 1024 * 1024
            if ((response.contentLength() ?: 0L) > maxBytes) {
                throw V2ResponseLimitException()
            }
            withContext(Dispatchers.IO) {
                val input = V2LimitedInputStream(response.bodyAsChannel().toInputStream(), maxBytes)
                InputStreamReader(input, Charsets.UTF_8).use { readV2Response(json, it, path) }
            }
        }
    }

    private fun JsonElement.data(): JsonElement = jsonObject["data"] ?: error("Missing OpenCode v2 response data")
    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun id(value: String) = value.encodeURLPathPart()

    suspend fun health(conn: ServerConnection): ServerHealth {
        val info = payload(conn, "/api/info").jsonObject
        return ServerHealth(healthy = true, version = info.str("version"))
    }

    suspend fun paths(conn: ServerConnection): ServerPaths {
        val location = payload(conn, "/api/location").jsonObject
        val directory = location.str("directory")
        return ServerPaths(worktree = location["project"]?.jsonObject?.str("canonical").orEmpty(), directory = directory)
    }

    suspend fun projects(conn: ServerConnection): List<Project> =
        payload(conn, "/api/project").jsonArray.map { V2Protocol.project(json, it.jsonObject) }

    suspend fun currentProject(conn: ServerConnection): Project {
        val location = payload(conn, "/api/location").jsonObject
        val projectId = location["project"]!!.jsonObject.str("id")
        return projects(conn).firstOrNull { it.id == projectId } ?: error("Current OpenCode project is unavailable")
    }

    suspend fun agents(conn: ServerConnection): List<AgentInfo> =
        payload(conn, "/api/agent").data().jsonArray.map { V2Protocol.agent(json, it.jsonObject) }

    suspend fun sessions(conn: ServerConnection, directory: String? = null, parentId: String? = "null"): List<Session> {
        val result = mutableListOf<Session>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = payload(conn, "/api/session", query = mapOf(
                "directory" to directory, "parentID" to parentId, "limit" to "100", "order" to "desc", "cursor" to cursor,
            )).jsonObject
            result += page.data().jsonArray.map { V2Protocol.session(json, it.jsonObject) }
            cursor = page["cursor"]?.jsonObject?.get("next")?.jsonPrimitive?.contentOrNull
            check(cursor == null || cursors.add(cursor)) { "Repeated OpenCode session pagination cursor" }
        } while (cursor != null)
        return result.distinctBy { it.id }
    }

    suspend fun statuses(conn: ServerConnection): Map<String, SessionStatus> =
        payload(conn, "/api/session/active").data().jsonObject.mapValues { V2Protocol.sessionStatus(it.value.jsonObject) }

    suspend fun session(conn: ServerConnection, sessionId: String): Session =
        V2Protocol.session(json, payload(conn, "/api/session/${id(sessionId)}").data().jsonObject)

    suspend fun createSession(conn: ServerConnection, title: String?, parentId: String?, directory: String?): Session {
        val body = buildJsonObject {
            title?.let { put("title", it) }; parentId?.let { put("parentID", it) }
            directory?.let { putJsonObject("location") { put("directory", it) } }
        }
        return V2Protocol.session(json, payload(conn, "/api/session", HttpMethod.Post, body).data().jsonObject)
    }

    suspend fun updateSession(conn: ServerConnection, sessionId: String, title: String): Session {
        request(conn, "/api/session/${id(sessionId)}", HttpMethod.Patch, buildJsonObject { put("title", title) })
        return session(conn, sessionId)
    }

    suspend fun action(conn: ServerConnection, sessionId: String, action: String, body: JsonObject = JsonObject(emptyMap())): Boolean {
        request(conn, "/api/session/${id(sessionId)}/$action", HttpMethod.Post, body)
        return true
    }

    suspend fun diffs(conn: ServerConnection, sessionId: String): List<FileDiff> =
        json.decodeFromJsonElement(payload(conn, "/api/session/${id(sessionId)}/diff").data())

    suspend fun revert(conn: ServerConnection, sessionId: String, messageId: String): Session {
        action(conn, sessionId, "revert/stage", buildJsonObject { put("messageID", messageId) })
        return session(conn, sessionId)
    }

    suspend fun unrevert(conn: ServerConnection, sessionId: String): Session {
        request(conn, "/api/session/${id(sessionId)}/revert", HttpMethod.Delete)
        return session(conn, sessionId)
    }

    suspend fun fork(conn: ServerConnection, sessionId: String, messageId: String?): Session =
        V2Protocol.session(json, payload(conn, "/api/session/${id(sessionId)}/fork", HttpMethod.Post,
            buildJsonObject { messageId?.let { put("before", it) } }).data().jsonObject)

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun messagePagePayload(conn: ServerConnection, sessionId: String, limit: Int, before: String?,
        maxResponseBytes: Long,
    ): JsonObject = client.prepareGet("${conn.baseUrl}/api/session/${id(sessionId)}/message") {
        conn.authHeader?.let { header(HttpHeaders.Authorization, it) }
        parameter("limit", limit); parameter("order", "desc")
        before?.let { parameter("cursor", it) }
    }.execute { response ->
        verifyResponse(response)
        if ((response.contentLength() ?: 0) > maxResponseBytes && limit > 1) {
            response.bodyAsChannel().cancel(null)
            return@execute messagePagePayload(conn, sessionId, (limit / 2).coerceAtLeast(1), before, maxResponseBytes)
        }
        val raw = withContext(Dispatchers.IO) { File.createTempFile("oc-v2-messages-", ".json") }
        var transformed: File? = null
        try {
            withContext(Dispatchers.IO) {
                FileOutputStream(raw).use { output -> copyResponse(response, output) }
                val safe = File.createTempFile("oc-v2-safe-messages-", ".json")
                transformed = safe
                InputStreamReader(FileInputStream(raw), Charsets.UTF_8).use { input ->
                    OutputStreamWriter(FileOutputStream(safe), Charsets.UTF_8).use { output ->
                        transformMessageJson(input, output, omitPayloadFields = raw.length() > maxResponseBytes,
                            preserveEnvelopeData = true)
                    }
                }
                FileInputStream(safe).use { json.decodeFromStream<JsonObject>(it) }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { raw.delete(); transformed?.delete() }
        }
    }

    suspend fun messagesPage(conn: ServerConnection, sessionId: String, limit: Int?, before: String?,
        maxResponseBytes: Long = 32L * 1024 * 1024,
    ): MessagePage {
        val page = messagePagePayload(conn, sessionId, limit ?: 100, before, maxResponseBytes)
        var parentId = ""
        val messages = page.data().jsonArray.map { it.jsonObject }
            .sortedWith(compareBy({ it["time"]?.jsonObject?.get("created")?.jsonPrimitive?.longOrNull ?: 0 }, { it.str("id") }))
            .mapNotNull { value ->
                if (value.str("type") == "user") parentId = value.str("id")
                V2Protocol.message(json, value, sessionId, parentId)?.let { message ->
                    message.copy(parts = message.parts.map { part ->
                        if (part is Part.File && part.url != null && imageCache != null) {
                            part.copy(url = imageCache.cacheDataUrl(part.url) ?: part.url)
                        } else part
                    })
                }
            }
            .sortedWith(compareBy({ it.info.time.created }, { it.info.id }))
        return MessagePage(messages, page["cursor"]?.jsonObject?.get("next")?.jsonPrimitive?.contentOrNull)
    }

    suspend fun message(conn: ServerConnection, sessionId: String, messageId: String): MessageWithParts =
        V2Protocol.message(json, payload(conn, "/api/session/${id(sessionId)}/message/${id(messageId)}").data().jsonObject, sessionId)
            ?: error("This OpenCode message has no chat content")

    suspend fun export(conn: ServerConnection, sessionId: String): String =
        payload(conn, "/api/experimental/session/${id(sessionId)}/export").data().toString()

    suspend fun prompt(conn: ServerConnection, sessionId: String, messageId: String, parts: List<PromptPart>,
        model: ModelSelection?, agent: String?, variant: String?,
    ) {
        agent?.let { action(conn, sessionId, "agent", buildJsonObject { put("agent", it) }) }
        model?.let { switchModel(conn, sessionId, it, variant) }
        action(conn, sessionId, "prompt", v2PromptBody(messageId, parts))
    }

    suspend fun switchModel(conn: ServerConnection, sessionId: String, model: ModelSelection, variant: String?) {
        action(conn, sessionId, "model", buildJsonObject {
            putJsonObject("model") { put("providerID", model.providerId); put("id", model.modelId); variant?.let { put("variant", it) } }
        })
    }

    suspend fun permissions(conn: ServerConnection, directory: String?): List<PermissionRequest> =
        payload(conn, "/api/permission/request", directory = directory).data().jsonArray.map { V2Protocol.permission(json, it.jsonObject) }

    suspend fun replyPermission(conn: ServerConnection, requestId: String, reply: String, message: String?, directory: String?): Boolean {
        val pending = permissions(conn, directory).firstOrNull { it.id == requestId } ?: error("Permission request is no longer pending")
        action(conn, pending.sessionId, "permission/${id(requestId)}/reply", buildJsonObject {
            put("decision", reply); message?.let { put("message", it) }
        })
        return true
    }

    suspend fun questions(conn: ServerConnection, directory: String?): List<QuestionRequest> =
        payload(conn, "/api/form", directory = directory).data().jsonArray.mapNotNull {
            val form = it.jsonObject
            forms[conn to form.str("id")] = form
            V2Protocol.question(json, form)
        }

    private suspend fun pendingForm(conn: ServerConnection, requestId: String, directory: String?): JsonObject {
        // An SSE form may not have been reconciled through REST yet.
        forms.remove(conn to requestId)
        questions(conn, directory)
        return forms[conn to requestId] ?: error("OpenCode form is no longer pending")
    }

    suspend fun replyQuestion(conn: ServerConnection, requestId: String, answers: List<List<String>>, directory: String?): Boolean {
        val form = pendingForm(conn, requestId, directory)
        val answer = v2FormAnswer(form, answers)
        action(conn, form.str("sessionID"), "form/${id(requestId)}/reply", buildJsonObject { put("answer", answer) })
        forms.remove(conn to requestId)
        return true
    }

    suspend fun rejectQuestion(conn: ServerConnection, requestId: String, directory: String?): Boolean {
        val form = pendingForm(conn, requestId, directory)
        request(conn, "/api/session/${id(form.str("sessionID"))}/form/${id(requestId)}", HttpMethod.Delete)
        forms.remove(conn to requestId)
        return true
    }

    suspend fun providers(conn: ServerConnection): ProviderCatalogResponse =
        catalogLocks.getOrPut(conn) { Mutex() }.withLock {
            catalogCache[conn]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let { return@withLock it.value }
            val catalog = loadProviders(conn)
            // Share model instances between the chat and settings consumers of the same catalog.
            catalogCache.entries.removeIf { it.value.expiresAt <= System.currentTimeMillis() }
            catalogCache[conn] = CatalogEntry(catalog, System.currentTimeMillis() + 30_000L)
            catalog
        }

    private suspend fun loadProviders(conn: ServerConnection): ProviderCatalogResponse {
        val providers = payload(conn, "/api/provider").data().jsonArray
        val models = payload(conn, "/api/model").data().jsonArray
        val catalog = V2Protocol.providers(json, providers, models)
        val default = payload(conn, "/api/model/default").data() as? JsonObject
        return if (default == null) catalog else catalog.copy(default = mapOf(default.str("providerID") to default.str("id")))
    }

    private suspend fun <T> catalogMutation(conn: ServerConnection, operation: suspend () -> T): T =
        catalogLocks.getOrPut(conn) { Mutex() }.withLock {
            try {
                operation()
            } finally {
                catalogCache.remove(conn)
            }
        }

    suspend fun mcp(conn: ServerConnection): Map<String, McpStatus> =
        payload(conn, "/api/mcp").data().jsonArray.associate {
            val server = it.jsonObject
            server.str("name") to json.decodeFromJsonElement<McpStatus>(server["status"]!!)
        }

    suspend fun updateMcp(conn: ServerConnection, name: String, connect: Boolean): Boolean {
        request(conn, "/api/experimental/mcp/${id(name)}/${if (connect) "connect" else "disconnect"}", HttpMethod.Post)
        return true
    }

    private suspend fun integration(conn: ServerConnection, providerId: String): JsonObject {
        val provider = payload(conn, "/api/provider/${id(providerId)}").data().jsonObject
        val integrationId = provider.str("integrationID").ifBlank { providerId }
        return payload(conn, "/api/integration/${id(integrationId)}").data().jsonObject
    }

    suspend fun authMethods(conn: ServerConnection): Map<String, List<ProviderAuthMethod>> {
        val integrations = payload(conn, "/api/integration").data().jsonArray.associateBy { it.jsonObject.str("id") }
        return payload(conn, "/api/provider").data().jsonArray.associate {
            val provider = it.jsonObject
            val methods = integrations[provider.str("integrationID")]?.jsonObject?.get("methods") as? JsonArray
            provider.str("id") to methods.orEmpty().mapNotNull { method ->
                val obj = method.jsonObject
                when (obj.str("type")) {
                    "oauth" -> ProviderAuthMethod("oauth", obj.str("label"))
                    "key" -> ProviderAuthMethod("api", obj.str("label").ifBlank { "API key" })
                    else -> null
                }
            }
        }
    }

    suspend fun setApiKey(conn: ServerConnection, providerId: String, key: String): Boolean = catalogMutation(conn) {
        val integration = integration(conn, providerId)
        request(conn, "/api/integration/${id(integration.str("id"))}/connect/key", HttpMethod.Post,
            buildJsonObject { put("key", key) })
        true
    }

    suspend fun removeAuth(conn: ServerConnection, providerId: String): Boolean = catalogMutation(conn) {
        val integrationId = integration(conn, providerId).str("id")
        val credentials = payload(conn, "/api/credential").data().jsonArray
        for (credential in credentials) {
            val obj = credential.jsonObject
            if (obj.str("integrationID") == integrationId) request(conn, "/api/credential/${id(obj.str("id"))}", HttpMethod.Delete)
        }
        true
    }

    suspend fun authorizeOauth(conn: ServerConnection, providerId: String, methodIndex: Int): ProviderOauthAuthorization {
        val integration = integration(conn, providerId)
        val methods = integration["methods"]!!.jsonArray.filter { it.jsonObject.str("type") in listOf("oauth", "key") }
        val method = methods.getOrNull(methodIndex)?.jsonObject ?: error("OAuth method is unavailable")
        require(method.str("type") == "oauth") { "Selected method is not OAuth" }
        require((method["form"] as? JsonArray).isNullOrEmpty()) { "This OAuth method requires a form; connect it using the OpenCode web client" }
        val integrationId = integration.str("id")
        val attempt = payload(conn, "/api/integration/${id(integrationId)}/connect/oauth", HttpMethod.Post,
            buildJsonObject { put("methodID", method.str("id")) }).data().jsonObject
        oauthAttempts[conn to providerId] = integrationId to attempt.str("attemptID")
        return ProviderOauthAuthorization(url = attempt.str("url"),
            method = if (attempt.str("mode") == "code") "code" else "auto",
            instructions = attempt.str("instructions"))
    }

    suspend fun completeOauth(conn: ServerConnection, providerId: String, code: String?): Boolean = catalogMutation(conn) {
        val (integrationId, attemptId) = oauthAttempts[conn to providerId] ?: error("Start OAuth authorization again")
        val path = "/api/integration/${id(integrationId)}/connect/oauth/${id(attemptId)}"
        if (code != null) request(conn, "$path/complete", HttpMethod.Post, buildJsonObject { put("code", code) })
        val status = payload(conn, path).data().jsonObject
        status.str("status") == "complete"
    }

    suspend fun config(conn: ServerConnection): ServerConfigResponse {
        val entries = payload(conn, "/api/config").jsonArray
        val config = entries.fold(JsonObject(emptyMap())) { result, entry ->
            val info = entry.jsonObject["info"] as? JsonObject ?: JsonObject(emptyMap())
            JsonObject(result + info)
        }
        val providers = payload(conn, "/api/provider").data().jsonArray
        val disabled = providers.filter { it.jsonObject.str("activation") == "disabled" }.map { it.jsonObject.str("id") }
        val model = when (val ref = config["model"]) {
            is JsonPrimitive -> ref.contentOrNull
            is JsonObject -> "${ref.str("providerID")}/${ref.str("model")}" + ref.str("variant").takeIf { it.isNotBlank() }?.let { "#$it" }.orEmpty()
            else -> null
        }
        return ServerConfigResponse(disabledProviders = disabled, model = model, defaultAgent = config.str("default_agent").ifBlank { null }, readOnly = true)
    }

    suspend fun commands(conn: ServerConnection): List<CommandInfo> =
        json.decodeFromJsonElement(payload(conn, "/api/command").data())

    suspend fun findFiles(conn: ServerConnection, query: String, type: String?, directory: String?, limit: Int?): List<String> =
        json.decodeFromJsonElement(payload(conn, "/api/fs/find", directory = directory,
            query = mapOf("query" to query, "type" to type, "limit" to limit?.toString())).data())

    suspend fun readFile(conn: ServerConnection, path: String, directory: String?): FileContent {
        val encoded = path.split('/').joinToString("/") { id(it) }
        val response = request(conn, "/api/fs/read/$encoded", directory = directory)
        val mime = response.contentType()?.withoutParameters()?.toString() ?: "application/octet-stream"
        val bytes = response.body<ByteArray>()
        val text = mime.startsWith("text/") || mime in listOf("application/json", "application/xml", "application/javascript") ||
            (mime == "application/octet-stream" && bytes.none { it == 0.toByte() } && runCatching {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes))
            }.isSuccess)
        val specificMime = mime.takeUnless { it == "application/octet-stream" }
        return if (text) FileContent("text", bytes.toString(Charsets.UTF_8), mimeType = specificMime)
            else FileContent("binary", Base64.getEncoder().encodeToString(bytes), encoding = "base64", mimeType = specificMime)
    }

    suspend fun listDirectory(conn: ServerConnection, path: String, directory: String?): List<FileNode> =
        payload(conn, "/api/fs/list", directory = directory, query = mapOf("path" to path)).data().jsonArray.map {
            val obj = it.jsonObject
            val entryPath = obj.str("path")
            FileNode(name = entryPath.trimEnd('/').substringAfterLast('/'), path = entryPath, type = obj.str("type"))
        }

    suspend fun createPty(conn: ServerConnection, title: String?, cwd: String?, directory: String?): PtyInfo =
        json.decodeFromJsonElement(payload(conn, "/api/pty", HttpMethod.Post, buildJsonObject {
            title?.let { put("title", it) }; cwd?.let { put("cwd", it) }
        }, directory).data())

    suspend fun resizePty(conn: ServerConnection, ptyId: String, cols: Int, rows: Int, directory: String?): Boolean {
        request(conn, "/api/pty/${id(ptyId)}", HttpMethod.Put,
            buildJsonObject { putJsonObject("size") { put("cols", cols); put("rows", rows) } }, directory)
        return true
    }

    suspend fun ptySocket(conn: ServerConnection, ptyId: String, cursor: Int, directory: String?): PtySocket {
        val token = json.parseToJsonElement(request(conn, "/api/pty/${id(ptyId)}/connect-token", HttpMethod.Post,
            directory = directory, headers = mapOf("x-opencode-ticket" to "1")).bodyAsText()).data().jsonObject.str("ticket")
        require(token.isNotBlank()) { "OpenCode did not issue a PTY connection ticket" }
        val wsBase = conn.baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val socket = client.webSocketSession {
            url("$wsBase/api/pty/${id(ptyId)}/connect")
            conn.authHeader?.let { header(HttpHeaders.Authorization, it) }
            parameter("cursor", cursor); parameter("ticket", token)
            directory?.let { parameter("location[directory]", it) }
        }
        return PtySocket(socket)
    }
}

class OpenCodeHttpException(val statusCode: Int) : RuntimeException("OpenCode v2 request failed (HTTP $statusCode)")

internal class V2LimitedInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
    private var readBytes = 0L

    private fun account(count: Int) {
        if (count > 0) readBytes += count
        if (readBytes > maxBytes) throw V2ResponseLimitException()
    }

    override fun read(): Int = `in`.read().also { if (it >= 0) account(1) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val boundedLength = minOf(length.toLong(), (maxBytes - readBytes + 1).coerceAtLeast(1)).toInt()
        return `in`.read(buffer, offset, boundedLength).also(::account)
    }
}

internal fun v2PromptBody(messageId: String, parts: List<PromptPart>): JsonObject = buildJsonObject {
    put("id", messageId)
    put("text", parts.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n"))
    put("delivery", "steer"); put("resume", true)
    putJsonArray("files") {
        parts.filter { it.type == "file" }.forEach { part -> add(buildJsonObject {
            put("uri", part.url ?: part.path?.let { "file://$it" } ?: error("Attachment URI is missing"))
            part.filename?.let { put("name", it) }
        }) }
    }
    putJsonArray("agents") {
        parts.filter { it.type == "agent" }.forEach { part -> add(buildJsonObject {
            put("name", part.text ?: part.path ?: error("Agent attachment name is missing"))
        }) }
    }
    require(parts.all { it.type in listOf("text", "file", "agent") }) { "Unsupported OpenCode v2 prompt attachment" }
}

internal fun v2FormAnswer(form: JsonObject, answers: List<List<String>>): JsonObject = buildJsonObject {
    val fields = form["fields"]!!.jsonArray
    require(fields.size == answers.size) { "Form answer count does not match its fields" }
    fields.forEachIndexed { index, element ->
        val field = element.jsonObject
        val selected = answers[index].map { answer ->
            (field["options"] as? JsonArray)?.firstOrNull { it.jsonObject["label"]?.jsonPrimitive?.content == answer }
                ?.jsonObject?.get("value")?.jsonPrimitive?.content ?: answer
        }
        val key = field["key"]!!.jsonPrimitive.content
        val value: JsonElement = when (field["type"]!!.jsonPrimitive.content) {
            "multiselect" -> JsonArray(selected.map(::JsonPrimitive))
            "boolean" -> JsonPrimitive(selected.single().toBooleanStrict())
            "integer" -> JsonPrimitive(selected.single().toLong())
            "number" -> JsonPrimitive(selected.single().toDouble().also { require(it.isFinite()) })
            "string" -> JsonPrimitive(selected.singleOrNull().orEmpty())
            else -> error("This OpenCode form requires the web client")
        }
        put(key, value)
    }
}
