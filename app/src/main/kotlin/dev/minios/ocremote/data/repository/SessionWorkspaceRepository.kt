package dev.minios.ocremote.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.minios.ocremote.logging.AppLogger as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SessionTab(
    val serverId: String,
    val sessionId: String,
    val title: String = "",
    val lastActive: Long = 0,
    val hasDraft: Boolean = false,
) {
    val key: String get() = "$serverId:$sessionId"
}

@Serializable
data class ChatViewSnapshot(
    val itemKey: String? = null,
    val offset: Int = 0,
    val following: Boolean = true,
    val terminal: Boolean = false,
)

internal fun openedSessionTabs(tabs: List<SessionTab>, tab: SessionTab): List<SessionTab> =
    if (tabs.any { it.key == tab.key }) tabs.map { if (it.key == tab.key) tab else it } else tabs + tab

/** Device-local metadata only: never stores credentials, messages, or terminal buffers. */
@Singleton
class SessionWorkspaceRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {
    private val preferences = context.getSharedPreferences("session_workspace", Context.MODE_PRIVATE)
    private val _tabs = MutableStateFlow(readTabs())
    val tabs = _tabs.asStateFlow()

    private fun readTabs(): List<SessionTab> = try {
        preferences.getString("tabs", null)?.let { json.decodeFromString<List<SessionTab>>(it) }
            .orEmpty().distinctBy { it.key }
    } catch (e: Exception) {
        Log.e("SessionWorkspace", "Failed to restore open sessions", e)
        emptyList()
    }

    @Synchronized
    fun open(serverId: String, sessionId: String, title: String = "") {
        val previous = _tabs.value.firstOrNull { it.serverId == serverId && it.sessionId == sessionId }
        write(openedSessionTabs(_tabs.value, SessionTab(serverId, sessionId,
            title.ifBlank { previous?.title.orEmpty() }, System.currentTimeMillis(), previous?.hasDraft ?: false)))
    }

    @Synchronized
    fun updateDraft(key: String, hasDraft: Boolean) {
        if (_tabs.value.any { it.key == key && it.hasDraft != hasDraft }) {
            write(_tabs.value.map { if (it.key == key) it.copy(hasDraft = hasDraft) else it })
        }
    }

    @Synchronized
    fun markViewed(key: String) {
        write(_tabs.value.map { if (it.key == key) it.copy(lastActive = System.currentTimeMillis()) else it })
    }

    @Synchronized
    fun close(key: String) { write(_tabs.value.filterNot { it.key == key }) }

    @Synchronized
    fun restore(tab: SessionTab) { write(openedSessionTabs(_tabs.value, tab)) }

    @Synchronized
    fun move(key: String, delta: Int) {
        val list = _tabs.value.toMutableList()
        val from = list.indexOfFirst { it.key == key }
        if (from < 0) return
        val to = (from + delta).coerceIn(list.indices)
        list.add(to, list.removeAt(from))
        write(list)
    }

    @Synchronized
    fun reorder(expected: List<String>, ordered: List<String>) {
        val current = _tabs.value
        if (current.map { it.key } != expected || ordered.size != expected.size || ordered.toSet() != expected.toSet()) return
        val byKey = current.associateBy { it.key }
        write(ordered.map { byKey.getValue(it) })
    }

    private fun write(tabs: List<SessionTab>) {
        _tabs.value = tabs
        preferences.edit().putString("tabs", json.encodeToString(tabs)).apply()
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
