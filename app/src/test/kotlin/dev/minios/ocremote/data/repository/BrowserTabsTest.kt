package dev.minios.ocremote.data.repository

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BrowserTabsTest {
    private val a = SessionTab("one", "a", "A")
    private val b = SessionTab("two", "b", "B")
    private val c = SessionTab("one", "c", "C")

    @Test fun closeActiveChoosesRightThenLeftAndKeepsInactiveSelection() {
        val state = BrowserTabs(listOf(a, b, c))
        assertEquals(c.key, state.close(setOf(b.key), b.key).activeKey)
        assertEquals(b.key, state.close(setOf(c.key), c.key).activeKey)
        assertEquals(a.key, state.close(setOf(b.key), a.key).activeKey)
    }

    @Test fun openToRightInsertsOnceWithoutDuplicatingExistingSession() {
        val state = BrowserTabs(listOf(a, c))
        assertEquals(listOf(a, b, c), state.openAfter(b, a.key).tabs)
        assertEquals(state.tabs, state.openAfter(c, a.key).tabs)
    }

    @Test fun closedHistorySurvivesRestartAndRestoresOriginalPosition() {
        val closed = BrowserTabs(listOf(a, b, c)).close(setOf(b.key), b.key).state
        val restarted = Json.decodeFromString<BrowserTabs>(Json.encodeToString(closed))
        val restored = restarted.restore()
        assertEquals(listOf(a, b, c), restored.state.tabs)
        assertEquals(b.key, restored.activeKey)
        assertTrue(restored.state.closed.isEmpty())
        assertEquals(restored.state, restored.state.restore().state)
    }

    @Test fun pinningAndBulkCloseProtectPinnedTabs() {
        val pinned = BrowserTabs(listOf(a, b, c)).pin(b.key, true)
        assertEquals(listOf(b.key, a.key, c.key), pinned.tabs.map { it.key })
        val others = pinned.closeOthers(a.key, a.key)
        assertEquals(listOf(b.key, a.key), others.state.tabs.map { it.key })
        assertEquals(listOf(b.key, a.key), pinned.closeRight(a.key, c.key).state.tabs.map { it.key })
        assertEquals(a.key, pinned.closeRight(a.key, c.key).activeKey)
    }

    @Test fun reopenedTabsAreNotDuplicatedAndHistoryIsBounded() {
        var state = BrowserTabs()
        repeat(110) { index ->
            val tab = SessionTab("s", "$index")
            state = state.open(tab).close(setOf(tab.key), tab.key).state
        }
        assertEquals(100, state.closed.size)
        val tab = state.closed.last().tab
        val reopened = state.open(tab)
        assertFalse(reopened.closed.any { it.tab.key == tab.key })
        assertEquals(1, reopened.open(tab).tabs.size)
    }

    @Test fun staleReorderCannotDropTabsAndPinnedBoundaryIsPreserved() {
        val state = BrowserTabs(listOf(a, b, c)).pin(b.key, true)
        assertEquals(state, state.reorder(listOf(a.key), listOf(a.key)))
        val moved = state.reorder(state.tabs.map { it.key }, listOf(c.key, a.key, b.key))
        assertEquals(listOf(b.key, c.key, a.key), moved.tabs.map { it.key })
    }
}
