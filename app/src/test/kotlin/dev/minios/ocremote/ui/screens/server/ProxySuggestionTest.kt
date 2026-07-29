package dev.minios.ocremote.ui.screens.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxySuggestionTest {
    @Test
    fun suggestsProxyForAnyProviderNetworkFailureOnLocalServer() {
        assertTrue(shouldSuggestLocalProxy(true, false, 500, "Failed to authorize Anthropic"))
        assertTrue(shouldSuggestLocalProxy(true, false, 500, "GitHub token exchange failed"))
    }

    @Test
    fun doesNotSuggestProxyForValidationOrRemoteServerFailures() {
        assertFalse(shouldSuggestLocalProxy(true, false, 400, "Invalid authorization code"))
        assertFalse(shouldSuggestLocalProxy(false, false, 500, "Network failure"))
        assertFalse(shouldSuggestLocalProxy(true, true, 500, "Network failure"))
    }
}
