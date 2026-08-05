package dev.minios.ocremote.data.api

import dev.minios.ocremote.logging.AppLogger as Log
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Holds resolved connection info for a server.
 * Create one via [ServerConnection.from] and pass it to every API / SSE call.
 */
data class ServerConnection(
    val baseUrl: String,
    val authHeader: String?,
    val allowSelfSigned: Boolean = false,
) {
    companion object {
        fun from(
            url: String,
            username: String = "opencode",
            password: String? = null,
            allowSelfSigned: Boolean = false,
        ): ServerConnection {
            val base = url.trimEnd('/')
            val auth = if (password != null) {
                val credentials = "$username:$password"
                "Basic ${Base64.getEncoder().encodeToString(credentials.toByteArray())}"
            } else {
                null
            }
            return ServerConnection(base, auth, allowSelfSigned)
        }
    }
}

class ServerAuthenticationException(val statusCode: Int) : Exception("Server authentication failed (HTTP $statusCode)")

class ServerHealthHttpException(val statusCode: Int) : Exception("Server health check failed (HTTP $statusCode)")

internal fun healthStatusException(statusCode: Int): Exception? = when (statusCode) {
    in 200..299 -> null
    401, 403 -> ServerAuthenticationException(statusCode)
    else -> ServerHealthHttpException(statusCode)
}

/**
 * OpenCode REST API Client
 *
 * All methods take a [ServerConnection] so the client is stateless
 * and safe to use for multiple servers concurrently.
 */
