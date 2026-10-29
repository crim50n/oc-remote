package dev.minios.ocremote.data.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WorkspaceVcsApiTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val connection = ServerConnection("https://vcs.example", "Bearer fixture")
    private val changes = """[{"file":"src/old.kt","status":"deleted","additions":0,"deletions":2,"patch":"@@ -1,2 +0,0 @@\n-old\n-lines"}]"""

    @Test fun v1UsesModernStatusAndGitModeWithProjectScope() = runBlocking {
        val engine = FixtureEngine { request ->
            assertEquals("/project", request.url.parameters["directory"])
            assertEquals("Bearer fixture", request.headers["Authorization"])
            if (request.url.encodedPath == "/vcs/diff") {
                assertEquals("git", request.url.parameters["mode"])
                assertEquals("3", request.url.parameters["context"])
            } else assertEquals("/vcs/status", request.url.encodedPath)
            FixtureResponse(changes)
        }
        fixtureClient(engine, json).use { client ->
            val status = readWorkspaceVcsV1(client, json, connection, "/vcs/status", "/project")!!
            assertEquals("src/old.kt", status.single().path)
            val diff = readWorkspaceVcsV1(client, json, connection, "/vcs/diff", "/project",
                mapOf("mode" to workspaceDiffMode(WorkspaceDiffMode.WORKING, false), "context" to "3"))!!
            assertTrue(diff.single().patch!!.contains("-old"))
        }
    }

    @Test fun v2DecodesEnvelopeAndSupportsAllComparisonModes() = runBlocking {
        val engine = FixtureEngine { request ->
            assertEquals("/project", request.headers["x-opencode-directory"])
            FixtureResponse("""{"data":$changes,"location":{"directory":"/project"}}""")
        }
        fixtureClient(engine, json).use { client ->
            val api = OpenCodeV2Api(client, json)
            assertEquals("deleted", api.vcsStatus(connection, "/project").single().status)
            WorkspaceDiffMode.entries.forEach { mode -> api.vcsDiff(connection, "/project", mode) }
        }
        assertEquals("/api/vcs/status", engine.requests.first().url.encodedPath)
        assertEquals(listOf("working", "branch", "committed"), engine.requests.drop(1).map { it.url.parameters["mode"] })
    }

    @Test fun onlyMissingEndpointsAllowLegacyFallback() = runBlocking {
        fixtureClient(FixtureEngine { FixtureResponse(status = 404) }, json).use {
            assertNull(readWorkspaceVcsV1(it, json, connection, "/vcs/status", "/project"))
        }
        fixtureClient(FixtureEngine { FixtureResponse(status = 403) }, json).use {
            try {
                readWorkspaceVcsV1(it, json, connection, "/vcs/status", "/project")
                fail("Authorization errors must not look like an empty repository")
            } catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("403")) }
        }
    }
}
