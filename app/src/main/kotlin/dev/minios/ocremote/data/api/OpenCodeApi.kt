package dev.minios.ocremote.data.api

import android.util.Log
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.domain.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.HttpMethod
import io.ktor.http.*
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds resolved connection info for a server.
 * Create one via [ServerConnection.from] and pass it to every API / SSE call.
 */
data class ServerConnection(
    val baseUrl: String,
    val authHeader: String?
) {
    companion object {
        fun from(url: String, username: String = "opencode", password: String? = null): ServerConnection {
            val base = url.trimEnd('/')
            val auth = if (password != null) {
                val credentials = "$username:$password"
                "Basic ${Base64.getEncoder().encodeToString(credentials.toByteArray())}"
            } else {
                null
            }
            return ServerConnection(base, auth)
        }
    }
}

/**
 * OpenCode REST API Client
 *
 * All methods take a [ServerConnection] so the client is stateless
 * and safe to use for multiple servers concurrently.
 */
@Singleton
class OpenCodeApi @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json
) {
    companion object {
        private const val TAG = "OpenCodeApi"
    }

    // ============ Global ============

    suspend fun getHealth(conn: ServerConnection): ServerHealth {
        return httpClient.get("${conn.baseUrl}/global/health") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Get server paths (home directory, worktree, etc.).
     * GET /path
     */
    suspend fun getServerPaths(conn: ServerConnection): ServerPaths {
        return httpClient.get("${conn.baseUrl}/path") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    // ============ Project ============

    suspend fun listProjects(conn: ServerConnection): List<Project> {
        return httpClient.get("${conn.baseUrl}/project") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun getCurrentProject(conn: ServerConnection): Project {
        return httpClient.get("${conn.baseUrl}/project/current") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    // ============ Agents ============

    /**
     * List available agents (build, plan, etc.).
     * GET /agent
     * Returns agents filtered to primary/visible ones for the mode selector.
     */
    suspend fun listAgents(conn: ServerConnection): List<AgentInfo> {
        return httpClient.get("${conn.baseUrl}/agent") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    // ============ Session ============

    suspend fun listSessions(conn: ServerConnection, directory: String? = null): List<Session> {
        return httpClient.get("${conn.baseUrl}/session") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
            parameter("roots", "true")
        }.body()
    }

    suspend fun getSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.get("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /** Returns session info as raw JSON string (for export without re-serialization). */
    suspend fun getSessionRaw(conn: ServerConnection, sessionId: String): String {
        return httpClient.get("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
        }.bodyAsText()
    }

    suspend fun createSession(conn: ServerConnection, title: String? = null, parentId: String? = null, directory: String? = null): Session {
        val body = buildMap<String, String> {
            title?.let { put("title", it) }
            parentId?.let { put("parentID", it) }
        }
        return httpClient.post("${conn.baseUrl}/session") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }

    suspend fun deleteSession(conn: ServerConnection, sessionId: String): Boolean {
        val response = httpClient.delete("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    suspend fun updateSession(conn: ServerConnection, sessionId: String, title: String): Session {
        return httpClient.patch("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("title" to title))
        }.body()
    }

    suspend fun abortSession(conn: ServerConnection, sessionId: String, directory: String? = null): Boolean {
        val response = httpClient.post("${conn.baseUrl}/session/$sessionId/abort") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
        }
        return response.status.isSuccess()
    }

    suspend fun getSessionDiff(conn: ServerConnection, sessionId: String): List<FileDiff> {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/diff") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Share a session, creating a shareable link.
     * POST /session/{sessionId}/share
     */
    suspend fun shareSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.post("${conn.baseUrl}/session/$sessionId/share") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Unshare a session, removing the shareable link.
     * DELETE /session/{sessionId}/share
     */
    suspend fun unshareSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.delete("${conn.baseUrl}/session/$sessionId/share") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Summarize (compact) a session to reduce context.
     * POST /session/{sessionId}/summarize
     */
    suspend fun summarizeSession(
        conn: ServerConnection,
        sessionId: String,
        providerId: String,
        modelId: String
    ): Boolean {
        val response = httpClient.post("${conn.baseUrl}/session/$sessionId/summarize") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("providerID" to providerId, "modelID" to modelId))
        }
        return response.status.isSuccess()
    }

    /**
     * Revert (undo) messages starting from the given messageId.
     * POST /session/{sessionId}/revert
     */
    suspend fun revertSession(conn: ServerConnection, sessionId: String, messageId: String): Session {
        return httpClient.post("${conn.baseUrl}/session/$sessionId/revert") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("messageID" to messageId))
        }.body()
    }

    /**
     * Unrevert (redo) the last reverted message in a session.
     * POST /session/{sessionId}/unrevert
     */
    suspend fun unrevertSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.post("${conn.baseUrl}/session/$sessionId/unrevert") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Fork a session (create a new session from a message point).
     * POST /session/{sessionId}/fork
     */
    suspend fun forkSession(conn: ServerConnection, sessionId: String, messageId: String? = null): Session {
        val body = buildMap<String, String> {
            messageId?.let { put("messageID", it) }
        }
        return httpClient.post("${conn.baseUrl}/session/$sessionId/fork") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }

    /**
     * Execute a server-side command in a session.
     * POST /session/{sessionId}/command
     * Body: { command: String, arguments: String }
     */
    suspend fun executeCommand(
        conn: ServerConnection,
        sessionId: String,
        command: String,
        arguments: String = "",
        directory: String? = null
    ): Boolean {
        val response = httpClient.post("${conn.baseUrl}/session/$sessionId/command") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("command" to command, "arguments" to arguments))
        }
        return response.status.isSuccess()
    }

    /**
     * Run a shell command in a session.
     * POST /session/{sessionId}/shell
     */
    suspend fun runShellCommand(
        conn: ServerConnection,
        sessionId: String,
        command: String,
        agent: String,
        model: ModelSelection? = null,
        directory: String? = null
    ): Boolean {
        val response = httpClient.post("${conn.baseUrl}/session/$sessionId/shell") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
            contentType(ContentType.Application.Json)
            setBody(
                ShellRequest(
                    agent = agent,
                    model = model,
                    command = command
                )
            )
        }
        return response.status.isSuccess()
    }

    suspend fun createPty(
        conn: ServerConnection,
        title: String? = null,
        cwd: String? = null,
        directory: String? = null
    ): PtyInfo {
        if (BuildConfig.DEBUG) {
            Log.d("OpenCodeApi", "createPty: POST ${conn.baseUrl}/pty title=$title cwd=$cwd directory=$directory")
        }
        val response = httpClient.post("${conn.baseUrl}/pty") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
            contentType(ContentType.Application.Json)
            setBody(PtyCreateRequest(title = title, cwd = cwd))
        }
        val body = response.bodyAsText()
        if (BuildConfig.DEBUG) {
            Log.d("OpenCodeApi", "createPty: response status=${response.status} body=$body")
        }
        if (!response.status.isSuccess()) {
            throw java.io.IOException("createPty failed: ${response.status}: $body")
        }

        val info = parsePtyInfoFromCreateResponse(body, title, cwd)
        if (BuildConfig.DEBUG) {
            Log.d("OpenCodeApi", "createPty: response status=${response.status} ptyId=${info.id}")
        }
        return info
    }

    private fun parsePtyInfoFromCreateResponse(body: String, title: String?, cwd: String?): PtyInfo {
        val trimmed = body.trim()

        // Most servers return the full PtyInfo object.
        runCatching { return json.decodeFromString(PtyInfo.serializer(), trimmed) }

        // Some local builds return only an id or wrap it in data/pty.
        val id = extractPtyIdFromResponse(trimmed)
            ?: throw java.io.IOException("createPty: could not parse PTY id from response: $trimmed")

        return PtyInfo(
            id = id,
            title = title ?: "Tab",
            command = "/bin/sh",
            args = emptyList(),
            cwd = cwd ?: "/",
            status = "running",
            pid = 0,
        )
    }

    private fun extractPtyIdFromResponse(responseBody: String): String? {
        // Raw string id: "pty_xxx" or pty_xxx
        val plain = responseBody.removeSurrounding("\"").trim()
        if (plain.startsWith("pty_")) return plain

        return runCatching {
            val root = json.parseToJsonElement(responseBody)
            findPtyId(root)
        }.getOrNull()
    }

    private fun findPtyId(element: JsonElement): String? {
        val obj = element as? JsonObject ?: return null

        obj["id"]?.jsonPrimitive?.contentOrNull?.let {
            if (it.startsWith("pty_")) return it
        }

        obj["pty"]?.let { nested ->
            findPtyId(nested)?.let { return it }
        }
        obj["data"]?.let { nested ->
            findPtyId(nested)?.let { return it }
        }
        obj["result"]?.let { nested ->
            findPtyId(nested)?.let { return it }
        }

        return null
    }

    suspend fun removePty(conn: ServerConnection, ptyId: String): Boolean {
        val response = httpClient.delete("${conn.baseUrl}/pty/$ptyId") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    suspend fun updatePtySize(
        conn: ServerConnection,
        ptyId: String,
        cols: Int,
        rows: Int,
        directory: String? = null
    ): Boolean {
        val body = PtyUpdateRequest(size = PtySize(rows = rows, cols = cols))
        if (BuildConfig.DEBUG) {
            val jsonStr = json.encodeToString(PtyUpdateRequest.serializer(), body)
            Log.d("OpenCodeApi", "updatePtySize: PUT ${conn.baseUrl}/pty/$ptyId body=$jsonStr directory=$directory")
        }
        val response = httpClient.put("${conn.baseUrl}/pty/$ptyId") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (BuildConfig.DEBUG) {
            val respBody = try { response.bodyAsText() } catch (_: Exception) { "<no body>" }
            Log.d("OpenCodeApi", "updatePtySize: response status=${response.status} body=$respBody")
        }
        return response.status.isSuccess()
    }

    suspend fun openPtySocket(
        conn: ServerConnection,
        ptyId: String,
        cursor: Int = -1,
        directory: String? = null
    ): PtySocket {
        val wsBase = when {
            conn.baseUrl.startsWith("https://") -> conn.baseUrl.replaceFirst("https://", "wss://")
            conn.baseUrl.startsWith("http://") -> conn.baseUrl.replaceFirst("http://", "ws://")
            else -> conn.baseUrl
        }
        val session = httpClient.webSocketSession {
            method = HttpMethod.Get
            url("$wsBase/pty/$ptyId/connect?cursor=$cursor")
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
        }
        return PtySocket(session)
    }

    // ============ Messages ============

    suspend fun listMessages(conn: ServerConnection, sessionId: String, limit: Int? = null): List<MessageWithParts> {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/message") {
            conn.authHeader?.let { header("Authorization", it) }
            limit?.let { parameter("limit", it) }
        }.body()
    }

    /** Returns messages as raw JSON string (for export without re-serialization). */
    suspend fun listMessagesRaw(conn: ServerConnection, sessionId: String): String {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/message") {
            conn.authHeader?.let { header("Authorization", it) }
        }.bodyAsText()
    }

    /**
     * Stream session export JSON directly to an OutputStream.
     * Writes: {"info":<session>,"messages":<messages>}
     * Uses raw OkHttp for the messages request to enable true streaming
     * (Ktor's ContentNegotiation plugin buffers the entire response).
     * @param onProgress called with bytes written so far
     */
    suspend fun exportSessionToStream(
        conn: ServerConnection,
        sessionId: String,
        outputStream: java.io.OutputStream,
        onProgress: (Long) -> Unit = {}
    ) {
        var bytesWritten = 0L
        // Write session info (small, safe to hold in memory)
        val sessionJson = httpClient.get("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
        }.bodyAsText()
        val header = """{"info":$sessionJson,"messages":"""
        outputStream.write(header.toByteArray())
        bytesWritten += header.toByteArray().size
        outputStream.flush()
        onProgress(bytesWritten)

        // Stream messages via raw OkHttp to get true byte-level streaming
        val okClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val request = okhttp3.Request.Builder()
            .url("${conn.baseUrl}/session/$sessionId/message")
            .apply { conn.authHeader?.let { addHeader("Authorization", it) } }
            .build()

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            okClient.newCall(request).execute().use { response ->
                val body = response.body ?: throw java.io.IOException("Empty response body")
                val source = body.source()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = source.read(buffer)
                    if (read == -1) break
                    outputStream.write(buffer, 0, read)
                    bytesWritten += read
                    onProgress(bytesWritten)
                }
            }
        }

        outputStream.write("}".toByteArray())
        bytesWritten += 1
        outputStream.flush()
        onProgress(bytesWritten)
    }

    suspend fun getMessage(conn: ServerConnection, sessionId: String, messageId: String): MessageWithParts {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/message/$messageId") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Send a prompt asynchronously (fire-and-forget).
     * Returns 204 No Content immediately.
     * @param directory The session's working directory, sent as x-opencode-directory header
     *                  so the server resolves the correct project context.
     */
    suspend fun promptAsync(
        conn: ServerConnection,
        sessionId: String,
        parts: List<PromptPart>,
        model: ModelSelection? = null,
        agent: String? = null,
        variant: String? = null,
        directory: String? = null
    ) {
        val response = httpClient.post("${conn.baseUrl}/session/$sessionId/prompt_async") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
            contentType(ContentType.Application.Json)
            setBody(PromptRequest(
                parts = parts,
                model = model,
                agent = agent,
                variant = variant
            ))
        }
        if (!response.status.isSuccess()) {
            throw RuntimeException("prompt_async failed: ${response.status}")
        }
    }

    // ============ Permissions ============

    /**
     * Reply to a permission request.
     * POST /permission/{requestID}/reply
     * Body: { reply: "once" | "always" | "reject", message?: string }
     */
    suspend fun replyToPermission(
        conn: ServerConnection,
        requestId: String,
        reply: String, // "once", "always", or "reject"
        message: String? = null,
        directory: String? = null
    ): Boolean {
        val body = buildMap<String, String> {
            put("reply", reply)
            message?.let { put("message", it) }
        }
        val result = httpClient.post("${conn.baseUrl}/permission/$requestId/reply") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return result.status.isSuccess()
    }

    /**
     * List pending permission requests.
     * GET /permission
     */
    suspend fun listPendingPermissions(conn: ServerConnection, directory: String? = null): List<PermissionRequest> {
        return httpClient.get("${conn.baseUrl}/permission") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
        }.body()
    }

    // ============ Questions ============

    /**
     * Reply to a question request.
     * POST /question/{requestID}/reply
     * Body: { answers: string[][] }
     */
    suspend fun replyToQuestion(
        conn: ServerConnection,
        requestId: String,
        answers: List<List<String>>,
        directory: String? = null
    ): Boolean {
        val url = "${conn.baseUrl}/question/$requestId/reply"
        val bodyJson = json.encodeToString(QuestionReplyBody.serializer(), QuestionReplyBody(answers = answers))
        if (BuildConfig.DEBUG) Log.d("OpenCodeApi", "replyToQuestion: POST $url, directory=$directory, bodyJson=$bodyJson")
        val result = httpClient.post(url) {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
            setBody(io.ktor.http.content.TextContent(bodyJson, ContentType.Application.Json))
        }
        val responseBody = result.bodyAsText()
        if (BuildConfig.DEBUG) Log.d("OpenCodeApi", "replyToQuestion: status=${result.status}, responseBody=$responseBody")
        return result.status.isSuccess()
    }

    /**
     * Reject a question request.
     * POST /question/{requestID}/reject
     */
    suspend fun rejectQuestion(
        conn: ServerConnection,
        requestId: String,
        directory: String? = null
    ): Boolean {
        val url = "${conn.baseUrl}/question/$requestId/reject"
        if (BuildConfig.DEBUG) Log.d("OpenCodeApi", "rejectQuestion: POST $url, directory=$directory")
        val result = httpClient.post(url) {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
        }
        if (BuildConfig.DEBUG) Log.d("OpenCodeApi", "rejectQuestion: status=${result.status}")
        return result.status.isSuccess()
    }

    /**
     * List pending question requests.
     * GET /question
     */
    suspend fun listPendingQuestions(conn: ServerConnection, directory: String? = null): List<QuestionRequest> {
        return httpClient.get("${conn.baseUrl}/question") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
        }.body()
    }

    // ============ Config / Providers ============

    /**
     * Get available providers and models.
     * GET /config/providers
     */
    suspend fun getProviders(conn: ServerConnection): ProvidersResponse {
        return httpClient.get("${conn.baseUrl}/config/providers") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Get provider catalog with connection status.
     * GET /provider
     */
    suspend fun listProviderCatalog(conn: ServerConnection): ProviderCatalogResponse {
        return httpClient.get("${conn.baseUrl}/provider") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Get available auth methods for providers.
     * GET /provider/auth
     */
    suspend fun getProviderAuthMethods(conn: ServerConnection): Map<String, List<ProviderAuthMethod>> {
        return httpClient.get("${conn.baseUrl}/provider/auth") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Start OAuth authorization for a provider.
     * POST /provider/{providerID}/oauth/authorize
     */
    suspend fun authorizeProviderOauth(
        conn: ServerConnection,
        providerId: String,
        methodIndex: Int
    ): ProviderOauthAuthorization? {
        val response = httpClient.post("${conn.baseUrl}/provider/$providerId/oauth/authorize") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("method" to methodIndex))
        }
        val body = response.bodyAsText().trim()
        if (BuildConfig.DEBUG) {
            Log.d("OpenCodeApi", "authorizeProviderOauth: status=${response.status} body=$body")
        }

        if (!response.status.isSuccess()) return null
        if (body.isBlank() || body == "null") return ProviderOauthAuthorization()

        return runCatching {
            json.decodeFromString(ProviderOauthAuthorization.serializer(), body)
        }.getOrElse {
            // Some server builds return an empty object for headless mode.
            ProviderOauthAuthorization()
        }
    }

    /**
     * Complete OAuth authorization for a provider.
     * POST /provider/{providerID}/oauth/callback
     */
    suspend fun completeProviderOauth(
        conn: ServerConnection,
        providerId: String,
        methodIndex: Int,
        code: String? = null
    ): Boolean {
        val body = if (code != null) mapOf("method" to methodIndex, "code" to code)
        else mapOf("method" to methodIndex)
        if (BuildConfig.DEBUG) Log.d(TAG, "completeProviderOauth: POST /provider/$providerId/oauth/callback body=$body")
        val response = httpClient.post("${conn.baseUrl}/provider/$providerId/oauth/callback") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (BuildConfig.DEBUG) {
            val responseBody = response.bodyAsText()
            Log.d(TAG, "completeProviderOauth: status=${response.status}, body=$responseBody")
        }
        return response.status.isSuccess()
    }

    /**
     * Set API key auth for provider.
     * PUT /auth/{providerID}
     */
    suspend fun setProviderApiKey(conn: ServerConnection, providerId: String, apiKey: String): Boolean {
        val response = httpClient.put("${conn.baseUrl}/auth/$providerId") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("type" to "api", "key" to apiKey))
        }
        return response.status.isSuccess()
    }

    /**
     * Remove stored auth for provider.
     * DELETE /auth/{providerID}
     */
    suspend fun removeProviderAuth(conn: ServerConnection, providerId: String): Boolean {
        if (BuildConfig.DEBUG) Log.d(TAG, "removeProviderAuth: DELETE ${conn.baseUrl}/auth/$providerId")
        val response = httpClient.delete("${conn.baseUrl}/auth/$providerId") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        if (BuildConfig.DEBUG) {
            val body = response.bodyAsText()
            Log.d(TAG, "removeProviderAuth: status=${response.status}, body=$body")
        }
        return response.status.isSuccess()
    }

    /**
     * Get current server config.
     * GET /config
     */
    suspend fun getConfig(conn: ServerConnection): ServerConfigResponse {
        return httpClient.get("${conn.baseUrl}/config") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Get global server config.
     * GET /global/config
     */
    suspend fun getGlobalConfig(conn: ServerConnection): ServerConfigResponse {
        return httpClient.get("${conn.baseUrl}/global/config") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Patch server config.
     * PATCH /config
     */
    suspend fun updateConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse {
        return httpClient.patch("${conn.baseUrl}/config") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(patch)
        }.body()
    }

    /**
     * Patch global server config.
     * PATCH /global/config
     */
    suspend fun updateGlobalConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse {
        return httpClient.patch("${conn.baseUrl}/global/config") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(patch)
        }.body()
    }

    // ============ Commands ============

    /**
     * List available slash commands.
     * GET /command
     */
    suspend fun listCommands(conn: ServerConnection): List<CommandInfo> {
        return httpClient.get("${conn.baseUrl}/command") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    // ============ Files ============

    suspend fun searchText(conn: ServerConnection, pattern: String): List<SearchMatch> {
        return httpClient.get("${conn.baseUrl}/find") {
            conn.authHeader?.let { header("Authorization", it) }
            parameter("pattern", pattern)
        }.body()
    }

    suspend fun findFiles(conn: ServerConnection, query: String, type: String? = null, directory: String? = null, limit: Int? = null, dirs: String? = null): List<String> {
        return httpClient.get("${conn.baseUrl}/find/file") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
            parameter("query", query)
            type?.let { parameter("type", it) }
            limit?.let { parameter("limit", it) }
            dirs?.let { parameter("dirs", it) }
        }.body()
    }

    suspend fun readFile(conn: ServerConnection, path: String): FileContent {
        return httpClient.get("${conn.baseUrl}/file/content") {
            conn.authHeader?.let { header("Authorization", it) }
            parameter("path", path)
        }.body()
    }

    suspend fun listDirectory(conn: ServerConnection, path: String = "", directory: String? = null): List<FileNode> {
        return httpClient.get("${conn.baseUrl}/file") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
            parameter("path", path)
        }.body()
    }
}

