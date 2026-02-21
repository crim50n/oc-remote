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
        var fontSizeSp: Float = 13f,
        var ptyId: String? = null,
        var socket: PtySocket? = null,
        var readerJob: Job? = null,
        var connected: Boolean = false,
        var lastSize: Pair<Int, Int>? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tabs = mutableListOf<RuntimeTab>()
    private val lock = Any()

    private val _tabList = MutableStateFlow<List<TerminalTabUi>>(emptyList())
    val tabList: StateFlow<List<TerminalTabUi>> = _tabList

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId

    private val _activeVersion = MutableStateFlow(0L)
    val activeVersion: StateFlow<Long> = _activeVersion

    private val _activeConnected = MutableStateFlow(false)
    val activeConnected: StateFlow<Boolean> = _activeConnected

    private val _activeFontSizeSp = MutableStateFlow(13f)
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
                val socket = api.openPtySocket(conn, info.id, cursor = -1, directory = directory)

                synchronized(lock) {
                    tab.ptyId = info.id
                    tab.socket = socket
                    tab.connected = true
                    tab.lastSize = null
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
                            synchronized(lock) {
                                tab.connected = false
                                publishTabsLocked()
                            }
                            publishActiveState()
                        }
                    }
                    publishTabsLocked()
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
        val clamped = fontSizeSp.coerceIn(6f, 32f)
        val tab = synchronized(lock) { activeTabLocked() } ?: return
        tab.fontSizeSp = clamped
        if (_activeTabId.value == tab.id) {
            _activeFontSizeSp.value = clamped
        }
    }

    fun resizeActive(cols: Int, rows: Int, directory: String?) {
        if (cols <= 0 || rows <= 0) return
        val tab = synchronized(lock) { activeTabLocked() } ?: return

        tab.emulator.resize(cols, rows)
        if (_activeTabId.value == tab.id) {
            _activeVersion.value = tab.emulator.version
        }

        val ptyId = tab.ptyId ?: return
        if (!tab.connected) return
        val size = cols to rows
        if (tab.lastSize == size) return

        scope.launch {
            try {
                val ok = api.updatePtySize(
                    conn = conn,
                    ptyId = ptyId,
                    cols = cols,
                    rows = rows,
                    directory = directory,
                )
                if (ok) {
                    tab.lastSize = size
                }
            } catch (e: Exception) {
                Log.w(WORKSPACE_TAG, "Failed to resize tab ${tab.id}: ${cols}x$rows", e)
            }
        }
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

    private fun publishTabsLocked() {
        _tabList.value = tabs.map { TerminalTabUi(it.id, it.title, it.connected) }
    }

    private fun publishActiveState() {
        val active = synchronized(lock) { activeTabLocked() }
        if (active == null) {
            _activeConnected.value = false
            _activeVersion.value = 0L
            _activeFontSizeSp.value = 13f
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
