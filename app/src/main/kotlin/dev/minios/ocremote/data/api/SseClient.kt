package dev.minios.ocremote.data.api

import android.util.Log
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import kotlinx.serialization.json.booleanOrNull
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SseClient"
private const val RECONNECT_DELAY_MS = 3000L
private const val HEARTBEAT_TIMEOUT_MS = 40_000L

/**
 * SSE (Server-Sent Events) Client
 *
 * Stateless — all connection info comes from the [ServerConnection] parameter.
 * Safe to use for multiple servers concurrently.
 */
@Singleton
class SseClient @Inject constructor(
    private val httpClient: HttpClient,
    private val json: Json
) {
    
    /**
     * Connect to the global event stream.
     * Returns a Flow that emits SSE events.
     * The flow does NOT auto-reconnect internally — callers should handle
     * reconnection themselves (the service already does exponential backoff).
     */
    fun connectToGlobalEvents(conn: ServerConnection, directory: String? = null): Flow<SseEvent> = flow {
        val sseUrl = "${conn.baseUrl}/global/event"
        Log.i(TAG, "Connecting to SSE: $sseUrl (auth=${conn.authHeader != null})")
        
        val statement = httpClient.prepareGet(sseUrl) {
            conn.authHeader?.let { header("Authorization", it) }
            header("Accept", "text/event-stream")
            directory?.let { header("x-opencode-directory", it) }
            
            timeout {
                requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
            }
        }
        
        statement.execute { response ->
            val statusCode = response.status.value
            Log.i(TAG, "SSE response: status=$statusCode, contentType=${response.headers["content-type"]}")
            
            if (statusCode == 401) {
                Log.e(TAG, "SSE auth failed (401). Check username/password.")
                throw SseAuthException("Authentication failed (401)")
            }
            
            if (statusCode !in 200..299) {
                Log.e(TAG, "SSE failed with HTTP $statusCode")
                throw SseConnectionException("HTTP $statusCode")
            }
            
            val channel = response.bodyAsChannel()
            var lastHeartbeat = System.currentTimeMillis()
            var buffer = ""
            var eventCount = 0
            
            Log.i(TAG, "SSE stream opened, reading events...")
            
            while (!channel.isClosedForRead) {
                if (System.currentTimeMillis() - lastHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                    Log.w(TAG, "Heartbeat timeout after $eventCount events, reconnecting...")
                    break
                }
                
                val line = channel.readUTF8Line() ?: break
                
                if (line.isEmpty()) {
                    if (buffer.isNotEmpty()) {
                        try {
                            val event = parseEvent(buffer)
                            if (event != null) {
                                eventCount++
                                if (event is SseEvent.ServerHeartbeat) {
                                    lastHeartbeat = System.currentTimeMillis()
                                    Log.d(TAG, "Heartbeat received (total events: $eventCount)")
                                } else {
                                    Log.d(TAG, "Event #$eventCount: ${event::class.simpleName}")
                                    emit(event)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Parse error: ${buffer.take(200)}", e)
                        }
                        buffer = ""
                    }
                } else if (line.startsWith("data: ")) {
                    buffer += line.substring(6)
                } else if (line.startsWith("data:")) {
                    buffer += line.substring(5)
                }
            }
            
            Log.w(TAG, "SSE stream closed after $eventCount events")
        }
    }
    
    /**
     * Parse SSE event from raw JSON.
     */
    private fun parseEvent(data: String): SseEvent? {
        val root = json.parseToJsonElement(data).jsonObject
        
        val payload = root["payload"]?.jsonObject ?: root
        val type = payload["type"]?.jsonPrimitive?.content ?: return null
        val properties = payload["properties"]?.jsonObject ?: JsonObject(emptyMap())
        
        return parseEventByType(type, properties)
    }
    
    private fun parseEventByType(type: String, props: JsonObject): SseEvent? {
        return try {
            when (type) {
                "server.connected" -> SseEvent.ServerConnected
                "server.heartbeat" -> SseEvent.ServerHeartbeat
                
                "session.status" -> {
                    val sessionId = props["sessionID"]?.jsonPrimitive?.content ?: ""
                    val statusObj = props["status"]?.jsonObject
                    val statusType = statusObj?.get("type")?.jsonPrimitive?.content ?: "idle"
                    
                    val status = when (statusType) {
                        "idle" -> SessionStatus.Idle
                        "busy" -> SessionStatus.Busy
                        "retry" -> SessionStatus.Retry(
                            attempt = statusObj?.get("attempt")?.jsonPrimitive?.int ?: 0,
                            message = statusObj?.get("message")?.jsonPrimitive?.content ?: "",
                            next = statusObj?.get("next")?.jsonPrimitive?.long ?: 0
                        )
                        else -> SessionStatus.Idle
                    }
                    
                    Log.i(TAG, "Session $sessionId status -> $statusType")
                    SseEvent.SessionStatus(sessionId = sessionId, status = status)
                }
                
                "session.idle" -> {
                    val sessionId = props["sessionID"]?.jsonPrimitive?.content ?: ""
                    Log.i(TAG, "Session $sessionId idle")
                    SseEvent.SessionIdle(sessionId = sessionId)
                }
                
                "session.created" -> {
                    val infoObj = props["info"]?.jsonObject ?: props
                    val info = json.decodeFromJsonElement<dev.minios.ocremote.domain.model.Session>(infoObj)
                    SseEvent.SessionCreated(info)
                }
                
                "session.updated" -> {
                    val infoObj = props["info"]?.jsonObject ?: props
                    val info = json.decodeFromJsonElement<dev.minios.ocremote.domain.model.Session>(infoObj)
                    SseEvent.SessionUpdated(info)
                }
                
                "session.deleted" -> {
                    val infoObj = props["info"]?.jsonObject ?: props
                    val info = json.decodeFromJsonElement<dev.minios.ocremote.domain.model.Session>(infoObj)
                    SseEvent.SessionDeleted(info)
                }
                
                "session.error" -> {
                    val sessionId = props["sessionID"]?.jsonPrimitive?.content
                    val error = props["error"]?.jsonPrimitive?.content ?: "Unknown error"
                    SseEvent.SessionError(sessionId = sessionId, error = error)
                }
                
                "message.updated" -> {
                    Log.d(TAG, "message.updated (skipped full parse)")
                    null
                }
                
                "message.part.updated" -> null
                
                "message.part.delta" -> {
                    val sessionId = props["sessionID"]?.jsonPrimitive?.content ?: ""
                    val messageId = props["messageID"]?.jsonPrimitive?.content ?: ""
                    val partId = props["partID"]?.jsonPrimitive?.content ?: ""
                    val field = props["field"]?.jsonPrimitive?.content ?: "text"
                    val delta = props["delta"]?.jsonPrimitive?.content ?: ""
                    SseEvent.MessagePartDelta(
                        sessionId = sessionId,
                        messageId = messageId,
                        partId = partId,
                        field = field,
                        delta = delta
                    )
                }
                
                "permission.asked" -> {
                    val id = props["id"]?.jsonPrimitive?.content ?: ""
                    val sessionId = props["sessionID"]?.jsonPrimitive?.content ?: ""
                    val tool = props["tool"]?.jsonPrimitive?.content ?: ""
                    val permission = props["permission"]?.jsonPrimitive?.content ?: ""
                    Log.i(TAG, "Permission asked: $permission for session $sessionId")
                    SseEvent.PermissionAsked(
                        id = id,
                        sessionId = sessionId,
                        tool = tool,
                        permission = permission
                    )
                }
                
                "permission.replied" -> {
                    val sessionId = props["sessionID"]?.jsonPrimitive?.content ?: ""
                    val requestId = props["requestID"]?.jsonPrimitive?.content ?: ""
                    SseEvent.PermissionReplied(sessionId = sessionId, requestId = requestId)
                }
                
                "question.asked" -> {
                    val id = props["id"]?.jsonPrimitive?.content ?: ""
                    val sessionId = props["sessionID"]?.jsonPrimitive?.content ?: ""
                    Log.i(TAG, "Question asked for session $sessionId")
                    val questionsArr = props["questions"]?.jsonArray
                    val questions = questionsArr?.map { qElement ->
                        val qObj = qElement.jsonObject
                        val optionsArr = qObj["options"]?.jsonArray ?: JsonArray(emptyList())
                        val options = optionsArr.map { oElement ->
                            val oObj = oElement.jsonObject
                            SseEvent.QuestionAsked.Option(
                                label = oObj["label"]?.jsonPrimitive?.content ?: "",
                                description = oObj["description"]?.jsonPrimitive?.content ?: ""
                            )
                        }
                        SseEvent.QuestionAsked.Question(
                            header = qObj["header"]?.jsonPrimitive?.content ?: "",
                            question = qObj["question"]?.jsonPrimitive?.content ?: "",
                            multiple = qObj["multiple"]?.jsonPrimitive?.booleanOrNull ?: false,
                            options = options
                        )
                    } ?: emptyList()
                    SseEvent.QuestionAsked(id = id, sessionId = sessionId, questions = questions)
                }
                
                "question.replied" -> {
                    val sessionId = props["sessionID"]?.jsonPrimitive?.content ?: ""
                    val requestId = props["requestID"]?.jsonPrimitive?.content ?: ""
                    SseEvent.QuestionReplied(sessionId = sessionId, requestId = requestId)
                }
                
                else -> {
                    Log.d(TAG, "Unhandled event: $type")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse $type: ${e.message}")
            null
        }
    }
}

/** Thrown when SSE returns 401 */
class SseAuthException(message: String) : Exception(message)

/** Thrown for non-2xx SSE responses */
class SseConnectionException(message: String) : Exception(message)