class PtySocket(
    private val session: ClientWebSocketSession
) {
    suspend fun send(input: String) {
        session.send(input)
    }

    suspend fun close() {
        session.close(CloseReason(CloseReason.Codes.NORMAL, "closed"))
    }

    suspend fun readLoop(onText: suspend (String) -> Unit) {
        for (frame in session.incoming) {
            when (frame) {
                is Frame.Text -> onText(frame.readText())
                is Frame.Binary -> {
                    val data = frame.data
                    // Server sends cursor metadata as 0x00 + JSON. Skip it.
                    if (data.isNotEmpty() && data[0].toInt() == 0) continue
                    onText(data.toString(Charsets.UTF_8))
                }
                else -> { /* ignore */ }
            }
        }
    }
}

// ============ Request/Response DTOs ============

@Serializable
data class PromptRequest(
    val parts: List<PromptPart>,
    val model: ModelSelection? = null,
    val agent: String? = null,
    val variant: String? = null,
    val format: OutputFormat? = null,
    val system: String? = null,
    val noReply: Boolean? = null
)

@Serializable
data class PromptPart(
    val type: String,
    val text: String? = null,
    val path: String? = null,
    val mime: String? = null,
    val url: String? = null,
    val filename: String? = null
)

@Serializable
data class ShellRequest(
    val agent: String,
    val model: ModelSelection? = null,
    val command: String
)

