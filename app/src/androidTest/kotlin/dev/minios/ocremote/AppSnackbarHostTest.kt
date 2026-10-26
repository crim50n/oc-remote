package dev.minios.ocremote

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.ui.components.AppSnackbarHost
import dev.minios.ocremote.ui.components.LocalAmoledTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppSnackbarHostTest {
    @get:Rule val compose = createComposeRule()

    @Test fun longTranslatedActionStaysBelowMessageWithLargeFontsInAmoled() {
        var result: SnackbarResult? = null
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalAmoledTheme provides true,
                LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    val host = remember { SnackbarHostState() }
                    LaunchedEffect(Unit) {
                        result = host.showSnackbar("Вкладка закрыта", "Восстановить закрытую вкладку",
                            duration = SnackbarDuration.Indefinite)
                    }
                    AppSnackbarHost(host, Modifier.width(300.dp))
                }
            }
        }
        val message = compose.onNodeWithText("Вкладка закрыта").fetchSemanticsNode().boundsInRoot
        val action = compose.onNodeWithText("Восстановить закрытую вкладку").fetchSemanticsNode().boundsInRoot
        assertTrue(action.top >= message.bottom)
        assertTrue(message.width > action.width / 2)
        compose.onNodeWithText("Восстановить закрытую вкладку").performClick()
        compose.runOnIdle { assertEquals(SnackbarResult.ActionPerformed, result) }
    }
}
