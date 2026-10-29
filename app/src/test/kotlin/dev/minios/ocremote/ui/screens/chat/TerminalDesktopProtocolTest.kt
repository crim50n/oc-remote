package dev.minios.ocremote.ui.screens.chat

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class TerminalDesktopProtocolTest {
    @Test fun hardwareKeysHonorApplicationModeAndModifiers() {
        assertEquals("\u001bOA", terminalHardwareSequence(KeyEvent.KEYCODE_DPAD_UP, applicationCursor = true))
        assertEquals("\u001b[1;5D", terminalHardwareSequence(KeyEvent.KEYCODE_DPAD_LEFT, ctrl = true))
        assertEquals("\u001b[3~", terminalHardwareSequence(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals("\u001b[Z", terminalHardwareSequence(KeyEvent.KEYCODE_TAB, shift = true))
        assertEquals("\u001b[24~", terminalHardwareSequence(KeyEvent.KEYCODE_F12))
        assertEquals("\u0003", terminalHardwareSequence(KeyEvent.KEYCODE_C, ctrl = true))
        assertEquals("\u001bx", terminalHardwareSequence(KeyEvent.KEYCODE_X, unicode = 'x'.code, alt = true))
        assertEquals("я", terminalHardwareSequence(KeyEvent.KEYCODE_Z, unicode = 'я'.code))
        assertEquals("\u001b[1;8Q", terminalHardwareSequence(KeyEvent.KEYCODE_F2, ctrl = true, alt = true, shift = true))
        assertEquals("\u0000", terminalHardwareSequence(KeyEvent.KEYCODE_SPACE, unicode = ' '.code, ctrl = true))
    }

    @Test fun pasteUsesTheModeRequestedByTheTerminalProgram() {
        val emulator = TerminalEmulator()
        assertEquals("one\rtwo", terminalPaste("one\ntwo", emulator.bracketedPasteEnabled))
        emulator.process("\u001b[?2004h")
        assertEquals("\u001b[200~one\ntwo\u001b[201~", terminalPaste("one\r\ntwo", emulator.bracketedPasteEnabled))
        emulator.reset()
        assertFalse(emulator.bracketedPasteEnabled)
        assertEquals("\u001b[200~[201~\u001b[201~", terminalPaste("\u001b[201~", true))
    }

    @Test fun sgrMouseReportsPressReleaseAndWheelAndResets() {
        val emulator = TerminalEmulator()
        emulator.process("\u001b[?1002h\u001b[?1006h")
        assertEquals(TerminalMouseTracking.DRAG, emulator.mouseTracking)
        assertEquals("\u001b[<0;120;3M", terminalMouseSequence(emulator.mouseEncoding, 0, 120, 3))
        assertEquals("\u001b[<0;120;3m", terminalMouseSequence(emulator.mouseEncoding, 0, 120, 3, release = true))
        assertEquals("\u001b[<64;1;1M", terminalMouseSequence(emulator.mouseEncoding, 64, 1, 1))
        emulator.process("\u001b[?1002l")
        assertEquals(TerminalMouseTracking.NONE, emulator.mouseTracking)
        emulator.reset()
        assertEquals(TerminalMouseEncoding.LEGACY, emulator.mouseEncoding)
        assertNull(terminalMouseSequence(TerminalMouseEncoding.LEGACY, 0, 120, 1))
        assertEquals("\u001b[96;120;1M", terminalMouseSequence(TerminalMouseEncoding.URXVT, 64, 120, 1))
    }

    @Test fun selectionCopiesOnlyTheDraggedRangeInEitherDirection() {
        val lines = listOf("first line", "second line")
        val from = TerminalCellPoint(0, 6)
        val to = TerminalCellPoint(1, 6)
        assertEquals("line\nsecond", terminalSelectedText(lines, from, to))
        assertEquals("line\nsecond", terminalSelectedText(lines, to, from))
        assertEquals("", terminalSelectedText(lines, from, from))
    }
}
