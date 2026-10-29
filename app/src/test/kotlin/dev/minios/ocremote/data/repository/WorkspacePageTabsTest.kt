package dev.minios.ocremote.data.repository

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WorkspacePageTabsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun settingsIsOneTabAndCanBeClosedAndRestoredBesideASession() {
        val session = SessionTab("server", "chat", "Chat")
        val settings = PageTab(WorkspacePage.SETTINGS, "Settings")
        val open = BrowserTabs().open(session).open(settings).open(settings)
        assertEquals(2, open.tabs.size)
        val closed = open.close(setOf(settings.key), settings.key)
        assertEquals(session.key, closed.activeKey)
        val restored = json.decodeFromString<BrowserTabs>(json.encodeToString(closed.state)).restore()
        assertEquals(listOf(session, settings), restored.state.tabs)
        assertEquals(settings.key, restored.activeKey)
    }

    @Test fun oldSessionOnlyStateMigratesWithoutLosingClosedHistory() {
        val state = decodeBrowserTabs(json, """{"tabs":[{"serverId":"s","sessionId":"one"}],"closed":[{"tab":{"serverId":"s","sessionId":"two"},"index":1}]}""")
        assertTrue(state.tabs.single() is SessionTab)
        assertEquals("s:two", state.restore().activeKey)
    }

    @Test fun scopedPagesHaveDistinctKeysWithoutPersistingConnectionSecrets() {
        val first = PageTab(WorkspacePage.FILES, "Files", serverId = "server", directory = "/first")
        val second = first.copy(directory = "/second")
        assertNotEquals(first.key, second.key)
        val encoded = json.encodeToString(BrowserTabs(listOf(first, second)))
        assertFalse(encoded.contains("password"))
        assertFalse(encoded.contains("serverUrl"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun serverPageRequiresAServerIdentity() {
        PageTab(WorkspacePage.FILES, "Files")
    }
}
