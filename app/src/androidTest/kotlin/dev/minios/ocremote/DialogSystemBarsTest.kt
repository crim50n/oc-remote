package dev.minios.ocremote

import android.view.View
import android.view.Window
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import dev.minios.ocremote.ui.components.DialogSystemBars
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DialogSystemBarsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun systemIconsFollowDialogBackgroundAcrossThemeChanges() {
        val background = mutableStateOf(Color.White)
        var window: Window? = null
        compose.setContent {
            Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
                val view = LocalView.current
                SideEffect {
                    window = generateSequence(view as View?) { it.parent as? View }
                        .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
                }
                DialogSystemBars(background.value)
                Surface(Modifier.fillMaxSize(), color = background.value) {}
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val target = requireNotNull(window)
            val controller = WindowCompat.getInsetsController(target, target.decorView)
            assertTrue(controller.isAppearanceLightStatusBars)
            assertTrue(controller.isAppearanceLightNavigationBars)
            background.value = Color.Black
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val target = requireNotNull(window)
            val controller = WindowCompat.getInsetsController(target, target.decorView)
            assertFalse(controller.isAppearanceLightStatusBars)
            assertFalse(controller.isAppearanceLightNavigationBars)
        }
    }
}