@Serializable
data class PtyCreateRequest(
    val title: String? = null,
    val cwd: String? = null
)

@Serializable
data class PtyInfo(
    val id: String,
    val title: String,
    val command: String,
    val args: List<String>,
    val cwd: String,
    val status: String,
    val pid: Int
)

@Serializable
data class PtyUpdateRequest(
    val title: String? = null,
    val size: PtySize? = null
)

@Serializable
data class PtySize(
    val rows: Int,
    val cols: Int
)

@Serializable
data class ModelSelection(
    @SerialName("providerID") val providerId: String,
    @SerialName("modelID") val modelId: String
)

@Serializable
data class OutputFormat(
    val type: String,
    val schema: String? = null
)

@Serializable
data class QuestionReplyBody(
    val answers: List<List<String>>
)

@Serializable
data class SearchMatch(
    val path: String,
    val lines: String,
    val lineNumber: Int,
    val absoluteOffset: Int
)

@Serializable
data class FileContent(
    val type: String,
    val content: String
)

@Serializable
data class FileNode(
    val name: String,
    val path: String,
    val type: String,
    val absolute: String? = null,
    val ignored: Boolean = false,
    val size: Long? = null,
    val modified: Long? = null
)

// ============ Permission/Question Request DTOs ============

@Serializable
data class PermissionRequest(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val permission: String,
    val patterns: List<String> = emptyList(),
    val metadata: Map<String, JsonElement>? = null,
    val always: List<String> = emptyList(),
    val tool: ToolRef? = null
)

