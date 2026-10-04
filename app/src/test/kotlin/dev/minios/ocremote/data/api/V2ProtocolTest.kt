package dev.minios.ocremote.data.api

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.ToolState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V2ProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun obj(raw: String) = json.parseToJsonElement(raw) as JsonObject
    private fun array(raw: String) = json.parseToJsonElement(raw) as JsonArray

    @Test
    fun sessionMapsLocationParentTimestampsAndPermissionRules() {
        val session = V2Protocol.session(json, obj("""{
          "id":"ses_1","parentID":"ses_parent","projectID":"prj_1",
          "location":{"directory":"/work/project"},"title":"Build app",
          "time":{"created":100,"updated":200,"archived":300},
          "cost":0.03,"tokens":{"input":10,"output":20,"reasoning":0,"cache":{"read":0,"write":0}},
          "permissions":[{"action":"fs.read","resource":"/work/*","effect":"ask"}],
          "revert":{"messageID":"msg_1","partID":"tool_1","snapshot":"snapshot_1"}
        }"""))
        assertEquals("ses_1", session.id)
        assertEquals("ses_parent", session.parentId)
        assertEquals("prj_1", session.projectId)
        assertEquals("/work/project", session.directory)
        assertEquals(100L, session.time.created)
        assertEquals(200L, session.time.updated)
        assertEquals(300L, session.time.archived)
        assertEquals("fs.read", session.permission!!.single().permission)
        assertEquals("/work/*", session.permission!!.single().pattern)
        assertEquals("ask", session.permission!!.single().action)
        assertEquals("tool_1", session.revert!!.partId)
        assertTrue(session.isArchived)
    }

    @Test
    fun createdEventSessionCanUseSessionIdAndEnvelopeTimestamp() {
        val session = V2Protocol.session(json, obj("""{
          "sessionID":"ses_new","projectID":"prj_1","created":789,
          "location":{"directory":"/work"},"slug":"new-chat","version":"2.0.22"
        }"""))
        assertEquals("ses_new", session.id)
        assertEquals(789L, session.time.created)
        assertEquals(789L, session.time.updated)
    }

    @Test
    fun forkOriginDoesNotMakeAnIndependentSessionASubagent() {
        val session = V2Protocol.session(json, obj("""{
          "id":"ses_fork","projectID":"prj_1","location":{"directory":"/work"},
          "time":{"created":100,"updated":100},
          "fork":{"sessionID":"ses_origin","boundary":{"type":"through","messageID":"msg_original"}}
        }"""))
        assertEquals("ses_fork", session.id)
        assertNull(session.parentId)
        assertFalse(session.isArchived)
    }

    @Test
    fun flattenedAssistantPreservesContentToolOutputsAndReferences() {
        val message = V2Protocol.message(json, obj("""{
          "id":"msg_a","type":"assistant","agent":"build",
          "model":{"id":"configured-model","providerID":"provider","variant":"high"},
          "time":{"created":100,"streamed":101,"completed":120},
          "cost":0.02,"finish":"tool-calls",
          "tokens":{"input":12,"output":13,"reasoning":4,"cache":{"read":5,"write":6}},
          "content":[
            {"type":"text","text":"Checking"},
            {"type":"reasoning","text":"Inspect first","time":{"created":101,"completed":105}},
            {"type":"tool","id":"tool_read","name":"read","time":{"created":106,"ran":107,"completed":110},
             "state":{"status":"completed","input":{"path":"/work/file"},"metadata":{"title":"Read file"},
               "content":[{"type":"text","text":"first"},{"type":"text","text":"second"},
                 {"type":"file","uri":"file:///work/image.png","mime":"image/png","name":"image.png"}]}}
          ]
        }"""), "ses_1", "msg_u")!!
        val info = message.info as Message.Assistant
        assertEquals("ses_1", info.sessionId)
        assertEquals("msg_u", info.parentId)
        assertEquals("configured-model", info.modelId)
        assertEquals("provider", info.providerId)
        assertEquals("high", info.variant)
        assertEquals(120L, info.time.completed)
        assertEquals(5, info.tokens!!.cache.read)
        assertEquals("msg_a:text:0", message.parts[0].id)
        assertEquals("msg_a:reasoning:0", message.parts[1].id)
        val tool = message.parts[2] as Part.Tool
        assertEquals("tool_read", tool.id)
        assertEquals("tool_read", tool.callId)
        val state = tool.state as ToolState.Completed
        assertEquals("first\nsecond", state.output)
        assertEquals(JsonPrimitive("/work/file"), state.input["path"])
        assertEquals(107L, state.time!!.start)
        assertEquals("file:///work/image.png", state.attachments!!.single().url)
        assertEquals("image.png", state.attachments!!.single().filename)
        assertEquals("ses_1", state.attachments!!.single().sessionId)
    }

    @Test
    fun mixedAssistantContentUsesIndependentTextAndReasoningOrdinals() {
        val message = V2Protocol.message(json, obj("""{
          "id":"msg_mixed","type":"assistant","agent":"build","model":{"id":"m","providerID":"p"},
          "time":{"created":100},"content":[
            {"type":"reasoning","text":"Reason first"},
            {"type":"text","text":"Before tool"},
            {"type":"tool","id":"call_1","name":"read","time":{"created":101},
             "state":{"status":"running","input":{},"metadata":{}}},
            {"type":"reasoning","text":"Reason again"},
            {"type":"text","text":"After tool"}
          ]
        }"""), "ses")!!
        assertEquals(listOf("msg_mixed:reasoning:0", "msg_mixed:text:0", "call_1",
            "msg_mixed:reasoning:1", "msg_mixed:text:1"), message.parts.map { it.id })
    }

    @Test
    fun toolStreamingAndStructuredFailureRemainVisible() {
        val pending = V2Protocol.part(json, obj("""{
          "type":"tool","id":"tool_1","name":"bash","time":{"created":1},
          "state":{"status":"streaming","input":"{\"command\":"}
        }"""), "ses", "msg") as Part.Tool
        assertEquals("{\"command\":", (pending.state as ToolState.Pending).raw)
        val message = V2Protocol.message(json, obj("""{
          "type":"assistant","id":"msg","agent":"build","model":{"id":"m","providerID":"p"},
          "time":{"created":1},"error":{"type":"provider","message":"Provider rejected"},
          "content":[{"type":"tool","id":"tool_1","name":"bash","time":{"created":2,"ran":3,"completed":4},
            "state":{"status":"error","input":{},"error":{"type":"tool","message":"Exit 1"},
              "content":[{"type":"text","text":"stderr details"},
                {"type":"file","uri":"file:///tmp/error.txt","mime":"text/plain","name":"error.txt"}]}}]
        }"""), "ses")!!
        assertEquals("Provider rejected", (message.info as Message.Assistant).error!!.message)
        assertEquals("Exit 1", ((message.parts[0] as Part.Tool).state as ToolState.Error).error)
        assertEquals("stderr details", (message.parts[1] as Part.Text).text)
        assertEquals("file:///tmp/error.txt", (message.parts[2] as Part.File).url)
    }

    @Test
    fun userAttachmentsBecomeRenderableFilesWithStableIds() {
        val message = V2Protocol.message(json, obj("""{
          "id":"msg_u","type":"user","time":{"created":10},"text":"Analyze this",
          "files":[{"data":"AA==","mime":"image/png","name":"picture.png","source":{"type":"inline"}}],
          "agents":[{"name":"build","mention":{"start":0,"end":5,"text":"build"}}],
          "skills":[{"id":"skill_review","name":"Review","text":"Review carefully"}]
        }"""), "ses_1")!!
        assertTrue(message.info is Message.User)
        assertEquals("Analyze this", (message.parts[0] as Part.Text).text)
        val file = message.parts[1] as Part.File
        assertEquals("msg_u:file:0", file.id)
        assertEquals("data:image/png;base64,AA==", file.url)
        assertEquals("picture.png", file.filename)
        assertEquals("build", (message.parts[2] as Part.Agent).name)
        assertEquals("Review carefully", (message.parts[3] as Part.Text).text)
    }

    @Test
    fun compactionAndShellStayVisibleWhileControlMessagesAreIgnored() {
        val compaction = V2Protocol.message(json, obj("""{
          "id":"compact","type":"compaction","time":{"created":10},"status":"completed",
          "reason":"auto","summary":"Summary of previous work","recent":"Recent context"
        }"""), "ses")!!
        assertTrue((compaction.parts[0] as Part.Compaction).auto)
        assertEquals("Summary of previous work", (compaction.parts[1] as Part.Text).text)
        assertEquals("Recent context", (compaction.parts[2] as Part.Text).text)
        val shell = V2Protocol.message(json, obj("""{
          "id":"msg_shell","type":"shell","shellID":"shell_1","command":"pwd","status":"exited","exit":0,
          "time":{"created":20,"completed":30},"output":{"output":"/work\n","cursor":6,"size":6,"truncated":false}
        }"""), "ses")!!
        val tool = shell.parts.single() as Part.Tool
        assertEquals("pwd", (tool.state as ToolState.Completed).input["command"]!!.toString().trim('"'))
        assertEquals("/work\n", (tool.state as ToolState.Completed).output)
        assertEquals(JsonPrimitive(0), (tool.state as ToolState.Completed).metadata!!["exit"])
        assertNull(V2Protocol.message(json, obj("""{"id":"idle","type":"idle","time":{"created":1},"outcome":"succeeded"}"""), "ses"))
    }

    @Test
    fun providersJoinSeparateModelsByProviderAndKeepSelectionModelId() {
        val catalog = V2Protocol.providers(json, array("""[
          {"id":"provider","name":"Provider","activation":"enabled","package":"provider-package"},
          {"id":"disabled","name":"Disabled","activation":"disabled","package":"provider-package"}
        ]"""), array("""[
          {"id":"configured-model","modelID":"upstream-name","providerID":"provider","name":"Display name",
           "capabilities":{"tools":true,"input":["text","image"],"output":["text"]},
           "variants":[{"id":"high","settings":{"reasoningEffort":"high"}}],"status":"active","enabled":true,
           "time":{"released":1},"cost":[{"input":1,"output":2,"cache":{"read":0.1,"write":0.2}}],
           "limit":{"context":128000,"output":4096}},
          {"id":"off","modelID":"off","providerID":"provider","name":"Off","enabled":false}
        ]"""))
        assertEquals(listOf("provider"), catalog.connected)
        assertEquals(2, catalog.all.size)
        val model = catalog.all.first().models.values.single()
        assertEquals("configured-model", model.id)
        assertEquals("provider", model.providerId)
        assertEquals("Display name", model.name)
        assertTrue(model.capabilities!!.toolcall)
        assertTrue(model.capabilities!!.attachment)
        assertTrue(model.variants!!.containsKey("high"))
        assertEquals(128000, model.limit!!.context)
        assertEquals(1.0, model.cost!!.input, 0.0)
    }

    @Test
    fun automaticallyActivatedProvidersWithAvailableModelsRemainSelectable() {
        val catalog = V2Protocol.providers(json, array("""[
          {"id":"auto","name":"Automatic","activation":"auto","package":"provider-package"},
          {"id":"unconnected","name":"Unconnected","activation":"auto","package":"provider-package"},
          {"id":"disabled","name":"Disabled","activation":"disabled","package":"provider-package"}
        ]"""), array("""[
          {"id":"model","providerID":"auto","name":"Available model","enabled":true},
          {"id":"off","providerID":"unconnected","name":"Unavailable model","enabled":false},
          {"id":"disabled-model","providerID":"disabled","name":"Disabled model","enabled":true}
        ]"""))
        assertEquals(listOf("auto"), catalog.connected)
    }

    @Test
    fun agentUsesSelectionIdAndProjectUsesCanonicalPath() {
        val agent = V2Protocol.agent(json, obj("""{
          "id":"agent_build","name":"Build display","description":"Build projects","mode":"primary","hidden":false,
          "request":{"settings":{},"headers":{},"body":{}},"permissions":[]
        }"""))
        assertEquals("agent_build", agent.name)
        assertFalse(agent.hidden)
        val project = V2Protocol.project(json, obj("""{
          "id":"prj","canonical":"/work/project","name":"Project","vcs":"git",
          "time":{"created":1,"updated":2,"active":3},"sandboxes":[]
        }"""))
        assertEquals("/work/project", project.worktree)
        assertEquals("/work/project", project.directory)
        assertEquals("Project", project.displayName)
    }

    @Test
    fun permissionMapsResourcesSaveAndSourceToolId() {
        val permission = V2Protocol.permission(json, obj("""{
          "id":"perm","sessionID":"ses","action":"fs.read","resources":["/work/*"],"save":["/work/**"],
          "source":{"type":"tool","messageID":"msg","id":"tool_1"},"message":"Read source files?"
        }"""))
        assertEquals("fs.read", permission.permission)
        assertEquals(listOf("/work/*"), permission.patterns)
        assertEquals(listOf("/work/**"), permission.always)
        assertEquals("tool_1", permission.tool!!.callId)
        assertEquals(JsonPrimitive("Read source files?"), permission.metadata!!["message"])
    }

    @Test
    fun formQuestionPreservesFieldKeysAndOptionValuesApartFromLabels() {
        val question = V2Protocol.question(json, obj("""{
          "id":"form","sessionID":"ses","title":"Choose","fields":[
            {"key":"color","type":"string","title":"Color","description":"Choose a color","custom":false,
             "options":[{"value":"red_value","label":"Red display","description":"Warm"}]},
            {"key":"features","type":"multiselect","options":[{"value":"feature_a","label":"Feature A"}]},
            {"key":"confirm","type":"boolean","title":"Confirm"}
          ]
        }"""))!!
        assertEquals("color", question.questions[0].key)
        assertEquals("Red display", question.questions[0].options.single().label)
        assertEquals("red_value", question.questions[0].options.single().value)
        assertFalse(question.questions[0].custom)
        assertTrue(question.questions[1].multiple)
        assertEquals("true", question.questions[2].options[0].value)
        assertNull(V2Protocol.question(json, obj("""{
          "id":"external","sessionID":"ses","title":"Auth","fields":[{"key":"login","type":"external","url":"https://example.test"}]
        }""")))
        assertNull(V2Protocol.question(json, obj("""{
          "id":"conditional","sessionID":"ses","title":"Condition","fields":[
            {"key":"choice","type":"string","when":[{"key":"other","op":"eq","value":"yes"}]}]
        }""")))
    }

    @Test
    fun runningStatusTranslatesToBusyAndRetryKeepsScheduling() {
        assertEquals(SessionStatus.Busy, V2Protocol.sessionStatus(obj("""{"type":"running"}""")))
        assertEquals(SessionStatus.Retry(2, "Retrying", 123L),
            V2Protocol.sessionStatus(obj("""{"type":"retry","attempt":2,"message":"Retrying","next":123}""")))
        assertEquals(SessionStatus.Idle, V2Protocol.sessionStatus(obj("""{"type":"idle"}""")))
    }
}
