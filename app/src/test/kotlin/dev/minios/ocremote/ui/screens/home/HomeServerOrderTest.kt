package dev.minios.ocremote.ui.screens.home

import dev.minios.ocremote.domain.model.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeServerOrderTest {
    private val servers = listOf(
        ServerConfig(id = "first", url = "https://first.example"),
        ServerConfig(id = "second", url = "https://second.example"),
        ServerConfig(id = "third", url = "https://third.example"),
    )

    @Test
    fun `drag movement uses stable server keys`() {
        val reordered = moveServerByKey(
            servers,
            fromKey = serverReorderKey("first"),
            toKey = serverReorderKey("third"),
        )

        assertEquals(listOf("second", "third", "first"), reordered.map(ServerConfig::id))
    }

    @Test
    fun `pseudo item key cannot reorder servers`() {
        val reordered = moveServerByKey(servers, fromKey = "first", toKey = "__favorite_sessions")

        assertEquals(servers, reordered)
    }
}
