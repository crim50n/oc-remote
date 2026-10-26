package dev.minios.ocremote.data.repository

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WorkspaceDraftTest {
    @Test fun explicitModelSurvivesDraftSerializationWithoutText() {
        val draft = Draft(selectedProviderId = "provider", selectedModelId = "model")
        assertFalse(draft.isEmpty)
        assertEquals(draft, Json.decodeFromString<Draft>(Json.encodeToString(draft)))
    }
    @Test fun legacyDraftWithoutModelStillLoads() {
        val draft = Json.decodeFromString<Draft>("""{"text":"unsent"}""")
        assertEquals("unsent", draft.text)
        assertEquals(null, draft.selectedModelId)
    }
    @Test fun viewSnapshotPersistsAnchorAndFollowMode() {
        val snapshot = ChatViewSnapshot("turn:message", 345, false, true)
        assertEquals(snapshot, Json.decodeFromString<ChatViewSnapshot>(Json.encodeToString(snapshot)))
    }
}
