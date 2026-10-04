package dev.minios.ocremote.data.api

import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.MessageWithParts
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.Project
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.TimeInfo
import dev.minios.ocremote.domain.model.ToolRef
import dev.minios.ocremote.domain.model.ToolState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Adapts the released @opencode/client 2.0.22 contract to the existing UI models. */
object V2Protocol {
    fun session(json: Json, value: JsonObject): Session {
        val time = value.obj("time")
        val created = time?.long("created") ?: value.long("created") ?: 0L
        return Session(
            id = value.string("id") ?: value.required("sessionID"),
            slug = value.string("slug").orEmpty(),
            projectId = value.string("projectID").orEmpty(),
            directory = value.obj("location")?.string("directory") ?: value.string("directory").orEmpty(),
            parentId = value.string("parentID"),
            title = value.string("title"),
            version = value.string("version").orEmpty(),
            time = Session.Time(created, time?.long("updated") ?: created, archived = time?.long("archived")),
            permission = value.array("permissions")?.objects()?.map { rule ->
                Session.PermissionRule(rule.required("action"), rule.required("resource"), rule.required("effect"))
            },
            revert = value.obj("revert")?.let { revert ->
                Session.Revert(revert.required("messageID"), revert.string("partID"), revert.string("snapshot"))
            },
        )
    }

    fun sessionStatus(value: JsonObject): SessionStatus = when (value.string("type") ?: value.string("status")) {
        "running", "busy" -> SessionStatus.Busy
        "retry" -> SessionStatus.Retry(
            value.int("attempt") ?: 0,
            value.string("message").orEmpty(),
            value.long("next") ?: 0L,
        )
        else -> SessionStatus.Idle
    }

    /** SessionMessageInfo omits its session ID; callers supply the containing session. */
    fun message(json: Json, value: JsonObject, sessionId: String, parentId: String = ""): MessageWithParts? =
        message(json, JsonObject(value + mapOf("sessionID" to JsonPrimitive(sessionId), "parentID" to JsonPrimitive(parentId))))

    fun message(json: Json, value: JsonObject): MessageWithParts? {
        val type = value.string("type") ?: return null
        if (type in setOf("agent-switched", "model-switched", "location-switched", "idle")) return null
        if (type !in setOf("user", "assistant", "synthetic", "system", "skill", "shell", "compaction")) return null
        val id = value.required("id")
        val sessionId = value.required("sessionID")
        val time = value.obj("time")
        val created = time?.long("created") ?: 0L
        val completed = time?.long("completed")
        val parts = mutableListOf<Part>()
        val info: Message = if (type in setOf("user", "synthetic", "system", "skill")) {
            parts += Part.Text(
                "$id:text:0", sessionId, id, value.string("text").orEmpty(),
                synthetic = type != "user", metadata = value.obj("metadata"),
                time = Part.Text.Time(created, completed),
            )
            value.array("files")?.objects()?.forEachIndexed { index, file ->
                val mime = file.string("mime") ?: "application/octet-stream"
                val data = file.string("data")
                parts += Part.File(
                    "$id:file:$index", sessionId, id, mime, file.string("name"),
                    url = data?.let { "data:$mime;base64,$it" } ?: file.obj("source")?.string("uri"),
                    source = file["source"],
                )
            }
            value.array("agents")?.objects()?.forEachIndexed { index, agent ->
                parts += Part.Agent("$id:agent:$index", sessionId, id, agent.required("name"), agent["mention"])
            }
            value.array("skills")?.objects()?.forEachIndexed { index, skill ->
                parts += Part.Text("$id:skill:$index", sessionId, id,
                    skill.string("text") ?: skill.required("name"), synthetic = true, metadata = skill)
            }
            val model = value.obj("model")
            Message.User(id, sessionId, time = TimeInfo(created, completed), agent = value.string("agent"),
                model = model?.let { Message.User.Model(it.required("providerID"), it.required("id")) },
                variant = model?.string("variant"))
        } else {
            val model = value.obj("model")
            when (type) {
                "assistant" -> {
                    // The server maintains independent fragment ordinals for text and reasoning.
                    val ordinals = mutableMapOf<String, Int>()
                    value.array("content")?.objects()?.forEach { content ->
                        val contentType = content.required("type")
                        val ordinal = ordinals[contentType] ?: 0
                        ordinals[contentType] = ordinal + 1
                        parts += part(json, content, sessionId, id, ordinal, created)
                        // Error states have no output fields in the legacy ToolState.Error model.
                        if (contentType == "tool" && content.obj("state")?.string("status") == "error") {
                            content.obj("state")?.array("content")?.objects()?.forEachIndexed { outputIndex, output ->
                                val outputId = "${content.required("id")}:output:$outputIndex"
                                when (output.string("type")) {
                                    "text" -> parts += Part.Text(outputId, sessionId, id, output.string("text").orEmpty())
                                    "file" -> parts += filePart(output, outputId, sessionId, id)
                                }
                            }
                        }
                    }
                }
                "shell" -> parts += shellPart(value, sessionId)
                "compaction" -> {
                    parts += Part.Compaction("$id:compaction", sessionId, id, value.string("reason") == "auto")
                    value.string("summary")?.takeIf { it.isNotEmpty() }?.let {
                        parts += Part.Text("$id:summary", sessionId, id, it, synthetic = true)
                    }
                    value.string("recent")?.takeIf { it.isNotEmpty() }?.let {
                        parts += Part.Text("$id:recent", sessionId, id, it, synthetic = true)
                    }
                }
            }
            Message.Assistant(
                id, sessionId, time = TimeInfo(created, completed), parentId = value.string("parentID").orEmpty(),
                modelId = model?.string("id"), providerId = model?.string("providerID"),
                agent = value.string("agent"), variant = model?.string("variant"),
                cost = value.double("cost"), tokens = value.obj("tokens")?.let(::tokens),
                finish = value.string("finish"), error = value.obj("error")?.let(::error),
                summary = type == "compaction",
            )
        }
        return MessageWithParts(info, parts)
    }