@Singleton
class OpenCodeApi @Inject constructor(
    @Named("secure") private val httpClient: HttpClient,
    @Named("insecure") private val insecureHttpClient: HttpClient,
    private val json: Json
) {

    /**
     * Pick the HTTP client matching the server's TLS preference.
     * [ServerConnection.allowSelfSigned] selects the trust-all client.
     */
    private fun clientFor(conn: ServerConnection): HttpClient =
        if (conn.allowSelfSigned) insecureHttpClient else httpClient
    companion object {
        private const val TAG = "OpenCodeApi"
    }

    // ============ Global ============

    suspend fun getHealth(conn: ServerConnection): ServerHealth {
        val response = clientFor(conn).get("${conn.baseUrl}/global/health") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        healthStatusException(response.status.value)?.let { throw it }
        return response.body()
    }

    /**
     * Get server paths (home directory, worktree, etc.).
     * GET /path
     */
    suspend fun getServerPaths(conn: ServerConnection): ServerPaths {
        return clientFor(conn).get("${conn.baseUrl}/path") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    // ============ Project ============

    suspend fun listProjects(conn: ServerConnection): List<Project> {
        return clientFor(conn).get("${conn.baseUrl}/project") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    suspend fun getProjectDirectories(
        conn: ServerConnection,
        projectId: String,
        directory: String? = null,
        workspaceId: String? = null,
    ): List<ProjectDirectory> = clientFor(conn).get("${conn.baseUrl}/project/$projectId/directories") {
        conn.authHeader?.let { header("Authorization", it) }
        directory?.let { parameter("directory", it) }
        workspaceId?.let { parameter("workspace", it) }
    }.body()

    suspend fun listWorkspaces(
        conn: ServerConnection,
        directory: String,
        workspaceId: String? = null,
    ): List<WorkspaceInfo> = clientFor(conn).get("${conn.baseUrl}/experimental/workspace") {
        conn.authHeader?.let { header("Authorization", it) }
        parameter("directory", directory)
        workspaceId?.let { parameter("workspace", it) }
    }.body()

    suspend fun getCurrentProject(conn: ServerConnection): Project {
        return clientFor(conn).get("${conn.baseUrl}/project/current") {
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
        return clientFor(conn).get("${conn.baseUrl}/agent") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    // ============ Session ============

    suspend fun listSessions(conn: ServerConnection, directory: String? = null): List<Session> {
        return clientFor(conn).get("${conn.baseUrl}/session") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
            parameter("roots", "true")
        }.body()
    }

    suspend fun listSessionStatuses(
        conn: ServerConnection,
        directory: String? = null,
    ): Map<String, SessionStatus> {
        val payload: JsonObject = clientFor(conn).get("${conn.baseUrl}/session/status") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
        }.body()
        return payload.mapValues { (_, value) ->
            val status = value.jsonObject
            when (status["type"]?.jsonPrimitive?.content) {
                "busy" -> SessionStatus.Busy
                "retry" -> SessionStatus.Retry(
                    attempt = status["attempt"]?.jsonPrimitive?.intOrNull ?: 0,
                    message = status["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    next = status["next"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0,
                )
                else -> SessionStatus.Idle
            }
        }
    }

    suspend fun getSession(conn: ServerConnection, sessionId: String, directory: String? = null): Session {
        return clientFor(conn).get("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
        }.body()
    }

    suspend fun listChildSessions(
        conn: ServerConnection,
        sessionId: String,
        directory: String? = null,
    ): List<Session> {
        return clientFor(conn).get("${conn.baseUrl}/session/$sessionId/children") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
        }.body()
    }

    /** Returns session info as raw JSON string (for export without re-serialization). */
    suspend fun getSessionRaw(conn: ServerConnection, sessionId: String): String {
        return clientFor(conn).get("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
        }.bodyAsText()
    }

    suspend fun createSession(conn: ServerConnection, title: String? = null, parentId: String? = null, directory: String? = null): Session {
        val body = buildMap<String, String> {
            title?.let { put("title", it) }
            parentId?.let { put("parentID", it) }
        }
        return clientFor(conn).post("${conn.baseUrl}/session") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }.body()
    }

    suspend fun deleteSession(conn: ServerConnection, sessionId: String): Boolean {
        val response = clientFor(conn).delete("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    suspend fun updateSession(conn: ServerConnection, sessionId: String, title: String): Session {
        return clientFor(conn).patch("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("title" to title))
        }.body()
    }

    suspend fun abortSession(conn: ServerConnection, sessionId: String, directory: String? = null): Boolean {
        val response = clientFor(conn).post("${conn.baseUrl}/session/$sessionId/abort") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
        }
        return response.status.isSuccess()
    }

    suspend fun getSessionDiff(conn: ServerConnection, sessionId: String): List<FileDiff> {
        return clientFor(conn).get("${conn.baseUrl}/session/$sessionId/diff") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Share a session, creating a shareable link.
     * POST /session/{sessionId}/share
     */
    suspend fun shareSession(conn: ServerConnection, sessionId: String): Session {
        return clientFor(conn).post("${conn.baseUrl}/session/$sessionId/share") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Unshare a session, removing the shareable link.
     * DELETE /session/{sessionId}/share
     */
    suspend fun unshareSession(conn: ServerConnection, sessionId: String): Session {
        return clientFor(conn).delete("${conn.baseUrl}/session/$sessionId/share") {
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
        val response = clientFor(conn).post("${conn.baseUrl}/session/$sessionId/summarize") {
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
        return clientFor(conn).post("${conn.baseUrl}/session/$sessionId/revert") {
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
        return clientFor(conn).post("${conn.baseUrl}/session/$sessionId/unrevert") {
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
        return clientFor(conn).post("${conn.baseUrl}/session/$sessionId/fork") {
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
        val response = clientFor(conn).post("${conn.baseUrl}/session/$sessionId/command") {
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
        val response = clientFor(conn).post("${conn.baseUrl}/session/$sessionId/shell") {
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
            Log.d("OpenCodeApi", "createPty: request")
        }
        val response = clientFor(conn).post("${conn.baseUrl}/pty") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
            contentType(ContentType.Application.Json)
            setBody(PtyCreateRequest(title = title, cwd = cwd))
        }
        val body = response.bodyAsText()
        if (BuildConfig.DEBUG) {
            Log.d("OpenCodeApi", "createPty: status=${response.status}")
        }
        if (!response.status.isSuccess()) {
            throw java.io.IOException("createPty failed: ${response.status}")
        }

        val info = parsePtyInfoFromCreateResponse(body, title, cwd)
        if (BuildConfig.DEBUG) {
            Log.d("OpenCodeApi", "createPty: response parsed")
        }
        return info
    }

    private fun parsePtyInfoFromCreateResponse(body: String, title: String?, cwd: String?): PtyInfo {
        val trimmed = body.trim()

        // Most servers return the full PtyInfo object.
        runCatching { return json.decodeFromString(PtyInfo.serializer(), trimmed) }

        // Some local builds return only an id or wrap it in data/pty.
        val id = extractPtyIdFromResponse(trimmed)
            ?: throw java.io.IOException("createPty: could not parse PTY id")

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
        val response = clientFor(conn).delete("${conn.baseUrl}/pty/$ptyId") {
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
            Log.d("OpenCodeApi", "updatePtySize: ${cols}x$rows")
        }
        val response = clientFor(conn).put("${conn.baseUrl}/pty/$ptyId") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (BuildConfig.DEBUG) {
            Log.d("OpenCodeApi", "updatePtySize: status=${response.status}")
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
        val session = clientFor(conn).webSocketSession {
            method = HttpMethod.Get
            url("$wsBase/pty/$ptyId/connect?cursor=$cursor")
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
        }
        return PtySocket(session)
    }

    // ============ Messages ============

    suspend fun listMessages(
        conn: ServerConnection,
        sessionId: String,
        limit: Int? = null,
        directory: String? = null,
    ): List<MessageWithParts> {
        return listMessagesPage(conn, sessionId, limit, directory = directory).messages
    }

    suspend fun listMessagesPage(
        conn: ServerConnection,
        sessionId: String,
        limit: Int? = null,
        before: String? = null,
        directory: String? = null,
    ): MessagePage {
        val response = clientFor(conn).get("${conn.baseUrl}/session/$sessionId/message") {
            conn.authHeader?.let { header("Authorization", it) }
            limit?.let { parameter("limit", it) }
            before?.let { parameter("before", it) }
            directory?.let { header("x-opencode-directory", it) }
        }
        return MessagePage(
            messages = response.body(),
            nextCursor = response.headers["X-Next-Cursor"]?.takeIf { it.isNotBlank() },
        )
    }

    /** Returns messages as raw JSON string (for export without re-serialization). */
    suspend fun listMessagesRaw(conn: ServerConnection, sessionId: String): String {
        return clientFor(conn).get("${conn.baseUrl}/session/$sessionId/message") {
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
        val sessionJson = clientFor(conn).get("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
        }.bodyAsText()
        val header = """{"info":$sessionJson,"messages":"""
        outputStream.write(header.toByteArray())
        bytesWritten += header.toByteArray().size
        outputStream.flush()
        onProgress(bytesWritten)

        // Stream messages via raw OkHttp to get true byte-level streaming
        val okClientBuilder = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        if (conn.allowSelfSigned) {
            dev.minios.ocremote.core.network.InsecureTls.applyToOkHttp(okClientBuilder)
        }
        val okClient = okClientBuilder.build()
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
        return clientFor(conn).get("${conn.baseUrl}/session/$sessionId/message/$messageId") {
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
        messageId: String,
        parts: List<PromptPart>,
        model: ModelSelection? = null,
        agent: String? = null,
        variant: String? = null,
        directory: String? = null
    ) {
        val response = clientFor(conn).post("${conn.baseUrl}/session/$sessionId/prompt_async") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
            contentType(ContentType.Application.Json)
            setBody(PromptRequest(
                messageId = messageId,
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

    suspend fun switchSessionAgentV2(
        conn: ServerConnection,
        sessionId: String,
        agent: String,
        directory: String? = null,
        workspaceId: String? = null,
    ) {
        val response = clientFor(conn).post("${conn.baseUrl}/api/session/$sessionId/agent") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { parameter("directory", it) }
            workspaceId?.let { parameter("workspace", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf("agent" to agent))
        }
        if (!response.status.isSuccess()) throw RuntimeException("V2 agent switch failed: ${response.status}")
    }

    suspend fun switchSessionModelV2(
        conn: ServerConnection,
        sessionId: String,
        model: ModelSelection,
        variant: String? = null,
        directory: String? = null,
        workspaceId: String? = null,
    ) {
        val response = clientFor(conn).post("${conn.baseUrl}/api/session/$sessionId/model") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { parameter("directory", it) }
            workspaceId?.let { parameter("workspace", it) }
            contentType(ContentType.Application.Json)
            setBody(V2ModelRef(model.providerId, model.modelId, variant))
        }
        if (!response.status.isSuccess()) throw RuntimeException("V2 model switch failed: ${response.status}")
    }

    suspend fun promptV2(
        conn: ServerConnection,
        sessionId: String,
        request: V2PromptRequest,
        directory: String? = null,
        workspaceId: String? = null,
    ): V2AdmittedPrompt {
        val response = clientFor(conn).post("${conn.baseUrl}/api/session/$sessionId/prompt") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { parameter("directory", it) }
            workspaceId?.let { parameter("workspace", it) }
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        if (!response.status.isSuccess()) throw RuntimeException("V2 prompt admission failed: ${response.status}")
        return response.body<V2DataResponse<V2AdmittedPrompt>>().data
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
        val result = clientFor(conn).post("${conn.baseUrl}/permission/$requestId/reply") {
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
        return clientFor(conn).get("${conn.baseUrl}/permission") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
        }.body()
    }

    // ============ MCP ============

    suspend fun getMcpStatus(conn: ServerConnection): Map<String, McpStatus> {
        val response = clientFor(conn).get("${conn.baseUrl}/mcp") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        if (!response.status.isSuccess()) throw RuntimeException("MCP status failed: ${response.status}")
        return response.body()
    }

    suspend fun connectMcp(conn: ServerConnection, name: String): Boolean =
        updateMcpConnection(conn, name, connect = true)

    suspend fun disconnectMcp(conn: ServerConnection, name: String): Boolean =
        updateMcpConnection(conn, name, connect = false)

    private suspend fun updateMcpConnection(
        conn: ServerConnection,
        name: String,
        connect: Boolean,
    ): Boolean {
        val action = if (connect) "connect" else "disconnect"
        val response = clientFor(conn).post("${conn.baseUrl}/mcp/${name.encodeURLPathPart()}/$action") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        if (!response.status.isSuccess()) throw RuntimeException("MCP $action failed: ${response.status}")
        return response.body()
    }

    suspend fun startMcpAuth(conn: ServerConnection, name: String): McpAuthStart {
        val response = clientFor(conn).post("${conn.baseUrl}/mcp/${name.encodeURLPathPart()}/auth") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        if (!response.status.isSuccess()) throw RuntimeException("MCP authentication failed: ${response.status}")
        return response.body()
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
        if (BuildConfig.DEBUG) Log.d("OpenCodeApi", "replyToQuestion: answers=${answers.size}")
        val result = clientFor(conn).post(url) {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let { header("x-opencode-directory", it) }
            setBody(io.ktor.http.content.TextContent(bodyJson, ContentType.Application.Json))
        }
        if (BuildConfig.DEBUG) Log.d("OpenCodeApi", "replyToQuestion: status=${result.status}")
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
        if (BuildConfig.DEBUG) Log.d("OpenCodeApi", "rejectQuestion: request")
        val result = clientFor(conn).post(url) {
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
        return clientFor(conn).get("${conn.baseUrl}/question") {
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
        return clientFor(conn).get("${conn.baseUrl}/config/providers") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Get provider catalog with connection status.
     * GET /provider
     */
    suspend fun listProviderCatalog(conn: ServerConnection): ProviderCatalogResponse {
        return clientFor(conn).get("${conn.baseUrl}/provider") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Get available auth methods for providers.
     * GET /provider/auth
     */
    suspend fun getProviderAuthMethods(conn: ServerConnection): Map<String, List<ProviderAuthMethod>> {
        return clientFor(conn).get("${conn.baseUrl}/provider/auth") {
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
        val response = clientFor(conn).post("${conn.baseUrl}/provider/$providerId/oauth/authorize") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(ProviderOauthAuthorizeRequest(method = methodIndex))
        }
        val body = response.bodyAsText().trim()
        if (BuildConfig.DEBUG) {
            Log.d("OpenCodeApi", "authorizeProviderOauth: status=${response.status}")
        }

        if (!response.status.isSuccess()) {
            throw ProviderAuthException(
                response.status.value,
                providerAuthErrorMessage(json, response.status.value, body, "Failed to start OAuth"),
            )
        }
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
        val body = ProviderOauthCallbackRequest(method = methodIndex, code = code)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "completeProviderOauth: POST /provider/$providerId/oauth/callback method=$methodIndex hasCode=${code != null}")
        }
        val response = clientFor(conn).post("${conn.baseUrl}/provider/$providerId/oauth/callback") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val responseBody = response.bodyAsText().trim()
        if (BuildConfig.DEBUG) Log.d(TAG, "completeProviderOauth: status=${response.status}")
        if (!response.status.isSuccess()) {
            throw ProviderAuthException(
                response.status.value,
                providerAuthErrorMessage(json, response.status.value, responseBody, "Failed to complete OAuth"),
            )
        }
        return true
    }

    /**
     * Set API key auth for provider.
     * PUT /auth/{providerID}
     */
    suspend fun setProviderApiKey(conn: ServerConnection, providerId: String, apiKey: String): Boolean {
        val response = clientFor(conn).put("${conn.baseUrl}/auth/$providerId") {
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
        if (BuildConfig.DEBUG) Log.d(TAG, "removeProviderAuth: request")
        val response = clientFor(conn).delete("${conn.baseUrl}/auth/$providerId") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "removeProviderAuth: status=${response.status}")
        }
        return response.status.isSuccess()
    }

    /**
     * Get current server config.
     * GET /config
     */
    suspend fun getConfig(conn: ServerConnection): ServerConfigResponse {
        return clientFor(conn).get("${conn.baseUrl}/config") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Get global server config.
     * GET /global/config
     */
    suspend fun getGlobalConfig(conn: ServerConnection): ServerConfigResponse {
        return clientFor(conn).get("${conn.baseUrl}/global/config") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    /**
     * Patch server config.
     * PATCH /config
     */
    suspend fun updateConfig(conn: ServerConnection, patch: ServerConfigPatch): ServerConfigResponse {
        return clientFor(conn).patch("${conn.baseUrl}/config") {
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
        return clientFor(conn).patch("${conn.baseUrl}/global/config") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(patch)
        }.body()
    }

    /**
     * Dispose global instances and force provider/auth state refresh.
     * POST /global/dispose
     */
    suspend fun disposeGlobal(conn: ServerConnection): Boolean {
        val response = clientFor(conn).post("${conn.baseUrl}/global/dispose") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }

    // ============ Commands ============

    /**
     * List available slash commands.
     * GET /command
     */
    suspend fun listCommands(conn: ServerConnection): List<CommandInfo> {
        return clientFor(conn).get("${conn.baseUrl}/command") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }

    // ============ Files ============

    suspend fun searchText(conn: ServerConnection, pattern: String): List<SearchMatch> {
        return clientFor(conn).get("${conn.baseUrl}/find") {
            conn.authHeader?.let { header("Authorization", it) }
            parameter("pattern", pattern)
        }.body()
    }

    suspend fun findFiles(conn: ServerConnection, query: String, type: String? = null, directory: String? = null, limit: Int? = null, dirs: String? = null): List<String> {
        return clientFor(conn).get("${conn.baseUrl}/find/file") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", it)
            }
            parameter("query", query)
            type?.let { parameter("type", it) }
            limit?.let { parameter("limit", it) }
            dirs?.let { parameter("dirs", it) }
        }.body()
    }

    suspend fun readFile(conn: ServerConnection, path: String): FileContent {
        return clientFor(conn).get("${conn.baseUrl}/file/content") {
            conn.authHeader?.let { header("Authorization", it) }
            parameter("path", path)
        }.body()
    }

    suspend fun listDirectory(conn: ServerConnection, path: String = "", directory: String? = null): List<FileNode> {
        return clientFor(conn).get("${conn.baseUrl}/file") {
            conn.authHeader?.let { header("Authorization", it) }
            directory?.let {
                parameter("directory", it)
                header("x-opencode-directory", it)
            }
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
    @SerialName("messageID") val messageId: String,
    val parts: List<PromptPart>,
    val model: ModelSelection? = null,
    val agent: String? = null,
    val variant: String? = null,
    val format: OutputFormat? = null,
    val system: String? = null,
    val noReply: Boolean? = null
)

@Serializable
data class ProjectDirectory(
    val directory: String,
    val strategy: String? = null,
)

@Serializable
data class WorkspaceInfo(
    val id: String,
    val type: String,
    val branch: String,
    val name: String? = null,
    val directory: String,
    @SerialName("projectID") val projectId: String,
    val timeUsed: Long,
    val extra: JsonElement? = null,
)

@Serializable
data class V2DataResponse<T>(val data: T)

@Serializable
data class V2PromptRequest(
    val id: String,
    val prompt: V2Prompt,
    val delivery: String = "steer",
    val resume: Boolean = true,
)

@Serializable
data class V2Prompt(
    val text: String,
    val files: List<V2FileAttachment> = emptyList(),
    val agents: List<V2AgentAttachment> = emptyList(),
)

@Serializable
data class V2FileAttachment(
    val uri: String,
    val name: String? = null,
    val description: String? = null,
)

@Serializable
data class V2AgentAttachment(val name: String)

@Serializable
data class V2ModelRef(
    @SerialName("providerID") val providerId: String,
    @SerialName("modelID") val modelId: String,
    val variant: String? = null,
)

@Serializable
data class V2AdmittedPrompt(
    val admittedSeq: Long,
    val id: String,
    @SerialName("sessionID") val sessionId: String,
    val prompt: V2Prompt,
    val delivery: String,
    val timeCreated: Long,
    val promotedSeq: Long? = null,
)

data class MessagePage(
    val messages: List<MessageWithParts>,
    val nextCursor: String?,
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
data class McpStatus(
    val status: String,
    val error: String? = null,
)

@Serializable
data class McpAuthStart(
    val authorizationUrl: String,
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
internal data class ProviderOauthAuthorizeRequest(
    val method: Int,
)

@Serializable
internal data class ProviderOauthCallbackRequest(
    val method: Int,
    val code: String? = null,
)

internal class ProviderAuthException(
    val statusCode: Int,
    message: String,
) : Exception(message)

internal fun providerAuthErrorMessage(
    json: Json,
    statusCode: Int,
    body: String,
    fallback: String,
): String {
    val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
    val data = parsed?.get("data") as? JsonObject
    val error = parsed?.get("error") as? JsonObject
    val errors = parsed?.get("errors") as? JsonArray
    val detail = sequenceOf(
        data?.get("message"),
        error?.get("message"),
        parsed?.get("message"),
        errors?.firstOrNull()?.let { it as? JsonObject }?.get("message"),
    ).mapNotNull { element ->
        runCatching { element?.jsonPrimitive?.contentOrNull }.getOrNull()
    }.firstOrNull { it.isNotBlank() }
        ?: body.takeIf { it.isNotBlank() && !it.trimStart().startsWith("<") }

    val conciseDetail = detail
        ?.lineSequence()
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()
        ?.removePrefix("Error: ")
        ?.take(240)
    return if (conciseDetail.isNullOrBlank()) {
        "$fallback (HTTP $statusCode)"
    } else {
        "$conciseDetail (HTTP $statusCode)"
    }
}

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
