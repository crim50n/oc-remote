package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPhoneShortcutsTest {
    @Test fun volumeDownProvidesCtrlLettersAndNumericAliases() {
        ('a'..'z').forEachIndexed { index, letter ->
            assertEquals((index + 1).toChar(), ctrlTransform(letter))
            assertEquals((index + 1).toChar(), ctrlTransform(letter.uppercaseChar()))
        }
        listOf(" 2" to 0, "[3" to 27, "\\4" to 28, "]5" to 29,
            "^6" to 30, "_7/" to 31, "8" to 127).forEach { (keys, code) ->
            keys.forEach { assertEquals(code.toChar(), ctrlTransform(it)) }
        }
        assertEquals('9', ctrlTransform('9'))
    }

    @Test fun volumeUpProvidesTermuxTerminalSequences() {
        val bindings = mapOf(
            'w' to "\u001B[A", 'a' to "\u001B[D", 's' to "\u001B[B", 'd' to "\u001B[C",
            'p' to "\u001B[5~", 'n' to "\u001B[6~", 't' to "\t", 'i' to "\u001B[2~",
            'h' to "~", 'u' to "_", 'l' to "|", 'e' to "\u001B", '.' to "\u001C",
            'b' to "\u001Bb", 'f' to "\u001Bf", 'x' to "\u001Bx",
            '1' to "\u001BOP", '2' to "\u001BOQ", '3' to "\u001BOR", '4' to "\u001BOS",
            '5' to "\u001B[15~", '6' to "\u001B[17~", '7' to "\u001B[18~", '8' to "\u001B[19~",
            '9' to "\u001B[20~", '0' to "\u001B[21~",
        )
        bindings.forEach { (key, sequence) ->
            assertEquals(sequence, applyTermuxFnBindings(key.toString(), false).output)
            assertEquals(sequence, applyTermuxFnBindings(key.uppercase(), false).output)
        }
        assertEquals("\u001BOA\u001BOD\u001BOB\u001BOC", applyTermuxFnBindings("wasd", true).output)
    }

    @Test fun localActionsDoNotSendTextToTheShell() {
        for (key in listOf("q", "k", "Q", "K")) {
            val result = applyTermuxFnBindings(key, false)
            assertTrue(result.toggleToolbar)
            assertEquals("", result.output)
        }
        assertTrue(applyTermuxFnBindings("v", false).showVolumeUi)
        assertEquals("", applyTermuxFnBindings("v", false).output)
        assertEquals("", applyTermuxFnBindings("z", false).output)
    }
}