    /** [index] is the server fragment ordinal for this content type, not its global array position. */
    fun part(
        json: Json,
        value: JsonObject,
        sessionId: String,
        messageId: String,
        index: Int = 0,
        created: Long = 0L,
    ): Part {
        val type = value.required("type")
        val id = value.string("id") ?: "$messageId:$type:$index"
        val time = value.obj("time")
        return when (type) {
            "text" -> Part.Text(id, sessionId, messageId, value.string("text").orEmpty(),
                metadata = value.obj("state"), time = Part.Text.Time(created))
            "reasoning" -> Part.Reasoning(id, sessionId, messageId, value.string("text").orEmpty(),
                metadata = value.obj("state"), time = time?.let {
                    Part.Reasoning.Time(it.long("created") ?: created, it.long("completed"))
                })
            "tool" -> Part.Tool(id, sessionId, messageId, value.required("id"), value.required("name"),
                toolState(json, value.requiredObject("state"), time, sessionId, messageId, id),
                metadata = value.obj("providerState"))
            "file" -> filePart(value, id, sessionId, messageId)
            else -> Part.Unknown(id, sessionId, messageId)
        }
    }

    private fun shellPart(value: JsonObject, sessionId: String): Part.Tool {
        val id = value.required("id")
        val time = value.obj("time")
        val start = time?.long("created") ?: 0L
        val input = mapOf("command" to JsonPrimitive(value.required("command")))
        val output = value.obj("output")?.string("output").orEmpty()
        val metadata = JsonObject(value.filterKeys { it in setOf("status", "exit", "output") })
        val state = if (value.string("status") == "running") {
            ToolState.Running(input, value.string("command"), metadata, ToolState.Running.Time(start))
        } else {
            ToolState.Completed(input, output, value.string("command"), metadata,
                ToolState.Completed.Time(start, time?.long("completed") ?: start))
        }
        return Part.Tool(value.string("shellID") ?: "$id:shell", sessionId, id,
            value.string("shellID") ?: id, "bash", state)
    }

