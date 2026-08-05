package dev.minios.ocremote.ui.screens.server

import dev.minios.ocremote.logging.AppLogger as Log
import dev.minios.ocremote.BuildConfig
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.api.AgentInfo
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ProviderAuthMethod
import dev.minios.ocremote.data.api.ProviderInfo
import dev.minios.ocremote.data.api.ProviderModel
import dev.minios.ocremote.data.api.ProviderOauthAuthorization
import dev.minios.ocremote.data.api.ProviderAuthException
import dev.minios.ocremote.data.api.ServerConfigPatch
import dev.minios.ocremote.data.api.ServerConfigResponse
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.data.repository.DiagnosticLogRepository
import dev.minios.ocremote.data.repository.LocalServerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ServerSettingsViewModel"

internal fun shouldSuggestLocalProxy(
    isLocalServer: Boolean,
    proxyConfigured: Boolean,
    statusCode: Int?,
    message: String,
): Boolean {
    if (!isLocalServer || proxyConfigured) return false
    return statusCode?.let { it >= 500 } == true ||
        listOf("403", "network", "connect", "token exchange", "timeout", "dns")
            .any { message.contains(it, ignoreCase = true) }
}

data class ServerSettingsUiState(
    val serverName: String = "",
    val providers: List<ProviderToggle> = emptyList(),
    val modelOptions: List<ModelOption> = emptyList(),
    val agentOptions: List<String> = emptyList(),
    val selectedModel: String? = null,
    val selectedSmallModel: String? = null,
    val selectedDefaultAgent: String? = null,
    val groups: List<ModelGroup> = emptyList(),
    val authMethods: Map<String, List<ProviderAuthMethod>> = emptyMap(),
    val pendingOauth: PendingOauth? = null,
    val isSaving: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    val oauthProxyHint: Boolean = false,
)

data class PendingOauth(
    val providerId: String,
    val providerName: String,
    val methodIndex: Int,
    val authorization: ProviderOauthAuthorization,
    val fallbackFromHeadless: Boolean = false,
)

data class ProviderToggle(
    val providerId: String,
    val providerName: String,
    val source: String? = null,
    val connected: Boolean = false,
    val hasPaidModels: Boolean = false,
    val enabled: Boolean
)

data class ModelOption(
    val key: String,
    val label: String
)

data class ModelGroup(
    val providerId: String,
    val providerName: String,
    val models: List<ModelToggle>
)

data class ModelToggle(
    val modelId: String,
    val modelName: String,
    val visible: Boolean
)

