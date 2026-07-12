package dev.minios.ocremote.data.repository

import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventReducerTest {

    @Test
    fun sessionCreated_upsertsWithoutReplacingBusyOrRetryStatus() {
        val reducer = EventReducer()
        val busySession = session("busy", updated = 1)
        val retrySession = session("retry", updated = 1)
        val retry = SessionStatus.Retry(attempt = 2, message = "later", next = 10)

        reducer.processEvent(SseEvent.SessionStatus("busy", SessionStatus.Busy), "server")
        reducer.processEvent(SseEvent.SessionStatus("retry", retry), "server")
        reducer.processEvent(SseEvent.SessionCreated(busySession), "server")
        reducer.processEvent(SseEvent.SessionCreated(retrySession), "server")
        reducer.processEvent(SseEvent.SessionCreated(busySession.copy(title = "updated", time = busySession.time.copy(updated = 2))), "server")

        assertEquals(SessionStatus.Busy, reducer.sessionStatuses.value["busy"])
        assertEquals(retry, reducer.sessionStatuses.value["retry"])
        assertEquals(2, reducer.sessions.value.size)
        assertEquals("updated", reducer.sessions.value.single { it.id == "busy" }.title)
    }

    @Test
    fun statusAndIdleEvents_establishOwnershipForServerCleanup() {
        val reducer = EventReducer()

        reducer.processEvent(SseEvent.SessionStatus("busy", SessionStatus.Busy), "server")
        reducer.processEvent(SseEvent.SessionIdle("idle"), "server")

        assertEquals(setOf("busy", "idle"), reducer.serverSessions.value["server"])
        reducer.clearForServer("server")
        assertTrue(reducer.sessionStatuses.value.isEmpty())
        assertFalse(reducer.serverSessions.value.containsKey("server"))
    }

    @Test
    fun sessionDeleted_removesStateAndOwnership() {
        val reducer = EventReducer()
        val session = session("deleted")
        reducer.processEvent(SseEvent.SessionCreated(session), "server")

        reducer.processEvent(SseEvent.SessionDeleted(session), "server")

        assertTrue(reducer.sessions.value.isEmpty())
        assertNull(reducer.sessionStatuses.value[session.id])
        assertFalse(reducer.serverSessions.value.containsKey("server"))
    }

    @Test
    fun clearForServer_doesNotClearAnotherServersSessions() {
        val reducer = EventReducer()
        reducer.processEvent(SseEvent.SessionStatus("first", SessionStatus.Busy), "server-1")
        reducer.processEvent(SseEvent.SessionIdle("second"), "server-2")

        reducer.clearForServer("server-1")

        assertNull(reducer.sessionStatuses.value["first"])
        assertEquals(SessionStatus.Idle, reducer.sessionStatuses.value["second"])
        assertEquals(setOf("second"), reducer.serverSessions.value["server-2"])
    }

    private fun session(id: String, updated: Long = 1) = Session(
        id = id,
        title = id,
        time = Session.Time(created = 1, updated = updated),
    )
}
