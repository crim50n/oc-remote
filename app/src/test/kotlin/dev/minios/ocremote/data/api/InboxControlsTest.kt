package dev.minios.ocremote.data.api

import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class InboxControlsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val conn = ServerConnection("https://inbox.example", "Bearer fixture")

    @Test fun cancellationAndDeliveryChangesTargetOnlyTheSelectedItem() = runBlocking {
        val engine = FixtureEngine { request ->
            assertEquals("/api/session/session/inbox/selected", request.url.encodedPath)
            assertEquals("Bearer fixture", request.headers["Authorization"])
            FixtureResponse(body = "", status = 204)
        }
        fixtureClient(engine, json).use { client ->
            val api = OpenCodeV2Api(client, json)
            api.cancelInbox(conn, "session", "selected")
            api.changeInboxDelivery(conn, "session", "selected", "steer")
            api.changeInboxDelivery(conn, "session", "selected", "queue")
        }
        assertEquals(listOf(HttpMethod.Delete, HttpMethod.Patch, HttpMethod.Patch), engine.requests.map { it.method })
        assertEquals(listOf("steer", "queue"), engine.requests.drop(1).map { it.jsonBody(json)["delivery"]!!.jsonPrimitive.content })
    }

    @Test fun invalidModeDoesNotSendARequest() = runBlocking {
        val engine = FixtureEngine { error("Unexpected request") }
        fixtureClient(engine, json).use { client ->
            try {
                OpenCodeV2Api(client, json).changeInboxDelivery(conn, "s", "id", "interrupt")
                fail("Invalid mode accepted")
            } catch (_: IllegalArgumentException) { }
        }
        assertTrue(engine.requests.isEmpty())
    }

    @Test fun deliveryRaceIsReportedAsConflict() = runBlocking {
        fixtureClient(FixtureEngine { FixtureResponse(status = 409) }, json).use { client ->
            try {
                OpenCodeV2Api(client, json).changeInboxDelivery(conn, "s", "id", "queue")
                fail("Conflict ignored")
            } catch (e: OpenCodeHttpException) { assertEquals(409, e.statusCode) }
        }
    }
}
