package dev.minios.ocremote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.minios.ocremote.data.repository.SessionTab
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.ui.navigation.DesktopSessionTabs
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DesktopSessionTabsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun searchOpensAnAnchoredPopupAndFiltersPageTabs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var selected: dev.minios.ocremote.data.repository.WorkspaceTab? = null
        val settings = dev.minios.ocremote.data.repository.PageTab(
            dev.minios.ocremote.data.repository.WorkspacePage.SETTINGS, "Settings page")
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(600.dp)) {
                    DesktopSessionTabs(
                        tabs = listOf(SessionTab("server", "one", "First"), settings),
                        servers = listOf(ServerConfig(id = "server", url = "http://localhost", name = "Server")),
                        statuses = emptyMap(), attention = emptySet(), connected = setOf("server"), activeKey = "server:one",
                        onSelect = { selected = it }, onClose = {}, onOrder = { _, _ -> }, onAdd = {},
                    )
                }
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.session_tabs_count, 2)).performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithContentDescription(context.getString(R.string.tabs_search_hint)).performTextInput("Settings")
        compose.onNodeWithTag("desktopTabSearchResult:${settings.key}").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("Settings page", selected?.title) }
    }

    @Test fun closeDoesNotSelectAndPlusOpensPicker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var selected = 0
        var closed = 0
        var added = 0
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(600.dp)) {
                    DesktopSessionTabs(
                        tabs = listOf(SessionTab("server", "one", "First"), SessionTab("server", "two", "Second")),
                        servers = listOf(ServerConfig(id = "server", url = "http://localhost", name = "Server")),
                        statuses = emptyMap(), attention = emptySet(), connected = setOf("server"), activeKey = "server:one",
                        onSelect = { selected++ }, onClose = { closed++ }, onOrder = { _, _ -> },
                        onAdd = { added++ },
                    )
                }
            }
        }
        compose.onAllNodesWithContentDescription(context.getString(R.string.session_tabs_close))[0].performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.session_tabs_add)).performClick()
        compose.runOnIdle {
            assertEquals(0, selected)
            assertEquals(1, closed)
            assertEquals(1, added)
        }
    }
}
