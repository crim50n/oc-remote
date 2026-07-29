package dev.minios.ocremote.data.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderOauthRequestTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun serializesCodeCallbackWithTypedFields() {
        assertEquals(
            "{\"method\":1,\"code\":\"authorization-code\"}",
            json.encodeToString(ProviderOauthCallbackRequest(method = 1, code = "authorization-code")),
        )
    }

    @Test
    fun omitsCodeForAutomaticCallback() {
        assertEquals(
            "{\"method\":0}",
            json.encodeToString(ProviderOauthCallbackRequest(method = 0)),
        )
    }
}
