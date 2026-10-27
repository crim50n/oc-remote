package dev.minios.ocremote.ui.components

import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/** Apply after Dialog.updateParameters, which otherwise reapplies the system theme's icon colors. */
@Composable
internal fun DialogSystemBars(background: Color) {
    val view = LocalView.current
    val currentBackground = rememberUpdatedState(background)
    DisposableEffect(view) {
        fun dialogWindow() = generateSequence(view as View?) { it.parent as? View }
            .filterIsInstance<DialogWindowProvider>().firstOrNull()?.window
            ?: (view.parent as? DialogWindowProvider)?.window
        var restore: (() -> Unit)? = null
        fun applyColors() {
            val window = dialogWindow() ?: return
            if (!window.decorView.isAttachedToWindow) return
            // On API 30+ a controller obtained before Dialog.show() can wrap a null platform
            // controller permanently. Resolve it after attachment, not in the composition effect.
            val attachedController = WindowCompat.getInsetsController(window, window.decorView)
            if (restore == null) {
                val statusColor = window.statusBarColor
                val navColor = window.navigationBarColor
                val lightStatus = attachedController.isAppearanceLightStatusBars
                val lightNavigation = attachedController.isAppearanceLightNavigationBars
                val statusContrast = if (Build.VERSION.SDK_INT >= 29) window.isStatusBarContrastEnforced else false
                val navContrast = if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced else false
                restore = {
                    window.statusBarColor = statusColor
                    window.navigationBarColor = navColor
                    attachedController.isAppearanceLightStatusBars = lightStatus
                    attachedController.isAppearanceLightNavigationBars = lightNavigation
                    if (Build.VERSION.SDK_INT >= 29) {
                        window.isStatusBarContrastEnforced = statusContrast
                        window.isNavigationBarContrastEnforced = navContrast
                    }
                }
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                window.setDimAmount(0f)
            }
            val color = currentBackground.value.toArgb()
            val light = currentBackground.value.luminance() > 0.5f
            if (window.statusBarColor != color) window.statusBarColor = color
            if (window.navigationBarColor != color) window.navigationBarColor = color
            // Some system bars/OEMs still read the decor flags while others read InsetsController.
            // The controller getter alone can report the requested appearance even after Compose
            // has overwritten the legacy flags when the dialog gains focus.
            val iconFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            val previousFlags = window.decorView.systemUiVisibility
            val requestedFlags = if (light) previousFlags or iconFlags else previousFlags and iconFlags.inv()
            if (previousFlags != requestedFlags) window.decorView.systemUiVisibility = requestedFlags
            attachedController.isAppearanceLightStatusBars = light
            attachedController.isAppearanceLightNavigationBars = light
            if (Build.VERSION.SDK_INT >= 29) {
                if (window.isStatusBarContrastEnforced) window.isStatusBarContrastEnforced = false
                if (window.isNavigationBarContrastEnforced) window.isNavigationBarContrastEnforced = false
            }
        }
        val listener = ViewTreeObserver.OnPreDrawListener { applyColors(); true }
        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) applyColors()
        }
        view.viewTreeObserver.addOnPreDrawListener(listener)
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        applyColors()
        onDispose {
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnPreDrawListener(listener)
                view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            }
            restore?.invoke()
        }
    }
}