    private fun toolState(
        json: Json,
        state: JsonObject,
        time: JsonObject?,
        sessionId: String,
        messageId: String,
        callId: String,
    ): ToolState {
        val input = state.obj("input").orEmpty()
        val metadata = state.obj("metadata")
        val start = time?.long("ran") ?: time?.long("created") ?: 0L
        val end = time?.long("completed") ?: start
        val title = metadata?.string("title")
        return when (state.required("status")) {
            "streaming" -> ToolState.Pending(raw = state.string("input"))
            "running" -> ToolState.Running(input, title, metadata, ToolState.Running.Time(start))
            "completed" -> {
                val content = state.array("content")?.objects().orEmpty()
                ToolState.Completed(
                    input, content.filter { it.string("type") == "text" }.joinToString("\n") { it.string("text").orEmpty() },
                    title, metadata, ToolState.Completed.Time(start, end),
                    attachments = content.mapIndexedNotNull { index, item ->
                        if (item.string("type") != "file") null else ToolState.Completed.Attachment(
                            id = "$callId:file:$index", sessionId = sessionId, messageId = messageId,
                            mime = item.string("mime") ?: "application/octet-stream", filename = item.string("name"),
                            url = item.string("uri"), source = item,
                        )
                    }.takeIf { it.isNotEmpty() },
                )
            }
            "error" -> ToolState.Error(input, state.obj("error")?.string("message") ?: "Tool execution failed",
                metadata, ToolState.Error.Time(start, end))
            else -> throw IllegalArgumentException("Unsupported OpenCode v2 tool status: ${state.string("status")}")
        }
    }

    private fun filePart(value: JsonObject, id: String, sessionId: String, messageId: String) = Part.File(
        id, sessionId, messageId, value.string("mime") ?: "application/octet-stream",
        value.string("name"), value.string("uri"), value,
    )

    private fun tokens(value: JsonObject) = Message.Assistant.Tokens(
        input = value.int("input") ?: 0, output = value.int("output") ?: 0,
        reasoning = value.int("reasoning") ?: 0,
        cache = value.obj("cache")?.let {
            Message.Assistant.Tokens.Cache(it.int("read") ?: 0, it.int("write") ?: 0)
        } ?: Message.Assistant.Tokens.Cache(),
    )

    private fun error(value: JsonObject) = Message.Assistant.ErrorInfo(value.string("type").orEmpty(), value)

    /** Agent selection on v2 uses id rather than its display name. */
    fun agent(json: Json, value: JsonObject) = AgentInfo(
        name = value.string("id") ?: value.required("name"), description = value.string("description"),
        mode = value.string("mode") ?: "primary", hidden = value.bool("hidden") ?: false, color = value.string("color"),
    )

    fun providers(json: Json, providers: JsonArray, models: JsonArray): ProviderCatalogResponse {
        val allModels = models.objects()
        val all = providers.objects().map { provider ->
            val id = provider.required("id")
            ProviderInfo(
                id, provider.string("name") ?: id, source = provider.string("package").orEmpty(),
                options = provider.obj("settings").orEmpty(),
                models = allModels.filter { it.string("providerID") == id && it.bool("enabled") != false }
                    .associate { item -> item.required("id") to model(item) },
            )
        }
        return ProviderCatalogResponse(all = all,
            connected = providers.objects().filter { provider ->
                provider.string("activation") != "disabled" &&
                    (provider.string("activation") == "enabled" || allModels.any {
                        it.string("providerID") == provider.string("id") && it.bool("enabled") == true
                    })
            }.map { it.required("id") })
    }

