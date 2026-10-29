package dev.minios.ocremote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.minios.ocremote.data.repository.SessionTab
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.ui.navigation.SessionTabsSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SessionTabsSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test fun closingTabDoesNotActivateIt() {
        var selected = 0
        var closed = 0
        var dismissed = 0
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme {
                SessionTabsSheet(
                    tabs = listOf(SessionTab("server", "session", "Workspace chat")),
                    servers = listOf(ServerConfig(id = "server", url = "http://localhost", name = "Laptop")),
                    sessions = emptyList(), statuses = emptyMap(), attentionSessionIds = emptySet(),
                    connectedServerIds = emptySet(), activeKey = null, canUndo = false,
                    onSelect = { selected++ }, onClose = { closed++ }, onUndo = {},
                    onMove = { _, _ -> }, onAdd = {}, onDismiss = { dismissed++ },
                )
            }
        }
        compose.onNodeWithText("Workspace chat").assertIsDisplayed()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText(context.getString(R.string.session_tabs_count, 1)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.session_tabs_search)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.session_tabs_close_hint)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.session_tabs_earlier)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.session_tabs_later)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.session_tabs_close)).performClick()
        compose.runOnIdle {
            assertEquals(0, selected)
            assertEquals(1, closed)
        }
        compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun plusOpensExistingSessionsWithoutCreationOption() {
        var selected: dev.minios.ocremote.data.repository.WorkspaceTab? = null
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme {
                SessionTabsSheet(
                    tabs = emptyList(),
                    servers = listOf(ServerConfig(id = "server", url = "http://localhost", name = "Laptop")),
                    sessions = listOf(Session(id = "existing", title = "Existing session", time = Session.Time(1, 1))),
                    statuses = emptyMap(), attentionSessionIds = emptySet(), connectedServerIds = setOf("server"),
                    activeKey = null, canUndo = false, onSelect = { selected = it }, onClose = {}, onUndo = {},
                    onMove = { _, _ -> }, onAdd = {}, onDismiss = {},
                    serverSessions = mapOf("server" to setOf("existing")),
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.session_tabs_add)).performClick()
        compose.onNodeWithText(context.getString(R.string.menu_new_session)).assertDoesNotExist()
        compose.onNodeWithText("Existing session").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("server:existing", selected?.key) }
    }
}
