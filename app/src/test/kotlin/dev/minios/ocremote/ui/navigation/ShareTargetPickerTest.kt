package dev.minios.ocremote.ui.navigation

import dev.minios.ocremote.domain.model.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareTargetPickerTest {
    @Test
    fun exposesOnlyServersWithLiveConnections() {
        val connected = ServerConfig(id = "connected", url = "https://connected.example")
        val disconnectedWithCache = ServerConfig(
            id = "disconnected",
            url = "https://disconnected.example",
            lastConnected = 123,
            isHealthy = true,
        )

        val result = connectedShareServers(
            servers = listOf(connected, disconnectedWithCache),
            connectedServerIds = setOf(connected.id),
        )

        assertEquals(listOf(connected), result)
    }
}
