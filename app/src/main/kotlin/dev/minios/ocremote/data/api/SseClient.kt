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
        val protocol = try {
            ServerProtocolRegistry.resolve(httpClient, json, conn)
        } catch (error: ServerAuthenticationException) {
            throw SseAuthException("Authentication failed (${error.statusCode})")
        }
        val sseUrl = "${conn.baseUrl}${sseEventPath(protocol)}"
        val toolNames = mutableMapOf<String, String>()
        Log.i(TAG, "Connecting to global SSE (auth=${conn.authHeader != null})")

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

            if (statusCode == 401 || statusCode == 403) {
                Log.e(TAG, "SSE authentication failed ($statusCode).")
                throw SseAuthException("Authentication failed ($statusCode)")
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
                val line = withTimeoutOrNull(HEARTBEAT_TIMEOUT_MS) { channel.readUTF8Line() }
                    ?: if (channel.isClosedForRead) break else {
                        throw SseConnectionException("SSE stream timed out")
                    }
                decoder.accept(line)?.let { data ->
                    eventCount += processFrame(data, toolNames) { emit(it) }
                }
            }

            decoder.finish()?.let { data ->
                eventCount += processFrame(data, toolNames) { emit(it) }
            }

            if (currentCoroutineContext().isActive) {
                Log.w(TAG, "SSE stream closed after $eventCount events")
            } else if (BuildConfig.DEBUG) {
                Log.d(TAG, "SSE stream cancelled after $eventCount events")
            }
        }
    }

    private suspend fun processFrame(
        data: String,
        toolNames: MutableMap<String, String>,
        emitEvent: suspend (ScopedSseEvent) -> Unit,
    ): Int {
        return try {
            val event = parseEvent(data, toolNames) ?: return 0
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
    internal fun parseEvent(
        data: String,
        toolNames: MutableMap<String, String> = mutableMapOf(),
    ): ScopedSseEvent? {
        val root = json.parseToJsonElement(data).jsonObject

        val payload = root["payload"]?.jsonObject ?: root
        val type = payload["type"]?.jsonPrimitive?.content ?: return null
        val properties = sseEventData(payload)
        val location = payload["location"] as? JsonObject
        val directory = location?.get("directory")?.jsonPrimitive?.contentOrNull
            ?: root["directory"]?.jsonPrimitive?.contentOrNull
        val workspace = location?.get("workspaceID")?.jsonPrimitive?.contentOrNull
            ?: root["workspace"]?.jsonPrimitive?.contentOrNull

        return ScopedSseEvent(
            event = (if (payload["data"] is JsonObject && payload["properties"] == null) {
                parseV2SseEvent(payload, json, toolNames)
            } else null) ?: parseEventByType(
                type,
                properties,
                directory,
                workspace,
            ) ?: return null,
            directory = directory,
            projectId = properties["projectID"]?.jsonPrimitive?.contentOrNull
                ?: root["project"]?.jsonPrimitive?.contentOrNull,
            workspaceId = workspace,
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

internal fun sseEventPath(protocol: ServerProtocol): String = when (protocol) {
    ServerProtocol.V1 -> "/global/event"
    ServerProtocol.V2 -> "/api/event"
}

/** Adapts the released v2 event contract to the reducer's stable internal events. */
internal fun parseV2SseEvent(
    payload: JsonObject,
    json: Json,
    toolNames: MutableMap<String, String> = mutableMapOf(),
): SseEvent? {
    val type = payload["type"]?.jsonPrimitive?.contentOrNull ?: return null
    val data = payload["data"] as? JsonObject ?: return null
    fun str(key: String) = data[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    fun obj(key: String) = data[key] as? JsonObject ?: JsonObject(emptyMap())
    val sessionId = str("sessionID")
    val messageId = str("assistantMessageID")
    val timestamp = data["started"]?.jsonPrimitive?.longOrNull
        ?: payload["created"]?.jsonPrimitive?.longOrNull ?: 0L
    val ordinal = data["ordinal"]?.jsonPrimitive?.intOrNull ?: 0
    val textId = "$messageId:text:$ordinal"
    val reasoningId = "$messageId:reasoning:$ordinal"
    val callId = str("id")
    val toolKey = "$sessionId:$messageId:$callId"
    return when (type) {
        "server.connected" -> SseEvent.ServerConnected
        "location.shutdown" -> SseEvent.ServerInstanceDisposed(
            (payload["location"] as? JsonObject)?.get("directory")?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
        "session.created" -> {
            val info = JsonObject(data + mapOf(
                "id" to JsonPrimitive(sessionId),
                "created" to JsonPrimitive(timestamp),
            ))
            SseEvent.SessionCreated(V2Protocol.session(json, info))
        }
        "session.deleted" -> SseEvent.SessionDeleted(V2Protocol.session(json,
            JsonObject(data + mapOf("id" to JsonPrimitive(sessionId), "created" to JsonPrimitive(timestamp))),
        ))
        "session.renamed" -> SseEvent.SessionRenamed(sessionId, str("title"))
        "session.moved" -> SseEvent.SessionMoved(
            sessionId,
            obj("location")["directory"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            obj("location")["workspaceID"]?.jsonPrimitive?.contentOrNull,
            str("projectID"),
        )
        "session.status" -> SseEvent.SessionStatus(sessionId, V2Protocol.sessionStatus(obj("status")))
        "session.execution.started" -> SseEvent.SessionStatus(sessionId, SessionStatus.Busy)
        "session.execution.succeeded", "session.execution.interrupted", "session.idle" ->
            SseEvent.SessionIdle(sessionId)
        "session.execution.failed" -> parseSessionError(data, json)
        "session.retry.scheduled" -> SseEvent.SessionStatus(sessionId, SessionStatus.Retry(
            attempt = data["attempt"]?.jsonPrimitive?.intOrNull ?: 0,
            message = obj("error")["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            next = data["at"]?.jsonPrimitive?.longOrNull ?: 0,
        ))
        "session.compaction.ended" -> SseEvent.SessionCompacted(sessionId)
        "session.shell.started" -> {
            val shell = obj("shell")
            val eventId = payload["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            SseEvent.NextShellStarted(
                sessionId = sessionId,
                messageId = eventId.replaceFirst(Regex("^evt_"), "msg_"),
                callId = shell["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                command = shell["command"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                timestamp = timestamp,
                standalone = true,
                metadata = JsonObject(shell.filterKeys { it in setOf("status", "exit") }),
            )
        }
        "session.shell.ended" -> {
            val shell = obj("shell")
            SseEvent.NextShellEnded(
                sessionId, shell["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                obj("output")["output"]?.jsonPrimitive?.contentOrNull.orEmpty(), timestamp,
                metadata = JsonObject(shell.filterKeys { it in setOf("status", "exit") } +
                    mapOf("output" to obj("output"))),
            )
        }
        "session.message.content.updated" -> {
            val info = JsonObject(data + mapOf("id" to JsonPrimitive(str("messageID")),
                "type" to JsonPrimitive("assistant")))
            val message = V2Protocol.message(json, info, sessionId) ?: return null
            SseEvent.MessageContentUpdated(sessionId, message.info.id, message.parts)
        }
        "session.step.started" -> {
            val model = obj("model")
            SseEvent.NextStepStarted(sessionId, messageId, str("agent"),
                JsonObject(model + mapOf("modelID" to (model["id"] ?: JsonPrimitive("")))), timestamp)
        }
        "session.step.ended" -> SseEvent.NextStepEnded(
            sessionId, messageId, str("finish"), data["cost"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            obj("tokens"), timestamp,
        )
        "session.step.failed" -> {
            val error = obj("error")
            SseEvent.NextStepFailed(sessionId, messageId, buildJsonObject {
                put("name", error["type"] ?: JsonPrimitive("Error"))
                put("data", error)
            }, timestamp)
        }
        "session.text.started" -> SseEvent.NextTextStarted(sessionId, messageId, textId, timestamp)
        "session.text.delta" -> SseEvent.NextTextDelta(sessionId, messageId, textId, str("delta"))
        "session.text.ended" -> SseEvent.NextTextEnded(sessionId, messageId, textId, str("text"), timestamp)
        "session.reasoning.started" -> SseEvent.NextReasoningStarted(sessionId, messageId, reasoningId, timestamp)
        "session.reasoning.delta" -> SseEvent.NextReasoningDelta(sessionId, messageId, reasoningId, str("delta"))
        "session.reasoning.ended" -> SseEvent.NextReasoningEnded(sessionId, messageId, reasoningId, str("text"), timestamp)
        "session.tool.input.started" -> {
            toolNames[toolKey] = str("name")
            SseEvent.NextToolInputStarted(sessionId, messageId, callId, str("name"), timestamp)
        }
        "session.tool.input.delta" -> SseEvent.NextToolInputDelta(sessionId, messageId, callId, str("delta"))
        "session.tool.input.ended" -> SseEvent.NextToolInputEnded(sessionId, messageId, callId, str("text"))
        "session.tool.called" -> SseEvent.NextToolCalled(
            sessionId, messageId, callId, toolNames[toolKey].orEmpty(), obj("input"), timestamp,
        )
        "session.tool.progress" -> SseEvent.NextToolProgress(
            sessionId, messageId, callId, obj("metadata"), JsonArray(emptyList()), timestamp,
        )
        "session.tool.success" -> {
            toolNames.remove(toolKey)
            SseEvent.NextToolSuccess(sessionId, messageId, callId, obj("metadata"),
                data["content"] ?: JsonArray(emptyList()), timestamp)
        }
        "session.tool.failed" -> {
            toolNames.remove(toolKey)
            SseEvent.NextToolFailed(sessionId, messageId, callId, obj("error"), timestamp,
                structured = data["metadata"], content = data["content"])
        }
        "session.inbox.enqueued" -> {
            val item = obj("item")
            if (item["type"]?.jsonPrimitive?.contentOrNull != "user") return null
            SseEvent.PromptEnqueued(sessionId, str("inboxID"),
                item["delivery"]?.jsonPrimitive?.contentOrNull.orEmpty(), item["payload"], timestamp)
        }
        "session.inbox.delivered" -> SseEvent.PromptDelivered(sessionId, str("inboxID"))
        "session.inbox.cancelled" -> SseEvent.PromptCancelled(sessionId, str("inboxID"))
        "permission.asked" -> V2Protocol.permission(json, data).let { request ->
            SseEvent.PermissionAsked(request.id, request.sessionId, request.permission, request.patterns,
                request.metadata, request.always, request.tool)
        }
        "permission.replied" -> SseEvent.PermissionReplied(sessionId, str("requestID"))
        "form.created" -> V2Protocol.question(json, obj("form"))?.let { request ->
            SseEvent.QuestionAsked(request.id, request.sessionId, request.questions.map { question ->
                SseEvent.QuestionAsked.Question(question.header, question.question, question.multiple,
                    question.custom, question.options.map { SseEvent.QuestionAsked.Option(it.label, it.description, it.value) },
                    question.key)
            }, request.tool)
        }
        "form.replied" -> SseEvent.QuestionReplied(sessionId, callId)
        "form.cancelled" -> SseEvent.QuestionRejected(sessionId, callId)
        else -> null
    }
}

internal fun parseSessionError(props: JsonObject, json: Json): SseEvent.SessionError {
    val sessionId = props["sessionID"]?.jsonPrimitive?.contentOrNull
    val error = when (val value = props["error"]) {
        is JsonObject -> if (value["name"] == null && value["message"] != null) {
            Message.Assistant.ErrorInfo(
                name = value["type"]?.jsonPrimitive?.contentOrNull ?: "Error",
                data = value,
            )
        } else json.decodeFromJsonElement<Message.Assistant.ErrorInfo>(value)
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
