package dev.minios.ocremote.ui.screens.files

import dev.minios.ocremote.data.api.ServerProtocol
import dev.minios.ocremote.data.api.WorkspaceGitStatus
import dev.minios.ocremote.data.api.workspaceFileCapabilities
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WorkspaceFileActionsTest {
    @Test fun apkMimeOverridesGenericServerMime() {
        assertEquals("application/vnd.android.package-archive", workspaceMime("app-debug.APK", "application/octet-stream"))
        assertEquals("application/pdf", workspaceMime("report.pdf", "application/octet-stream"))
        assertEquals("text/plain", workspaceMime("LICENSE", null, true))
    }
    @Test fun protocolCapabilitiesDoNotInventV1WriteOrV2Grep() {
        val v1 = workspaceFileCapabilities(ServerProtocol.V1)
        assertTrue(v1.textSearch)
        assertTrue(v1.gitStatus)
        assertFalse(v1.write)
        assertFalse(v1.rawDownload)
        val v2 = workspaceFileCapabilities(ServerProtocol.V2)
        assertTrue(v2.write)
        assertTrue(v2.rawDownload)
        assertFalse(v2.textSearch)
        assertTrue(v2.gitStatus)
        assertTrue(v2.committedDiff)
        assertFalse(v1.committedDiff)
    }
    @Test fun v1StatusUsesAddedRemovedFieldNames() {
        val status = Json.decodeFromString<WorkspaceGitStatus>("""{"path":"file.kt","added":4,"removed":2,"status":"modified"}""")
        assertEquals(4, status.additions)
        assertEquals(2, status.deletions)
    }
}
