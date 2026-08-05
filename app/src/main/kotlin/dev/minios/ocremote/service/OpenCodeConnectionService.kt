package dev.minios.ocremote.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import dev.minios.ocremote.logging.AppLogger as Log
import androidx.core.app.NotificationCompat
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.MainActivity
import dev.minios.ocremote.R
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.api.SseClient
import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.data.repository.ServerRepository
import dev.minios.ocremote.data.repository.normalizeServerUrl
import dev.minios.ocremote.data.repository.ServerConnectionStateRepository
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SseEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

private const val TAG = "OpenCodeService"
private const val NOTIFICATION_CHANNEL_ID = "opencode_connection"
private const val NOTIFICATION_CHANNEL_TASKS_ID = "opencode_tasks"
private const val NOTIFICATION_CHANNEL_TASKS_SILENT_ID = "opencode_tasks_silent"
private const val NOTIFICATION_CHANNEL_PERMISSIONS_ID = "opencode_permissions"
private const val PERSISTENT_NOTIFICATION_ID = 1001
private const val WAKELOCK_TAG = "OpenCodeRemote::SSEConnection"

// Reconnect timing
private const val RECONNECT_BASE_DELAY_MS = 1_000L   // 1 second
private const val RECONNECT_MAX_DELAY_MS = 30_000L   // 30 seconds
private const val RECONNECT_BACKOFF_FACTOR = 2.0
private const val RECOVERY_DEBOUNCE_MS = 5_000L
private const val MAX_RECONCILED_MESSAGE_SESSIONS = 20
internal const val FAILED_CONNECTION_TIMEOUT_MS = 15 * 60 * 1000L

internal fun hasFailedConnectionTimedOut(failureStartedAt: Long, now: Long): Boolean {
    return now - failureStartedAt >= FAILED_CONNECTION_TIMEOUT_MS
}

internal fun sameServerEndpoint(firstUrl: String, secondUrl: String): Boolean {
    return normalizeServerUrl(firstUrl) == normalizeServerUrl(secondUrl)
}

internal fun sessionsNeedingMessageReconciliation(
    localSessions: Map<String, Session>,
    remoteSessions: List<Session>,
    limit: Int = MAX_RECONCILED_MESSAGE_SESSIONS,
): List<Session> = remoteSessions
    .asSequence()
    .filter { remote ->
        val local = localSessions[remote.id]
        local == null || remote.time.updated > local.time.updated
    }
    .sortedByDescending { it.time.updated }
    .take(limit)
    .toList()

/**
 * Per-server connection state held by the service.
 */
private data class ServerConnectionState(
    val config: ServerConfig,
    val conn: ServerConnection,
    val sseJob: Job,
    val isConnected: Boolean = false
)

/**
 * Foreground Service for maintaining OpenCode SSE connections to multiple servers.
 *
 * This service:
 * - Maintains persistent SSE connections to one or more servers simultaneously
 * - Processes events via EventReducer (with serverId tracking)
 * - Shows notifications for task completion and permission requests
 * - Auto-reconnects with exponential backoff on disconnection/error
 * - Optionally holds a single partial WakeLock while any server is connected
 * - Shows an InboxStyle persistent notification summarising connected servers
 * - Groups event notifications by server
 *
 * The connections stay alive until the user explicitly disconnects each server
 * (or uses "Disconnect All").
 */
@AndroidEntryPoint
class OpenCodeConnectionService : Service() {

