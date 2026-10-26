package dev.minios.ocremote.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionWorkspaceTest {
    @Test fun sameSessionOnDifferentServersHasSeparateTabs() {
        val tabs = openedSessionTabs(listOf(SessionTab("a", "session")), SessionTab("b", "session"))
        assertEquals(2, tabs.size)
        assertEquals(listOf("a:session", "b:session"), tabs.map { it.key })
    }
    @Test fun reopeningUpdatesTitleAndRecencyWithoutChangingOrder() {
        val tabs = listOf(SessionTab("a", "one", "Old"), SessionTab("b", "two"))
        val updated = openedSessionTabs(tabs, SessionTab("a", "one", "New", 123))
        assertEquals(listOf("one", "two"), updated.map { it.sessionId })
        assertEquals("New", updated.first().title)
        assertEquals(123L, updated.first().lastActive)
    }
}
