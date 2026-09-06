package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.logging.AppLogger as Log
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.PtySocket
import dev.minios.ocremote.data.api.ServerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

private const val WORKSPACE_TAG = "ServerTerminalWorkspace"
private val RECONNECT_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
private const val DEFAULT_TERMINAL_FONT_SIZE_SP = 13f

internal fun isMissingPtyFailure(error: Throwable): Boolean {
    return generateSequence(error) { it.cause }
        .any { cause -> cause.message?.contains("404 Not Found", ignoreCase = true) == true }
}

enum class TerminalTabState {
    Starting,
    Connected,
    Reconnecting,
    Disconnected,
    Exited,
}

enum class TerminalRecoveryAction {
    None,
    Reconnect,
    Restart,
}

internal fun terminalRecoveryAction(state: TerminalTabState, hasPty: Boolean): TerminalRecoveryAction = when {
    state == TerminalTabState.Connected || state == TerminalTabState.Starting -> TerminalRecoveryAction.None
    state == TerminalTabState.Exited || !hasPty -> TerminalRecoveryAction.Restart
    else -> TerminalRecoveryAction.Reconnect
}

data class TerminalTabUi(
    val id: String,
    val title: String,
    val state: TerminalTabState,
    val hasPty: Boolean,
) {
    val connected: Boolean get() = state == TerminalTabState.Connected
    val recoveryAction: TerminalRecoveryAction get() = terminalRecoveryAction(state, hasPty)
}

