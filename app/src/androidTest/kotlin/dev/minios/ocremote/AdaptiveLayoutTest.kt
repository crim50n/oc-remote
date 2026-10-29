package dev.minios.ocremote

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.ui.components.AppDialogActions
import dev.minios.ocremote.ui.components.readableContentWidth
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AdaptiveLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun readableContentFollowsResizeAndDoesNotStretchAcrossTablet() {
        val width = mutableStateOf(1200.dp)
        compose.setContent {
            Box(Modifier.requiredWidth(width.value).height(100.dp)) {
                Box(Modifier.readableContentWidth().height(40.dp).testTag("content"))
            }
        }
        compose.onNodeWithTag("content").assertWidthIsEqualTo(840.dp)
        compose.runOnIdle { width.value = 280.dp }
        compose.onNodeWithTag("content").assertWidthIsEqualTo(280.dp)
    }

    @Test fun longDialogActionsWrapOnNarrowWindows() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.requiredWidth(220.dp)) {
                    AppDialogActions("Cancel current operation", "Save these settings", {}, {})
                }
            }
        }
        val dismiss = compose.onNodeWithText("Cancel current operation").fetchSemanticsNode().boundsInRoot
        val confirm = compose.onNodeWithText("Save these settings").fetchSemanticsNode().boundsInRoot
        assertTrue(confirm.top >= dismiss.bottom)
    }
}
