package dev.minios.ocremote.data.api

import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.data.repository.PromptDeliveryState
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.domain.model.ToolState
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V2SseEventParserTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun payload(type: String, data: String, created: Long = 1_000): JsonObject = buildJsonObject {
        put("id", "event-$type")
        put("type", type)
        put("created", created)
        put("location", json.parseToJsonElement("""{"directory":"/project","workspaceID":"workspace"}"""))
        put("data", json.parseToJsonElement(data))
    }

    private fun event(type: String, data: String, created: Long = 1_000): SseEvent =
        requireNotNull(parseV2SseEvent(payload(type, data, created), json))

    @Test
    fun usesReleasedV2RouteAndPreservesV1Route() {
        assertEquals("/api/event", sseEventPath(ServerProtocol.V2))
        assertEquals("/global/event", sseEventPath(ServerProtocol.V1))
    }

    @Test
    fun readsLocationAndDurableMetadataFromV2Envelope() {
        val client = HttpClient()
        try {
            val envelope = JsonObject(payload("session.text.started",
                """{"sessionID":"session","assistantMessageID":"message","ordinal":2}""").toMutableMap().apply {
                put("durable", json.parseToJsonElement("""{"aggregateID":"session","seq":5,"version":1}"""))
            })
            val parsed = requireNotNull(SseClient(client, json).parseEvent(envelope.toString()))
            assertEquals("/project", parsed.directory)
            assertEquals("workspace", parsed.workspaceId)
            assertEquals("event-session.text.started", parsed.eventId)
            assertEquals(5L, parsed.durableSeq)
        } finally {
            client.close()
        }
    }

    @Test
    fun textLifecycleUsesStableOrdinalPartIdAndEnvelopeTime() {
        val data = """{"sessionID":"session","assistantMessageID":"message","ordinal":2}"""
        val started = event("session.text.started", data) as SseEvent.NextTextStarted
        val delta = event("session.text.delta", """{"sessionID":"session","assistantMessageID":"message","ordinal":2,"delta":"Hello"}""") as SseEvent.NextTextDelta
        val ended = event("session.text.ended", """{"sessionID":"session","assistantMessageID":"message","ordinal":2,"text":"Hello world"}""", 2_000) as SseEvent.NextTextEnded
        assertEquals("message:text:2", started.textId)
        assertEquals(started.textId, delta.textId)
        assertEquals(started.textId, ended.textId)
        assertEquals(1_000L, started.timestamp)
        assertEquals(2_000L, ended.timestamp)
        assertEquals("Hello world", ended.text)
    }

    @Test
    fun reasoningOrdinalsRemainDistinctFromText() {
        val data = """{"sessionID":"session","assistantMessageID":"message","ordinal":1,"text":"Thinking","delta":"Think"}"""
        val started = event("session.reasoning.started", data) as SseEvent.NextReasoningStarted
        val delta = event("session.reasoning.delta", data) as SseEvent.NextReasoningDelta
        val ended = event("session.reasoning.ended", data) as SseEvent.NextReasoningEnded
        assertEquals("message:reasoning:1", started.reasoningId)
        assertEquals(started.reasoningId, delta.reasoningId)
        assertEquals(started.reasoningId, ended.reasoningId)
    }

    @Test
    fun stepUsesStartedTimeAndConvertsTerminalCostAndTokens() {
        val started = event("session.step.started", """{"sessionID":"session","assistantMessageID":"message","agent":"build","model":{"providerID":"openai","id":"model"},"started":900}""") as SseEvent.NextStepStarted
        val ended = event("session.step.ended", """{"sessionID":"session","assistantMessageID":"message","finish":"stop","cost":0.2,"tokens":{"input":5,"output":10,"reasoning":2,"cache":{"read":1,"write":0}}}""", 2_000) as SseEvent.NextStepEnded
        assertEquals(900L, started.timestamp)
        assertEquals("\"model\"", started.model.jsonObject["modelID"].toString())
        assertEquals("stop", ended.finish)
        assertEquals(0.2, ended.cost, 0.0)
        assertEquals(2_000L, ended.timestamp)
        assertEquals(json.parseToJsonElement("""{"input":5,"output":10,"reasoning":2,"cache":{"read":1,"write":0}}"""), ended.tokens)
    }

    @Test
    fun toolLifecyclePreservesNameAndMapsMetadataAndContent() {
        val reducer = EventReducer()
        val names = mutableMapOf<String, String>()
        val events = listOf(
            "session.tool.input.started" to """{"sessionID":"session","assistantMessageID":"message","id":"call","name":"bash"}""",
            "session.tool.input.delta" to """{"sessionID":"session","assistantMessageID":"message","id":"call","delta":"{\"command\":\"ls\"}"}""",
            "session.tool.input.ended" to """{"sessionID":"session","assistantMessageID":"message","id":"call","text":"{\"command\":\"ls\"}"}""",
            "session.tool.called" to """{"sessionID":"session","assistantMessageID":"message","id":"call","input":{"command":"ls"},"executed":true}""",
            "session.tool.progress" to """{"sessionID":"session","assistantMessageID":"message","id":"call","metadata":{"title":"Listing"}}""",
            "session.tool.success" to """{"sessionID":"session","assistantMessageID":"message","id":"call","content":[{"type":"text","text":"file.txt"}],"metadata":{"exit":0},"executed":true}""",
        )
        events.forEach { (type, data) ->
            reducer.processEvent(requireNotNull(parseV2SseEvent(payload(type, data), json, names)), "server")
        }
        val tool = reducer.parts.value["message"]?.single() as Part.Tool
        val completed = tool.state as ToolState.Completed
        assertEquals("call", tool.id)
        assertEquals("bash", tool.tool)
        assertEquals("file.txt", completed.output)
        assertEquals("\"ls\"", completed.input["command"].toString())
        assertEquals("0", completed.metadata?.get("exit").toString())
        assertTrue(names.isEmpty())
    }

    @Test
    fun permissionUsesV2ActionResourcesSaveAndSource() {
        val asked = event("permission.asked", """{"id":"request","sessionID":"session","action":"bash","resources":["ls"],"save":["ls *"],"source":{"type":"tool","messageID":"message","id":"call"}}""") as SseEvent.PermissionAsked
        assertEquals("bash", asked.permission)
        assertEquals(listOf("ls"), asked.patterns)
        assertEquals(listOf("ls *"), asked.always)
        assertEquals("call", asked.tool?.callId)
        assertEquals(SseEvent.PermissionReplied("session", "request"), event("permission.replied",
            """{"sessionID":"session","requestID":"request","reply":"once"}"""))
    }

    @Test
    fun formsConvertToQuestionsAndResolveUsingFormId() {
        val asked = event("form.created", """{"form":{"id":"form","sessionID":"session","title":"Choose","fields":[{"key":"choice","type":"string","title":"Target","options":[{"value":"prod","label":"Production","description":"Live"}],"custom":false}]}}""") as SseEvent.QuestionAsked
        assertEquals("form", asked.id)
        assertEquals("session", asked.sessionId)
        assertEquals("Production", asked.questions.single().options.single().label)
        assertEquals("prod", asked.questions.single().options.single().value)
        assertEquals("choice", asked.questions.single().key)
        assertEquals(false, asked.questions.single().custom)
        assertEquals(SseEvent.QuestionReplied("session", "form"), event("form.replied",
            """{"id":"form","sessionID":"session","answer":{"choice":"prod"}}"""))
        assertEquals(SseEvent.QuestionRejected("session", "form"), event("form.cancelled",
            """{"id":"form","sessionID":"session"}"""))
    }

    @Test
    fun statusAndExecutionEventsUpdateReducerWithoutV1StatusShapeChanges() {
        assertEquals(SseEvent.SessionStatus("session", SessionStatus.Busy), event("session.execution.started", """{"sessionID":"session"}"""))
        assertEquals(SseEvent.SessionIdle("session"), event("session.execution.succeeded", """{"sessionID":"session"}"""))
        val retry = event("session.status", """{"sessionID":"session","status":{"type":"retry","attempt":2,"message":"later","next":4000}}""") as SseEvent.SessionStatus
        assertEquals(SessionStatus.Retry(2, "later", 4_000), retry.status)
    }

    @Test
    fun preservesStructuredV2ErrorMessage() {
        val error = event("session.execution.failed", """{"sessionID":"session","error":{"type":"provider","message":"Quota exceeded","status":429}}""") as SseEvent.SessionError
        assertEquals("provider", error.error.name)
        assertEquals("Quota exceeded", error.error.message)
        val failed = event("session.step.failed", """{"sessionID":"session","assistantMessageID":"message","error":{"type":"provider","message":"Quota exceeded"}}""") as SseEvent.NextStepFailed
        assertEquals("\"provider\"", failed.error.jsonObject["name"].toString())
        assertEquals("\"Quota exceeded\"", failed.error.jsonObject["data"]?.jsonObject?.get("message").toString())
    }

    @Test
    fun partialRenameAndMovePreserveExistingSessionFields() {
        val reducer = EventReducer()
        val original = Session("session", "slug", "project", "/before", title = "Before", version = "2.0.22",
            time = Session.Time(100, 200))
        reducer.processEvent(SseEvent.SessionCreated(original), "server")
        reducer.processEvent(event("session.renamed", """{"sessionID":"session","title":"After"}"""), "server")
        reducer.processEvent(event("session.moved", """{"sessionID":"session","projectID":"new-project","location":{"directory":"/after","workspaceID":"new-workspace"}}"""), "server")
        assertEquals(original.copy(title = "After", directory = "/after", projectId = "new-project", workspaceId = "new-workspace"),
            reducer.sessions.value.single())
    }

    @Test
    fun parsesLegacyGlobalEnvelopeWithoutChangingMessageParts() {
        val client = HttpClient()
        try {
            val parsed = requireNotNull(SseClient(client, json).parseEvent(
                """{"directory":"/legacy","payload":{"type":"message.part.delta","properties":{"sessionID":"session","messageID":"message","partID":"part","field":"text","delta":"Hello"}}}""",
            ))
            assertEquals("/legacy", parsed.directory)
            assertEquals(SseEvent.MessagePartDelta("session", "message", "part", "text", "Hello"), parsed.event)
        } finally {
            client.close()
        }
    }

    @Test
    fun creationUsesSessionIdAndEnvelopeTimestampAndDeletionNeedsNoFullSession() {
        val created = event("session.created", """{"sessionID":"session","projectID":"project","location":{"directory":"/project"},"slug":"slug","title":"Title","version":"2.0.22"}""", 3_000) as SseEvent.SessionCreated
        assertEquals("session", created.info.id)
        assertEquals("/project", created.info.directory)
        assertEquals(Session.Time(3_000, 3_000), created.info.time)
        val deleted = event("session.deleted", """{"sessionID":"session"}""") as SseEvent.SessionDeleted
        assertEquals("session", deleted.info.id)
    }

    @Test
    fun inboxQueueIsAdmittedUntilDeliveryAndCancelledItemsAreRemoved() {
        val reducer = EventReducer()
        fun enqueue(id: String) = event("session.inbox.enqueued", """{"sessionID":"session","inboxID":"$id","item":{"type":"user","delivery":"queue","payload":{"text":"Queued prompt"}}}""")
        reducer.processEvent(enqueue("delivered"), "server")
        assertEquals(PromptDeliveryState.ADMITTED, reducer.promptDeliveries.value["delivered"]?.state)
        assertEquals("delivered", reducer.messages.value["session"]?.single()?.id)
        assertEquals("Queued prompt", (reducer.parts.value["delivered"]?.single() as Part.Text).text)
        reducer.mergeMessages("session", listOf(requireNotNull(V2Protocol.message(json,
            json.parseToJsonElement("""{"id":"delivered","type":"user","time":{"created":1000},"text":"Queued prompt"}""").jsonObject,
            "session"))))
        assertEquals(1, reducer.parts.value["delivered"]?.size)
        reducer.processEvent(event("session.inbox.delivered", """{"sessionID":"session","inboxID":"delivered"}"""), "server")
        assertEquals(PromptDeliveryState.PROMOTED, reducer.promptDeliveries.value["delivered"]?.state)
        reducer.processEvent(enqueue("cancelled"), "server")
        reducer.processEvent(event("session.inbox.cancelled", """{"sessionID":"session","inboxID":"cancelled"}"""), "server")
        assertEquals(listOf("delivered"), reducer.messages.value["session"]?.map { it.id })
        assertEquals(null, reducer.parts.value["cancelled"])
        assertEquals(null, reducer.promptDeliveries.value["cancelled"])
    }

    @Test
    fun formAndPermissionRepliesRemoveOnlyMatchingPendingInteraction() {
        val reducer = EventReducer()
        val permission = event("permission.asked", """{"id":"permission","sessionID":"session","action":"bash","resources":["ls"]}""")
        val form = event("form.created", """{"form":{"id":"form","sessionID":"session","title":"Question","fields":[{"key":"answer","type":"string"}]}}""")
        reducer.processEvent(permission, "server")
        reducer.processEvent(form, "server")
        assertEquals(2, reducer.pendingInteractions.value.size)
        reducer.processEvent(event("permission.replied", """{"sessionID":"session","requestID":"permission","reply":"once"}"""), "server")
        assertEquals(1, reducer.pendingInteractions.value.size)
        reducer.processEvent(event("form.cancelled", """{"sessionID":"session","id":"form"}"""), "server")
        assertTrue(reducer.pendingInteractions.value.isEmpty())
    }

    @Test
    fun authenticationFailureDuringNegotiationIsReportedAsNonRetryableSseAuthentication() = runBlocking {
        for (status in listOf(401, 403)) {
            ServerProtocolRegistry.clear()
            val engine = FixtureEngine { FixtureResponse("{}", status) }
            val client = fixtureClient(engine, json)
            try {
                val error = runCatching {
                    SseClient(client, json).connectToGlobalEvents(ServerConnection("https://auth.example", null)).toList()
                }.exceptionOrNull()
                assertTrue(error is SseAuthException)
                assertEquals("Authentication failed ($status)", error?.message)
                assertEquals(listOf("/api/info"), engine.requests.map { it.url.encodedPath })
            } finally {
                client.close()
                ServerProtocolRegistry.clear()
            }
        }
    }

    @Test
    fun failedToolsPreserveTextFilesAndMetadataAcrossSseAndRestMerge() {
        val reducer = EventReducer()
        reducer.processEvent(event("session.tool.input.started", """{"sessionID":"session","assistantMessageID":"message","id":"call","name":"bash"}"""), "server")
        reducer.processEvent(event("session.tool.called", """{"sessionID":"session","assistantMessageID":"message","id":"call","input":{"command":"ls"},"executed":true}"""), "server")
        val content = """[{"type":"text","text":"Partial output"},{"type":"file","uri":"file:///result.txt","mime":"text/plain","name":"result.txt"}]"""
        reducer.processEvent(event("session.tool.failed", """{"sessionID":"session","assistantMessageID":"message","id":"call","error":{"type":"tool","message":"Exit 1"},"content":$content,"metadata":{"exit":1},"executed":true}""", 2_000), "server")
        val parts = reducer.parts.value["message"].orEmpty()
        val tool = parts.filterIsInstance<Part.Tool>().single()
        val failed = tool.state as ToolState.Error
        assertEquals("Exit 1", failed.error)
        assertEquals("1", failed.metadata?.get("exit").toString())
        assertEquals("Partial output", parts.filterIsInstance<Part.Text>().single().text)
        assertEquals("call:output:0", parts.filterIsInstance<Part.Text>().single().id)
        assertEquals("file:///result.txt", parts.filterIsInstance<Part.File>().single().url)
        assertEquals("call:output:1", parts.filterIsInstance<Part.File>().single().id)
        val snapshot = V2Protocol.message(json, json.parseToJsonElement("""{"id":"message","type":"assistant","time":{"created":1000},"content":[{"type":"tool","id":"call","name":"bash","state":{"status":"error","input":{"command":"ls"},"error":{"type":"tool","message":"Exit 1"},"metadata":{"exit":1},"content":$content},"time":{"created":1000,"completed":2000}}]}""").jsonObject, "session")
        reducer.mergeMessages("session", listOf(requireNotNull(snapshot)))
        assertEquals(3, reducer.parts.value["message"]?.size)
    }

    @Test
    fun liveShellCreatesStableMessageAndMatchesRestAfterCompletion() {
        val reducer = EventReducer()
        val started = JsonObject(payload("session.shell.started", """{"sessionID":"session","shell":{"id":"shell","command":"ls","status":"running","metadata":{},"time":{"started":1000}}}""") + mapOf("id" to JsonPrimitive("evt_shell")))
        reducer.processEvent(requireNotNull(parseV2SseEvent(started, json)), "server")
        assertEquals("msg_shell", reducer.messages.value["session"]?.single()?.id)
        assertEquals("shell", reducer.parts.value["msg_shell"]?.single()?.id)
        reducer.processEvent(event("session.shell.ended", """{"sessionID":"session","shell":{"id":"shell","command":"ls","status":"exited","exit":0,"metadata":{},"time":{"started":1000,"completed":2000}},"output":{"output":"file.txt","cursor":8,"size":8,"truncated":false}}""", 2_000), "server")
        assertEquals(2_000L, reducer.messages.value["session"]?.single()?.time?.completed)
        val completed = (reducer.parts.value["msg_shell"]?.single() as Part.Tool).state as ToolState.Completed
        assertEquals("file.txt", completed.output)
        assertEquals("0", completed.metadata?.get("exit").toString())
        val snapshot = V2Protocol.message(json, json.parseToJsonElement("""{"id":"msg_shell","type":"shell","shellID":"shell","command":"ls","status":"exited","exit":0,"output":{"output":"file.txt","cursor":8,"size":8,"truncated":false},"time":{"created":1000,"completed":2000}}""").jsonObject, "session")
        reducer.mergeMessages("session", listOf(requireNotNull(snapshot)))
        assertEquals(1, reducer.parts.value["msg_shell"]?.size)
    }

    @Test
    fun authoritativeContentUpdateUsesIndependentFragmentOrdinalsAndReplacesOldParts() {
        val reducer = EventReducer()
        reducer.processEvent(event("session.text.started", """{"sessionID":"session","assistantMessageID":"message","ordinal":5}"""), "server")
        val content = """[{"type":"reasoning","text":"Thinking"},{"type":"text","text":"Answer"},{"type":"reasoning","text":"More thinking"},{"type":"text","text":"More answer"}]"""
        reducer.processEvent(event("session.message.content.updated", """{"sessionID":"session","messageID":"message","content":$content}"""), "server")
        assertEquals(listOf("message:reasoning:0", "message:text:0", "message:reasoning:1", "message:text:1"),
            reducer.parts.value["message"]?.map { it.id })
        val snapshot = V2Protocol.message(json, json.parseToJsonElement("""{"id":"message","type":"assistant","time":{"created":1000},"content":$content}""").jsonObject, "session")
        reducer.mergeMessages("session", listOf(requireNotNull(snapshot)))
        assertEquals(4, reducer.parts.value["message"]?.size)
    }

    @Test
    fun movingWithoutProjectIdPreservesOriginalProject() {
        val reducer = EventReducer()
        val original = Session("session", projectId = "project", directory = "/old", time = Session.Time(100, 200))
        reducer.processEvent(SseEvent.SessionCreated(original), "server")
        reducer.processEvent(event("session.moved", """{"sessionID":"session","location":{"directory":"/new"}}"""), "server")
        assertEquals("project", reducer.sessions.value.single().projectId)
        assertEquals("/new", reducer.sessions.value.single().directory)
    }
}
