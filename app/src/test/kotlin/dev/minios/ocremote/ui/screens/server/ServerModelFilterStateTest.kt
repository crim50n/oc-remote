package dev.minios.ocremote.ui.screens.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerModelFilterStateTest {
    @Test
    fun `connected server stays loading until first provider result`() {
        assertTrue(
            shouldShowInitialModelLoading(
                isServerConnected = true,
                isLoading = false,
                hasLoadedProviders = false,
            ),
        )
    }

    @Test
    fun `empty state is allowed after provider result`() {
        assertFalse(
            shouldShowInitialModelLoading(
                isServerConnected = true,
                isLoading = false,
                hasLoadedProviders = true,
            ),
        )
    }

    @Test
    fun `disconnected server does not show indefinite loading`() {
        assertFalse(
            shouldShowInitialModelLoading(
                isServerConnected = false,
                isLoading = true,
                hasLoadedProviders = false,
            ),
        )
    }
}
