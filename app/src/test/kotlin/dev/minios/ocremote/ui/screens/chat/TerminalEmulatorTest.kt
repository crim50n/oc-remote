package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {
    @Test
    fun `DEC private modes control cursor visibility and blinking`() {
        val emulator = TerminalEmulator()

        emulator.process("\u001B[?25l\u001B[?12l")
        assertFalse(emulator.cursorVisible)
        assertFalse(emulator.cursorBlinkEnabled)

        emulator.process("\u001B[?25h\u001B[?12h")
        assertTrue(emulator.cursorVisible)
        assertTrue(emulator.cursorBlinkEnabled)
    }

    @Test
    fun `DECSCUSR controls cursor shape and blink mode`() {
        val emulator = TerminalEmulator()

        emulator.process("\u001B[4 q")
        assertEquals(TerminalCursorStyle.UNDERLINE, emulator.cursorStyle)
        assertFalse(emulator.cursorBlinkEnabled)

        emulator.process("\u001B[5 q")
        assertEquals(TerminalCursorStyle.BAR, emulator.cursorStyle)
        assertTrue(emulator.cursorBlinkEnabled)

        emulator.process("\u001B[2 q")
        assertEquals(TerminalCursorStyle.BLOCK, emulator.cursorStyle)
        assertFalse(emulator.cursorBlinkEnabled)
    }

    @Test
    fun `reset restores default cursor state`() {
        val emulator = TerminalEmulator()
        emulator.process("\u001B[?25l\u001B[4 q")

        emulator.reset()

        assertTrue(emulator.cursorVisible)
        assertTrue(emulator.cursorBlinkEnabled)
        assertEquals(TerminalCursorStyle.BLOCK, emulator.cursorStyle)
    }
}
