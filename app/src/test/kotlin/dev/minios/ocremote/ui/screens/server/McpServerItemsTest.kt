package dev.minios.ocremote.ui.screens.server

import dev.minios.ocremote.data.api.McpStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class McpServerItemsTest {
    @Test
    fun `mcp servers are mapped and sorted by name`() {
        val items = mcpItems(
            mapOf(
                "Zeta" to McpStatus(status = "failed", error = "offline"),
                "alpha" to McpStatus(status = "connected"),
            )
        )

        assertEquals(listOf("alpha", "Zeta"), items.map(McpServerItem::name))
        assertEquals("offline", items.last().error)
    }
}
