package dev.minios.ocremote.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.MainActivity
import dev.minios.ocremote.R
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.data.api.SseClient
import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.data.repository.ServerRepository
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.domain.model.ServerConfig
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
 * - Holds a single partial WakeLock while any server is connected
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

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** All active/pending server connections keyed by serverId. */
    private val connections = mutableMapOf<String, ServerConnectionState>()

    private var notificationWatchdogJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var notificationManager: NotificationManager
    private var foregroundStarted: Boolean = false

    /** Observable set of server IDs that are actually connected (SSE stream active). */
    private val _connectedServerIds = MutableStateFlow<Set<String>>(emptySet())
    val connectedServerIds: StateFlow<Set<String>> = _connectedServerIds.asStateFlow()

    /** Observable set of server IDs that are attempting to connect (SSE not yet established or reconnecting). */
    private val _connectingServerIds = MutableStateFlow<Set<String>>(emptySet())
    val connectingServerIds: StateFlow<Set<String>> = _connectingServerIds.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): OpenCodeConnectionService = this@OpenCodeConnectionService
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Log.d(TAG, "Service created")

        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()

        serviceScope.launch {
            autoConnectConfiguredServers()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (BuildConfig.DEBUG) Log.d(TAG, "Service started, action=${intent?.action}")

        when (intent?.action) {
            ACTION_DISCONNECT_ALL -> {
                Log.i(TAG, "Disconnect All requested via notification")
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

        ensureForegroundStarted()

        // Read server details from intent and connect
        intent?.let { i ->
            val serverId = i.getStringExtra("server_id")
            val serverName = i.getStringExtra("server_name")
            val serverUrl = i.getStringExtra("server_url")
            val serverUsername = i.getStringExtra("server_username") ?: "opencode"
            val serverPassword = i.getStringExtra("server_password")

            if (serverId != null && serverUrl != null) {
                val serverConfig = ServerConfig(
                    id = serverId,
                    url = serverUrl,
                    username = serverUsername,
                    password = serverPassword,
                    name = serverName
                )
                connect(serverConfig)
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
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
    fun connect(server: ServerConfig) {
        if (connections.containsKey(server.id)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "Already connected to server ${server.id}, skipping")
            return
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "Connecting to server: ${server.displayName} (${server.url})")

        ensureForegroundStarted()

        val conn = ServerConnection.from(server.url, server.username, server.password)

        // Acquire wake lock (shared — first connect acquires, last disconnect releases)
        acquireWakeLock()

        // Start SSE connection with auto-reconnect
        val job = startSseConnection(server, conn)

        connections[server.id] = ServerConnectionState(
            config = server,
            conn = conn,
            sseJob = job,
            isConnected = false
        )

        _connectingServerIds.update { it + server.id }

        // Update persistent notification
        updatePersistentNotification()

        // Start watchdog if not already running
        startNotificationWatchdog()
    }

    /**
     * Disconnect from a single server.
     */
    fun disconnect(serverId: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Disconnecting server $serverId")

        val state = connections.remove(serverId) ?: return
        state.sseJob.cancel()

        _connectedServerIds.update { it - serverId }
        _connectingServerIds.update { it - serverId }

        eventReducer.clearForServer(serverId)

        if (connections.isEmpty()) {
            // Last server disconnected — clean up and stop service
            releaseWakeLock()
            notificationWatchdogJob?.cancel()
            notificationWatchdogJob = null
            stopForeground(STOP_FOREGROUND_REMOVE)
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

    private fun disconnectAllInternal(stopService: Boolean) {
        if (BuildConfig.DEBUG) Log.d(TAG, "Disconnecting all servers")

        for ((_, state) in connections) {
            state.sseJob.cancel()
        }
        val serverIds = connections.keys.toList()
        connections.clear()

        _connectedServerIds.value = emptySet()
        _connectingServerIds.value = emptySet()

        for (serverId in serverIds) {
            eventReducer.clearForServer(serverId)
        }

        releaseWakeLock()
        notificationWatchdogJob?.cancel()
        notificationWatchdogJob = null

        if (stopService) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
            stopSelf()
        }
    }

    private suspend fun autoConnectConfiguredServers() {
        try {
            val autoConnectServers = serverRepository.servers.first().filter { it.autoConnect }
            if (autoConnectServers.isEmpty()) return
            Log.i(TAG, "Auto-connecting ${autoConnectServers.size} server(s)")
            autoConnectServers.forEach { connect(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-connect servers", e)
        }
    }

    private fun ensureForegroundStarted() {
        if (foregroundStarted) return
        startForeground(PERSISTENT_NOTIFICATION_ID, createPersistentNotification())
        foregroundStarted = true
    }

    /**
     * Check if a specific server is connected.
     */
    fun isConnected(serverId: String): Boolean {
        return connections[serverId]?.sseJob?.isActive == true
    }

    // ============ Notification Watchdog ============

    private fun startNotificationWatchdog() {
        if (notificationWatchdogJob?.isActive == true) return
        notificationWatchdogJob = serviceScope.launch {
            while (isActive && connections.isNotEmpty()) {
                delay(5_000)
                if (!isNotificationVisible()) {
                    Log.i(TAG, "Foreground notification was dismissed, restoring it")
                    startForeground(PERSISTENT_NOTIFICATION_ID, createPersistentNotification())
                }
            }
        }
    }

    private fun isNotificationVisible(): Boolean {
        return notificationManager.activeNotifications.any { it.id == PERSISTENT_NOTIFICATION_ID }
    }

    // ============ WakeLock ============

    private fun acquireWakeLock() {
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

    // ============ SSE Connection with Auto-Reconnect ============

    private fun startSseConnection(server: ServerConfig, conn: ServerConnection): Job {
        return serviceScope.launch {
            var attempt = 0

            while (isActive) {
                attempt++
                Log.i(TAG, "[${server.displayName}] SSE connection attempt #$attempt")

                // Pre-load sessions via REST API for all projects
                try {
                    val projects = api.listProjects(conn)
                    if (projects.isEmpty()) {
                        // Fallback: load sessions without directory header (server CWD only)
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
                            } catch (e: Exception) {
                                Log.w(TAG, "[${server.displayName}] Failed to pre-load sessions for project ${project.displayName}: ${e.message}")
                            }
                        }
                        Log.i(TAG, "[${server.displayName}] Pre-loaded $totalSessions sessions across ${projects.size} projects")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[${server.displayName}] Failed to pre-load sessions: ${e.message}")
                }

                try {
                    sseClient.connectToGlobalEvents(conn)
                        .catch { error ->
                            Log.e(TAG, "[${server.displayName}] SSE stream error", error)
                            updateServerConnected(server.id, false)
                            throw error
                        }
                        .collect { event ->
                            if (connections[server.id]?.isConnected != true) {
                                updateServerConnected(server.id, true)
                                attempt = 0
                                updatePersistentNotification()
                            }
                            processEvent(server, event)
                        }

                    // Flow completed normally (server closed connection)
                    Log.w(TAG, "[${server.displayName}] SSE stream completed")
                    updateServerConnected(server.id, false)
                } catch (e: CancellationException) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "[${server.displayName}] SSE job cancelled, not reconnecting")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "[${server.displayName}] SSE connection failed: ${e.message}")
                    updateServerConnected(server.id, false)
                }

                // If this server was removed from connections, stop the loop
                if (!connections.containsKey(server.id)) break

                val delayMs = calculateBackoff(attempt)
                Log.i(TAG, "[${server.displayName}] Reconnecting in ${delayMs}ms (attempt #$attempt)")
                updatePersistentNotification()
                delay(delayMs)
            }
        }
    }

    private fun updateServerConnected(serverId: String, connected: Boolean) {
        val state = connections[serverId] ?: return
        connections[serverId] = state.copy(isConnected = connected)
        if (connected) {
            _connectingServerIds.update { it - serverId }
            _connectedServerIds.update { it + serverId }
        } else {
            _connectedServerIds.update { it - serverId }
            _connectingServerIds.update { it + serverId }
        }
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

    private fun processEvent(server: ServerConfig, event: SseEvent) {
        if (BuildConfig.DEBUG) Log.d(TAG, "[${server.displayName}] SSE event: ${event.javaClass.simpleName}")

        eventReducer.processEvent(event, server.id)

        when (event) {
            is SseEvent.SessionIdle -> {
                if (isChildSession(event.sessionId)) return
                Log.i(TAG, "[${server.displayName}] Session idle -> Response ready for ${event.sessionId}")
                serviceScope.launch {
                    if (settingsRepository.notificationsEnabled.first()) {
                        showTaskCompleteNotification(server, event.sessionId)
                    }
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
                Log.i(TAG, "[${server.displayName}] Session error: ${event.error}")
                showErrorNotification(server, event.sessionId, event.error)
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

    /** Generate a stable notification ID for a server+session event type. */
    private fun eventNotificationId(serverId: String, sessionId: String, typeOffset: Int): Int {
        return (serverId + sessionId).hashCode() + typeOffset
    }

    companion object {
        const val ACTION_OPEN_SESSION = "dev.minios.ocremote.OPEN_SESSION"
        const val ACTION_DISCONNECT = "dev.minios.ocremote.DISCONNECT"
        const val ACTION_DISCONNECT_ALL = "dev.minios.ocremote.DISCONNECT_ALL"
        const val EXTRA_SERVER_URL = "server_url"
        const val EXTRA_SERVER_USERNAME = "server_username"
        const val EXTRA_SERVER_PASSWORD = "server_password"
        const val EXTRA_SERVER_NAME = "server_name"
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

        // Disconnect All action
        val disconnectAllIntent = Intent(this, OpenCodeConnectionService::class.java).apply {
            action = ACTION_DISCONNECT_ALL
        }
        val disconnectAllPendingIntent = PendingIntent.getService(
            this, 1, disconnectAllIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val serverCount = connections.size
        val connectedCount = connections.values.count { it.isConnected }

        val title = if (serverCount == 0) {
            getString(R.string.app_name)
        } else if (serverCount == 1) {
            val server = connections.values.first()
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
            .addAction(R.mipmap.ic_launcher, getString(R.string.notification_disconnect_all), disconnectAllPendingIntent)

        // InboxStyle when multiple servers
        if (serverCount > 1) {
            val inboxStyle = NotificationCompat.InboxStyle()
                .setBigContentTitle(getString(R.string.notification_inbox_title, connectedCount, serverCount))
            for ((_, state) in connections) {
                val status = if (state.isConnected) getString(R.string.notification_status_connected) else getString(R.string.notification_status_connecting)
                inboxStyle.addLine("${state.config.displayName}: $status")
            }
            builder.setStyle(inboxStyle)
        }

        return builder.build()
    }

    private fun updatePersistentNotification() {
        val notification = createPersistentNotification()
        notificationManager.notify(PERSISTENT_NOTIFICATION_ID, notification)
    }

    // ============ Event Notifications (grouped by server) ============

    private suspend fun showTaskCompleteNotification(server: ServerConfig, sessionId: String) {
        val (sessionTitle, _) = getSessionInfo(sessionId)
        val body = sessionTitle ?: sessionId

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

        notificationManager.notify(notifId, builder.build())
        showServerGroupSummary(server)
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

        notificationManager.notify(notifId, notification)
        showServerGroupSummary(server)
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

        notificationManager.notify(notifId, notification)
        showServerGroupSummary(server)
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

        notificationManager.notify(notifId, notification)
        showServerGroupSummary(server)
    }

    /**
     * Post a group summary notification for a server so Android bundles
     * event notifications from the same server together.
     */
    private fun showServerGroupSummary(server: ServerConfig) {
        val summaryId = "server_summary_${server.id}".hashCode()
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
