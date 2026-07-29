package dev.minios.ocremote.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class NavigationArgumentTest {
    @Test
    fun `route arguments encode percent plus and spaces exactly once`() {
        val value = "abc%NR xyz+%25"
        val encoded = encodeNavigationArgument(value)

        assertEquals("abc%25NR%20xyz%2B%2525", encoded)
        assertEquals(value, URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()))
    }

    @Test
    fun `chat route safely carries literal percent in password and session id`() {
        val route = Screen.Chat.createRoute(
            serverUrl = "https://example.test",
            username = "user",
            password = "abc%NRxyz",
            serverName = "Percent server",
            serverId = "server%id",
            sessionId = "session%id",
        )

        assertTrue("password=abc%25NRxyz" in route)
        assertTrue("serverId=server%25id" in route)
        assertTrue("sessionId=session%25id" in route)
    }
}
