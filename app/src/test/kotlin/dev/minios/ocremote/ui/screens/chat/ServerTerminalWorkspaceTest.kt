package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ProtocolException

class ServerTerminalWorkspaceTest {
    @Test
    fun `missing PTY handshake stops reconnect attempts`() {
        assertTrue(isMissingPtyFailure(ProtocolException("Expected HTTP 101 response but was '404 Not Found'")))
        assertTrue(isMissingPtyFailure(IllegalStateException("wrapper", ProtocolException("404 Not Found"))))
        assertFalse(isMissingPtyFailure(ProtocolException("Expected HTTP 101 response but was '503 Unavailable'")))
    }

    @Test
    fun `terminal recovery reconnects existing PTY and restarts missing PTY`() {
        assertEquals(
            TerminalRecoveryAction.Reconnect,
            terminalRecoveryAction(TerminalTabState.Disconnected, hasPty = true),
        )
        assertEquals(
            TerminalRecoveryAction.Restart,
            terminalRecoveryAction(TerminalTabState.Exited, hasPty = false),
        )
        assertEquals(
            TerminalRecoveryAction.None,
            terminalRecoveryAction(TerminalTabState.Connected, hasPty = true),
        )
    }
}
