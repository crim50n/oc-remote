package dev.minios.ocremote

import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.minios.ocremote.data.api.MessageIdGenerator
import dev.minios.ocremote.data.api.MessageImageCache
import dev.minios.ocremote.data.api.ModelSelection
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.OpenCodeV2Api
import dev.minios.ocremote.data.api.PromptPart
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.api.SseClient
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.di.NetworkModule
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.SseEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import io.ktor.http.HttpMethod
import java.util.Locale
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/** Opt-in live test: credentials stay in the target app and tools are denied. */
@RunWith(AndroidJUnit4::class)
class PhysicalV2RoundTripTest {
    @Test
    fun diagnosesExistingSmokeSessionWithoutSendingAgain() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val configuredUrl = args.getString("serverUrl")
        assumeTrue("Live server diagnostics require explicit instrumentation arguments", !configuredUrl.isNullOrBlank())
        var stage = "diagnostic-setup"
        try {
            val serverUrl = configuredUrl!!
            val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val json = NetworkModule.provideJson()
            val store = NetworkModule.provideDataStore(context)
            val encoded = store.data.first()[stringPreferencesKey("servers")] ?: fail(stage)
            val server = json.decodeFromString<List<ServerConfig>>(encoded).singleOrNull {
                it.url.trimEnd('/') == serverUrl.trimEnd('/')
            } ?: fail(stage)
            val conn = ServerConnection.from(server.url, server.username, server.password)
            val client = NetworkModule.provideHttpClient(json)
            try {
                val api = OpenCodeApi(client, json, SettingsRepository(store, json, context), MessageImageCache(context))
                val v2 = OpenCodeV2Api(client, json)
                val temporary = v2.payload(conn, "/api/info").jsonObject["paths"]!!.jsonObject["tmp"]!!.jsonPrimitive.content
                stage = "find-owned-test-session"
                val session = api.listSessions(conn, temporary).filter { it.title == "OC Remote V2 compatibility smoke test" }
                    .maxByOrNull { it.time.created } ?: fail(stage)
                stage = "read-owned-test-history"
                val messages = api.listMessages(conn, session.id, limit = 10)
                val assistants = messages.map { it.info }.filterIsInstance<Message.Assistant>()
                val errors = assistants.mapNotNull { it.error }
                val categories = errors.map { error ->
                    val text = error.message.lowercase(Locale.ROOT)
                    when {
                        "quota" in text || "credit" in text || "balance" in text -> "quota-or-credits"
                        "401" in text || "403" in text || "auth" in text || "credential" in text || "api key" in text -> "provider-authentication"
                        "context" in text || "too many token" in text || "token limit" in text -> "context-limit"
                        "variant" in text || "reasoning" in text || "parameter" in text -> "provider-request-options"
                        "model" in text && ("not found" in text || "unsupported" in text || "invalid" in text) -> "model-unavailable"
                        "timeout" in text || "timed out" in text || "connect" in text -> "provider-transport"
                        else -> "other-server-step-error"
                    }
                }.distinct()
                report("OCREMOTE_DIAGNOSTIC userMessages=" + messages.count { it.info is Message.User })
                report("OCREMOTE_DIAGNOSTIC assistantMessages=" + assistants.size)
                report("OCREMOTE_DIAGNOSTIC completedAssistants=" + assistants.count { it.time.completed != null })
                report("OCREMOTE_DIAGNOSTIC toolParts=" + messages.sumOf { it.parts.count { part -> part is Part.Tool } })
                val replies = messages.filter { it.info is Message.Assistant }
                report("OCREMOTE_DIAGNOSTIC assistantHasText=" + replies.any { it.parts.filterIsInstance<Part.Text>().any { part -> part.text.isNotBlank() } })
                report("OCREMOTE_DIAGNOSTIC markerInAssistantHistory=" + replies.any { it.parts.filterIsInstance<Part.Text>().any { part -> "OC_REMOTE_V2_OK" in part.text } })
                report("OCREMOTE_DIAGNOSTIC stepErrorCategories=" + categories.joinToString(","))
                report("OCREMOTE_DIAGNOSTIC insufficientQuotaOrCredits=" + errors.any { error ->
                    val text = error.message.lowercase(Locale.ROOT)
                    (listOf("quota", "credit", "balance", "crédito", "saldo").any { it in text }) &&
                        (listOf("insufficient", "not enough", "exceed", "exhaust", "no credit", "out of credit", "insuficiente", "sem crédito").any { it in text })
                })
            } finally {
                client.close()
            }
        } catch (_: Throwable) {
            throw AssertionError("Physical V2 round trip failed at stage: $stage")
        }
    }

    @Test
    fun sendsRequestedFlashModelAndObservesStreamAndHistory() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val configuredUrl = args.getString("serverUrl")
        val configuredModel = args.getString("requestedModel")
        assumeTrue("Live model test requires explicit instrumentation arguments",
            !configuredUrl.isNullOrBlank() && !configuredModel.isNullOrBlank())
        var stage = "arguments"
        try {
            val serverUrl = configuredUrl!!
            val requested = normalize(configuredModel!!)
            // The live invocation explicitly selects the operator-requested non-frontier model.
            check(requested == "qwen37flash")
            val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val json = NetworkModule.provideJson()
            val store = NetworkModule.provideDataStore(context)
            stage = "configured-server"
            val encoded = store.data.first()[stringPreferencesKey("servers")] ?: fail(stage)
            val server = json.decodeFromString<List<ServerConfig>>(encoded).singleOrNull {
                it.url.trimEnd('/') == serverUrl.trimEnd('/')
            } ?: fail(stage)
            val conn = ServerConnection.from(server.url, server.username, server.password)
            val client = NetworkModule.provideHttpClient(json)
            try {
                val api = OpenCodeApi(client, json, SettingsRepository(store, json, context), MessageImageCache(context))
                val v2 = OpenCodeV2Api(client, json)
                stage = "v2-health"
                check(api.getHealth(conn).let { it.healthy && it.version.orEmpty().startsWith("2.") })
                stage = "requested-model"
                val catalog = api.listProviderCatalog(conn)
                val candidates = catalog.all.filter { it.id in catalog.connected }.flatMap { provider ->
                    provider.models.values.filter { normalize(it.id).contains(requested) || normalize(it.name).contains(requested) }
                        .map { provider.id to it }
                }
                val (providerId, model) = candidates.firstOrNull() ?: fail(stage)
                val variants = model.variants?.keys.orEmpty()
                val variant = listOf("none", "minimal", "low", "fast", "medium", "high", "xhigh")
                    .firstOrNull { it in variants }
                check(variants.isEmpty() || variant != null)
                stage = "temporary-location"
                val info = v2.payload(conn, "/api/info").jsonObject
                val temporary = info["paths"]?.jsonObject?.get("tmp")?.jsonPrimitive?.contentOrNull ?: fail(stage)
                check(temporary.isNotBlank())
                stage = "create-isolated-session"
                val created = v2.payload(conn, "/api/session", HttpMethod.Post, buildJsonObject {
                    put("title", "OC Remote V2 compatibility smoke test")
                    putJsonObject("location") { put("directory", temporary) }
                    putJsonArray("permissions") {
                        add(buildJsonObject { put("action", "*"); put("resource", "*"); put("effect", "deny") })
                    }
                }).jsonObject["data"]!!.jsonObject
                val sessionId = created["id"]!!.jsonPrimitive.content
                stage = "verify-tools-denied"
                val session = api.getSession(conn, sessionId)
                check(session.permission.orEmpty().any { it.permission == "*" && it.pattern == "*" && it.action == "deny" })
                val opened = CompletableDeferred<Unit>()
                var textStreamed = false
                var stepCompleted = false
                var stepFailed = false
                var historyMatched = false
                var assistantError = false
                var toolObserved = false
                val stream = launch {
                    SseClient(client, json).connectToGlobalEvents(conn, onOpen = { opened.complete(Unit) }).collect { scoped ->
                        when (val event = scoped.event) {
                            is SseEvent.NextTextDelta -> if (event.sessionId == sessionId) textStreamed = true
                            is SseEvent.NextTextEnded -> if (event.sessionId == sessionId) textStreamed = true
                            is SseEvent.NextStepEnded -> if (event.sessionId == sessionId) stepCompleted = true
                            is SseEvent.NextStepFailed -> if (event.sessionId == sessionId) stepFailed = true
                            else -> Unit
                        }
                    }
                }
                try {
                    stage = "stream-open"
                    withTimeout(15_000) { opened.await() }
                    stage = "prompt-admission"
                    api.promptAsync(conn, sessionId, MessageIdGenerator.next(), listOf(
                        PromptPart("text", text = "Responda somente OC_REMOTE_V2_OK. Não use ferramentas, arquivos ou comandos."),
                    ), model = ModelSelection(providerId, model.id), variant = variant, directory = temporary)
                    stage = "response-and-history"
                    withTimeout(90_000) {
                        while (true) {
                            check(!stepFailed)
                            val messages = api.listMessages(conn, sessionId, limit = 10)
                            toolObserved = messages.any { message -> message.parts.any { it is Part.Tool } }
                            assistantError = messages.map { it.info }.filterIsInstance<Message.Assistant>().any { it.error != null }
                            check(!toolObserved)
                            check(!assistantError)
                            val complete = messages.any { message ->
                                val assistant = message.info as? Message.Assistant
                                assistant != null && assistant.time.completed != null && message.parts.filterIsInstance<Part.Text>()
                                    .joinToString("\n") { it.text }.contains("OC_REMOTE_V2_OK")
                            }
                            historyMatched = complete
                            if (complete && textStreamed && stepCompleted) break
                            delay(750)
                        }
                    }
                } finally {
                    report("OCREMOTE_DIAGNOSTIC streamText=$textStreamed stepCompleted=$stepCompleted stepFailed=$stepFailed historyMatched=$historyMatched assistantError=$assistantError toolObserved=$toolObserved")
                    stream.cancelAndJoin()
                }
            } finally {
                client.close()
            }
        } catch (_: Throwable) {
            // Never expose preferences, authentication, server replies, or original causes.
            throw AssertionError("Physical V2 round trip failed at stage: $stage")
        }
    }

    private fun report(value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(100, android.os.Bundle().apply {
            putString("ocremoteDiagnostic", value)
        })
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
    private fun fail(stage: String): Nothing = throw AssertionError("Physical V2 round trip failed at stage: $stage")
}
