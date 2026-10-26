package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingCollapseTest {
    @Test fun panelsFollowEachDragDeltaContinuously() {
        assertEquals(0.25f, readingCollapseFraction(0f, -24f, 96f), 0.0001f)
        assertEquals(0.5f, readingCollapseFraction(0.25f, -24f, 96f), 0.0001f)
    }
    @Test fun reverseDragRevealsImmediately() {
        assertEquals(0.75f, readingCollapseFraction(1f, 24f, 96f), 0.0001f)
        assertEquals(0.25f, readingCollapseFraction(0.5f, 24f, 96f), 0.0001f)
    }
    @Test fun progressClampsAtBothEnds() {
        assertEquals(1f, readingCollapseFraction(0.5f, -500f, 96f), 0f)
        assertEquals(0f, readingCollapseFraction(0.5f, 500f, 96f), 0f)
    }
    @Test fun noScrollLeavesPanelsUnchanged() {
        assertEquals(0.4f, readingCollapseFraction(0.4f, 0f, 96f), 0f)
    }
}
