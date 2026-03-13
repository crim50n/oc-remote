package dev.minios.ocremote.data.repository

import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventReducerTest {

    @Test
    fun sessionCreatedPreservesExistingBusyStatus() {
        val reducer = EventReducer()
        val session = testSession(id = "ses_new")

        processStatusEvent(reducer, session.id, SessionStatus.Busy, serverId = "server-1")
        reducer.processEvent(SseEvent.SessionCreated(session), serverId = "server-1")

        assertEquals(SessionStatus.Busy, reducer.sessionStatuses.value[session.id])
    }

    @Test
    fun sessionCreatedInitializesMissingStatusToIdle() {
        val reducer = EventReducer()
        val session = testSession(id = "ses_new")

        reducer.processEvent(SseEvent.SessionCreated(session), serverId = "server-1")

        assertEquals(SessionStatus.Idle, reducer.sessionStatuses.value[session.id])
    }

    @Test
    fun statusBeforeSessionCreatedIsClearedOnDisconnect() {
        val reducer = EventReducer()
        val session = testSession(id = "ses_new")

        processStatusEvent(reducer, session.id, SessionStatus.Busy, serverId = "server-1")

        clearForServer(reducer, "server-1")

        assertNull(reducer.sessionStatuses.value[session.id])
    }

    @Test
    fun clearedStatusDoesNotLeakIntoReconnectedSession() {
        val reducer = EventReducer()
        val session = testSession(id = "ses_new")

        processStatusEvent(reducer, session.id, SessionStatus.Busy, serverId = "server-1")

        clearForServer(reducer, "server-1")
        reducer.processEvent(SseEvent.SessionCreated(session), serverId = "server-1")

        assertEquals(SessionStatus.Idle, reducer.sessionStatuses.value[session.id])
    }

    @Test
    fun sessionIdleBeforeSessionCreatedIsClearedOnDisconnect() {
        val reducer = EventReducer()
        val session = testSession(id = "ses_new")

        reducer.processEvent(SseEvent.SessionIdle(sessionId = session.id), serverId = "server-1")

        clearForServer(reducer, "server-1")

        assertNull(reducer.sessionStatuses.value[session.id])
    }

    private fun testSession(id: String) = Session(
        id = id,
        directory = "/tmp/project",
        time = Session.Time(
            created = 1L,
            updated = 1L,
        ),
    )

    private fun processStatusEvent(
        reducer: EventReducer,
        sessionId: String,
        status: SessionStatus,
        serverId: String,
    ) {
        try {
            reducer.processEvent(
                SseEvent.SessionStatus(sessionId = sessionId, status = status),
                serverId = serverId
            )
        } catch (error: RuntimeException) {
            if (!error.message.orEmpty().contains("android.util.Log not mocked")) {
                throw error
            }
        }
    }

    private fun clearForServer(reducer: EventReducer, serverId: String) {
        try {
            reducer.clearForServer(serverId)
        } catch (error: RuntimeException) {
            if (!error.message.orEmpty().contains("android.util.Log not mocked")) {
                throw error
            }
        }
    }
}