@Serializable
data class QuestionRequest(
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val questions: List<QuestionInfo>,
    val tool: ToolRef? = null
)

@Serializable
data class QuestionInfo(
    val question: String,
    val header: String,
    val options: List<QuestionOption>,
    val multiple: Boolean = false,
    val custom: Boolean = true
)

@Serializable
data class QuestionOption(
    val label: String,
    val description: String
)

// ============ Provider DTOs ============

@Serializable
data class ProvidersResponse(
    val providers: List<ProviderInfo>,
    val default: Map<String, String> = emptyMap()
)

@Serializable
data class ProviderCatalogResponse(
    val all: List<ProviderInfo>,
    val default: Map<String, String> = emptyMap(),
    val connected: List<String> = emptyList()
)

@Serializable
data class ProviderAuthMethod(
    val type: String,
    val label: String
)

@Serializable
data class ProviderOauthAuthorization(
    val url: String = "",
    val method: String = "none",
    val instructions: String = ""
)

@Serializable
data class ServerConfigResponse(
    @SerialName("disabled_providers") val disabledProviders: List<String> = emptyList(),
    @SerialName("enabled_providers") val enabledProviders: List<String>? = null,
    val model: String? = null,
    @SerialName("small_model") val smallModel: String? = null,
    @SerialName("default_agent") val defaultAgent: String? = null
)

