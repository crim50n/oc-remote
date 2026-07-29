package dev.minios.ocremote.domain

import dev.minios.ocremote.domain.model.FavoriteSessionSnapshot
import dev.minios.ocremote.domain.model.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavoriteSessionSnapshotTest {
    @Test
    fun `snapshot preserves list metadata without conversation payload`() {
        val session = Session(
            id = "session",
            projectId = "project",
            directory = "/workspace/project",
            title = "Favorite session",
            time = Session.Time(created = 10, updated = 20),
            summary = Session.Summary(additions = 4, deletions = 2),
        )

        val restored = FavoriteSessionSnapshot.from(session).toSession()

        assertEquals(session.id, restored.id)
        assertEquals(session.projectId, restored.projectId)
        assertEquals(session.directory, restored.directory)
        assertEquals(session.title, restored.title)
        assertEquals(session.time, restored.time)
        assertNull(restored.summary)
    }
}
