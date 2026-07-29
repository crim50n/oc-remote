package dev.minios.ocremote

import dev.minios.ocremote.domain.model.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainActivityTest {
    private val servers = listOf(
        ServerConfig(
            id = "server-1",
            url = "http://10.9.0.6:4096/",
            username = "opencode",
        ),
        ServerConfig(
            id = "server-2",
            url = "https://example.test",
            username = "opencode",
        ),
    )

    @Test
    fun `deep link resolves server by id first`() {
        val server = findDeepLinkServer(servers, "server-2", "http://10.9.0.6:4096")

        assertEquals("server-2", server?.id)
    }

    @Test
    fun `legacy deep link resolves server by normalized url`() {
        val server = findDeepLinkServer(servers, "", "http://10.9.0.6:4096")

        assertEquals("server-1", server?.id)
    }

    @Test
    fun `unknown deep link server is not resolved`() {
        assertNull(findDeepLinkServer(servers, "", "https://unknown.test"))
    }
}
