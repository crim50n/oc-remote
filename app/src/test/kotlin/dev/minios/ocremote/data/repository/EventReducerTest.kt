package dev.minios.ocremote.data.repository

import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class EventReducerTest {

    @Test
    fun sessionCreatedPreservesExistingBusyStatus() {
        val reducer = EventReducer()
        val session = testSession(id = "ses_new")

        setSessionStatuses(reducer, mapOf(session.id to SessionStatus.Busy))
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

    private fun testSession(id: String) = Session(
        id = id,
        directory = "/tmp/project",
        time = Session.Time(
            created = 1L,
            updated = 1L,
        ),
    )

    @Suppress("UNCHECKED_CAST")
    private fun setSessionStatuses(reducer: EventReducer, statuses: Map<String, SessionStatus>) {
        val field = EventReducer::class.java.getDeclaredField("_sessionStatuses")
        field.isAccessible = true
        val stateFlow = field.get(reducer) as MutableStateFlow<Map<String, SessionStatus>>
        stateFlow.value = statuses
    }
}
