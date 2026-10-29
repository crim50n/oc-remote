package dev.minios.ocremote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.ui.screens.chat.SessionTerminalInline
import dev.minios.ocremote.ui.screens.chat.TerminalEmulator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class TerminalDesktopInputTest {
    @get:Rule val compose = createComposeRule()

    @Test fun mouseFocusRoutesControlAndCursorKeysToTheTerminal() {
        val output = mutableListOf<String>()
        val emulator = TerminalEmulator().apply { process("\u001b[?1hHello") }
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 240.dp)) {
                    SessionTerminalInline(emulator, emulator.version, true, remember { FocusRequester() },
                        onSendInput = { output += it }, onPaste = {}, onResize = { _, _ -> },
                        fontSizeSp = 13f, onFontSizeChange = {}, desktopInput = true)
                }
            }
        }
        compose.onNodeWithTag("terminalViewport").performMouseInput { click(center) }
        compose.onRoot().performKeyInput {
            keyDown(Key.CtrlLeft); pressKey(Key.C); keyUp(Key.CtrlLeft)
            pressKey(Key.DirectionUp)
        }
        compose.runOnIdle { assertEquals(listOf("\u0003", "\u001bOA"), output) }
    }

    @Test fun applicationMouseModeReceivesClickAndRelease() {
        val output = mutableListOf<String>()
        val emulator = TerminalEmulator().apply { process("\u001b[?1000h\u001b[?1006h") }
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(320.dp, 240.dp)) {
                    SessionTerminalInline(emulator, emulator.version, true, remember { FocusRequester() },
                        onSendInput = { output += it }, onPaste = {}, onResize = { _, _ -> },
                        fontSizeSp = 13f, onFontSizeChange = {}, desktopInput = true)
                }
            }
        }
        compose.onNodeWithTag("terminalViewport").performMouseInput { click(center) }
        compose.runOnIdle {
            assertEquals(2, output.size)
            assertTrue(output[0].startsWith("\u001b[<0;") && output[0].endsWith("M"))
            assertTrue(output[1].startsWith("\u001b[<0;") && output[1].endsWith("m"))
        }
    }
}
