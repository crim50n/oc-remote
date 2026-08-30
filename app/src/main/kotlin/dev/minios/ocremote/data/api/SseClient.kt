package dev.minios.ocremote.data.api

import dev.minios.ocremote.logging.AppLogger as Log
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.domain.model.*
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SseClient"
private const val HEARTBEAT_TIMEOUT_MS = 90_000L

data class ScopedSseEvent(
    val event: SseEvent,
    val directory: String? = null,
    val projectId: String? = null,
    val workspaceId: String? = null,
    val eventId: String? = null,
    val durableSeq: Long? = null,
)

internal fun sseEventData(payload: JsonObject): JsonObject =
    payload["properties"]?.jsonObject
        ?: payload["data"]?.jsonObject
        ?: JsonObject(emptyMap())

internal fun isHighFrequencySseEvent(event: SseEvent): Boolean = when (event) {
    is SseEvent.MessagePartDelta,
    is SseEvent.MessagePartUpdated,
    is SseEvent.NextTextDelta,
    is SseEvent.NextReasoningDelta,
    is SseEvent.NextToolInputDelta,
    is SseEvent.NextToolProgress,
    is SseEvent.ServerHeartbeat -> true
    else -> false
}

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
    fun connectToGlobalEvents(
        conn: ServerConnection,
        directory: String? = null,
        onOpen: suspend () -> Unit = {},
    ): Flow<ScopedSseEvent> = flow {
        val sseUrl = "${conn.baseUrl}/global/event"
        Log.i(TAG, "Connecting to global SSE (auth=${conn.authHeader != null})")

        val statement = httpClient.prepareGet(sseUrl) {
            conn.authHeader?.let { header("Authorization", it) }
            header("Accept", "text/event-stream")
            directory?.let { header("x-opencode-directory", it) }

            timeout {
                requestTimeoutMillis = Long.MAX_VALUE
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = Long.MAX_VALUE
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
                throw SseConnectionException(
                    message = "HTTP $statusCode",
                    retryable = statusCode == 408 || statusCode == 429 || statusCode >= 500,
                )
            }

            val channel = response.bodyAsChannel()
            val decoder = SseFrameDecoder()
            var eventCount = 0

            Log.i(TAG, "SSE stream opened, reading events...")
            onOpen()

            while (!channel.isClosedForRead) {
                val line = withTimeoutOrNull(HEARTBEAT_TIMEOUT_MS) { channel.readLine() }
                    ?: if (channel.isClosedForRead) break else {
                        throw SseConnectionException("SSE stream timed out")
                    }
                decoder.accept(line)?.let { data ->
                    eventCount += processFrame(data) { emit(it) }
                }
            }

            decoder.finish()?.let { data ->
                eventCount += processFrame(data) { emit(it) }
            }

            if (currentCoroutineContext().isActive) {
                Log.w(TAG, "SSE stream closed after $eventCount events")
            } else if (BuildConfig.DEBUG) {
                Log.d(TAG, "SSE stream cancelled after $eventCount events")
            }
        }
    }

    private suspend fun processFrame(data: String, emitEvent: suspend (ScopedSseEvent) -> Unit): Int {
        return try {
            val event = parseEvent(data) ?: return 0
            if (event.event !is SseEvent.ServerHeartbeat) {
                if (BuildConfig.DEBUG && !isHighFrequencySseEvent(event.event)) {
                    Log.d(TAG, "Event: ${event.event::class.simpleName}")
                }
                emitEvent(event)
            }
            1
        } catch (e: Exception) {
            Log.e(TAG, "SSE event parse failed", e)
            0
        }
    }

    /**
     * Parse SSE event from raw JSON.
     * Global endpoint wraps events: {directory, payload: {type, properties}}
     * Per-instance endpoint sends directly: {type, properties}
     */
    private fun parseEvent(data: String): ScopedSseEvent? {
        val root = json.parseToJsonElement(data).jsonObject

        val payload = root["payload"]?.jsonObject ?: root
        val type = payload["type"]?.jsonPrimitive?.content ?: return null
        val properties = sseEventData(payload)
        val directory = root["directory"]?.jsonPrimitive?.contentOrNull

        return ScopedSseEvent(
            event = parseEventByType(
                type,
                properties,
                directory,
                root["workspace"]?.jsonPrimitive?.contentOrNull,
            ) ?: return null,
            directory = directory,
            projectId = root["project"]?.jsonPrimitive?.contentOrNull,
            workspaceId = root["workspace"]?.jsonPrimitive?.contentOrNull,
            eventId = payload["id"]?.jsonPrimitive?.contentOrNull,
            durableSeq = payload["durable"]?.jsonObject?.get("seq")?.jsonPrimitive?.longOrNull,
        )
    }

    private fun parseEventByType(
        type: String,
        props: JsonObject,
        envelopeDirectory: String? = null,
        envelopeWorkspace: String? = null,
    ): SseEvent? {
        return try {
            when (type) {
                "server.connected" -> SseEvent.ServerConnected
                "server.heartbeat" -> SseEvent.ServerHeartbeat
                "server.instance.disposed" -> SseEvent.ServerInstanceDisposed(
                    directory = props.str("directory").ifBlank { envelopeDirectory.orEmpty() },
                )
                "global.disposed" -> SseEvent.GlobalDisposed
                "workspace.status" -> SseEvent.WorkspaceStatus(
                    workspaceId = props.str("workspaceID"),
                    status = props.str("status"),
                )
                "workspace.ready" -> SseEvent.WorkspaceReady(envelopeWorkspace, props.str("name"))
                "workspace.failed" -> SseEvent.WorkspaceFailed(envelopeWorkspace, props.str("message"))
                "worktree.ready" -> SseEvent.WorktreeReady(
                    directory = envelopeDirectory,
                    name = props.str("name"),
                    branch = props["branch"]?.jsonPrimitive?.contentOrNull,
                )
                "worktree.failed" -> SseEvent.WorktreeFailed(envelopeDirectory, props.str("message"))

                "session.next.prompt.admitted" -> SseEvent.PromptAdmitted(
                    sessionId = props.str("sessionID"),
                    messageId = props.str("messageID"),
                    delivery = props.str("delivery"),
                    prompt = props["prompt"],
                    timestamp = props["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
                )
                "session.next.prompted" -> SseEvent.Prompted(
                    sessionId = props.str("sessionID"),
                    messageId = props.str("messageID"),
                    delivery = props.str("delivery"),
                    prompt = props["prompt"],
                    timestamp = props["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
                )
                "session.next.step.started" -> SseEvent.NextStepStarted(
                    sessionId = props.str("sessionID"),
                    assistantMessageId = props.str("assistantMessageID"),
                    agent = props.str("agent"),
                    model = props["model"] ?: JsonObject(emptyMap()),
                    timestamp = props["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
                )
                "session.next.step.ended" -> SseEvent.NextStepEnded(
                    sessionId = props.str("sessionID"),
                    assistantMessageId = props.str("assistantMessageID"),
                    finish = props.str("finish"),
                    cost = props["cost"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    tokens = props["tokens"] ?: JsonObject(emptyMap()),
                    timestamp = props["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
                )
                "session.next.step.failed" -> SseEvent.NextStepFailed(
                    props.str("sessionID"), props.str("assistantMessageID"),
                    props["error"] ?: JsonObject(emptyMap()), props["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
                )
                "session.next.agent.switched" -> SseEvent.NextAgentSwitched(
                    props.str("sessionID"), props.str("messageID"), props.str("agent"),
                )
                "session.next.model.switched" -> SseEvent.NextModelSwitched(
                    props.str("sessionID"), props.str("messageID"), props["model"] ?: JsonObject(emptyMap()),
                )
                "session.next.context.updated" -> SseEvent.NextContextUpdated(
                    props.str("sessionID"), props.str("messageID"), props.str("text"),
                    props["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
                )
                "session.next.synthetic" -> SseEvent.NextSynthetic(
                    props.str("sessionID"), props.str("messageID"), props.str("text"),
                    props["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
                )
                "session.next.shell.started" -> SseEvent.NextShellStarted(
                    props.str("sessionID"), props.str("messageID"), props.str("callID"), props.str("command"),
                    props["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
                )
                "session.next.shell.ended" -> SseEvent.NextShellEnded(
                    props.str("sessionID"), props.str("callID"), props.str("output"),
                    props["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
                )
                "session.next.text.started" -> SseEvent.NextTextStarted(
                    props.str("sessionID"), props.str("assistantMessageID"), props.str("textID"),
                    props["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
                )
                "session.next.text.delta" -> SseEvent.NextTextDelta(
                    props.str("sessionID"), props.str("assistantMessageID"), props.str("textID"), props.str("delta"),
                )
                "session.next.text.ended" -> SseEvent.NextTextEnded(
                    props.str("sessionID"), props.str("assistantMessageID"), props.str("textID"), props.str("text"),
                    props["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
                )
                "session.next.reasoning.started" -> SseEvent.NextReasoningStarted(
                    props.str("sessionID"), props.str("assistantMessageID"), props.str("reasoningID"),
                    props["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
                )
                "session.next.reasoning.delta" -> SseEvent.NextReasoningDelta(
                    props.str("sessionID"), props.str("assistantMessageID"), props.str("reasoningID"), props.str("delta"),
                )
                "session.next.reasoning.ended" -> SseEvent.NextReasoningEnded(
                    props.str("sessionID"), props.str("assistantMessageID"), props.str("reasoningID"), props.str("text"),
                    props["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
                )
                "session.next.tool.input.started" -> SseEvent.NextToolInputStarted(
                    props.str("sessionID"), props.str("assistantMessageID"), props.str("callID"), props.str("name"),
                    props["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
                )
                "session.next.tool.input.delta" -> SseEvent.NextToolInputDelta(
                    props.str("sessionID"), props.str("assistantMessageID"), props.str("callID"), props.str("delta"),
                )
                "session.next.tool.input.ended" -> SseEvent.NextToolInputEnded(
                    props.str("sessionID"), props.str("assistantMessageID"), props.str("callID"), props.str("text"),
                )
                "session.next.tool.called" -> SseEvent.NextToolCalled(
                    props.str("sessionID"), props.str("assistantMessageID"), props.str("callID"), props.str("tool"),
                    props["input"] ?: JsonObject(emptyMap()), props["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
                )
                "session.next.tool.progress" -> SseEvent.NextToolProgress(
                    props.str("sessionID"), props.str("assistantMessageID"), props.str("callID"),
                    props["structured"] ?: JsonObject(emptyMap()), props["content"] ?: JsonArray(emptyList()),
                    props["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
                )
                "session.next.tool.success" -> SseEvent.NextToolSuccess(
                    props.str("sessionID"), props.str("assistantMessageID"), props.str("callID"),
                    props["structured"] ?: JsonObject(emptyMap()), props["content"] ?: JsonArray(emptyList()),
                    props["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
                )
                "session.next.tool.failed" -> SseEvent.NextToolFailed(
                    props.str("sessionID"), props.str("assistantMessageID"), props.str("callID"),
                    props["error"] ?: JsonObject(emptyMap()), props["timestamp"]?.jsonPrimitive?.longOrNull ?: 0,
                )

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

                    SseEvent.SessionStatus(sessionId = sessionId, status = status)
                }

                "session.idle" -> {
                    val sessionId = props.str("sessionID")
                    SseEvent.SessionIdle(sessionId = sessionId)
                }

                "session.compacted" -> SseEvent.SessionCompacted(props.str("sessionID"))

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
                    parseSessionError(props, json)
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
                    Log.i(
                        TAG,
                        "Question asked: session=$sessionId request=$id questions=${questions.size}",
                    )
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

                "sync", "pty.updated" -> null

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

internal fun parseSessionError(props: JsonObject, json: Json): SseEvent.SessionError {
    val sessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull
    val error = when (val value = props["error"]) {
        is JsonObject -> json.decodeFromJsonElement<Message.Assistant.ErrorInfo>(value)
        is JsonPrimitive -> Message.Assistant.ErrorInfo(name = value.content)
        else -> Message.Assistant.ErrorInfo(name = "Unknown error")
    }
    return SseEvent.SessionError(sessionId = sessionId, error = error)
}

/** Thrown when SSE returns 401 */
class SseAuthException(message: String) : Exception(message)

/** Thrown for SSE transport and HTTP failures. */
class SseConnectionException(
    message: String,
    val retryable: Boolean = true,
) : Exception(message)
