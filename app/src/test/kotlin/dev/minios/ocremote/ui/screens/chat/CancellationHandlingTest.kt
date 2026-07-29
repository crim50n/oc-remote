package dev.minios.ocremote.ui.screens.chat

import java.util.concurrent.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Test

class CancellationHandlingTest {
    @Test
    fun `routine cancellation is rethrown instead of logged as failure`() {
        assertThrows(CancellationException::class.java) {
            CancellationException("cancelled").rethrowCancellation()
        }
    }
}
