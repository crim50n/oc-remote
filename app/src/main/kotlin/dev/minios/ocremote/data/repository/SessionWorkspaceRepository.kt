package dev.minios.ocremote.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.minios.ocremote.logging.AppLogger as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
@SerialName("session")
data class SessionTab(
    override val serverId: String,
    override val sessionId: String,
    override val title: String = "",
    override val lastActive: Long = 0,
    override val hasDraft: Boolean = false,
    override val pinned: Boolean = false,
) : WorkspaceTab {
    override val key: String get() = "$serverId:$sessionId"
}

@Serializable
data class ChatViewSnapshot(
    val itemKey: String? = null,
    val offset: Int = 0,
    val following: Boolean = true,
    val terminal: Boolean = false,
    val terminalDocked: Boolean = false,
    val terminalPaneFraction: Float = 0.45f,
    val terminalExtraKeys: Boolean? = null,
)

internal fun <T : WorkspaceTab> openedSessionTabs(tabs: List<T>, tab: T): List<T> =
    if (tabs.any { it.key == tab.key }) tabs.map { if (it.key == tab.key) tab else it } else tabs + tab

/** Device-local metadata only: never stores credentials, messages, or terminal buffers. */
@Singleton
class SessionWorkspaceRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val preferences = context.getSharedPreferences("session_workspace", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(readState())
    val state = _state.asStateFlow()
    val tabs: List<WorkspaceTab> get() = _state.value.tabs

    private fun readState(): BrowserTabs = try {
        (preferences.getString("workspace_tabs_v2", null) ?: preferences.getString("browser_tabs", null))
            ?.let { decodeBrowserTabs(json, it) }?.normalized()
            ?: BrowserTabs(preferences.getString("tabs", null)?.let { json.decodeFromString<List<SessionTab>>(it) }.orEmpty()).normalized()
    } catch (e: Exception) {
        Log.e("SessionWorkspace", "Failed to restore open sessions", e)
        BrowserTabs()
    }

    @Synchronized
    fun open(serverId: String, sessionId: String, title: String = "") {
        val previous = tabs.firstOrNull { it.serverId == serverId && it.sessionId == sessionId }
        write(_state.value.open(SessionTab(serverId, sessionId,
            title.ifBlank { previous?.title.orEmpty() }, System.currentTimeMillis(), previous?.hasDraft ?: false)))
    }

    @Synchronized
    fun updateDraft(key: String, hasDraft: Boolean) {
        if (tabs.any { it.key == key && it.hasDraft != hasDraft }) {
            write(_state.value.copy(tabs = tabs.map { if (it.key == key) it.copyMetadata(hasDraft = hasDraft) else it }))
        }
    }

    @Synchronized
    fun openAfter(tab: WorkspaceTab, anchor: String) {
        write(_state.value.openAfter(tab.copyMetadata(lastActive = System.currentTimeMillis()), anchor))
    }

    @Synchronized
    fun openPage(tab: PageTab) { write(_state.value.open(tab.copy(lastActive = System.currentTimeMillis()))) }

    @Synchronized
    fun updateTitle(key: String, title: String) {
        if (title.isNotBlank() && tabs.any { it.key == key && it.title != title }) {
            write(_state.value.copy(tabs = tabs.map { if (it.key == key) it.copyMetadata(title = title) else it }))
        }
    }

    @Synchronized
    fun markViewed(key: String) {
        write(_state.value.copy(tabs = tabs.map { if (it.key == key) it.copyMetadata(lastActive = System.currentTimeMillis()) else it }))
    }

    @Synchronized
    fun closeTabs(keys: Set<String>, activeKey: String?, sessionsOnly: Boolean = false): String? {
        val result = _state.value.close(keys, activeKey, sessionsOnly)
        write(result.state)
        return result.activeKey
    }

    @Synchronized
    fun pin(key: String, pinned: Boolean) { write(_state.value.pin(key, pinned)) }

    @Synchronized
    fun restoreClosed(key: String? = null): WorkspaceTab? {
        val result = _state.value.restore(key)
        write(result.state)
        return tabs.firstOrNull { it.key == result.activeKey }
    }

    @Synchronized
    fun move(key: String, delta: Int) {
        val list = tabs.toMutableList()
        val from = list.indexOfFirst { it.key == key }
        if (from < 0) return
        val to = (from + delta).coerceIn(list.indices)
        list.add(to, list.removeAt(from))
        write(_state.value.copy(tabs = list).normalized())
    }

    @Synchronized
    fun reorder(expected: List<String>, ordered: List<String>) {
        write(_state.value.reorder(expected, ordered))
    }

    @Synchronized
    fun reorderSessions(expected: List<String>, ordered: List<String>) {
        write(_state.value.reorderSessions(expected, ordered))
    }

    @Synchronized
    fun moveSession(key: String, delta: Int) {
        val expected = tabs.filterIsInstance<SessionTab>().map { it.key }
        val from = expected.indexOf(key)
        if (from < 0) return
        val ordered = expected.toMutableList().apply { add((from + delta).coerceIn(indices), removeAt(from)) }
        write(_state.value.reorderSessions(expected, ordered))
    }

    private fun write(state: BrowserTabs) {
        _state.value = state
        preferences.edit().putString("workspace_tabs_v2", json.encodeToString(state)).apply()
    }

    fun snapshot(key: String): ChatViewSnapshot? = try {
        preferences.getString("view:$key", null)?.let { json.decodeFromString<ChatViewSnapshot>(it) }
    } catch (e: Exception) {
        Log.e("SessionWorkspace", "Failed to restore session view", e)
        null
    }

    fun saveSnapshot(key: String, snapshot: ChatViewSnapshot) {
        preferences.edit().putString("view:$key", json.encodeToString(snapshot)).apply()
    }
}
