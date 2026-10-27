package dev.minios.ocremote

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.minios.ocremote.ui.components.AppDropdownMenu
import dev.minios.ocremote.ui.components.AppDropdownMenuItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DropdownMenuAnimationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun dismissalDuringEntranceStillAnimatesBeforeRemovingPopup() {
        val expanded = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Box {
                    AppDropdownMenu(expanded.value, { expanded.value = false }) {
                        AppDropdownMenuItem(text = { Text("Item") }, onClick = {})
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { expanded.value = false }
        compose.mainClock.advanceTimeBy(80)
        compose.onNodeWithText("Item").assertExists()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("Item").assertDoesNotExist()
    }

    @Test fun menuRemainsDuringExitAndActionRunsAfterwards() {
        val expanded = mutableStateOf(false)
        var actions = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Box {
                    TextButton(onClick = { expanded.value = true }) { Text("Open") }
                    AppDropdownMenu(expanded.value, { expanded.value = false }) {
                        AppDropdownMenuItem(text = { Text("Action") }, onClick = { actions++ })
                    }
                }
            }
        }
        compose.onNodeWithText("Open").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("Action").performClick()
        compose.runOnIdle { assertEquals(0, actions) }
        compose.onNodeWithText("Action").assertExists()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("Action").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, actions) }
    }
}