@Serializable
data class ServerConfigPatch(
    @SerialName("disabled_providers") val disabledProviders: List<String>? = null,
    val model: String? = null,
    @SerialName("small_model") val smallModel: String? = null,
    @SerialName("default_agent") val defaultAgent: String? = null
)

@Serializable
data class ProviderInfo(
    val id: String,
    val name: String,
    val source: String = "",
    val env: List<String> = emptyList(),
    val key: String? = null,
    val options: Map<String, JsonElement> = emptyMap(),
    val models: Map<String, ProviderModel> = emptyMap()
)

@Serializable
data class ProviderModel(
    val id: String,
    @SerialName("providerID") val providerId: String = "",
    val name: String,
    val family: String? = null,
    val status: String = "active",
    val capabilities: ModelCapabilities? = null,
    val cost: ModelCost? = null,
    val limit: ModelLimit? = null,
    val variants: Map<String, JsonElement>? = null
)

@Serializable
data class ModelCapabilities(
    val temperature: Boolean = false,
    val reasoning: Boolean = false,
    val attachment: Boolean = false,
    val toolcall: Boolean = false
)

@Serializable
data class ModelCost(
    val input: Double = 0.0,
    val output: Double = 0.0,
    val cache: CacheCost? = null
) {
    @Serializable
    data class CacheCost(
        val read: Double = 0.0,
        val write: Double = 0.0
    )
}

@Serializable
data class ModelLimit(
    val context: Int = 0,
    val input: Int? = null,
    val output: Int = 0
)

// ============ Agent DTOs ============

@Serializable
data class AgentInfo(
    val name: String,
    val description: String? = null,
    val mode: String = "primary", // "primary", "subagent", "all"
    val hidden: Boolean = false,
    val color: String? = null
)

// ============ Command DTOs ============

@Serializable
data class CommandInfo(
    val name: String,
    val description: String? = null,
    val source: String? = null, // "command", "mcp", "skill"
    val hints: List<String> = emptyList()
)

// ============ Server Paths ============

@Serializable
data class ServerPaths(
    val home: String = "",
    val state: String = "",
    val config: String = "",
    val worktree: String = "",
    val directory: String = ""
)
