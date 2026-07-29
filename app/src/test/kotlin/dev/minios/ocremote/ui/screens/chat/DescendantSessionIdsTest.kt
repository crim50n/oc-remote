package dev.minios.ocremote.ui.screens.chat

import dev.minios.ocremote.domain.model.Session
import org.junit.Assert.assertEquals
import org.junit.Test

class DescendantSessionIdsTest {

    @Test
    fun includesRootAndRecursiveDescendantsButNotUnrelatedSessions() {
        val sessions = listOf(
            session("root"),
            session("child", "root"),
            session("grandchild", "child"),
            session("unrelated"),
        )

        assertEquals(
            setOf("root", "child", "grandchild"),
            descendantSessionIds(sessions, "root"),
        )
    }

    private fun session(id: String, parentId: String? = null) = Session(
        id = id,
        parentId = parentId,
        title = id,
        time = Session.Time(created = 0, updated = 0),
    )
}
