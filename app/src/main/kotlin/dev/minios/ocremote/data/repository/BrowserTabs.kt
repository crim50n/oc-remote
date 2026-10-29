package dev.minios.ocremote.data.repository

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class ClosedWorkspaceTab(val tab: WorkspaceTab, val index: Int)

data class TabTransition(val state: BrowserTabs, val activeKey: String?)
enum class TabCloseKind { SINGLE, OTHERS, RIGHT }

/** Local tabs are views of sessions. Closing a tab never changes the server session. */
@Serializable
data class BrowserTabs(
    val tabs: List<WorkspaceTab> = emptyList(),
    val closed: List<ClosedWorkspaceTab> = emptyList(),
) {
    fun normalized(): BrowserTabs {
        val open = tabs.distinctBy { it.key }.sortedByDescending { it.pinned }
        val keys = open.mapTo(mutableSetOf()) { it.key }
        return copy(tabs = open, closed = closed.filterNot { it.tab.key in keys }
            .asReversed().distinctBy { it.tab.key }.take(100).asReversed())
    }

    fun open(tab: WorkspaceTab): BrowserTabs {
        val previous = tabs.firstOrNull { it.key == tab.key }
        val value = tab.copyMetadata(pinned = previous?.pinned ?: tab.pinned)
        return copy(tabs = openedSessionTabs(tabs, value)).normalized()
    }

    fun openAfter(tab: WorkspaceTab, anchor: String): BrowserTabs {
        if (tabs.any { it.key == tab.key }) return this
        val index = tabs.indexOfFirst { it.key == anchor }
        if (index < 0) return open(tab)
        return copy(tabs = tabs.toMutableList().apply { add(index + 1, tab) }).normalized()
    }

    fun close(keys: Set<String>, activeKey: String?, sessionsOnly: Boolean = false): TabTransition {
        val selectedKeys = if (sessionsOnly) keys.intersect(tabs.filterIsInstance<SessionTab>().map { it.key }.toSet()) else keys
        val removed = tabs.withIndex().filter { it.value.key in selectedKeys }
        if (removed.isEmpty()) return TabTransition(this, activeKey)
        val remaining = tabs.filterNot { it.key in selectedKeys }
        val activeIndex = tabs.indexOfFirst { it.key == activeKey }
        val next = if (activeKey !in selectedKeys) activeKey else {
            fun selectable(tab: WorkspaceTab) = tab.key !in selectedKeys && (!sessionsOnly || tab is SessionTab)
            tabs.drop(activeIndex + 1).firstOrNull(::selectable)?.key
                ?: tabs.take(activeIndex.coerceAtLeast(0)).lastOrNull(::selectable)?.key
        }
        val history = closed.filterNot { it.tab.key in selectedKeys } + removed.map { ClosedWorkspaceTab(it.value, it.index) }
        return TabTransition(copy(tabs = remaining, closed = history).normalized(), next)
    }

    fun closeKeys(key: String, kind: TabCloseKind, sessionsOnly: Boolean = false): Set<String> {
        val visible = if (sessionsOnly) tabs.filterIsInstance<SessionTab>() else tabs
        val index = visible.indexOfFirst { it.key == key }
        if (index < 0) return emptySet()
        return when (kind) {
            TabCloseKind.SINGLE -> setOf(key)
            TabCloseKind.OTHERS -> visible.filter { it.key != key && !it.pinned }.mapTo(mutableSetOf()) { it.key }
            TabCloseKind.RIGHT -> visible.drop(index + 1).filterNot { it.pinned }.mapTo(mutableSetOf()) { it.key }
        }
    }

    fun closeOthers(key: String, activeKey: String?): TabTransition = close(closeKeys(key, TabCloseKind.OTHERS), activeKey)

    fun closeRight(key: String, activeKey: String?): TabTransition {
        return close(closeKeys(key, TabCloseKind.RIGHT), activeKey)
    }

    fun restore(key: String? = null): TabTransition {
        val valid = normalized()
        val entry = valid.closed.lastOrNull { key == null || it.tab.key == key } ?: return TabTransition(valid, null)
        val restored = valid.tabs.toMutableList().apply { add(entry.index.coerceIn(0, size), entry.tab) }
        return TabTransition(valid.copy(tabs = restored).normalized(), entry.tab.key)
    }

    fun pin(key: String, pinned: Boolean): BrowserTabs =
        copy(tabs = tabs.map { if (it.key == key) it.copyMetadata(pinned = pinned) else it }).normalized()

    fun reorder(expected: List<String>, ordered: List<String>): BrowserTabs {
        if (tabs.map { it.key } != expected || ordered.size != expected.size || ordered.toSet() != expected.toSet()) return this
        val byKey = tabs.associateBy { it.key }
        return copy(tabs = ordered.map(byKey::getValue)).normalized()
    }

    fun reorderSessions(expected: List<String>, ordered: List<String>): BrowserTabs {
        val sessions = tabs.filterIsInstance<SessionTab>()
        if (sessions.map { it.key } != expected || expected.size != ordered.size || expected.toSet() != ordered.toSet()) return this
        val byKey = sessions.associateBy { it.key }
        val replacement = ordered.map(byKey::getValue).iterator()
        return copy(tabs = tabs.map { if (it is SessionTab) replacement.next() else it }).normalized()
    }
}

/** Older saved state contains concrete SessionTab objects without a polymorphic discriminator. */
internal fun decodeBrowserTabs(json: Json, value: String): BrowserTabs {
    val root = json.parseToJsonElement(value).jsonObject
    fun tab(element: JsonElement): WorkspaceTab = if ("type" in element.jsonObject) {
        json.decodeFromJsonElement<WorkspaceTab>(element)
    } else json.decodeFromJsonElement<SessionTab>(element)
    return BrowserTabs(
        tabs = root["tabs"]?.jsonArray.orEmpty().map(::tab),
        closed = root["closed"]?.jsonArray.orEmpty().map { entry ->
            ClosedWorkspaceTab(tab(entry.jsonObject.getValue("tab")), entry.jsonObject.getValue("index").jsonPrimitive.int)
        },
    ).normalized()
}
