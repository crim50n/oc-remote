package dev.minios.ocremote.ui.navigation

import dev.minios.ocremote.data.repository.PageTab
import dev.minios.ocremote.data.repository.SessionTab
import dev.minios.ocremote.data.repository.WorkspacePage
import dev.minios.ocremote.domain.model.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopTabSearchTest {
    @Test fun searchIncludesPagesServerNamesAndProjectDirectories() {
        val settings = PageTab(WorkspacePage.SETTINGS, "Настройки")
        val files = PageTab(WorkspacePage.FILES, "Files", serverId = "server", directory = "/project/mobile")
        val session = SessionTab("server", "chat", "Build")
        val tabs = listOf(settings, files, session)
        val servers = listOf(ServerConfig(id = "server", url = "https://example.test", name = "Laptop"))
        assertEquals(listOf(settings), filterWorkspaceTabs(tabs, servers, emptyList(), "настр"))
        assertEquals(listOf(files), filterWorkspaceTabs(tabs, servers, emptyList(), "mobile"))
        assertEquals(listOf(files, session), filterWorkspaceTabs(tabs, servers, emptyList(), "LAPTOP"))
        assertEquals(tabs, filterWorkspaceTabs(tabs, servers, emptyList(), "  "))
    }
}
