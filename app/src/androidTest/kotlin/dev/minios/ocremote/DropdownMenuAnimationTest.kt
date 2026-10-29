package dev.minios.ocremote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.Assert.assertTrue
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

    @Test fun menuUsesActualWindowWhenConfigurationHasStalePortraitHeight() {
        var anchor: android.view.View? = null
        compose.setContent {
            anchor = LocalView.current
            val stale = android.content.res.Configuration(LocalConfiguration.current).apply { screenHeightDp = 2400 }
            CompositionLocalProvider(LocalConfiguration provides stale) {
                MaterialTheme {
                    Box {
                        AppDropdownMenu(true, {}, modifier = Modifier.testTag("boundedMenu")) {
                            repeat(40) { index ->
                                AppDropdownMenuItem(text = { Text("Long item $index") }, onClick = {})
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val bounds = compose.onNodeWithTag("boundedMenu").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.height <= requireNotNull(anchor).rootView.height)
        compose.onNodeWithText("Long item 39").performScrollTo().assertIsDisplayed()
    }

    @Test fun shortMenuViewportCanScrollToLastAction() {
        val expanded = mutableStateOf(true)
        var selected = -1
        compose.setContent {
            MaterialTheme {
                Box {
                    AppDropdownMenu(expanded.value, { expanded.value = false }, modifier = Modifier.heightIn(max = 180.dp)) {
                        repeat(20) { index ->
                            AppDropdownMenuItem(text = { Text("Item $index") }, onClick = { selected = index })
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Item 19").performScrollTo().assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(19, selected) }
    }

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
