package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalZoomTest {
    @Test
    fun `zoom uses continuous scale and clamps supported range`() {
        assertEquals(12f, terminalZoomFontSize(8f, 1.5f))
        assertEquals(6f, terminalZoomFontSize(8f, 0.1f))
        assertEquals(20f, terminalZoomFontSize(16f, 2f))
    }

    @Test
    fun `pinch starts as soon as two pointers are pressed`() {
        assertEquals(false, terminalGestureIsPinch(1))
        assertEquals(true, terminalGestureIsPinch(2))
        assertEquals(true, terminalGestureIsPinch(3))
    }

    @Test
    fun `fling converts pointer velocity to scroll direction and applies threshold`() {
        assertEquals(1_000f, terminalFlingScrollVelocity(-1_000f, 100f))
        assertEquals(-1_000f, terminalFlingScrollVelocity(1_000f, 100f))
        assertEquals(0f, terminalFlingScrollVelocity(99f, 100f))
    }

    @Test
    fun `terminal input delta preserves repeated input and handles edits`() {
        assertEquals("a", terminalInputDelta("a", "aa"))
        assertEquals("\u007F", terminalInputDelta("aa", "a"))
        assertEquals("\u007F\u007Fxy", terminalInputDelta("abc", "axy"))
        assertEquals("", terminalInputDelta("same", "same"))
    }
}
