package dev.minios.ocremote.data.repository

import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionStatus
import dev.minios.ocremote.domain.model.SseEvent
import dev.minios.ocremote.domain.model.Message
import dev.minios.ocremote.domain.model.Part
import dev.minios.ocremote.domain.model.TimeInfo
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

    @Test
    fun pendingRequests_areUpsertedByRequestId() {
        val reducer = EventReducer()
        val first = SseEvent.PermissionAsked("permission", "session", "read")
        val updated = first.copy(permission = "write")

        reducer.processEvent(first, "server")
        reducer.processEvent(updated, "server")

        assertEquals(listOf(updated), reducer.permissions.value["session"])
    }

    @Test
    fun stalePendingSnapshot_doesNotResurrectRepliedRequest() {
        val reducer = EventReducer()
        reducer.processEvent(SseEvent.SessionCreated(session("session")), "server")
        val request = SseEvent.PermissionAsked("permission", "session", "read")
        reducer.processEvent(request, "server")
        val revision = reducer.pendingSnapshotRevision()
        reducer.processEvent(SseEvent.PermissionReplied("session", "permission"), "server")

        val replaced = reducer.replacePendingRequests(
            serverId = "server",
            permissions = listOf(request),
            questions = emptyList(),
            expectedRevision = revision,
        )

        assertFalse(replaced)
        assertNull(reducer.permissions.value["session"])
    }

    @Test
    fun sessionDeleted_removesMessagesPartsTodosAndErrors() {
        val reducer = EventReducer()
        val session = session("session")
        val message = Message.User("message", session.id, time = TimeInfo(1))
        val part = Part.Text("part", session.id, message.id, text = "text")
        reducer.processEvent(SseEvent.SessionCreated(session), "server")
        reducer.processEvent(SseEvent.MessageUpdated(message), "server")
        reducer.processEvent(SseEvent.MessagePartUpdated(part), "server")
        reducer.processEvent(
            SseEvent.TodoUpdated(session.id, listOf(SseEvent.TodoUpdated.Todo("todo", "pending", "medium"))),
            "server",
        )
        reducer.processEvent(
            SseEvent.SessionError(session.id, Message.Assistant.ErrorInfo(name = "failed")),
            "server",
        )

        reducer.processEvent(SseEvent.SessionDeleted(session), "server")

        assertNull(reducer.messages.value[session.id])
        assertNull(reducer.parts.value[message.id])
        assertNull(reducer.todos.value[session.id])
        assertNull(reducer.sessionErrors.value[session.id])
    }

    @Test
    fun deltaBeforePart_isReplayedOnceInArrivalOrder() {
        val reducer = EventReducer()
        reducer.processEvent(SseEvent.MessagePartDelta("session", "message", "part", "text", "one"), "server")
        reducer.processEvent(SseEvent.MessagePartDelta("session", "message", "part", "text", " two"), "server")

        reducer.processEvent(
            SseEvent.MessagePartUpdated(Part.Text("part", "session", "message", text = "start ")),
            "server",
        )

        val part = reducer.parts.value["message"]?.single() as Part.Text
        assertEquals("start one two", part.text)
    }

    @Test
    fun unknownDeltaField_doesNotMutateText() {
        val reducer = EventReducer()
        reducer.processEvent(
            SseEvent.MessagePartUpdated(Part.Text("part", "session", "message", text = "original")),
            "server",
        )

        reducer.processEvent(SseEvent.MessagePartDelta("session", "message", "part", "metadata", "bad"), "server")

        val part = reducer.parts.value["message"]?.single() as Part.Text
        assertEquals("original", part.text)
    }

    @Test
    fun successfulStatusSnapshot_setsMissingActiveSessionToIdle() {
        val reducer = EventReducer()
        reducer.processEvent(SseEvent.SessionStatus("busy", SessionStatus.Busy), "server")
        reducer.processEvent(SseEvent.SessionStatus("retry", SessionStatus.Retry(1, "later", 2)), "server")

        reducer.replaceSessionStatuses(
            serverId = "server",
            sessionIds = setOf("busy", "retry"),
            statuses = mapOf("retry" to SessionStatus.Retry(2, "again", 3)),
        )

        assertEquals(SessionStatus.Idle, reducer.sessionStatuses.value["busy"])
        assertEquals(SessionStatus.Retry(2, "again", 3), reducer.sessionStatuses.value["retry"])
    }

    @Test
    fun sessionError_isRetainedAndEndsBusyState() {
        val reducer = EventReducer()
        val error = Message.Assistant.ErrorInfo(name = "ProviderError")
        reducer.processEvent(SseEvent.SessionStatus("session", SessionStatus.Busy), "server")

        reducer.processEvent(SseEvent.SessionError("session", error), "server")

        assertEquals(error, reducer.sessionErrors.value["session"])
        assertEquals(SessionStatus.Idle, reducer.sessionStatuses.value["session"])
    }

    private fun session(id: String, updated: Long = 1) = Session(
        id = id,
        title = id,
        time = Session.Time(created = 1, updated = updated),
    )
}