    override fun attachBaseContext(newBase: Context) {
        val languageCode = SettingsRepository.getStoredLanguage(newBase)
        if (languageCode.isNotEmpty()) {
            val locale = MainActivity.parseLocale(languageCode)
            Locale.setDefault(locale)
            val config = newBase.resources.configuration
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    @Inject
    lateinit var api: OpenCodeApi

    @Inject
    lateinit var sseClient: SseClient

    @Inject
    lateinit var eventReducer: EventReducer

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var serverRepository: ServerRepository

    @Inject
    lateinit var serverConnectionStateRepository: ServerConnectionStateRepository

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** All active/pending server connections keyed by serverId. */
    private val connections = ConcurrentHashMap<String, ServerConnectionState>()

    private var autoConnectJob: Job? = null
    @Volatile
    private var recoveryJob: Job? = null
    private val reconciliationJobs = ConcurrentHashMap<String, Job>()
    private val explicitlyDisconnectedServerIds = ConcurrentHashMap.newKeySet<String>()
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var backgroundWakeLockEnabled = false
    @Volatile
    private var lastRecoveryAt = 0L
    @Volatile
    private var lastDefaultNetwork: Network? = null
    private lateinit var connectivityManager: ConnectivityManager
    @Volatile
    private var lastPersistentNotificationState: List<Triple<String, String, Boolean>>? = null

    private val wakeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                if (powerManager.isDeviceIdleMode) return
            }
            recoverConnectionsWithoutWakeLock("device wake")
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val previous = lastDefaultNetwork
            lastDefaultNetwork = network
            if (previous != null && previous != network) {
                recoverConnectionsWithoutWakeLock("default network changed")
            }
        }
    }

    private lateinit var notificationManager: NotificationManager
    private var foregroundStarted: Boolean = false

    /** Observable set of server IDs that are actually connected (SSE stream active). */
    private val _connectedServerIds = MutableStateFlow<Set<String>>(emptySet())
    val connectedServerIds: StateFlow<Set<String>> = _connectedServerIds.asStateFlow()

    /** Observable set of server IDs that are attempting to connect (SSE not yet established or reconnecting). */
    private val _connectingServerIds = MutableStateFlow<Set<String>>(emptySet())
    val connectingServerIds: StateFlow<Set<String>> = _connectingServerIds.asStateFlow()

    /** Dedup response-ready notifications per session by last assistant message ID. */
    private val lastNotifiedAssistantMessageBySession = ConcurrentHashMap<String, String>()

    inner class LocalBinder : Binder() {
        fun getService(): OpenCodeConnectionService = this@OpenCodeConnectionService
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Log.d(TAG, "Service created")

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        createNotificationChannels()

        val wakeFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wakeReceiver, wakeFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(wakeReceiver, wakeFilter)
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback)

        autoConnectJob = serviceScope.launch {
            autoConnectConfiguredServers()
        }
        serviceScope.launch {
            settingsRepository.backgroundWakeLock.collect { enabled ->
                if (backgroundWakeLockEnabled == enabled) return@collect
                backgroundWakeLockEnabled = enabled
                if (enabled && connections.isNotEmpty()) {
                    acquireWakeLock()
                } else if (!enabled) {
                    releaseWakeLock()
                }
                Log.i(TAG, "Background WakeLock ${if (enabled) "enabled" else "disabled"}")
            }
        }
        serviceScope.launch {
            connectedServerIds.collect(serverConnectionStateRepository::updateConnectedServerIds)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BuildConfig.DEBUG) Log.d(TAG, "Service started, action=${intent?.action}")

        when (intent?.action) {
            ACTION_EXIT -> {
                Log.i(TAG, "Exit requested via notification")
                notificationManager.cancelAll()
                sendBroadcast(Intent(ACTION_APP_EXIT).setPackage(packageName))
                disconnectAll()
                return START_NOT_STICKY
            }
            ACTION_DISCONNECT -> {
                val serverId = intent.getStringExtra("server_id")
                if (serverId != null) {
                    Log.i(TAG, "Disconnect requested for server $serverId")
                    disconnect(serverId)
                }
                return START_NOT_STICKY
            }
        }

        // Read server details from intent and connect
        val serverId = intent?.getStringExtra("server_id")
        val serverUrl = intent?.getStringExtra("server_url")
        if (serverId != null && serverUrl != null) {
                connect(
                    ServerConfig(
                        id = serverId,
                        url = serverUrl,
                        username = intent.getStringExtra("server_username") ?: "opencode",
                        password = intent.getStringExtra("server_password"),
                        name = intent.getStringExtra("server_name"),
                        allowSelfSigned = intent.getBooleanExtra("server_allow_self_signed", false),
                    )
                )
            return START_NOT_STICKY
        }

        // A sticky restart has no server extras. Give auto-connect a chance, then remove any orphan notification.
        ensureForegroundStarted()
        serviceScope.launch {
            autoConnectJob?.join()
            if (connections.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                notificationManager.cancel(PERSISTENT_NOTIFICATION_ID)
                foregroundStarted = false
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        serverConnectionStateRepository.updateConnectedServerIds(emptySet())
        unregisterReceiver(wakeReceiver)
        connectivityManager.unregisterNetworkCallback(networkCallback)
        super.onDestroy()
        if (BuildConfig.DEBUG) Log.d(TAG, "Service destroyed")
        disconnectAllInternal(stopService = false)
        serviceScope.cancel()
    }

    // ============ Public API ============

    /**
     * Connect to an OpenCode server. If already connected to this server, no-op.
     * Multiple servers can be connected simultaneously.
     */
    @Synchronized
    fun connect(server: ServerConfig) {
        explicitlyDisconnectedServerIds.remove(server.id)
        connectInternal(server)
    }

    @Synchronized
    private fun connectInternal(server: ServerConfig) {
        val activeEndpointConnection = connections.values.firstOrNull { state ->
            sameServerEndpoint(state.config.url, server.url) && !state.sseJob.isCompleted
        }
        if (activeEndpointConnection != null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Already connected to endpoint for server ${activeEndpointConnection.config.id}, skipping ${server.id}")
            }
            return
        }
        var replacement: ServerConnectionState? = null
        var replaced: ServerConnectionState? = null
        connections.compute(server.id) { _, existing ->
            if (existing != null && !existing.sseJob.isCompleted) return@compute existing
            replaced = existing
            val conn = ServerConnection.from(
                server.url,
                server.username,
                server.password,
                allowSelfSigned = server.allowSelfSigned,
            )
            ServerConnectionState(
                config = server,
                conn = conn,
                sseJob = startSseConnection(server, conn),
                isConnected = false,
            ).also { replacement = it }
        }
        val state = replacement
        if (state == null) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Already connected to server ${server.id}, skipping")
            return
        }
        replaced?.sseJob?.cancel()

        if (BuildConfig.DEBUG) Log.d(TAG, "Connecting to configured server")

        ensureForegroundStarted()
        acquireWakeLock()
        _connectingServerIds.update { it + server.id }
        updatePersistentNotification()
        state.sseJob.start()
    }

    /**
     * Disconnect from a single server.
     */
    @Synchronized
    fun disconnect(serverId: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Disconnecting server $serverId")

        explicitlyDisconnectedServerIds.add(serverId)
        val state = connections.remove(serverId) ?: return
        state.sseJob.cancel()
        reconciliationJobs.remove(serverId)?.cancel()

        _connectedServerIds.update { it - serverId }
        _connectingServerIds.update { it - serverId }

        eventReducer.clearForServer(serverId)

        if (connections.isEmpty()) {
            // Last server disconnected — clean up and stop service
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            lastPersistentNotificationState = null
            foregroundStarted = false
            stopSelf()
        } else {
            updatePersistentNotification()
        }
    }

    /**
     * Disconnect from all servers and stop the service.
     */
    fun disconnectAll() {
        disconnectAllInternal(stopService = true)
    }

    @Synchronized
    private fun disconnectAllInternal(stopService: Boolean) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Disconnecting all servers")
        if (stopService) autoConnectJob?.cancel()

        for ((_, state) in connections) {
            state.sseJob.cancel()
        }
        reconciliationJobs.values.forEach { it.cancel() }
        reconciliationJobs.clear()
        val serverIds = connections.keys.toList()
        connections.clear()

        _connectedServerIds.value = emptySet()
        _connectingServerIds.value = emptySet()

        for (serverId in serverIds) {
            eventReducer.clearForServer(serverId)
        }

        releaseWakeLock()

        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            lastPersistentNotificationState = null
            foregroundStarted = false
            stopSelf()
        }
    }

    private suspend fun autoConnectConfiguredServers() {
        try {
            val autoConnectServers = serverRepository.servers.first().filter { it.autoConnect }
            if (autoConnectServers.isEmpty()) return
            Log.i(TAG, "Auto-connecting ${autoConnectServers.size} server(s)")
            autoConnectServers.forEach { server ->
                if (server.id !in explicitlyDisconnectedServerIds) connectInternal(server)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-connect servers", e)
        }
    }

    @Synchronized
    private fun ensureForegroundStarted() {
        if (foregroundStarted) return
        startForeground(PERSISTENT_NOTIFICATION_ID, createPersistentNotification())
        lastPersistentNotificationState = connections.values
            .map { Triple(it.config.id, it.config.displayName, it.isConnected) }
            .sortedBy { it.first }
        foregroundStarted = true
    }

    /**
     * Check if a specific server is connected.
     */
    fun isConnected(serverId: String): Boolean {
        return connections[serverId]?.sseJob?.isActive == true
    }

    // ============ WakeLock ============

    private fun acquireWakeLock() {
        if (!backgroundWakeLockEnabled) return
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
            acquire()
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                if (BuildConfig.DEBUG) Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    /**
     * Without a WakeLock Android may suspend a healthy-looking socket during Doze. Replacing each SSE job after
     * wake/network recovery forces a fresh stream and the normal server-state reconciliation.
     */
    @Synchronized
    private fun recoverConnectionsWithoutWakeLock(reason: String) {
        if (backgroundWakeLockEnabled || connections.isEmpty()) return
        if (recoveryJob?.isActive == true) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecoveryAt < RECOVERY_DEBOUNCE_MS) return
        lastRecoveryAt = now

        recoveryJob = serviceScope.launch {
            val states = connections.values.toList()
            if (states.isEmpty() || backgroundWakeLockEnabled) return@launch
            Log.i(TAG, "Recovering ${states.size} connection(s) after $reason")
            for (state in states) {
                val job = startSseConnection(state.config, state.conn, preload = false)
                val replacement = state.copy(sseJob = job, isConnected = false)
                if (!connections.replace(state.config.id, state, replacement)) {
                    job.cancel()
                    continue
                }
                state.sseJob.cancel()
                reconciliationJobs.remove(state.config.id)?.cancel()
                _connectedServerIds.update { it - state.config.id }
                _connectingServerIds.update { it + state.config.id }
                job.start()
            }
            updatePersistentNotification()
        }
    }

    // ============ SSE Connection with Auto-Reconnect ============

    private fun startSseConnection(
        server: ServerConfig,
        conn: ServerConnection,
        preload: Boolean = true,
    ): Job {
        return serviceScope.launch(start = CoroutineStart.LAZY) {
            val currentJob = coroutineContext[Job]!!
            var attempt = 0
            var failureStartedAt: Long? = SystemClock.elapsedRealtime()
            var preloaded = !preload

            while (isActive) {
                failureStartedAt?.let { failedSince ->
                    if (hasFailedConnectionTimedOut(failedSince, SystemClock.elapsedRealtime())) {
                        Log.w(TAG, "[${server.displayName}] Stopping reconnect after 15 minutes without a connection")
                        cleanupTerminatedConnection(server.id, currentJob)
                        return@launch
                    }
                }
                attempt++
                Log.i(TAG, "[${server.displayName}] SSE connection attempt #$attempt")

                if (!preloaded) {
                    preloaded = true
                    // Pre-load once. Reconciliation refreshes state after a successful reconnect.
                    try {
                        val projects = api.listProjects(conn)
                        if (projects.isEmpty()) {
                            val sessions = api.listSessions(conn)
                            eventReducer.setSessions(server.id, sessions)
                            Log.i(TAG, "[${server.displayName}] Pre-loaded ${sessions.size} sessions (no projects)")
                        } else {
                            var totalSessions = 0
                            for (project in projects) {
                                try {
                                    val sessions = api.listSessions(conn, directory = project.worktree)
                                    eventReducer.setSessions(server.id, sessions)
                                    totalSessions += sessions.size
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.w(TAG, "[${server.displayName}] Failed to pre-load sessions for project ${project.displayName}: ${e.message}")
                                }
                            }
                            Log.i(TAG, "[${server.displayName}] Pre-loaded $totalSessions sessions across ${projects.size} projects")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "[${server.displayName}] Failed to pre-load sessions: ${e.message}")
                    }
                }

                try {
                    sseClient.connectToGlobalEvents(
                        conn = conn,
                        onOpen = {
                            if (connections[server.id]?.sseJob === currentJob) {
                                updateServerConnected(server.id, true, currentJob)
                                attempt = 0
                                failureStartedAt = null
                            }
                        },
                    )
                        .catch { error ->
                            Log.e(TAG, "[${server.displayName}] SSE stream error", error)
                            updateServerConnected(server.id, false, currentJob)
                            throw error
                        }
                        .collect { scoped ->
                            if (connections[server.id]?.sseJob !== currentJob) return@collect
                            val event = scoped.event
                            if (connections[server.id]?.isConnected != true) {
                                updateServerConnected(server.id, true, currentJob)
                                attempt = 0
                                failureStartedAt = null
                            }
                            processEvent(server, event, scoped.directory, scoped.workspaceId)
                            if (event is SseEvent.ServerConnected) {
                                startReconciliation(server, conn)
                            }
                        }

                    // Flow completed normally (server closed connection)
                    Log.w(TAG, "[${server.displayName}] SSE stream completed")
                    updateServerConnected(server.id, false, currentJob)
                } catch (e: CancellationException) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "[${server.displayName}] SSE job cancelled, not reconnecting")
                    throw e
                } catch (e: dev.minios.ocremote.data.api.SseAuthException) {
                    Log.e(TAG, "[${server.displayName}] Authentication failed; automatic reconnect stopped", e)
                    updateServerConnected(server.id, false, currentJob)
                    cleanupTerminatedConnection(server.id, currentJob)
                    break
                } catch (e: dev.minios.ocremote.data.api.SseConnectionException) {
                    Log.e(TAG, "[${server.displayName}] SSE connection failed: ${e.message}")
                    updateServerConnected(server.id, false, currentJob)
                    if (!e.retryable) {
                        cleanupTerminatedConnection(server.id, currentJob)
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[${server.displayName}] SSE connection failed: ${e.message}")
                    updateServerConnected(server.id, false, currentJob)
                }

                // If this server was removed from connections, stop the loop
                if (!connections.containsKey(server.id)) break

                val now = SystemClock.elapsedRealtime()
                val failedSince = failureStartedAt ?: now.also { failureStartedAt = it }
                val delayMs = calculateBackoff(attempt)
                Log.i(TAG, "[${server.displayName}] Reconnecting in ${delayMs}ms (attempt #$attempt)")
                val remainingMs = FAILED_CONNECTION_TIMEOUT_MS - (now - failedSince)
                delay(minOf(delayMs, remainingMs.coerceAtLeast(1L)))
            }
        }
    }

    private fun startReconciliation(server: ServerConfig, conn: ServerConnection) {
        val job = serviceScope.launch { reconcileServerState(server, conn) }
        reconciliationJobs.put(server.id, job)?.cancel()
        job.invokeOnCompletion { reconciliationJobs.remove(server.id, job) }
    }

    private suspend fun reconcileServerState(server: ServerConfig, conn: ServerConnection) {
        val directories = try {
            api.listProjects(conn).map { it.worktree }.ifEmpty { listOf(null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "[${server.displayName}] Reconciliation project lookup failed", e)
            return
        }

        val revision = eventReducer.pendingSnapshotRevision()
        val permissions = mutableListOf<SseEvent.PermissionAsked>()
        val questions = mutableListOf<SseEvent.QuestionAsked>()
        var complete = true
        for (directory in directories) {
            try {
                val localSessions = eventReducer.sessions.value.associateBy { it.id }
                val sessions = api.listSessions(conn, directory)
                val changedSessions = sessionsNeedingMessageReconciliation(localSessions, sessions)
                eventReducer.setSessions(server.id, sessions)
                changedSessions.forEach { session ->
                    try {
                        eventReducer.mergeMessages(
                            session.id,
                            api.listMessages(conn, session.id, limit = 50, directory = directory),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "[${server.displayName}] Message reconciliation failed for ${session.id}", e)
                    }
                }
                val statuses = api.listSessionStatuses(conn, directory)
                val serverSessionIds = eventReducer.serverSessions.value[server.id].orEmpty()
                val directorySessionIds = eventReducer.sessions.value
                    .asSequence()
                    .filter { it.id in serverSessionIds }
                    .filter { directory == null || it.directory == directory }
                    .map { it.id }
                    .toSet()
                eventReducer.replaceSessionStatuses(server.id, directorySessionIds, statuses)
                permissions += api.listPendingPermissions(conn, directory).map { request ->
                    SseEvent.PermissionAsked(
                        id = request.id,
                        sessionId = request.sessionId,
                        permission = request.permission,
                        patterns = request.patterns,
                        always = request.always,
                        metadata = request.metadata,
                        tool = request.tool,
                    )
                }
                questions += api.listPendingQuestions(conn, directory).map { request ->
                    SseEvent.QuestionAsked(
                        id = request.id,
                        sessionId = request.sessionId,
                        questions = request.questions.map { question ->
                            SseEvent.QuestionAsked.Question(
                                header = question.header,
                                question = question.question,
                                multiple = question.multiple,
                                custom = question.custom,
                                options = question.options.map { option ->
                                    SseEvent.QuestionAsked.Option(option.label, option.description)
                                },
                            )
                        },
                        tool = request.tool,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                complete = false
                Log.w(TAG, "[${server.displayName}] Reconciliation failed for project", e)
            }
        }
        if (complete) {
            eventReducer.replacePendingRequests(server.id, permissions, questions, revision)
        }
    }

    @Synchronized
    private fun cleanupTerminatedConnection(serverId: String, job: Job) {
        val state = connections[serverId] ?: return
        if (state.sseJob !== job || !connections.remove(serverId, state)) return
        reconciliationJobs.remove(serverId)?.cancel()

        _connectedServerIds.update { it - serverId }
        _connectingServerIds.update { it - serverId }
        eventReducer.clearTransientForServer(serverId)

        if (connections.isEmpty()) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            lastPersistentNotificationState = null
            foregroundStarted = false
            stopSelf()
        } else {
            updatePersistentNotification()
        }
    }

    private fun updateServerConnected(serverId: String, connected: Boolean, expectedJob: Job) {
        var changed = false
        connections.computeIfPresent(serverId) { _, state ->
            if (state.sseJob !== expectedJob) return@computeIfPresent state
            if (state.isConnected == connected) {
                state
            } else {
                changed = true
                state.copy(isConnected = connected)
            }
        }
        if (!changed) return
        if (connected) {
            _connectingServerIds.update { it - serverId }
            _connectedServerIds.update { it + serverId }
        } else {
            _connectedServerIds.update { it - serverId }
            _connectingServerIds.update { it + serverId }
        }
        updatePersistentNotification()
    }

    private suspend fun calculateBackoff(attempt: Int): Long {
        val maxDelay = when (settingsRepository.reconnectMode.first()) {
            "aggressive" -> 5_000L
            "conservative" -> 60_000L
            else -> RECONNECT_MAX_DELAY_MS // normal: 30s
        }
        val delay = (RECONNECT_BASE_DELAY_MS * Math.pow(RECONNECT_BACKOFF_FACTOR, (attempt - 1).coerceAtLeast(0).toDouble())).toLong()
        return delay.coerceAtMost(maxDelay)
    }

    // ============ Event Processing ============

    /**
     * Check if a session is a child/sub-agent session (has parentID set).
     * Child sessions should not trigger user-facing notifications,
     * matching the behavior of the official opencode WebUI and TUI.
     */
    private fun isChildSession(sessionId: String): Boolean {
        val session = eventReducer.sessions.value.find { it.id == sessionId }
        return session?.parentId != null
    }

    private fun processEvent(server: ServerConfig, event: SseEvent, directory: String?, workspaceId: String?) {
        eventReducer.processEvent(event, server.id, directory, workspaceId)

        when (event) {
            is SseEvent.SessionIdle -> {
                if (isChildSession(event.sessionId)) return
                serviceScope.launch {
                    if (!settingsRepository.notificationsEnabled.first()) return@launch

                    // Give reducer a brief moment to receive trailing message/part events.
                    delay(250)
                    if (!connections.containsKey(server.id)) return@launch

                    val assistantMessageId = latestNotifiableAssistantMessageId(event.sessionId)
                    if (assistantMessageId == null) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "[${server.displayName}] Skip response-ready: no assistant text output (${event.sessionId})")
                        }
                        return@launch
                    }

                    val previousNotified = lastNotifiedAssistantMessageBySession.put(
                        event.sessionId,
                        assistantMessageId,
                    )
                    if (previousNotified == assistantMessageId) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "[${server.displayName}] Skip duplicate response-ready (${event.sessionId}, msg=$assistantMessageId)")
                        }
                        return@launch
                    }

                    Log.i(TAG, "[${server.displayName}] Session idle -> Response ready for ${event.sessionId}")
                        showTaskCompleteNotification(server, event.sessionId)
                }
            }
            is SseEvent.PermissionAsked -> {
                if (isChildSession(event.sessionId)) return
                Log.i(TAG, "[${server.displayName}] Permission asked: ${event.permission}")
                showPermissionNotification(server, event.sessionId, event.permission)
            }
            is SseEvent.QuestionAsked -> {
                if (isChildSession(event.sessionId)) return
                Log.i(TAG, "[${server.displayName}] Question asked for session ${event.sessionId}")
                val questionText = event.questions.firstOrNull()?.question ?: getString(R.string.notification_has_question, getString(R.string.notification_new_session))
                showQuestionNotification(server, event.sessionId, questionText)
            }
            is SseEvent.SessionError -> {
                if (event.sessionId != null && isChildSession(event.sessionId)) return
                Log.i(TAG, "[${server.displayName}] Session error: ${event.error.message}")
                showErrorNotification(server, event.sessionId, event.error.message)
            }
            else -> { }
        }
    }

    // ============ Helpers ============

    private fun getServerConnection(server: ServerConfig): ServerConnection? {
        return connections[server.id]?.conn
    }

    private fun getSessionInfo(sessionId: String): Pair<String?, String?> {
        val session = eventReducer.sessions.value.find { it.id == sessionId }
        return Pair(session?.title, session?.directory)
    }

    private fun latestNotifiableAssistantMessageId(sessionId: String): String? {
        val sessionMessages = eventReducer.messages.value[sessionId] ?: return null
        val latestAssistant = sessionMessages
            .asReversed()
            .firstOrNull { it is Message.Assistant } as? Message.Assistant ?: return null

        if (!latestAssistant.error?.message.isNullOrBlank()) return latestAssistant.id

        val parts = eventReducer.parts.value[latestAssistant.id] ?: return null
        val hasTextOutput = parts.any { part ->
            when (part) {
                is Part.Text -> part.text.isNotBlank()
                is Part.Reasoning -> part.text.isNotBlank()
                else -> false
            }
        }
        return if (hasTextOutput) latestAssistant.id else null
    }

    private fun getProjectName(directory: String?): String? {
        if (directory.isNullOrBlank()) return null
        return directory.trimEnd('/').substringAfterLast('/')
    }

    private fun base64UrlEncode(value: String): String {
        val encoded = android.util.Base64.encodeToString(
            value.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        return encoded
            .replace('+', '-')
            .replace('/', '_')
            .replace("=", "")
    }

    private fun buildSessionPath(sessionId: String): String? {
        val session = eventReducer.sessions.value.find { it.id == sessionId }
        if (session == null) {
            Log.w(TAG, "buildSessionPath: session $sessionId not found")
            return null
        }
        val encodedDir = base64UrlEncode(session.directory)
        return "/$encodedDir/session/$sessionId"
    }

    private fun createSessionPendingIntent(server: ServerConfig, sessionId: String?, requestCode: Int): PendingIntent {
        val sessionPath = sessionId?.let { buildSessionPath(it) }

        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_SESSION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SERVER_URL, server.url)
            putExtra(EXTRA_SERVER_USERNAME, server.username)
            putExtra(EXTRA_SERVER_PASSWORD, server.password ?: "")
            putExtra(EXTRA_SERVER_NAME, server.displayName)
            putExtra(EXTRA_SERVER_ID, server.id)
            sessionPath?.let { putExtra(EXTRA_SESSION_PATH, it) }
            sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
        }

        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        const val ACTION_OPEN_SESSION = "dev.minios.ocremote.OPEN_SESSION"
        const val ACTION_DISCONNECT = "dev.minios.ocremote.DISCONNECT"
        const val ACTION_EXIT = "dev.minios.ocremote.EXIT"
        const val ACTION_APP_EXIT = "dev.minios.ocremote.APP_EXIT"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_SERVER_USERNAME = "server_username"
        const val EXTRA_SERVER_PASSWORD = "server_password"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_SESSION_PATH = "session_path"
        const val EXTRA_SESSION_ID = "sessionId"
    }

    // ============ Notification Channels ============

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val connectionChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_connection),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_connection_desc)
                setShowBadge(false)
            }

            val tasksChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_TASKS_ID,
                getString(R.string.notification_channel_tasks),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_tasks_desc)
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
            }

            val tasksSilentChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_TASKS_SILENT_ID,
                getString(R.string.notification_channel_tasks_silent),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_tasks_silent_desc)
                setShowBadge(true)
                enableVibration(false)
                enableLights(false)
                setSound(null, null)
            }

            val permissionsChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_PERMISSIONS_ID,
                getString(R.string.notification_channel_permissions),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notification_channel_permissions_desc)
                setShowBadge(true)
                enableVibration(true)
                enableLights(true)
            }

            notificationManager.createNotificationChannel(connectionChannel)
            notificationManager.createNotificationChannel(tasksChannel)
            notificationManager.createNotificationChannel(tasksSilentChannel)
            notificationManager.createNotificationChannel(permissionsChannel)
        }
    }

    // ============ Persistent Notification (InboxStyle, multi-server) ============

    private fun createPersistentNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val exitIntent = Intent(this, OpenCodeConnectionService::class.java).apply {
            action = ACTION_EXIT
        }
        val exitPendingIntent = PendingIntent.getService(
            this, 1, exitIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val visibleConnections = connections.values.sortedBy { it.config.id }
        val serverCount = visibleConnections.size
        val connectedCount = visibleConnections.count { it.isConnected }

        val title = if (serverCount == 0) {
            getString(R.string.app_name)
        } else if (serverCount == 1) {
            val server = visibleConnections.first()
            if (server.isConnected) getString(R.string.notification_connected, server.config.displayName)
            else getString(R.string.notification_connecting, server.config.displayName)
        } else {
            getString(R.string.notification_connected_count, connectedCount, serverCount)
        }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(title)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(tapPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (serverCount > 0) {
            builder.addAction(
                R.mipmap.ic_launcher,
                getString(R.string.notification_exit),
                exitPendingIntent,
            )
        }

        // InboxStyle when multiple servers
        if (serverCount > 1) {
            val inboxStyle = NotificationCompat.InboxStyle()
                .setBigContentTitle(getString(R.string.notification_inbox_title, connectedCount, serverCount))
            for (state in visibleConnections) {
                val status = if (state.isConnected) getString(R.string.notification_status_connected) else getString(R.string.notification_status_connecting)
                inboxStyle.addLine("${state.config.displayName}: $status")
            }
            builder.setStyle(inboxStyle)
        }

        return builder.build()
    }

    @Synchronized
    private fun updatePersistentNotification() {
        if (connections.isEmpty()) {
            if (foregroundStarted) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
            }
            notificationManager.cancel(PERSISTENT_NOTIFICATION_ID)
            lastPersistentNotificationState = null
            return
        }
        val state = connections.values
            .map { Triple(it.config.id, it.config.displayName, it.isConnected) }
            .sortedBy { it.first }
        if (state == lastPersistentNotificationState) return
        lastPersistentNotificationState = state
        val notification = createPersistentNotification()
        notificationManager.notify(PERSISTENT_NOTIFICATION_ID, notification)
    }

    // ============ Event Notifications (grouped by server) ============

    private suspend fun showTaskCompleteNotification(server: ServerConfig, sessionId: String) {
        val (sessionTitle, _) = getSessionInfo(sessionId)
        val body = sessionTitle?.takeIf { it.isNotBlank() } ?: getString(R.string.notification_new_session)

        val pendingIntent = createSessionPendingIntent(server, sessionId, sessionId.hashCode())

        val silent = settingsRepository.silentNotifications.first()
        val channelId = if (silent) NOTIFICATION_CHANNEL_TASKS_SILENT_ID else NOTIFICATION_CHANNEL_TASKS_ID

        val notifId = eventNotificationId(server.id, sessionId, 0)
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_response_ready))
            .setContentText(body)
            .setSubText(server.displayName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setGroup("server_${server.id}")

        if (!silent) {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVibrate(longArrayOf(0, 500, 200, 500))
        }

        postEventNotification(server, sessionId, notifId, builder.build())
    }

    private fun showPermissionNotification(server: ServerConfig, sessionId: String, permission: String) {
        val (sessionTitle, directory) = getSessionInfo(sessionId)
        val displayTitle = sessionTitle ?: getString(R.string.notification_new_session)
        val projectName = getProjectName(directory)
        val body = if (projectName != null) {
            getString(R.string.notification_needs_permission_project, displayTitle, projectName)
        } else {
            getString(R.string.notification_needs_permission, displayTitle)
        }

        val notifId = eventNotificationId(server.id, sessionId, 1000)
        val pendingIntent = createSessionPendingIntent(server, sessionId, notifId)

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_PERMISSIONS_ID)
            .setContentTitle(getString(R.string.notification_permission_required))
            .setContentText(body)
            .setSubText(server.displayName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .setGroup("server_${server.id}")
            .build()

        postEventNotification(server, sessionId, notifId, notification)
    }

    private fun showQuestionNotification(server: ServerConfig, sessionId: String, questionText: String) {
        val (sessionTitle, directory) = getSessionInfo(sessionId)
        val displayTitle = sessionTitle ?: getString(R.string.notification_new_session)
        val projectName = getProjectName(directory)
        val body = if (projectName != null) {
            getString(R.string.notification_has_question_project, displayTitle, projectName)
        } else {
            getString(R.string.notification_has_question, displayTitle)
        }

        val notifId = eventNotificationId(server.id, sessionId, 2000)
        val pendingIntent = createSessionPendingIntent(server, sessionId, notifId)

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_PERMISSIONS_ID)
            .setContentTitle(getString(R.string.notification_question))
            .setContentText(body)
            .setSubText(server.displayName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 300, 100, 300))
            .setGroup("server_${server.id}")
            .build()

        postEventNotification(server, sessionId, notifId, notification)
    }

    private fun showErrorNotification(server: ServerConfig, sessionId: String?, error: String) {
        val body = if (sessionId != null) {
            val (sessionTitle, _) = getSessionInfo(sessionId)
            sessionTitle ?: error.ifBlank { getString(R.string.error_unknown) }
        } else {
            error.ifBlank { getString(R.string.error_unknown) }
        }

        val notifId = eventNotificationId(server.id, sessionId ?: "error", 3000)
        val pendingIntent = createSessionPendingIntent(server, sessionId, notifId)

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_TASKS_ID)
            .setContentTitle(getString(R.string.notification_session_error))
            .setContentText(body)
            .setSubText(server.displayName)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setGroup("server_${server.id}")
            .build()

        postEventNotification(server, sessionId, notifId, notification)
    }

    private fun postEventNotification(
        server: ServerConfig,
        sessionId: String?,
        notificationId: Int,
        notification: Notification,
    ) {
        val post = {
            notificationManager.notify(notificationId, notification)
            showServerGroupSummary(server)
        }
        if (sessionId == null) {
            post()
        } else {
            SessionNotificationCoordinator.postUnlessActive(server.id, sessionId, post)
        }
    }

    /**
     * Post a group summary notification for a server so Android bundles
     * event notifications from the same server together.
     */
    private fun showServerGroupSummary(server: ServerConfig) {
        val summaryId = serverGroupSummaryNotificationId(server.id)
        val summary = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_TASKS_SILENT_ID)
            .setContentTitle(server.displayName)
            .setContentText(getString(R.string.notification_group_summary))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setGroup("server_${server.id}")
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(summaryId, summary)
    }
}
