package dev.minios.ocremote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.ui.screens.chat.CollapsingChatPanel
import org.junit.Rule
import org.junit.Test

class ReadingPanelLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun collapseReclaimsHeightContinuouslyWithoutRemovingContent() {
        val fraction = mutableFloatStateOf(0f)
        compose.setContent {
            Box(Modifier.testTag("panel")) {
                CollapsingChatPanel(fraction.floatValue, top = true) {
                    Box(Modifier.width(200.dp).height(100.dp).testTag("content"))
                }
            }
        }
        compose.onNodeWithTag("panel").assertHeightIsEqualTo(100.dp)
        compose.runOnIdle { fraction.floatValue = 0.5f }
        compose.onNodeWithTag("panel").assertHeightIsEqualTo(50.dp)
        compose.onNodeWithTag("content").assertExists()
        compose.runOnIdle { fraction.floatValue = 1f }
        compose.onNodeWithTag("panel").assertHeightIsEqualTo(0.dp)
        compose.onNodeWithTag("content").assertExists()
        compose.runOnIdle { fraction.floatValue = 0f }
        compose.onNodeWithTag("panel").assertHeightIsEqualTo(100.dp)
    }
}
