package dev.minios.ocremote.ui.screens.chat

import android.util.Log
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.PtySocket
import dev.minios.ocremote.data.api.ServerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

private const val WORKSPACE_TAG = "ServerTerminalWorkspace"
private val RECONNECT_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
private const val DEFAULT_TERMINAL_FONT_SIZE_SP = 13f

data class TerminalTabUi(
    val id: String,
    val title: String,
    val connected: Boolean,
)

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
        var connected: Boolean = false,
        var lastSize: Pair<Int, Int>? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tabs = mutableListOf<RuntimeTab>()
    private val lock = Any()
    private var defaultFontSizeSp: Float = DEFAULT_TERMINAL_FONT_SIZE_SP

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
        val hasActive = synchronized(lock) { activeTabLocked() != null }
        if (hasActive) {
            onResult(true)
            return
        }
        createTab(cwd = cwd, directory = directory, onResult = onResult)
    }

    fun createTab(cwd: String?, directory: String?, onResult: (Boolean) -> Unit = {}) {
        val tab = synchronized(lock) {
            val index = tabs.size + 1
            RuntimeTab(
                id = UUID.randomUUID().toString(),
                title = "Tab $index",
                fontSizeSp = defaultFontSizeSp,
                directory = directory,
            ).also {
                tabs.add(it)
                _activeTabId.value = it.id
                publishTabsLocked()
            }
        }
        publishActiveState()

        scope.launch {
            try {
                val info = api.createPty(
                    conn = conn,
                    title = tab.title,
                    cwd = cwd,
                    directory = directory,
                )
                val socket = api.openPtySocket(conn, info.id, cursor = 0, directory = directory)

                synchronized(lock) {
                    tab.ptyId = info.id
                    bindConnectedSocketLocked(tab, socket)
                }

                publishActiveState()
                onResult(true)
            } catch (e: Exception) {
                Log.e(WORKSPACE_TAG, "Failed to create tab", e)
                synchronized(lock) {
                    tabs.removeAll { it.id == tab.id }
                    if (_activeTabId.value == tab.id) {
                        _activeTabId.value = tabs.lastOrNull()?.id
                    }
                    publishTabsLocked()
                }
                publishActiveState()
                onResult(false)
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
        val tab = synchronized(lock) { activeTabLocked() } ?: run {
            android.util.Log.w("TerminalZoom", "setActiveFontSize: no active tab!")
            return
        }
        android.util.Log.d("TerminalZoom", "setActiveFontSize: clamped=$clamped old=${tab.fontSizeSp} tabId=${tab.id} activeId=${_activeTabId.value} flowId=${System.identityHashCode(_activeFontSizeSp)} workspaceId=${System.identityHashCode(this)}")
        tab.fontSizeSp = clamped
        if (_activeTabId.value == tab.id) {
            _activeFontSizeSp.value = clamped
            android.util.Log.d("TerminalZoom", "setActiveFontSize: StateFlow updated to ${_activeFontSizeSp.value}")
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

    fun resizeActive(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        val tab = synchronized(lock) { activeTabLocked() } ?: return

        android.util.Log.d("TerminalZoom", "resizeActive: cols=$cols rows=$rows ptyId=${tab.ptyId} lastSize=${tab.lastSize} connected=${tab.connected} tabDir=${tab.directory}")

        tab.emulator.resize(cols, rows)
        if (_activeTabId.value == tab.id) {
            _activeVersion.value = tab.emulator.version
        }

        val ptyId = tab.ptyId ?: run {
            android.util.Log.w("TerminalZoom", "resizeActive: no ptyId, skipping server resize")
            return
        }
        val size = cols to rows
        if (tab.lastSize == size && tab.connected) {
            android.util.Log.d("TerminalZoom", "resizeActive: dedup, same size and connected")
            return
        }
        tab.lastSize = size
        if (!tab.connected) {
            android.util.Log.d("TerminalZoom", "resizeActive: not connected, skipping server resize")
            return
        }

        // Use the directory stored on the tab (the one used when creating the PTY)
        // rather than the caller's sessionDirectory, which may differ if loadSession()
        // completed after the PTY was created.
        val tabDirectory = tab.directory
        android.util.Log.d("TerminalZoom", "resizeActive: sending updatePtySize cols=$cols rows=$rows dir=$tabDirectory")
        scope.launch {
            try {
                val ok = api.updatePtySize(
                    conn = conn,
                    ptyId = ptyId,
                    cols = cols,
                    rows = rows,
                    directory = tabDirectory,
                )
                android.util.Log.d("TerminalZoom", "resizeActive: updatePtySize result=$ok")
                if (!ok) Log.w(WORKSPACE_TAG, "Resize rejected for tab ${tab.id}")
            } catch (e: Exception) {
                Log.w(WORKSPACE_TAG, "Failed to resize tab ${tab.id}: ${cols}x$rows", e)
            }
        }
    }

    fun reconnectTab(tabId: String, onResult: (Boolean) -> Unit = {}) {
        val scheduled = synchronized(lock) {
            val tab = tabs.firstOrNull { it.id == tabId } ?: return@synchronized false
            if (tab.connected) return@synchronized true
            if (tab.ptyId == null) return@synchronized false
            if (tab.reconnectJob?.isActive == true) return@synchronized true
            tab.reconnectJob = scope.launch {
                reconnectLoop(tabId = tab.id, immediate = true, onFirstResult = null)
            }
            true
        }
        onResult(scheduled)
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
        tab.connected = true
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
        tab.lastSize?.let { (cols, rows) ->
            scope.launch {
                try {
                    val ptyId = synchronized(lock) { tabs.firstOrNull { it.id == tab.id }?.ptyId } ?: return@launch
                    api.updatePtySize(
                        conn = conn,
                        ptyId = ptyId,
                        cols = cols,
                        rows = rows,
                        directory = tab.directory,
                    )
                } catch (e: Exception) {
                    Log.w(WORKSPACE_TAG, "Failed to apply pending resize for tab ${tab.id}", e)
                }
            }
        }
    }

    private fun onSocketClosed(tabId: String, socket: PtySocket) {
        var shouldReconnect = false
        synchronized(lock) {
            val tab = tabs.firstOrNull { it.id == tabId } ?: return
            if (tab.socket !== socket) return
            tab.socket = null
            tab.connected = false
            tab.readerJob = null
            publishTabsLocked()
            shouldReconnect = tab.ptyId != null && tab.reconnectJob?.isActive != true
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
            val snapshot = synchronized(lock) {
                val tab = tabs.firstOrNull { it.id == tabId } ?: return
                if (tab.connected) {
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
                Log.w(WORKSPACE_TAG, "Reconnect failed for tab $tabId", e)
                synchronized(lock) {
                    val tab = tabs.firstOrNull { it.id == tabId } ?: return
                    tab.reconnectAttempt += 1
                    publishTabsLocked()
                }
                if (firstAttempt) onFirstResult?.invoke(false)
                firstAttempt = false
            }
        }
    }

    private fun publishTabsLocked() {
        _tabList.value = tabs.map { TerminalTabUi(it.id, it.title, it.connected) }
    }

    private fun publishActiveState() {
        val active = synchronized(lock) { activeTabLocked() }
        if (active == null) {
            _activeConnected.value = false
            _activeVersion.value = 0L
            _activeFontSizeSp.value = defaultFontSizeSp
            return
        }
        _activeConnected.value = active.connected
        _activeVersion.value = active.emulator.version
        _activeFontSizeSp.value = active.fontSizeSp
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
