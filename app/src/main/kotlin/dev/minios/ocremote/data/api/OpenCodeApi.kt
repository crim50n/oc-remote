package dev.minios.ocremote.data.api

import dev.minios.ocremote.domain.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    
    // ============ Global ============
    
    suspend fun getHealth(conn: ServerConnection): ServerHealth {
        return httpClient.get("${conn.baseUrl}/global/health") {
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
    
    // ============ Session ============
    
    suspend fun listSessions(conn: ServerConnection): List<Session> {
        return httpClient.get("${conn.baseUrl}/session") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }
    
    suspend fun getSession(conn: ServerConnection, sessionId: String): Session {
        return httpClient.get("${conn.baseUrl}/session/$sessionId") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }
    
    suspend fun createSession(conn: ServerConnection, title: String? = null, parentId: String? = null): Session {
        return httpClient.post("${conn.baseUrl}/session") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "title" to title,
                "parentID" to parentId
            ))
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
    
    suspend fun abortSession(conn: ServerConnection, sessionId: String): Boolean {
        val response = httpClient.post("${conn.baseUrl}/session/$sessionId/abort") {
            conn.authHeader?.let { header("Authorization", it) }
        }
        return response.status.isSuccess()
    }
    
    suspend fun getSessionDiff(conn: ServerConnection, sessionId: String): List<FileDiff> {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/diff") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }
    
    // ============ Messages ============
    
    suspend fun listMessages(conn: ServerConnection, sessionId: String, limit: Int? = null): List<MessageWithParts> {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/message") {
            conn.authHeader?.let { header("Authorization", it) }
            limit?.let { parameter("limit", it) }
        }.body()
    }
    
    suspend fun getMessage(conn: ServerConnection, sessionId: String, messageId: String): MessageWithParts {
        return httpClient.get("${conn.baseUrl}/session/$sessionId/message/$messageId") {
            conn.authHeader?.let { header("Authorization", it) }
        }.body()
    }
    
    /**
     * Send a prompt asynchronously (fire-and-forget)
     * Returns 204 No Content immediately
     */
    suspend fun promptAsync(
        conn: ServerConnection,
        sessionId: String,
        parts: List<PromptPart>,
        model: ModelSelection? = null,
        agent: String? = null
    ) {
        httpClient.post("${conn.baseUrl}/session/$sessionId/prompt_async") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(PromptRequest(
                parts = parts,
                model = model,
                agent = agent
            ))
        }
    }
    
    // ============ Permissions ============
    
    suspend fun respondToPermission(
        conn: ServerConnection,
        sessionId: String,
        permissionId: String,
        response: String, // "allow" or "deny"
        remember: Boolean = false
    ): Boolean {
        val result = httpClient.post("${conn.baseUrl}/session/$sessionId/permissions/$permissionId") {
            conn.authHeader?.let { header("Authorization", it) }
            contentType(ContentType.Application.Json)
            setBody(mapOf(
                "response" to response,
                "remember" to remember
            ))
        }
        return result.status.isSuccess()
    }
    
    // ============ Files ============
    
    suspend fun searchText(conn: ServerConnection, pattern: String): List<SearchMatch> {
        return httpClient.get("${conn.baseUrl}/find") {
            conn.authHeader?.let { header("Authorization", it) }
            parameter("pattern", pattern)
        }.body()
    }
    
    suspend fun findFiles(conn: ServerConnection, query: String, type: String? = null): List<String> {
        return httpClient.get("${conn.baseUrl}/find/file") {
            conn.authHeader?.let { header("Authorization", it) }
            parameter("query", query)
            type?.let { parameter("type", it) }
        }.body()
    }
    
    suspend fun readFile(conn: ServerConnection, path: String): FileContent {
        return httpClient.get("${conn.baseUrl}/file/content") {
            conn.authHeader?.let { header("Authorization", it) }
            parameter("path", path)
        }.body()
    }
    
    suspend fun listDirectory(conn: ServerConnection, path: String = ""): List<FileNode> {
        return httpClient.get("${conn.baseUrl}/file") {
            conn.authHeader?.let { header("Authorization", it) }
            parameter("path", path)
        }.body()
    }
}

// ============ Request/Response DTOs ============

@Serializable
data class PromptRequest(
    val parts: List<PromptPart>,
    val model: ModelSelection? = null,
    val agent: String? = null,
    val format: OutputFormat? = null,
    val system: String? = null,
    val noReply: Boolean? = null
)

@Serializable
data class PromptPart(
    val type: String, // "text", "file", etc.
    val text: String? = null,
    val path: String? = null,
    val mime: String? = null
)

@Serializable
data class ModelSelection(
    val providerId: String,
    val modelId: String
)

@Serializable
data class OutputFormat(
    val type: String,
    val schema: String? = null // JSON string
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
    val type: String, // "raw" or "patch"
    val content: String
)

@Serializable
data class FileNode(
    val name: String,
    val path: String,
    val type: String, // "file" or "directory"
    val size: Long? = null,
    val modified: Long? = null
)
