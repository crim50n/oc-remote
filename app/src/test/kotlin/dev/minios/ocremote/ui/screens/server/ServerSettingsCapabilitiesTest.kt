package dev.minios.ocremote.ui.screens.server

import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import dev.minios.ocremote.data.api.FixtureEngine
import dev.minios.ocremote.data.api.FixtureResponse
import dev.minios.ocremote.data.api.MessageImageCache
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerProtocol
import dev.minios.ocremote.data.api.ServerProtocolRegistry
import dev.minios.ocremote.data.api.fixtureClient
import dev.minios.ocremote.data.repository.DiagnosticLogRepository
import dev.minios.ocremote.data.repository.ServerConnectionStateRepository
import dev.minios.ocremote.data.repository.SettingsRepository
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSettingsCapabilitiesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = object : ContextWrapper(null) {
        override fun getCacheDir(): File = temporaryFolder.root
    }

    private fun memoryDataStore(): DataStore<Preferences> = object : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    @Test
    fun `read only config blocks all setters but allows local model visibility`() = runBlocking {
        checkConfigMutations(readOnly = true)
    }

    @Test
    fun `writable config retains setters and offline guards`() = runBlocking {
        checkConfigMutations(readOnly = false)
    }

    private suspend fun checkConfigMutations(readOnly: Boolean) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ServerProtocolRegistry.clear()
        val json = Json { ignoreUnknownKeys = true }
        val patches = MutableStateFlow(0)
        val engine = FixtureEngine { request ->
            if (request.method == HttpMethod.Patch) patches.update { it + 1 }
            when (request.url.encodedPath) {
                "/api/info" -> FixtureResponse(status = 404)
                "/global/health" -> FixtureResponse("""{"healthy":true}""")
                "/global/config" -> FixtureResponse("""{"readOnly":$readOnly,"model":"p/m"}""")
                "/config/providers" -> FixtureResponse("""{"providers":[{"id":"p","name":"Provider","models":{"m":{"id":"m","name":"Model"}}}]}""")
                "/provider" -> FixtureResponse("""{"all":[{"id":"p","name":"Provider"}],"connected":["p"]}""")
                "/agent" -> FixtureResponse("[]")
                "/provider/auth" -> FixtureResponse("{}")
                else -> error("Unexpected request: ${request.url}")
            }
        }
        val client = fixtureClient(engine, json)
        val store = ViewModelStore()
        try {
            val dataStore = memoryDataStore()
            val settings = SettingsRepository(dataStore, json, context)
            val connection = ServerConnectionStateRepository()
            val viewModel = ServerSettingsViewModel(
                SavedStateHandle(mapOf("serverId" to "server", "serverUrl" to "https://settings.example")),
                OpenCodeApi(client, json, settings, MessageImageCache(context)),
                settings, DiagnosticLogRepository(dataStore, json, context), connection,
            )
            store.put("settings", viewModel)

            assertTrue(viewModel.uiState.value.readOnly)
            connection.updateConnectedServerIds(setOf("server"))
            withTimeout(5_000) { viewModel.uiState.first { it.hasLoadedProviders && it.selectedModel == "p/m" } }
            assertEquals(readOnly, viewModel.uiState.value.readOnly)

            viewModel.setDefaultModel("p/new")
            viewModel.setSmallModel("p/small")
            viewModel.setDefaultAgent("build")
            viewModel.setProviderEnabled("p", false)
            viewModel.setProviderEnabled("p", true)
            if (!readOnly) withTimeout(5_000) { patches.first { it == 5 } }
            assertEquals(if (readOnly) 0 else 5, patches.value)
            assertNull(viewModel.uiState.value.error)

            viewModel.setModelVisible("p", "m", false)
            withTimeout(5_000) { settings.hiddenModels("server").first { "p:m" in it } }
            withTimeout(5_000) { viewModel.uiState.first { it.groups.first().models.none { model -> model.visible } } }

            connection.updateConnectedServerIds(emptySet())
            viewModel.setDefaultModel("p/offline")
            viewModel.setSmallModel("p/offline")
            viewModel.setDefaultAgent("offline")
            viewModel.setProviderEnabled("p", false)
            assertEquals(if (readOnly) 0 else 5, patches.value)
            viewModel.setModelVisible("p", "m", true)
            withTimeout(5_000) { settings.hiddenModels("server").first { "p:m" !in it } }
        } finally {
            store.clear()
            client.close()
            ServerProtocolRegistry.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `MCP authentication requires known v1 protocol`() {
        assertFalse(supportsMcpAuthentication(null))
        assertFalse(supportsMcpAuthentication(ServerProtocol.V2))
        assertTrue(supportsMcpAuthentication(ServerProtocol.V1))
        assertFalse(ServerMcpUiState().canAuthenticate)
    }

    @Test
    fun `v2 MCP auth is disabled without blocking connection controls`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        ServerProtocolRegistry.clear()
        val json = Json { ignoreUnknownKeys = true }
        val engine = FixtureEngine { request ->
            when (request.url.encodedPath) {
                "/api/info" -> FixtureResponse("""{"version":"2.0.0"}""")
                "/api/mcp" -> FixtureResponse("""{"data":[{"name":"tool","status":{"status":"needs_auth"}}]}""")
                "/api/experimental/mcp/tool/connect", "/api/experimental/mcp/tool/disconnect" -> FixtureResponse()
                else -> error("Unsupported MCP request: ${request.url}")
            }
        }
        val client = fixtureClient(engine, json)
        val store = ViewModelStore()
        try {
            val settings = SettingsRepository(memoryDataStore(), json, context)
            val connection = ServerConnectionStateRepository()
            val viewModel = ServerMcpViewModel(
                SavedStateHandle(mapOf("serverId" to "server", "serverUrl" to "https://mcp.example")),
                OpenCodeApi(client, json, settings, MessageImageCache(context)), connection,
            )
            store.put("mcp", viewModel)
            connection.updateConnectedServerIds(setOf("server"))
            withTimeout(5_000) { viewModel.uiState.first { it.servers.isNotEmpty() && !it.isLoading } }
            assertFalse(viewModel.uiState.value.canAuthenticate)
            val requestCount = engine.requests.size
            viewModel.authenticate("tool")
            assertEquals(requestCount, engine.requests.size)
            assertNull(viewModel.uiState.value.error)
            assertNull(viewModel.uiState.value.loadingName)

            viewModel.connect("tool")
            withTimeout(5_000) { viewModel.uiState.first { it.loadingName == null } }
            assertTrue(engine.requests.any { it.url.encodedPath.endsWith("/tool/connect") })
            viewModel.disconnect("tool")
            withTimeout(5_000) { viewModel.uiState.first { it.loadingName == null } }
            assertTrue(engine.requests.any { it.url.encodedPath.endsWith("/tool/disconnect") })
        } finally {
            store.clear()
            client.close()
            ServerProtocolRegistry.clear()
            Dispatchers.resetMain()
        }
    }
}
