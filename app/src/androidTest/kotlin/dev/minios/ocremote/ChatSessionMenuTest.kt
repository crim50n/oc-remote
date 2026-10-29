package dev.minios.ocremote

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.minios.ocremote.ui.screens.chat.ChatSessionMenu
import dev.minios.ocremote.ui.screens.chat.SessionMenuAction
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatSessionMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test fun phoneMenuContainsSessionActionsWithoutAppSettings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme {
                Box {
                    ChatSessionMenu(true, {}, {}, sessionActions = true, connected = true,
                        supportsSharing = true, shared = false, compressImages = false,
                        desktop = false, fullWidth = false)
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.menu_export_session)).assertExists()
        compose.onNodeWithText(context.getString(R.string.settings_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.chat_use_window_width)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.menu_servers_sessions)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.about_title)).assertDoesNotExist()
    }

    @Test fun desktopMenuOffersSettingsAndWidthBeforeSessionLoads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val expanded = mutableStateOf(true)
        var selected: SessionMenuAction? = null
        compose.setContent {
            MaterialTheme {
                Box {
                    ChatSessionMenu(expanded.value, { expanded.value = false }, { selected = it },
                        sessionActions = false, connected = false, supportsSharing = false, shared = false,
                        compressImages = false, desktop = true, fullWidth = false)
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.menu_rename_session)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.chat_use_window_width)).assertExists()
        compose.onNodeWithText(context.getString(R.string.about_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.settings_title)).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(SessionMenuAction.SETTINGS, selected) }
    }
}
