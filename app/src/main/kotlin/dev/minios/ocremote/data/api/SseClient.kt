package dev.minios.ocremote.data.api

import android.util.Log
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.domain.model.*
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SseClient"
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
                                    if (BuildConfig.DEBUG) Log.d(TAG, "Heartbeat received (total events: $eventCount)")
                                } else {
                                    if (BuildConfig.DEBUG) Log.d(TAG, "Event #$eventCount: ${event::class.simpleName}")
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
     * Global endpoint wraps events: {directory, payload: {type, properties}}
     * Per-instance endpoint sends directly: {type, properties}
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
                    val sessionId = props.str("sessionID")
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
                    val sessionId = props.str("sessionID")
                    Log.i(TAG, "Session $sessionId idle")
                    SseEvent.SessionIdle(sessionId = sessionId)
                }

                "session.created" -> {
                    val infoObj = props["info"]?.jsonObject ?: props
                    val info = json.decodeFromJsonElement<Session>(infoObj)
                    SseEvent.SessionCreated(info)
                }

                "session.updated" -> {
                    val infoObj = props["info"]?.jsonObject ?: props
                    val info = json.decodeFromJsonElement<Session>(infoObj)
                    SseEvent.SessionUpdated(info)
                }

                "session.deleted" -> {
                    val infoObj = props["info"]?.jsonObject ?: props
                    val info = json.decodeFromJsonElement<Session>(infoObj)
                    SseEvent.SessionDeleted(info)
                }

                "session.error" -> {
                    val sessionId = props["sessionID"]?.jsonPrimitive?.content
                    val error = props.str("error", "Unknown error")
                    SseEvent.SessionError(sessionId = sessionId, error = error)
                }

                "session.diff" -> {
                    val sessionId = props.str("sessionID")
                    val diffArr = props["diff"]?.jsonArray
                    val diffs = diffArr?.map { json.decodeFromJsonElement<FileDiff>(it) } ?: emptyList()
                    SseEvent.SessionDiff(sessionId = sessionId, diff = diffs)
                }

                "message.updated" -> {
                    val infoObj = props["info"]?.jsonObject ?: return null
                    val message = parseMessage(infoObj) ?: return null
                    SseEvent.MessageUpdated(info = message)
                }

                "message.removed" -> {
                    val sessionId = props.str("sessionID")
                    val messageId = props.str("messageID")
                    SseEvent.MessageRemoved(sessionId = sessionId, messageId = messageId)
                }

                "message.part.updated" -> {
                    val partObj = props["part"]?.jsonObject ?: return null
                    val part = parsePart(partObj) ?: return null
                    SseEvent.MessagePartUpdated(part = part)
                }

                "message.part.delta" -> {
                    val sessionId = props.str("sessionID")
                    val messageId = props.str("messageID")
                    val partId = props.str("partID")
                    val field = props.str("field", "text")
                    val delta = props.str("delta")
                    SseEvent.MessagePartDelta(
                        sessionId = sessionId,
                        messageId = messageId,
                        partId = partId,
                        field = field,
                        delta = delta
                    )
                }

                "message.part.removed" -> {
                    val sessionId = props.str("sessionID")
                    val messageId = props.str("messageID")
                    val partId = props.str("partID")
                    SseEvent.MessagePartRemoved(
                        sessionId = sessionId,
                        messageId = messageId,
                        partId = partId
                    )
                }

                "permission.asked" -> {
                    val id = props.str("id")
                    val sessionId = props.str("sessionID")
                    val permission = props.str("permission")
                    val patterns = props["patterns"]?.jsonArray
                        ?.map { it.jsonPrimitive.content } ?: emptyList()
                    val always = props["always"]?.jsonArray
                        ?.map { it.jsonPrimitive.content } ?: emptyList()
                    val metadata = props["metadata"]?.jsonObject?.let {
                        it.mapValues { (_, v) -> v }
                    }
                    val toolRef = props["tool"]?.jsonObject?.let { toolObj ->
                        ToolRef(
                            messageId = toolObj.str("messageID"),
                            callId = toolObj.str("callID")
                        )
                    }

                    Log.i(TAG, "Permission asked: $permission for session $sessionId")
                    SseEvent.PermissionAsked(
                        id = id,
                        sessionId = sessionId,
                        permission = permission,
                        patterns = patterns,
                        always = always,
                        metadata = metadata,
                        tool = toolRef
                    )
                }

                "permission.replied" -> {
                    val sessionId = props.str("sessionID")
                    val requestId = props.str("requestID")
                    SseEvent.PermissionReplied(sessionId = sessionId, requestId = requestId)
                }

                "question.asked" -> {
                    val id = props.str("id")
                    val sessionId = props.str("sessionID")
                    val toolRef = props["tool"]?.jsonObject?.let { toolObj ->
                        ToolRef(
                            messageId = toolObj.str("messageID"),
                            callId = toolObj.str("callID")
                        )
                    }
                    Log.i(TAG, "Question asked for session $sessionId")
                    val questionsArr = props["questions"]?.jsonArray
                    val questions = questionsArr?.map { qElement ->
                        val qObj = qElement.jsonObject
                        val optionsArr = qObj["options"]?.jsonArray ?: JsonArray(emptyList())
                        val options = optionsArr.map { oElement ->
                            val oObj = oElement.jsonObject
                            SseEvent.QuestionAsked.Option(
                                label = oObj.str("label"),
                                description = oObj.str("description")
                            )
                        }
                        SseEvent.QuestionAsked.Question(
                            header = qObj.str("header"),
                            question = qObj.str("question"),
                            multiple = qObj["multiple"]?.jsonPrimitive?.booleanOrNull ?: false,
                            custom = qObj["custom"]?.jsonPrimitive?.booleanOrNull ?: true,
                            options = options
                        )
                    } ?: emptyList()
                    SseEvent.QuestionAsked(
                        id = id,
                        sessionId = sessionId,
                        questions = questions,
                        tool = toolRef
                    )
                }

                "question.replied" -> {
                    val sessionId = props.str("sessionID")
                    val requestId = props.str("requestID")
                    SseEvent.QuestionReplied(sessionId = sessionId, requestId = requestId)
                }

                "question.rejected" -> {
                    val sessionId = props.str("sessionID")
                    val requestId = props.str("requestID")
                    SseEvent.QuestionRejected(sessionId = sessionId, requestId = requestId)
                }

                "todo.updated" -> {
                    val sessionId = props.str("sessionID")
                    val todosArr = props["todos"]?.jsonArray
                    val todos = todosArr?.map { tElement ->
                        val tObj = tElement.jsonObject
                        SseEvent.TodoUpdated.Todo(
                            content = tObj.str("content"),
                            status = tObj.str("status", "pending"),
                            priority = tObj.str("priority", "medium")
                        )
                    } ?: emptyList()
                    SseEvent.TodoUpdated(sessionId = sessionId, todos = todos)
                }

                "vcs.branch.updated" -> {
                    val branch = props.str("branch")
                    SseEvent.VcsBranchUpdated(branch = branch)
                }

                "lsp.updated" -> SseEvent.LspUpdated

                "project.updated" -> {
                    val infoObj = props["info"]?.jsonObject ?: props
                    val info = json.decodeFromJsonElement<Project>(infoObj)
                    SseEvent.ProjectUpdated(info)
                }

                else -> {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Unhandled event: $type")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse $type: ${e.message}", e)
            null
        }
    }

    // ============ Message Parsing ============

    /**
     * Parse a Message from JSON, dispatching on "role" field.
     */
    private fun parseMessage(obj: JsonObject): Message? {
        val role = obj["role"]?.jsonPrimitive?.content ?: return null
        return when (role) {
            "user" -> json.decodeFromJsonElement<Message.User>(obj)
            "assistant" -> json.decodeFromJsonElement<Message.Assistant>(obj)
            else -> {
                Log.w(TAG, "Unknown message role: $role")
                null
            }
        }
    }

    /**
     * Parse a Part from JSON, dispatching on "type" field.
     */
    private fun parsePart(obj: JsonObject): Part? {
        val type = obj["type"]?.jsonPrimitive?.content ?: return null
        return try {
            when (type) {
                "text" -> json.decodeFromJsonElement<Part.Text>(obj)
                "reasoning" -> json.decodeFromJsonElement<Part.Reasoning>(obj)
                "tool" -> json.decodeFromJsonElement<Part.Tool>(obj)
                "step-start" -> json.decodeFromJsonElement<Part.StepStart>(obj)
                "step-finish" -> json.decodeFromJsonElement<Part.StepFinish>(obj)
                "file" -> json.decodeFromJsonElement<Part.File>(obj)
                "snapshot" -> json.decodeFromJsonElement<Part.Snapshot>(obj)
                "patch" -> json.decodeFromJsonElement<Part.Patch>(obj)
                "subtask" -> json.decodeFromJsonElement<Part.Subtask>(obj)
                "compaction" -> json.decodeFromJsonElement<Part.Compaction>(obj)
                "retry" -> json.decodeFromJsonElement<Part.Retry>(obj)
                "agent" -> json.decodeFromJsonElement<Part.Agent>(obj)
                else -> {
                    Log.w(TAG, "Unknown part type: $type")
                    // Return an Unknown part so it's at least tracked
                    Part.Unknown(
                        id = obj.str("id"),
                        sessionId = obj.str("sessionID"),
                        messageId = obj.str("messageID")
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse part type=$type: ${e.message}", e)
            null
        }
    }

    // ============ Helpers ============

    /** Safe string extraction with default. */
    private fun JsonObject.str(key: String, default: String = ""): String =
        this[key]?.jsonPrimitive?.content ?: default
}

/** Thrown when SSE returns 401 */
class SseAuthException(message: String) : Exception(message)

/** Thrown for non-2xx SSE responses */
class SseConnectionException(message: String) : Exception(message)
