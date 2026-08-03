package dev.minios.ocremote.ui.screens.home

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import dev.minios.ocremote.logging.AppLogger as Log
import androidx.annotation.StringRes
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.R
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerAuthenticationException
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.api.ServerHealthHttpException
import dev.minios.ocremote.data.repository.LocalServerManager
import dev.minios.ocremote.data.repository.ServerRepository
import dev.minios.ocremote.data.repository.normalizeServerUrl
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.data.repository.DiagnosticLogRepository
import dev.minios.ocremote.data.update.UpdateRepository
import dev.minios.ocremote.data.update.UpdateState
import dev.minios.ocremote.data.update.AvailableUpdate
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.service.OpenCodeConnectionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "HomeViewModel"
private const val LOCAL_SERVER_NAME = "Local OpenCode"

enum class LocalRuntimeStatus {
    Unavailable,
    NeedsSetup,
    Stopped,
    Starting,
    Stopping,
    Running,
    Error,
}

data class HomeUiState(
    val servers: List<ServerConfig> = emptyList(),
    val connectedServerIds: Set<String> = emptySet(),
    val serverSettingsReadyIds: Set<String> = emptySet(),
    val connectingServerIds: Set<String> = emptySet(),
    val connectionErrors: Map<String, String> = emptyMap(),
    val showAddServerDialog: Boolean = false,
    val editingServer: ServerConfig? = null,
    val isLoading: Boolean = true,
    val termuxInstalled: Boolean = false,
    val localRuntimeStatus: LocalRuntimeStatus = LocalRuntimeStatus.Unavailable,
    val localRuntimeMessage: String? = null,
    val localRuntimeFixCommand: String? = null,
    val localRuntimeNeedsOverlaySettings: Boolean = false,
    val setupCommand: String? = null,
    val showLocalRuntime: Boolean = true,
    val localProxyEnabled: Boolean = false,
    val localProxyUrl: String = "",
    val localProxyNoProxy: String = LocalServerManager.DEFAULT_NO_PROXY_LIST,
    val localServerAllowLan: Boolean = false,
    val localServerUsername: String = "",
    val localServerPassword: String = "",
    val localServerRunInBackground: Boolean = true,
    val localServerAutoStart: Boolean = false,
    val localServerStartupTimeoutSec: Int = 30,
    val updateState: UpdateState = UpdateState.Idle,
    val hasFavoriteSessions: Boolean? = null,
)

