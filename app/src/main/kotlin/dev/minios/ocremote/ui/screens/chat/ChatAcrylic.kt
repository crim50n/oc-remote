package dev.minios.ocremote.ui.screens.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.ViewTreeObserver
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import dev.minios.ocremote.ui.components.isAmoledTheme
import androidx.core.view.WindowCompat
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild

@Composable
internal fun chatAcrylicModifier(state: HazeState): Modifier {
    val surface = MaterialTheme.colorScheme.surface
    val edge = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    val background = if (isAmoledTheme()) Modifier.background(Color.Black) else Modifier.hazeChild(state) {
        backgroundColor = surface
        tints = listOf(HazeTint(surface.copy(alpha = 0.78f)))
        blurRadius = 22.dp
        noiseFactor = 0.08f
        fallbackTint = HazeTint(surface.copy(alpha = 0.94f))
    }
    return background.drawWithContent {
        drawContent()
        drawLine(edge, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
    }
}

private tailrec fun Context.hostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.hostActivity()
    else -> null
}

/** The acrylic panel paints behind the transparent system bars; the icons remain theme-aware. */
@Composable
internal fun ChatAcrylicSystemBars(enabled: Boolean, background: Color) {
    val activity = LocalContext.current.hostActivity()
    val view = LocalView.current
    val currentBackground = rememberUpdatedState(background)
    DisposableEffect(activity, view, enabled) {
        val window = activity?.window
        if (!enabled || window == null) return@DisposableEffect onDispose { }
        val statusColor = window.statusBarColor
        val navigationColor = window.navigationBarColor
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val lightStatus = controller.isAppearanceLightStatusBars
        val lightNavigation = controller.isAppearanceLightNavigationBars
        val statusContrast = if (Build.VERSION.SDK_INT >= 29) window.isStatusBarContrastEnforced else false
        val navigationContrast = if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced else false
        val listener = ViewTreeObserver.OnPreDrawListener {
            val light = currentBackground.value.luminance() > 0.5f
            if (window.statusBarColor != android.graphics.Color.TRANSPARENT) window.statusBarColor = android.graphics.Color.TRANSPARENT
            if (window.navigationBarColor != android.graphics.Color.TRANSPARENT) window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (controller.isAppearanceLightStatusBars != light) controller.isAppearanceLightStatusBars = light
            if (controller.isAppearanceLightNavigationBars != light) controller.isAppearanceLightNavigationBars = light
            if (Build.VERSION.SDK_INT >= 29) {
                if (window.isStatusBarContrastEnforced) window.isStatusBarContrastEnforced = false
                if (window.isNavigationBarContrastEnforced) window.isNavigationBarContrastEnforced = false
            }
            true
        }
        view.viewTreeObserver.addOnPreDrawListener(listener)
        onDispose {
            if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(listener)
            window.statusBarColor = statusColor
            window.navigationBarColor = navigationColor
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNavigation
            if (Build.VERSION.SDK_INT >= 29) {
                window.isStatusBarContrastEnforced = statusContrast
                window.isNavigationBarContrastEnforced = navigationContrast
            }
        }
    }
}