    private fun model(value: JsonObject): ProviderModel {
        val capabilities = value.obj("capabilities")
        val variants = value.array("variants")?.objects()?.associate { it.required("id") to it }
        val cost = value.array("cost")?.objects()?.firstOrNull()
        val limit = value.obj("limit")
        return ProviderModel(
            id = value.required("id"), providerId = value.required("providerID"), name = value.required("name"),
            family = value.string("family"), status = value.string("status") ?: "active",
            capabilities = capabilities?.let {
                ModelCapabilities(
                    reasoning = variants?.keys?.any { key -> key.contains("reason", true) || key in setOf("low", "medium", "high", "xhigh") } ?: false,
                    attachment = it.array("input")?.strings()?.any { input -> input != "text" } ?: false,
                    toolcall = it.bool("tools") ?: false,
                )
            },
            cost = cost?.let { c -> ModelCost(c.double("input") ?: 0.0, c.double("output") ?: 0.0,
                c.obj("cache")?.let { ModelCost.CacheCost(it.double("read") ?: 0.0, it.double("write") ?: 0.0) }) },
            limit = limit?.let { ModelLimit(it.int("context") ?: 0, it.int("input"), it.int("output") ?: 0) },
            variants = variants,
        )
    }

    fun project(json: Json, value: JsonObject): Project {
        val canonical = value.string("canonical") ?: value.string("directory").orEmpty()
        return Project(id = value.required("id"), worktree = canonical, name = value.string("name"),
            path = canonical, vcs = value.string("vcs"), directory = canonical,
            sandboxes = value.array("sandboxes")?.strings().orEmpty())
    }

    fun permission(json: Json, value: JsonObject) = PermissionRequest(
        id = value.required("id"), sessionId = value.required("sessionID"), permission = value.required("action"),
        patterns = value.array("resources")?.strings().orEmpty(), always = value.array("save")?.strings().orEmpty(),
        metadata = value.obj("metadata")?.let { metadata ->
            value["message"]?.let { JsonObject(metadata + ("message" to it)) } ?: metadata
        } ?: value["message"]?.let { JsonObject(mapOf("message" to it)) },
        tool = value.obj("source")?.takeIf { it.string("type") == "tool" }?.let {
            ToolRef(it.required("messageID"), it.required("id"))
        },
    )

    /** Forms requiring conditional rendering or external interaction need the server's web UI. */
    fun question(json: Json, value: JsonObject): QuestionRequest? {
        val fields = value.array("fields")?.objects() ?: return null
        if (fields.isEmpty() || fields.any {
                it.bool("hidden") == true || !it.array("when").isNullOrEmpty() ||
                    it.string("type") !in setOf("string", "multiselect", "boolean", "number", "integer")
            }) return null
        return QuestionRequest(
            id = value.required("id"), sessionId = value.required("sessionID"),
            questions = fields.map { field ->
                val type = field.required("type")
                val title = field.string("title") ?: field.required("key")
                val options = if (type == "boolean") {
                    listOf(QuestionOption("true", "", "true"), QuestionOption("false", "", "false"))
                } else field.array("options")?.objects()?.map { option ->
                    QuestionOption(option.required("label"), option.string("description").orEmpty(), option.required("value"))
                }.orEmpty()
                QuestionInfo(
                    question = field.string("description")?.let { "$title\n$it" } ?: title,
                    header = field.string("title") ?: value.string("title") ?: title,
                    options = options, multiple = type == "multiselect",
                    custom = when (type) {
                        "boolean" -> false
                        "number", "integer" -> true
                        else -> field.bool("custom") ?: options.isEmpty()
                    },
                    key = field.required("key"),
                )
            },
        )
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.required(key: String): String = string(key)
        ?: throw IllegalArgumentException("Missing OpenCode v2 field: $key")
    private fun JsonObject.obj(key: String) = get(key) as? JsonObject
    private fun JsonObject.requiredObject(key: String): JsonObject = obj(key)
        ?: throw IllegalArgumentException("Missing OpenCode v2 object: $key")
    private fun JsonObject.array(key: String) = get(key) as? JsonArray
    private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
    private fun JsonObject.int(key: String) = (get(key) as? JsonPrimitive)?.intOrNull
    private fun JsonObject.double(key: String) = (get(key) as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.bool(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull
    private fun JsonArray.objects(): List<JsonObject> = map { it as? JsonObject
        ?: throw IllegalArgumentException("Expected OpenCode v2 object array") }
    private fun JsonArray.strings(): List<String> = map { (it as? JsonPrimitive)?.contentOrNull
        ?: throw IllegalArgumentException("Expected OpenCode v2 string array") }
}