private data class LocalRuntimeErrorInfo(
    val message: String,
    val fixCommand: String? = null,
    val status: LocalRuntimeStatus = LocalRuntimeStatus.Error,
    val requiresOverlaySettings: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val serverRepository: ServerRepository,
    private val api: OpenCodeApi,
    private val localServerManager: LocalServerManager,
    private val settingsRepository: SettingsRepository,
    private val diagnosticLogRepository: DiagnosticLogRepository,
    private val updateRepository: UpdateRepository,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var serviceBinder: OpenCodeConnectionService.LocalBinder? = null
    private var sseObserverJob: Job? = null
    private val serverSettingsCheckJobs = mutableMapOf<String, Job>()
    private val connectionAttemptJobs = mutableMapOf<String, Job>()
    private val connectionAttemptGenerations = mutableMapOf<String, Int>()
    private var localAutoStartTriggered = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceBinder = service as? OpenCodeConnectionService.LocalBinder
            restoreConnectionStateFromService()
            observeServiceConnectionState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder = null
            sseObserverJob?.cancel()
            sseObserverJob = null
            serverSettingsCheckJobs.values.forEach { it.cancel() }
            serverSettingsCheckJobs.clear()
            _uiState.update {
                it.copy(
                    connectedServerIds = emptySet(),
                    serverSettingsReadyIds = emptySet(),
                )
            }
        }
    }

    init {
        loadServers()
        bindToService()
        observeSettings()
        observeFavoriteSessions()
        refreshLocalRuntimeState()
        viewModelScope.launch {
            updateRepository.state.collect { state -> _uiState.update { it.copy(updateState = state) } }
        }
        viewModelScope.launch {
            updateRepository.restore()
            updateRepository.check(manual = false)
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.showLocalRuntime.collect { enabled ->
                _uiState.update { it.copy(showLocalRuntime = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.localProxyEnabled.collect { enabled ->
                _uiState.update { it.copy(localProxyEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.localProxyUrl.collect { url ->
                _uiState.update { it.copy(localProxyUrl = url) }
            }
        }
        viewModelScope.launch {
            settingsRepository.localProxyNoProxy.collect { value ->
                _uiState.update { it.copy(localProxyNoProxy = value) }
            }
        }
        viewModelScope.launch {
            settingsRepository.localServerAllowLan.collect { enabled ->
                _uiState.update { it.copy(localServerAllowLan = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.localServerUsername.collect { value ->
                _uiState.update { it.copy(localServerUsername = value) }
            }
        }
        viewModelScope.launch {
            settingsRepository.localServerPassword.collect { value ->
                _uiState.update { it.copy(localServerPassword = value) }
            }
        }
        viewModelScope.launch {
            settingsRepository.localServerRunInBackground.collect { enabled ->
                _uiState.update { state ->
                    state.copy(
                        localServerRunInBackground = enabled,
                        localServerAutoStart = if (enabled) state.localServerAutoStart else false,
                    )
                }
                if (!enabled) {
                    settingsRepository.setLocalServerAutoStart(false)
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.localServerAutoStart.collect { enabled ->
                _uiState.update { it.copy(localServerAutoStart = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.localServerStartupTimeoutSec.collect { seconds ->
                _uiState.update { it.copy(localServerStartupTimeoutSec = seconds) }
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun observeFavoriteSessions() {
        viewModelScope.launch {
            serverRepository.servers.flatMapLatest { servers ->
                if (servers.isEmpty()) {
                    flowOf(false)
                } else {
                    combine(servers.map { settingsRepository.favoriteSessionIds(it.id) }) { favoritesByServer ->
                        favoritesByServer.any(List<String>::isNotEmpty)
                    }
                }
            }.collect { hasFavorites ->
                _uiState.update { it.copy(hasFavoriteSessions = hasFavorites) }
            }
        }
    }

    /**
     * Restore connected state from the already-running service.
     */
    private fun restoreConnectionStateFromService() {
        val service = serviceBinder?.getService() ?: return
        val ids = service.connectedServerIds.value
        if (ids.isNotEmpty()) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Restoring connected state from service: serverIds=$ids")
            _uiState.update { it.copy(connectedServerIds = ids) }
        }
    }

    /**
     * Observe connectedServerIds and connectingServerIds from the service.
     */
    private fun observeServiceConnectionState() {
        sseObserverJob?.cancel()
        val service = serviceBinder?.getService() ?: return
        sseObserverJob = viewModelScope.launch {
            launch {
                service.connectedServerIds.collect { ids ->
                    if (BuildConfig.DEBUG) Log.d(TAG, "Service connected server IDs changed: $ids")
                    _uiState.update {
                        it.copy(
                            connectedServerIds = ids,
                            serverSettingsReadyIds = it.serverSettingsReadyIds.intersect(ids)
                        )
                    }
                    refreshServerSettingsAvailability(ids)
                }
            }
            launch {
                service.connectingServerIds.collect { ids ->
                    if (BuildConfig.DEBUG) Log.d(TAG, "Service connecting server IDs changed: $ids")
                    _uiState.update { it.copy(connectingServerIds = ids) }
                }
            }
        }
    }

    private fun loadServers() {
        viewModelScope.launch {
            serverRepository.getAllServers().collect { servers ->
                _uiState.update { 
                    it.copy(
                        servers = servers,
                        isLoading = false
                    )
                }
                refreshServerSettingsAvailability(_uiState.value.connectedServerIds)
            }
        }
    }

    private fun refreshServerSettingsAvailability(connectedIds: Set<String>) {
        // Cancel checks for disconnected servers
        val disconnected = serverSettingsCheckJobs.keys - connectedIds
        disconnected.forEach { id ->
            serverSettingsCheckJobs.remove(id)?.cancel()
        }

        // Start or restart checks for connected servers
        connectedIds.forEach { serverId ->
            serverSettingsCheckJobs.remove(serverId)?.cancel()
            serverSettingsCheckJobs[serverId] = viewModelScope.launch {
                val server = _uiState.value.servers.find { it.id == serverId }
                if (server == null) {
                    _uiState.update { it.copy(serverSettingsReadyIds = it.serverSettingsReadyIds - serverId) }
                    return@launch
                }

                try {
                    val conn = ServerConnection.from(server.url, server.username, server.password)
                    api.getProviders(conn)
                    _uiState.update {
                        it.copy(
                            serverSettingsReadyIds = resolveServerSettingsReadyIds(
                                readyIds = it.serverSettingsReadyIds,
                                connectedIds = it.connectedServerIds,
                                serverId = serverId,
                                probeSucceeded = true,
                            )
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _uiState.update {
                        it.copy(
                            serverSettingsReadyIds = resolveServerSettingsReadyIds(
                                readyIds = it.serverSettingsReadyIds,
                                connectedIds = it.connectedServerIds,
                                serverId = serverId,
                                probeSucceeded = false,
                            )
                        )
                    }
                    if (BuildConfig.DEBUG) Log.d(TAG, "Providers check failed for $serverId: ${e.message}")
                }
            }
        }
    }

    private fun bindToService() {
        val intent = Intent(getApplication(), OpenCodeConnectionService::class.java)
        getApplication<Application>().bindService(
            intent,
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    fun showAddServerDialog() {
        _uiState.update { it.copy(showAddServerDialog = true, editingServer = null) }
    }

    fun showEditServerDialog(server: ServerConfig) {
        _uiState.update { it.copy(showAddServerDialog = true, editingServer = server) }
    }

    fun hideServerDialog() {
        _uiState.update { it.copy(showAddServerDialog = false, editingServer = null) }
    }

    fun saveServer(
        name: String,
        url: String,
        username: String,
        password: String,
        autoConnect: Boolean
    ) {
        viewModelScope.launch {
            val editingServer = _uiState.value.editingServer
            
            if (editingServer != null) {
                val updatedServer = editingServer.copy(
                    name = name,
                    url = url,
                    username = username,
                    password = password,
                    autoConnect = autoConnect
                )
                serverRepository.updateServer(updatedServer)
            } else {
                serverRepository.addServer(
                    url = url,
                    username = username,
                    password = password,
                    name = name,
                    autoConnect = autoConnect
                )
            }
            
            hideServerDialog()
        }
    }

    fun deleteServer(serverId: String) {
        viewModelScope.launch {
            // Disconnect first if connected or connecting
            if (_uiState.value.connectedServerIds.contains(serverId) ||
                _uiState.value.connectingServerIds.contains(serverId)) {
                disconnectFromServer(serverId)
            }
            serverRepository.deleteServer(serverId)
        }
    }

    /**
     * Connect to a specific server. Multiple servers can be connected simultaneously.
     */
    fun connectToServer(serverId: String) {
        val server = _uiState.value.servers.find { it.id == serverId } ?: return

        // Already connected or connecting? No-op.
        val equivalentServerIds = _uiState.value.servers
            .filter { normalizeServerUrl(it.url) == normalizeServerUrl(server.url) }
            .mapTo(mutableSetOf()) { it.id }
        if (_uiState.value.connectedServerIds.any(equivalentServerIds::contains) ||
            _uiState.value.connectingServerIds.any(equivalentServerIds::contains)) return

        _uiState.update {
            it.copy(
                connectingServerIds = it.connectingServerIds + serverId,
                connectionErrors = it.connectionErrors - serverId
            )
        }

        connectionAttemptJobs.remove(serverId)?.cancel()
        val generation = (connectionAttemptGenerations[serverId] ?: 0) + 1
        connectionAttemptGenerations[serverId] = generation
        val job = viewModelScope.launch {
            try {
                val healthResult = serverRepository.checkHealth(server)
                if (connectionAttemptGenerations[serverId] != generation) return@launch
                if (healthResult.getOrNull()?.healthy != true) {
                    val error = healthResult.exceptionOrNull()
                    val message = when (error) {
                        is ServerAuthenticationException -> s(R.string.home_server_auth_failed)
                        is ServerHealthHttpException -> s(R.string.home_server_health_http_error, error.statusCode)
                        else -> s(R.string.home_server_not_responding)
                    }
                    _uiState.update {
                        it.copy(
                            connectingServerIds = it.connectingServerIds - serverId,
                            connectionErrors = it.connectionErrors + (serverId to message)
                        )
                    }
                    return@launch
                }
                if (serverId !in _uiState.value.connectingServerIds) return@launch

                val context = getApplication<Application>()
                val intent = Intent(context, OpenCodeConnectionService::class.java).apply {
                    putExtra("server_id", server.id)
                    putExtra("server_name", server.name)
                    putExtra("server_url", server.url)
                    putExtra("server_username", server.username)
                    putExtra("server_password", server.password)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }

                // Connection state will be updated by the service via
                // observeServiceConnectionState() — no optimistic update needed.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (connectionAttemptGenerations[serverId] != generation) return@launch
                _uiState.update {
                    it.copy(
                        connectingServerIds = it.connectingServerIds - serverId,
                        connectionErrors = it.connectionErrors + (serverId to (e.message ?: "Connection failed"))
                    )
                }
            }
        }
        connectionAttemptJobs[serverId] = job
        job.invokeOnCompletion { connectionAttemptJobs.remove(serverId, job) }
    }

    fun refreshLocalRuntimeState() {
        viewModelScope.launch {
            val termuxInstalled = localServerManager.isTermuxInstalled()
            if (!termuxInstalled) {
                _uiState.update {
                    it.copy(
                        termuxInstalled = false,
                        localRuntimeStatus = LocalRuntimeStatus.Unavailable,
                        localRuntimeMessage = null,
                        localRuntimeFixCommand = null,
                        localRuntimeNeedsOverlaySettings = false,
                        setupCommand = null,
                    )
                }
                return@launch
            }

            val serverUsername = _uiState.value.localServerUsername.trim().ifBlank { "opencode" }
            val serverPassword = _uiState.value.localServerPassword.trim().takeIf { it.isNotBlank() }
            val healthy = localServerManager.isServerHealthy(
                username = serverUsername,
                password = serverPassword,
            )
            if (healthy) {
                // Server is running — mark setup as done (in case flag was never set)
                settingsRepository.setLocalSetupCompleted(true)
                _uiState.update {
                    it.copy(
                        termuxInstalled = true,
                        localRuntimeStatus = LocalRuntimeStatus.Running,
                        localRuntimeMessage = null,
                        localRuntimeFixCommand = null,
                        localRuntimeNeedsOverlaySettings = false,
                        setupCommand = null,
                    )
                }
                // Auto-create local server entry and connect
                val localServer = ensureLocalServerExists()
                if (!_uiState.value.connectedServerIds.contains(localServer.id) &&
                    !_uiState.value.connectingServerIds.contains(localServer.id)
                ) {
                    connectToServer(localServer.id)
                }
                return@launch
            }

            // Server not healthy — check if setup was ever completed
            val setupDone = settingsRepository.localSetupCompleted.first()
            _uiState.update {
                it.copy(
                    termuxInstalled = true,
                    localRuntimeStatus = if (setupDone) LocalRuntimeStatus.Stopped else LocalRuntimeStatus.NeedsSetup,
                    localRuntimeMessage = null,
                    localRuntimeFixCommand = null,
                    localRuntimeNeedsOverlaySettings = false,
                    setupCommand = if (!setupDone) localServerManager.getSetupCommand() else null,
                )
            }

            if (setupDone && !localAutoStartTriggered &&
                settingsRepository.localServerRunInBackground.first() &&
                settingsRepository.localServerAutoStart.first()
            ) {
                localAutoStartTriggered = true
                startLocalServer(getApplication())
            }
        }
    }

    /**
     * Copy the setup command and open Termux so the user can paste it.
     */
    fun setupLocalServer(callerContext: Context) {
        localServerManager.openTermux(callerContext)
    }

    fun getLocalSetupCommand(): String = localServerManager.getSetupCommand()

    fun startLocalServer(callerContext: Context) {
        _uiState.update {
            it.copy(
                localRuntimeStatus = LocalRuntimeStatus.Starting,
                localRuntimeMessage = null,
                localRuntimeFixCommand = null,
                localRuntimeNeedsOverlaySettings = false,
            )
        }

        viewModelScope.launch {
            if (!localServerManager.isTermuxInstalled()) {
                _uiState.update {
                    it.copy(
                        termuxInstalled = false,
                        localRuntimeStatus = LocalRuntimeStatus.Unavailable,
                        localRuntimeMessage = null,
                        localRuntimeFixCommand = null,
                        localRuntimeNeedsOverlaySettings = false,
                    )
                }
                return@launch
            }

            val proxyUrl = _uiState.value.localProxyUrl.trim().takeIf {
                _uiState.value.localProxyEnabled && it.isNotBlank()
            }
            val noProxyList = _uiState.value.localProxyNoProxy
            val hostName = if (_uiState.value.localServerAllowLan) {
                "0.0.0.0"
            } else {
                LocalServerManager.DEFAULT_LOCAL_HOST
            }
            val serverUsername = _uiState.value.localServerUsername.trim().takeIf { it.isNotBlank() }
            val serverPassword = _uiState.value.localServerPassword.trim().takeIf { it.isNotBlank() }
            val runInBackground = _uiState.value.localServerRunInBackground
            val startResult = localServerManager.startServer(
                callerContext = callerContext,
                proxyUrl = proxyUrl,
                noProxyList = noProxyList,
                hostName = hostName,
                serverUsername = serverUsername,
                serverPassword = serverPassword,
                runInBackground = runInBackground,
            )
            if (startResult.isFailure) {
                val errorInfo = mapLocalRuntimeError(startResult.exceptionOrNull()?.message)
                diagnosticLogRepository.record(
                    level = "ERROR",
                    category = "Local runtime",
                    message = errorInfo.message,
                    details = mapOf(
                        "stage" to "start",
                        "proxyEnabled" to _uiState.value.localProxyEnabled.toString(),
                        "proxyConfigured" to (!proxyUrl.isNullOrBlank()).toString(),
                        "allowLan" to _uiState.value.localServerAllowLan.toString(),
                    ),
                )
                if (errorInfo.status == LocalRuntimeStatus.NeedsSetup) {
                    settingsRepository.setLocalSetupCompleted(false)
                }
                _uiState.update {
                    it.copy(
                        termuxInstalled = true,
                        localRuntimeStatus = errorInfo.status,
                        localRuntimeMessage = errorInfo.message,
                        localRuntimeFixCommand = errorInfo.fixCommand,
                        localRuntimeNeedsOverlaySettings = errorInfo.requiresOverlaySettings,
                        setupCommand = if (errorInfo.status == LocalRuntimeStatus.NeedsSetup) {
                            localServerManager.getSetupCommand()
                        } else null,
                    )
                }
                return@launch
            }

            val startupTimeoutMs = _uiState.value.localServerStartupTimeoutSec.coerceIn(10, 120) * 1000L
            val ready = waitForLocalServerReady(
                timeoutMs = startupTimeoutMs,
                username = serverUsername ?: "opencode",
                password = serverPassword,
            )
            if (!ready) {
                diagnosticLogRepository.record(
                    level = "ERROR",
                    category = "Local runtime",
                    message = s(R.string.home_local_error_timeout),
                    details = mapOf(
                        "stage" to "health-check",
                        "timeoutSeconds" to _uiState.value.localServerStartupTimeoutSec.toString(),
                        "proxyEnabled" to _uiState.value.localProxyEnabled.toString(),
                        "proxyConfigured" to (!proxyUrl.isNullOrBlank()).toString(),
                        "allowLan" to _uiState.value.localServerAllowLan.toString(),
                    ),
                )
                _uiState.update {
                    it.copy(
                        termuxInstalled = true,
                        localRuntimeStatus = LocalRuntimeStatus.Error,
                        localRuntimeMessage = s(R.string.home_local_error_timeout),
                        localRuntimeFixCommand = null,
                        localRuntimeNeedsOverlaySettings = false,
                    )
                }
                return@launch
            }

            settingsRepository.setLocalSetupCompleted(true)
            val localServer = ensureLocalServerExists()
            _uiState.update {
                it.copy(
                    termuxInstalled = true,
                    localRuntimeStatus = LocalRuntimeStatus.Running,
                    localRuntimeMessage = null,
                    localRuntimeFixCommand = null,
                    localRuntimeNeedsOverlaySettings = false,
                )
            }

            if (!_uiState.value.connectedServerIds.contains(localServer.id) &&
                !_uiState.value.connectingServerIds.contains(localServer.id)
            ) {
                connectToServer(localServer.id)
            }
        }
    }

    fun stopLocalServer(callerContext: Context) {
        _uiState.update {
            it.copy(
                localRuntimeStatus = LocalRuntimeStatus.Stopping,
                localRuntimeMessage = null,
                localRuntimeFixCommand = null,
                localRuntimeNeedsOverlaySettings = false,
            )
        }

        viewModelScope.launch {
            val stopResult = localServerManager.stopServer(callerContext)
            if (stopResult.isFailure) {
                val errorInfo = mapLocalRuntimeError(stopResult.exceptionOrNull()?.message)
                _uiState.update {
                    it.copy(
                        localRuntimeStatus = LocalRuntimeStatus.Error,
                        localRuntimeMessage = errorInfo.message,
                        localRuntimeFixCommand = errorInfo.fixCommand,
                        localRuntimeNeedsOverlaySettings = errorInfo.requiresOverlaySettings,
                    )
                }
                return@launch
            }

            val localServerId = _uiState.value.servers.firstOrNull {
                it.url == LocalServerManager.LOCAL_SERVER_URL
            }?.id
            if (localServerId != null) {
                disconnectFromServer(localServerId)
            }

            repeat(6) {
                delay(1000)
                val username = _uiState.value.localServerUsername.trim().ifBlank { "opencode" }
                val password = _uiState.value.localServerPassword.trim().takeIf { it.isNotBlank() }
                if (!localServerManager.isServerHealthy(username = username, password = password)) {
                    _uiState.update {
                        it.copy(
                            localRuntimeStatus = LocalRuntimeStatus.Stopped,
                            localRuntimeMessage = null,
                            localRuntimeFixCommand = null,
                            localRuntimeNeedsOverlaySettings = false,
                        )
                    }
                    return@launch
                }
            }

            _uiState.update {
                it.copy(
                    localRuntimeStatus = LocalRuntimeStatus.Stopped,
                    localRuntimeMessage = s(R.string.home_local_message_stop_sent),
                    localRuntimeFixCommand = null,
                    localRuntimeNeedsOverlaySettings = false,
                )
            }
        }
    }

    fun setLocalProxyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLocalProxyEnabled(enabled)
        }
    }

    fun setLocalProxyUrl(url: String) {
        viewModelScope.launch {
            settingsRepository.setLocalProxyUrl(url)
        }
    }

    fun setLocalProxyNoProxy(value: String) {
        viewModelScope.launch {
            settingsRepository.setLocalProxyNoProxy(value)
        }
    }

    fun setLocalServerAllowLan(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLocalServerAllowLan(enabled)
        }
    }

    fun setLocalServerUsername(value: String) {
        viewModelScope.launch {
            settingsRepository.setLocalServerUsername(value)
        }
    }

    fun setLocalServerPassword(value: String) {
        viewModelScope.launch {
            settingsRepository.setLocalServerPassword(value)
        }
    }

    fun setLocalServerRunInBackground(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLocalServerRunInBackground(enabled)
            if (!enabled) {
                settingsRepository.setLocalServerAutoStart(false)
            }
        }
    }

    fun setLocalServerAutoStart(enabled: Boolean) {
        viewModelScope.launch {
            val runInBackground = settingsRepository.localServerRunInBackground.first()
            settingsRepository.setLocalServerAutoStart(enabled && runInBackground)
        }
    }

    fun setLocalServerStartupTimeoutSec(value: Int) {
        viewModelScope.launch {
            settingsRepository.setLocalServerStartupTimeoutSec(value)
        }
    }

    fun prepareInstall(release: AvailableUpdate) {
        viewModelScope.launch { updateRepository.prepareInstall(release) }
    }

    fun installerLaunched() {
        updateRepository.markInstallerLaunched()
    }

    fun checkForUpdates() {
        viewModelScope.launch { updateRepository.check(manual = true) }
    }

    private suspend fun waitForLocalServerReady(
        timeoutMs: Long = 30000L,
        username: String,
        password: String?,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (localServerManager.isServerHealthy(username = username, password = password)) {
                return true
            }
            delay(1500)
        }
        return false
    }

    private suspend fun ensureLocalServerExists(): ServerConfig {
        val desiredUsername = _uiState.value.localServerUsername.trim().ifBlank { "opencode" }
        val desiredPassword = _uiState.value.localServerPassword.trim().takeIf { it.isNotBlank() }
        val result = serverRepository.upsertLocalServer(
            url = LocalServerManager.LOCAL_SERVER_URL,
            username = desiredUsername,
            password = desiredPassword,
            defaultName = LOCAL_SERVER_NAME,
        )
        result.removedServerIds.forEach { duplicateId ->
            serviceBinder?.getService()?.disconnect(duplicateId)
        }
        return result.server
    }

    private fun mapLocalRuntimeError(rawMessage: String?): LocalRuntimeErrorInfo {
        val raw = rawMessage.orEmpty()
        val lower = raw.lowercase()
        return when {
            "allow-external-apps" in lower -> {
                LocalRuntimeErrorInfo(
                    message = s(R.string.home_local_error_termux_blocked_external),
                    fixCommand = "mkdir -p ~/.termux && (grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null && sed -i 's/^allow-external-apps.*/allow-external-apps = true/' ~/.termux/termux.properties || echo 'allow-external-apps = true' >> ~/.termux/termux.properties) && termux-reload-settings",
                    status = LocalRuntimeStatus.NeedsSetup,
                )
            }

            "display over other apps" in lower || "draw over other apps" in lower -> {
                LocalRuntimeErrorInfo(
                    message = s(R.string.home_local_error_termux_overlay_permission),
                    requiresOverlaySettings = true,
                )
            }

            "run_command" in lower && "without permission" in lower -> {
                LocalRuntimeErrorInfo(s(R.string.home_local_error_run_command_permission))
            }

            "app is in background" in lower -> {
                LocalRuntimeErrorInfo(s(R.string.home_local_error_background_launch))
            }

            "regular file not found" in lower && "opencode-local" in lower -> {
                LocalRuntimeErrorInfo(
                    message = s(R.string.home_local_error_not_installed),
                    status = LocalRuntimeStatus.NeedsSetup,
                )
            }

            raw.isNotBlank() -> LocalRuntimeErrorInfo(raw)
            else -> LocalRuntimeErrorInfo(s(R.string.home_local_error_launch_failed))
        }
    }

    private fun s(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    /**
     * Disconnect from a specific server.
     */
    fun disconnectFromServer(serverId: String) {
        connectionAttemptGenerations[serverId] = (connectionAttemptGenerations[serverId] ?: 0) + 1
        connectionAttemptJobs.remove(serverId)?.cancel()
        serviceBinder?.getService()?.disconnect(serverId)
        _uiState.update {
            it.copy(
                connectedServerIds = it.connectedServerIds - serverId,
                connectingServerIds = it.connectingServerIds - serverId,
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        sseObserverJob?.cancel()
        serverSettingsCheckJobs.values.forEach { it.cancel() }
        serverSettingsCheckJobs.clear()
        connectionAttemptJobs.values.forEach { it.cancel() }
        connectionAttemptJobs.clear()
        connectionAttemptGenerations.clear()
        try {
            getApplication<Application>().unbindService(serviceConnection)
        } catch (e: Exception) {
            // Service might not be bound
        }
    }
}
