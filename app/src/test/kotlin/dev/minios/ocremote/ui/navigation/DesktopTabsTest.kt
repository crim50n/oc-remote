package dev.minios.ocremote.ui.navigation

import dev.minios.ocremote.data.repository.SessionTab
import org.junit.Assert.*
import org.junit.Test

class DesktopTabsTest {
    @Test fun tabsShrinkBeforeScrollingAndReserveRoomForTheAddButton() {
        assertEquals(220f, desktopTabWidths(1000f, 0, 2).normal, 0.01f)
        val crowded = desktopTabWidths(600f, 2, 2)
        assertTrue(crowded.normal < 220f)
        assertTrue(crowded.pinned < 160f)
        assertEquals(600f, crowded.normal * 2 + crowded.pinned * 2 + 48f + 16f + 12f, 0.1f)
        assertEquals(96f, desktopTabWidths(300f, 0, 10).normal, 0.01f)
    }
    @Test fun keyboardSwitchingWrapsAndUsesCompositeServerKeys() {
        val tabs = listOf(SessionTab("a", "same"), SessionTab("b", "same"), SessionTab("a", "third"))
        assertEquals(tabs[1], adjacentSessionTab(tabs, tabs[0].key, false))
        assertEquals(tabs[2], adjacentSessionTab(tabs, tabs[0].key, true))
        assertEquals(tabs[0], adjacentSessionTab(tabs, tabs[2].key, false))
        assertEquals(tabs[0], adjacentSessionTab(tabs, null, false))
        assertEquals(tabs[2], adjacentSessionTab(tabs, null, true))
        assertNull(adjacentSessionTab(emptyList(), null, false))
    }
}
