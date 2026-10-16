package dev.minios.ocremote

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.ui.screens.home.ServerCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeConnectionCancellationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun connectingCardExposesEnabledCancelAction() {
        var cancellations = 0
        var connections = 0
        val connected = mutableStateOf(false)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MaterialTheme {
                ServerCard(
                    server = ServerConfig(id = "unreachable", url = "http://192.0.2.1:4096"),
                    isConnected = connected.value,
                    isConnecting = !connected.value,
                    connectionError = null,
                    showServerSettings = false,
                    onConnect = { connections++ },
                    onDisconnect = { cancellations++ },
                    onOpenSessions = {},
                    onServerSettings = {},
                    onEdit = {},
                    onDelete = {},
                )
            }
        }

        val connecting = compose.onNodeWithText(context.getString(R.string.home_connecting))
            .assertIsDisplayed()
            .assertIsNotEnabled()
        val cancel = compose.onNodeWithText(context.getString(R.string.cancel))
            .assertIsDisplayed()
            .assertIsEnabled()
        assertTrue(connecting.fetchSemanticsNode().boundsInRoot.top < cancel.fetchSemanticsNode().boundsInRoot.top)
        cancel.performClick()
        compose.runOnIdle {
            assertEquals(1, cancellations)
            assertEquals(0, connections)
            connected.value = true
        }
        val disconnect = compose.onNodeWithText(context.getString(R.string.home_disconnect))
            .assertIsDisplayed()
            .assertIsEnabled()
        val sessions = compose.onNodeWithText(context.getString(R.string.sessions_title)).assertIsDisplayed()
        assertTrue(sessions.fetchSemanticsNode().boundsInRoot.top < disconnect.fetchSemanticsNode().boundsInRoot.top)
    }
}