@HiltViewModel
class ServerSettingsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: OpenCodeApi,
    private val settingsRepository: SettingsRepository,
    private val diagnosticLogRepository: DiagnosticLogRepository,
) : ViewModel() {

    private val serverUrl: String = savedStateHandle.get<String>("serverUrl").orEmpty()
    private val username: String = savedStateHandle.get<String>("username").orEmpty()
    private val password: String = savedStateHandle.get<String>("password").orEmpty()
    private val serverId: String = savedStateHandle.get<String>("serverId").orEmpty()
    private val serverName: String = savedStateHandle.get<String>("serverName").orEmpty()

    private val conn = ServerConnection.from(
        serverUrl,
        username,
        password.ifEmpty { null },
        allowSelfSigned = savedStateHandle.get<Boolean>("allowSelfSigned") ?: false,
    )

    private val _allProviders = MutableStateFlow<List<ProviderInfo>>(emptyList())
    private val _providerCatalog = MutableStateFlow<List<ProviderInfo>>(emptyList())
    private val _providerConnected = MutableStateFlow<Set<String>>(emptySet())
    private val _agents = MutableStateFlow<List<AgentInfo>>(emptyList())
    private val _config = MutableStateFlow(ServerConfigResponse())
    private val _authMethods = MutableStateFlow<Map<String, List<ProviderAuthMethod>>>(emptyMap())
    private val _hiddenModels = MutableStateFlow<Set<String>>(emptySet())
    private val _uiState = MutableStateFlow(ServerSettingsUiState(serverName = serverName, isLoading = true))
    val uiState: StateFlow<ServerSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.hiddenModels(serverId).collect { hidden ->
                _hiddenModels.value = hidden
                rebuildUi()
            }
        }
        loadProviders()
        loadConfig()
        loadAgents()
        loadAuthMethods()
    }

    fun loadProviders() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val response = api.getProviders(conn)
                _allProviders.value = response.providers
                val catalog = api.listProviderCatalog(conn)
                if (BuildConfig.DEBUG) Log.d(TAG, "loadProviders: catalog.connected=${catalog.connected}")
                _providerCatalog.value = catalog.all
                _providerConnected.value = catalog.connected.toSet()
                _config.value = api.getGlobalConfig(conn)
                rebuildUi()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load providers", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load providers"
                    )
                }
            }
        }
    }

    private fun loadAuthMethods() {
        viewModelScope.launch {
            try {
                _authMethods.value = api.getProviderAuthMethods(conn)
                rebuildUi()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load auth methods", e)
            }
        }
    }

    private fun loadConfig() {
        viewModelScope.launch {
            try {
                _config.value = api.getGlobalConfig(conn)
                rebuildUi()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load config", e)
            }
        }
    }

    private fun loadAgents() {
        viewModelScope.launch {
            try {
                _agents.value = api.listAgents(conn)
                rebuildUi()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load agents", e)
            }
        }
    }

    fun setProviderEnabled(providerId: String, enabled: Boolean) {
        viewModelScope.launch {
            val before = _config.value
            val current = before.disabledProviders.toSet()
            val next = if (enabled) current - providerId else current + providerId
            _config.value = before.copy(disabledProviders = next.toList().sorted())
            rebuildUi()
            try {
                api.updateGlobalConfig(conn, ServerConfigPatch(disabledProviders = next.toList().sorted()))
                _config.value = api.getGlobalConfig(conn)
                rebuildUi()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update provider state", e)
                _config.value = before
                _uiState.update { it.copy(error = e.message ?: "Failed to update provider") }
                rebuildUi()
            }
        }
    }

    fun connectProviderApi(providerId: String, apiKey: String) {
        if (apiKey.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                val updated = api.setProviderApiKey(conn, providerId, apiKey.trim())
                if (!updated) {
                    _uiState.update { it.copy(isSaving = false, error = "Failed to connect provider") }
                    return@launch
                }
                // Ensure provider is enabled after successful connect
                val disabled = _config.value.disabledProviders.toSet() - providerId
                api.updateGlobalConfig(conn, ServerConfigPatch(disabledProviders = disabled.toList().sorted()))
                _config.value = api.getGlobalConfig(conn)
                loadProviders()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect provider via API key", e)
                _uiState.update { it.copy(error = e.message ?: "Failed to connect provider") }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun startProviderOauth(providerId: String, methodIndex: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                var auth = api.authorizeProviderOauth(conn, providerId, methodIndex)

                if (auth == null) {
                    _uiState.update { it.copy(isSaving = false, error = "OAuth is not available for this provider") }
                    return@launch
                }
                val providerName = (_providerCatalog.value.find { it.id == providerId }?.name ?: providerId)
                _uiState.update {
                    it.copy(
                        pendingOauth = PendingOauth(
                            providerId = providerId,
                            providerName = providerName,
                            methodIndex = methodIndex,
                            authorization = auth,
                            fallbackFromHeadless = false,
                        ),
                        isSaving = false
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start provider oauth", e)
                val proxyHint = recordOauthFailure("authorize", providerId, methodIndex, e)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        error = e.message ?: "Failed to start OAuth",
                        oauthProxyHint = proxyHint,
                    )
                }
            }
        }
    }

    fun completeProviderOauth(code: String?) {
        val pending = _uiState.value.pendingOauth ?: return
        // Prevent duplicate calls while already in progress
        if (_uiState.value.isSaving) return
        // Set isSaving synchronously before launching coroutine to prevent race
        _uiState.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            try {
                val oauthCode = if (pending.authorization.method == "code") code?.trim()?.ifEmpty { null } else null
                if (BuildConfig.DEBUG) Log.d(TAG, "completeProviderOauth: calling callback for ${pending.providerId}, method=${pending.methodIndex}")
                val completed = api.completeProviderOauth(conn, pending.providerId, pending.methodIndex, oauthCode)
                if (!completed) {
                    // Some server builds complete auth out-of-band and callback can return non-success.
                    // Refresh provider catalog before surfacing an error.
                    val catalog = api.listProviderCatalog(conn)
                    _providerCatalog.value = catalog.all
                    _providerConnected.value = catalog.connected.toSet()
                    _config.value = api.getGlobalConfig(conn)
                    if (pending.providerId in catalog.connected) {
                        _uiState.update { it.copy(pendingOauth = null) }
                        rebuildUi()
                        return@launch
                    }
                    _uiState.update { it.copy(isSaving = false, error = "Failed to complete OAuth") }
                    return@launch
                }
                val disabled = _config.value.disabledProviders.toSet() - pending.providerId
                api.updateGlobalConfig(conn, ServerConfigPatch(disabledProviders = disabled.toList().sorted()))
                _config.value = api.getGlobalConfig(conn)
                _uiState.update { it.copy(pendingOauth = null) }
                loadProviders()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to complete provider oauth", e)
                val proxyHint = recordOauthFailure("callback", pending.providerId, pending.methodIndex, e)
                _uiState.update {
                    it.copy(error = e.message ?: "Failed to complete OAuth", oauthProxyHint = proxyHint)
                }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun cancelProviderOauth() {
        _uiState.update { it.copy(pendingOauth = null, error = null, oauthProxyHint = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null, oauthProxyHint = false) }
    }

    private suspend fun recordOauthFailure(
        stage: String,
        providerId: String,
        methodIndex: Int,
        error: Exception,
    ): Boolean {
        val isLocal = serverUrl.trimEnd('/') == LocalServerManager.LOCAL_SERVER_URL
        val proxyEnabled = settingsRepository.localProxyEnabled.first()
        val proxyConfigured = proxyEnabled && settingsRepository.localProxyUrl.first().isNotBlank()
        val statusCode = (error as? ProviderAuthException)?.statusCode
        val message = error.message ?: error::class.simpleName.orEmpty()
        val proxyHint = shouldSuggestLocalProxy(isLocal, proxyConfigured, statusCode, message)
        diagnosticLogRepository.record(
            level = "ERROR",
            category = "Provider OAuth",
            message = message,
            details = mapOf(
                "stage" to stage,
                "provider" to providerId,
                "methodIndex" to methodIndex.toString(),
                "server" to if (isLocal) "local" else "remote",
                "httpStatus" to (statusCode?.toString() ?: "unknown"),
                "localProxyEnabled" to proxyEnabled.toString(),
                "localProxyConfigured" to proxyConfigured.toString(),
                "missingProxyLikely" to proxyHint.toString(),
            ),
        )
        return proxyHint
    }

    fun disconnectProvider(providerId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            try {
                if (BuildConfig.DEBUG) Log.d(TAG, "disconnectProvider: calling DELETE /auth/$providerId")
                val removed = api.removeProviderAuth(conn, providerId)
                if (BuildConfig.DEBUG) Log.d(TAG, "disconnectProvider: removed=$removed")
                if (!removed) {
                    _uiState.update { it.copy(isSaving = false, error = "Failed to disconnect provider") }
                    return@launch
                }

                val disposed = runCatching { api.disposeGlobal(conn) }.getOrElse { false }
                if (BuildConfig.DEBUG) Log.d(TAG, "disconnectProvider: disposed=$disposed")

                // Optimistically remove from connected set before reload
                _providerConnected.update { it - providerId }
                rebuildUi()
                loadProviders()

                if (!disposed) {
                    _uiState.update { it.copy(error = "Provider credentials removed, but failed to refresh server instance") }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disconnect provider", e)
                _uiState.update { it.copy(error = e.message ?: "Failed to disconnect provider") }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun setDefaultModel(model: String?) {
        viewModelScope.launch {
            updateConfigPatch(ServerConfigPatch(model = model))
        }
    }

    fun setSmallModel(model: String?) {
        viewModelScope.launch {
            updateConfigPatch(ServerConfigPatch(smallModel = model))
        }
    }

    fun setDefaultAgent(agent: String?) {
        viewModelScope.launch {
            updateConfigPatch(ServerConfigPatch(defaultAgent = agent))
        }
    }

    private suspend fun updateConfigPatch(patch: ServerConfigPatch) {
        val before = _config.value
        try {
            api.updateGlobalConfig(conn, patch)
            _config.value = api.getGlobalConfig(conn)
            rebuildUi()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update config", e)
            _config.value = before
            _uiState.update { it.copy(error = e.message ?: "Failed to update config") }
            rebuildUi()
        }
    }

    fun setModelVisible(providerId: String, modelId: String, visible: Boolean) {
        viewModelScope.launch {
            settingsRepository.setModelVisibility(serverId, providerId, modelId, visible)
        }
    }

    private fun rebuildUi() {
        val hidden = _hiddenModels.value
        val disabled = _config.value.disabledProviders.toSet()

        val providerSource = if (_providerCatalog.value.isNotEmpty()) _providerCatalog.value else _allProviders.value
        val providerToggles = providerSource
            .map {
                ProviderToggle(
                    providerId = it.id,
                    providerName = it.name.ifEmpty { it.id },
                    source = it.source,
                    connected = (it.id in _providerConnected.value) && (it.id !in disabled),
                    hasPaidModels = it.models.values.any { model -> (model.cost?.input ?: 0.0) > 0.0 },
                    enabled = it.id !in disabled
                )
            }
            .sortedWith(
                compareByDescending<ProviderToggle> { it.connected }
                    .thenBy { it.providerName.lowercase() }
            )

        val modelOptions = _allProviders.value
            .filter { it.id !in disabled }
            .flatMap { provider ->
                provider.models.values
                    .filter { modelVisible(hidden, provider.id, it) }
                    .map { model ->
                    ModelOption(
                        key = "${provider.id}/${model.id}",
                        label = "${provider.name.ifEmpty { provider.id }} / ${model.name}"
                    )
                    }
            }
            .sortedBy { it.label.lowercase() }

        val agentOptions = _agents.value
            .filter { it.mode != "subagent" && !it.hidden }
            .map { it.name }
            .distinct()
            .sorted()

        val groups = _allProviders.value
            .mapNotNull { provider ->
                val models = provider.models.values
                    .sortedBy { it.name.lowercase() }
                    .map { model ->
                        ModelToggle(
                            modelId = model.id,
                            modelName = model.name,
                            visible = modelVisible(hidden, provider.id, model)
                        )
                    }
                if (models.isEmpty()) return@mapNotNull null
                ModelGroup(
                    providerId = provider.id,
                    providerName = provider.name.ifEmpty { provider.id },
                    models = models
                )
            }
            .sortedBy { it.providerName.lowercase() }

        _uiState.update {
            it.copy(
                serverName = serverName,
                providers = providerToggles,
                modelOptions = modelOptions,
                agentOptions = agentOptions,
                selectedModel = _config.value.model,
                selectedSmallModel = _config.value.smallModel,
                selectedDefaultAgent = _config.value.defaultAgent,
                groups = groups,
                authMethods = _authMethods.value,
                pendingOauth = it.pendingOauth,
                isSaving = it.isSaving,
                isLoading = false,
                error = it.error
            )
        }
    }

    private fun modelVisible(hidden: Set<String>, providerId: String, model: ProviderModel): Boolean {
        return "$providerId:${model.id}" !in hidden
    }

}