internal class ServerTerminalWorkspace(
    private val api: OpenCodeApi,
    private val conn: ServerConnection,
) {
    private data class RuntimeTab(
        val id: String,
        var title: String,
        val emulator: TerminalEmulator = TerminalEmulator(),
        var fontSizeSp: Float = DEFAULT_TERMINAL_FONT_SIZE_SP,
        var directory: String? = null,
        var ptyId: String? = null,
        var socket: PtySocket? = null,
        var readerJob: Job? = null,
        var reconnectJob: Job? = null,
        var reconnectAttempt: Int = 0,
        var state: TerminalTabState = TerminalTabState.Starting,
        var lastSize: Pair<Int, Int>? = null,
        var pendingSize: Pair<Int, Int>? = null,
        var resizeJob: Job? = null,
        var creationJob: Job? = null,
    )

    private data class ResizeRequest(
        val tabId: String,
        val ptyId: String,
        val cols: Int,
        val rows: Int,
        val directory: String?,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tabs = mutableListOf<RuntimeTab>()
    private val lock = Any()
    private var defaultFontSizeSp: Float = DEFAULT_TERMINAL_FONT_SIZE_SP
    @Volatile private var serverAvailable = false

    private val _tabList = MutableStateFlow<List<TerminalTabUi>>(emptyList())
    val tabList: StateFlow<List<TerminalTabUi>> = _tabList

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId

    private val _activeVersion = MutableStateFlow(0L)
    val activeVersion: StateFlow<Long> = _activeVersion

    private val _activeConnected = MutableStateFlow(false)
    val activeConnected: StateFlow<Boolean> = _activeConnected

    private val _activeFontSizeSp = MutableStateFlow(DEFAULT_TERMINAL_FONT_SIZE_SP)
    val activeFontSizeSp: StateFlow<Float> = _activeFontSizeSp

    val fallbackEmulator = TerminalEmulator()

    fun activeEmulator(): TerminalEmulator {
        val id = _activeTabId.value
        if (id == null) return fallbackEmulator
        synchronized(lock) {
            return tabs.firstOrNull { it.id == id }?.emulator ?: fallbackEmulator
        }
    }

    fun ensureActiveTab(cwd: String?, directory: String?, onResult: (Boolean) -> Unit = {}) {
        val active = synchronized(lock) { activeTabLocked() }
        if (active == null) {
            val tab = synchronized(lock) { addTabLocked(directory) }
            startTabCreation(tab, cwd, removeOnFailure = true, onResult)
            return
        }
        if (active.state == TerminalTabState.Disconnected && active.ptyId != null) {
            recoverTab(active.id, onResult)
        } else {
            onResult(true)
        }
    }

    fun createTab(cwd: String?, directory: String?, onResult: (Boolean) -> Unit = {}) {
        val tab = synchronized(lock) { addTabLocked(directory) }
        startTabCreation(tab, cwd, removeOnFailure = true, onResult)
    }

    private fun addTabLocked(directory: String?): RuntimeTab {
        val tab = RuntimeTab(
            id = UUID.randomUUID().toString(),
            title = "Tab ${tabs.size + 1}",
            fontSizeSp = defaultFontSizeSp,
            directory = directory,
        )
        tabs.add(tab)
        _activeTabId.value = tab.id
        publishTabsLocked()
        return tab
    }

    private fun startTabCreation(
        tab: RuntimeTab,
        cwd: String?,
        removeOnFailure: Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        publishActiveState()

        tab.creationJob?.cancel()
        tab.creationJob = scope.launch {
            var createdPtyId: String? = null
            try {
                val info = api.createPty(
                    conn = conn,
                    title = tab.title,
                    cwd = cwd,
                    directory = tab.directory,
                )
                createdPtyId = info.id
                val socket = api.openPtySocket(conn, info.id, cursor = 0, directory = tab.directory)

                val accepted = synchronized(lock) {
                    if (!serverAvailable || tabs.none { it.id == tab.id }) {
                        false
                    } else {
                        if (!removeOnFailure) tab.emulator.reset()
                        tab.ptyId = info.id
                        bindConnectedSocketLocked(tab, socket)
                        true
                    }
                }
                if (!accepted) {
                    socket.close()
                    api.removePty(conn, info.id)
                    return@launch
                }

                publishActiveState()
                onResult(true)
            } catch (e: CancellationException) {
                createdPtyId?.let { ptyId ->
                    withContext(NonCancellable) {
                        runCatching { api.removePty(conn, ptyId) }
                    }
                }
                synchronized(lock) {
                    tab.creationJob = null
                    if (tabs.any { it.id == tab.id }) {
                        tab.state = TerminalTabState.Exited
                        publishTabsLocked()
                    }
                }
                publishActiveState()
                throw e
            } catch (e: Exception) {
                Log.e(WORKSPACE_TAG, "Failed to create tab", e)
                createdPtyId?.let { ptyId ->
                    try {
                        api.removePty(conn, ptyId)
                    } catch (_: Exception) {
                    }
                }
                synchronized(lock) {
                    if (removeOnFailure) {
                        tabs.removeAll { it.id == tab.id }
                        if (_activeTabId.value == tab.id) {
                            _activeTabId.value = tabs.lastOrNull()?.id
                        }
                    } else {
                        tab.state = TerminalTabState.Exited
                    }
                    publishTabsLocked()
                }
                publishActiveState()
                onResult(false)
            } finally {
                synchronized(lock) { tab.creationJob = null }
            }
        }
    }

    fun switchTab(tabId: String) {
        synchronized(lock) {
            if (tabs.none { it.id == tabId }) return
            _activeTabId.value = tabId
        }
        publishActiveState()
    }

    fun closeTab(tabId: String) {
        val removed = synchronized(lock) {
            val index = tabs.indexOfFirst { it.id == tabId }
            if (index == -1) return
            val tab = tabs.removeAt(index)
            if (_activeTabId.value == tabId) {
                _activeTabId.value = tabs.getOrNull(index)?.id ?: tabs.lastOrNull()?.id
            }
            publishTabsLocked()
            tab
        }

        removed.readerJob?.cancel()
        removed.reconnectJob?.cancel()
        removed.resizeJob?.cancel()
        removed.creationJob?.cancel()
        scope.launch {
            try {
                removed.socket?.close()
            } catch (_: Exception) {
            }
            try {
                removed.ptyId?.let { api.removePty(conn, it) }
            } catch (_: Exception) {
            }
        }
        publishActiveState()
    }

    fun sendActiveInput(input: String) {
        val socket = synchronized(lock) { activeTabLocked()?.socket } ?: return
        scope.launch {
            try {
                socket.send(input)
            } catch (e: Exception) {
                Log.e(WORKSPACE_TAG, "Failed to write terminal input", e)
            }
        }
    }

    fun clearActiveBuffer() {
        val tab = synchronized(lock) { activeTabLocked() } ?: return
        tab.emulator.reset()
        if (_activeTabId.value == tab.id) {
            _activeVersion.value = tab.emulator.version
        }
    }

    fun setActiveFontSize(fontSizeSp: Float) {
        val clamped = fontSizeSp.coerceIn(6f, 20f)
        synchronized(lock) {
            val tab = activeTabLocked() ?: return
            tab.fontSizeSp = clamped
            if (_activeTabId.value == tab.id) {
                _activeFontSizeSp.value = clamped
            }
        }
    }

    fun setDefaultFontSize(fontSizeSp: Float) {
        val clamped = fontSizeSp.coerceIn(6f, 20f)
        synchronized(lock) {
            defaultFontSizeSp = clamped
            if (activeTabLocked() == null) {
                _activeFontSizeSp.value = clamped
            }
        }
    }

    fun setServerAvailable(available: Boolean) {
        if (serverAvailable == available) return
        serverAvailable = available
        synchronized(lock) {
            if (!available) {
                tabs.forEach { tab ->
                    tab.creationJob?.cancel()
                    tab.creationJob = null
                    tab.reconnectJob?.cancel()
                    tab.reconnectJob = null
                    tab.resizeJob?.cancel()
                    tab.resizeJob = null
                    if (tab.state == TerminalTabState.Reconnecting) tab.state = TerminalTabState.Disconnected
                }
            } else {
                tabs.filter { tab ->
                    tab.ptyId != null && tab.state != TerminalTabState.Connected && tab.reconnectJob?.isActive != true
                }.forEach { tab ->
                    tab.state = TerminalTabState.Reconnecting
                    tab.reconnectJob = scope.launch {
                        reconnectLoop(tab.id, immediate = true, onFirstResult = null)
                    }
                }
            }
            publishTabsLocked()
        }
        publishActiveState()
    }

    fun resizeActive(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        val size = cols to rows
        synchronized(lock) {
            val tab = activeTabLocked() ?: return
            if (tab.lastSize == size) return
            tab.emulator.resize(cols, rows)
            tab.lastSize = size
            if (_activeTabId.value == tab.id) {
                _activeVersion.value = tab.emulator.version
            }
            if (tab.state == TerminalTabState.Connected && tab.ptyId != null) {
                tab.pendingSize = size
                scheduleResizeLocked(tab)
            }
        }
    }

    private fun scheduleResizeLocked(tab: RuntimeTab) {
        if (tab.resizeJob?.isActive == true) return
        tab.resizeJob = scope.launch { resizeLoop(tab.id) }
    }

    private suspend fun resizeLoop(tabId: String) {
        while (true) {
            if (!serverAvailable) return
            delay(120)
            if (!serverAvailable) return
            val request = synchronized(lock) {
                val tab = tabs.firstOrNull { it.id == tabId } ?: return
                val size = tab.pendingSize
                val ptyId = tab.ptyId
                if (size == null || ptyId == null || tab.state != TerminalTabState.Connected) {
                    tab.resizeJob = null
                    return
                }
                tab.pendingSize = null
                ResizeRequest(tab.id, ptyId, size.first, size.second, tab.directory)
            }
            try {
                val ok = api.updatePtySize(
                    conn = conn,
                    ptyId = request.ptyId,
                    cols = request.cols,
                    rows = request.rows,
                    directory = request.directory,
                )
                if (!ok) Log.w(WORKSPACE_TAG, "Resize rejected for tab ${request.tabId}")
            } catch (e: Exception) {
                Log.w(WORKSPACE_TAG, "Failed to resize tab ${request.tabId}: ${request.cols}x${request.rows}", e)
            }
        }
    }

    fun recoverTab(tabId: String, onResult: (Boolean) -> Unit = {}) {
        val tab = synchronized(lock) { tabs.firstOrNull { it.id == tabId } }
        if (tab == null) {
            onResult(false)
            return
        }
        when (terminalRecoveryAction(tab.state, tab.ptyId != null)) {
            TerminalRecoveryAction.None -> onResult(true)
            TerminalRecoveryAction.Restart -> {
                synchronized(lock) {
                    tab.readerJob?.cancel()
                    tab.reconnectJob?.cancel()
                    tab.resizeJob?.cancel()
                    tab.creationJob?.cancel()
                    tab.socket = null
                    tab.ptyId = null
                    tab.state = TerminalTabState.Starting
                    tab.reconnectAttempt = 0
                    publishTabsLocked()
                }
                startTabCreation(tab, tab.directory, removeOnFailure = false, onResult)
            }
            TerminalRecoveryAction.Reconnect -> synchronized(lock) {
                tab.reconnectJob?.cancel()
                tab.reconnectAttempt = 0
                tab.state = TerminalTabState.Reconnecting
                tab.reconnectJob = scope.launch {
                    reconnectLoop(tabId = tab.id, immediate = true, onFirstResult = onResult)
                }
                publishTabsLocked()
            }
        }
        publishActiveState()
    }

    fun closeAll() {
        val all = synchronized(lock) {
            val copy = tabs.toList()
            tabs.clear()
            _activeTabId.value = null
            publishTabsLocked()
            copy
        }
        all.forEach { tab ->
            tab.readerJob?.cancel()
            tab.reconnectJob?.cancel()
            tab.resizeJob?.cancel()
            tab.creationJob?.cancel()
            scope.launch {
                try {
                    tab.socket?.close()
                } catch (_: Exception) {
                }
                try {
                    tab.ptyId?.let { api.removePty(conn, it) }
                } catch (_: Exception) {
                }
            }
        }
        publishActiveState()
    }

    private fun activeTabLocked(): RuntimeTab? {
        val id = _activeTabId.value ?: return null
        return tabs.firstOrNull { it.id == id }
    }

    private fun bindConnectedSocketLocked(tab: RuntimeTab, socket: PtySocket) {
        tab.socket = socket
        tab.state = TerminalTabState.Connected
        tab.reconnectAttempt = 0
        tab.reconnectJob?.cancel()
        tab.reconnectJob = null
        tab.readerJob?.cancel()
        tab.readerJob = scope.launch {
            try {
                socket.readLoop { chunk ->
                    tab.emulator.process(chunk)
                    if (_activeTabId.value == tab.id) {
                        _activeVersion.value = tab.emulator.version
                    }
                }
            } catch (e: Exception) {
                Log.w(WORKSPACE_TAG, "Tab stream closed: ${tab.id}", e)
            } finally {
                onSocketClosed(tab.id, socket)
            }
        }
        publishTabsLocked()
        tab.lastSize?.let { size ->
            tab.pendingSize = size
            scheduleResizeLocked(tab)
        }
    }

    private fun onSocketClosed(tabId: String, socket: PtySocket) {
        var shouldReconnect = false
        synchronized(lock) {
            val tab = tabs.firstOrNull { it.id == tabId } ?: return
            if (tab.socket !== socket) return
            tab.socket = null
            tab.state = TerminalTabState.Reconnecting
            tab.readerJob = null
            tab.resizeJob?.cancel()
            tab.resizeJob = null
            tab.pendingSize = tab.lastSize
            publishTabsLocked()
            shouldReconnect = serverAvailable && tab.ptyId != null && tab.reconnectJob?.isActive != true
            if (shouldReconnect) {
                tab.reconnectJob = scope.launch {
                    reconnectLoop(tabId = tabId, immediate = false, onFirstResult = null)
                }
            }
        }
        publishActiveState()
    }

    private suspend fun reconnectLoop(tabId: String, immediate: Boolean, onFirstResult: ((Boolean) -> Unit)?) {
        var firstAttempt = true
        while (true) {
            if (!serverAvailable) {
                synchronized(lock) {
                    tabs.firstOrNull { it.id == tabId }?.let { tab ->
                        tab.reconnectJob = null
                        if (tab.state == TerminalTabState.Reconnecting) tab.state = TerminalTabState.Disconnected
                        publishTabsLocked()
                    }
                }
                publishActiveState()
                return
            }
            val snapshot = synchronized(lock) {
                val tab = tabs.firstOrNull { it.id == tabId } ?: return
                if (tab.state == TerminalTabState.Connected) {
                    tab.reconnectJob = null
                    if (firstAttempt) onFirstResult?.invoke(true)
                    return
                }
                val pty = tab.ptyId
                if (pty == null) {
                    tab.reconnectJob = null
                    if (firstAttempt) onFirstResult?.invoke(false)
                    return
                }
                Triple(pty, tab.directory, tab.reconnectAttempt)
            }

            val delayMs = if (firstAttempt && immediate) {
                0L
            } else {
                RECONNECT_BACKOFF_MS[snapshot.third.coerceIn(0, RECONNECT_BACKOFF_MS.lastIndex)]
            }
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)

            try {
                val socket = api.openPtySocket(conn, snapshot.first, cursor = -1, directory = snapshot.second)
                synchronized(lock) {
                    val tab = tabs.firstOrNull { it.id == tabId }
                    if (tab == null || tab.ptyId != snapshot.first) {
                        scope.launch { socket.close() }
                        return@synchronized
                    }
                    bindConnectedSocketLocked(tab, socket)
                }
                publishActiveState()
                if (firstAttempt) onFirstResult?.invoke(true)
                return
            } catch (e: Exception) {
                if (isMissingPtyFailure(e)) {
                    Log.w(WORKSPACE_TAG, "PTY no longer exists for tab $tabId")
                    synchronized(lock) {
                        val tab = tabs.firstOrNull { it.id == tabId } ?: return
                        tab.ptyId = null
                        tab.state = TerminalTabState.Exited
                        tab.reconnectJob = null
                        publishTabsLocked()
                    }
                    publishActiveState()
                    if (firstAttempt) onFirstResult?.invoke(false)
                    return
                }
                Log.w(WORKSPACE_TAG, "Reconnect failed for tab $tabId", e)
                synchronized(lock) {
                    val tab = tabs.firstOrNull { it.id == tabId } ?: return
                    tab.reconnectAttempt += 1
                    if (tab.reconnectAttempt >= RECONNECT_BACKOFF_MS.size) {
                        tab.state = TerminalTabState.Disconnected
                        tab.reconnectJob = null
                    }
                    publishTabsLocked()
                }
                if (firstAttempt) onFirstResult?.invoke(false)
                firstAttempt = false
                val stopped = synchronized(lock) {
                    tabs.firstOrNull { it.id == tabId }?.state == TerminalTabState.Disconnected
                }
                if (stopped) return
            }
        }
    }

    private fun publishTabsLocked() {
        _tabList.value = tabs.map { TerminalTabUi(it.id, it.title, it.state, it.ptyId != null) }
    }

    private fun publishActiveState() {
        val active = synchronized(lock) {
            activeTabLocked()?.let {
                Triple(it.state == TerminalTabState.Connected, it.emulator.version, it.fontSizeSp)
            }
        }
        if (active == null) {
            _activeConnected.value = false
            _activeVersion.value = 0L
            _activeFontSizeSp.value = defaultFontSizeSp
            return
        }
        _activeConnected.value = active.first
        _activeVersion.value = active.second
        _activeFontSizeSp.value = active.third
    }
}

internal object ServerTerminalRegistry {
    private val lock = Any()
    private val byServer = mutableMapOf<String, ServerTerminalWorkspace>()

    fun workspaceFor(serverId: String, api: OpenCodeApi, conn: ServerConnection): ServerTerminalWorkspace {
        synchronized(lock) {
            return byServer.getOrPut(serverId) { ServerTerminalWorkspace(api, conn) }
        }
    }
}
