package dev.minios.ocremote.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerSettingsAvailabilityTest {

    @Test
    fun successfulCapabilityProbeMakesSettingsAvailable() {
        val result = resolveServerSettingsReadyIds(
            readyIds = emptySet(),
            connectedIds = setOf("server-1"),
            serverId = "server-1",
            probeSucceeded = true,
        )

        assertEquals(setOf("server-1"), result)
    }

    @Test
    fun failedCapabilityProbeRemovesSettingsAccess() {
        val result = resolveServerSettingsReadyIds(
            readyIds = setOf("server-1", "server-2"),
            connectedIds = setOf("server-1", "server-2"),
            serverId = "server-1",
            probeSucceeded = false,
        )

        assertEquals(setOf("server-2"), result)
    }

    @Test
    fun successfulProbeCompletedAfterDisconnectDoesNotRestoreAccess() {
        val result = resolveServerSettingsReadyIds(
            readyIds = setOf("server-1", "server-2"),
            connectedIds = setOf("server-2"),
            serverId = "server-1",
            probeSucceeded = true,
        )

        assertEquals(setOf("server-2"), result)
    }
}
