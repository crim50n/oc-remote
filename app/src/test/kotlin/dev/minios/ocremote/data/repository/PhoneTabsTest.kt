package dev.minios.ocremote.data.repository

import org.junit.Assert.*
import org.junit.Test

class PhoneTabsTest {
    private val first = SessionTab("s", "first")
    private val second = SessionTab("s", "second")
    private val settings = PageTab(WorkspacePage.SETTINGS, "Settings")

    @Test fun phoneBulkCloseKeepsDesktopPagesAndSelectsAnotherSession() {
        val state = BrowserTabs(listOf(first, settings, second))
        assertEquals(setOf(second.key), state.closeKeys(first.key, TabCloseKind.OTHERS, sessionsOnly = true))
        val closed = state.close(setOf(first.key), first.key, sessionsOnly = true)
        assertEquals(second.key, closed.activeKey)
        assertTrue(settings in closed.state.tabs)
        assertEquals(state, state.close(setOf(settings.key), first.key, sessionsOnly = true).state)
        assertNull(BrowserTabs(listOf(first, settings)).close(setOf(first.key), first.key, sessionsOnly = true).activeKey)
    }

    @Test fun phoneReorderPreservesHiddenPageSlotsAndRejectsStaleSnapshots() {
        val state = BrowserTabs(listOf(first, settings, second))
        val reordered = state.reorderSessions(listOf(first.key, second.key), listOf(second.key, first.key))
        assertEquals(listOf(second, settings, first), reordered.tabs)
        assertEquals(state, state.reorderSessions(listOf(first.key), listOf(first.key)))
    }
}
