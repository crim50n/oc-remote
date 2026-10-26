package dev.minios.ocremote.ui.screens.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import kotlin.math.roundToInt

/** Continuous user-driven collapse, not a threshold-triggered visibility toggle. */
internal fun readingCollapseFraction(current: Float, delta: Float, travel: Float): Float =
    (current - delta / travel.coerceAtLeast(1f)).coerceIn(0f, 1f)

/** Keep panel content composed and measured; clip and translate it out of the viewport. */
@Composable
internal fun CollapsingChatPanel(
    fraction: Float,
    top: Boolean,
    backgroundModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content = { Box(backgroundModifier) { content() } }, modifier = Modifier.clipToBounds().graphicsLayer {
        alpha = if (top) 1f - fraction.coerceIn(0f, 1f) else 1f
    }) { measurables, constraints ->
        val panel = measurables.single().measure(constraints.copy(minHeight = 0))
        val visibleHeight = (panel.height * (1f - fraction.coerceIn(0f, 1f))).roundToInt()
        layout(panel.width, visibleHeight) {
            panel.placeRelative(0, if (top) visibleHeight - panel.height else 0)
        }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
internal fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    }
    var enabled by remember(manager) { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

@Composable
internal fun ReadingSystemBars(hidden: Boolean, hideStatus: Boolean, hideNavigation: Boolean) {
    val activity = LocalContext.current.activity()
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(activity, view, lifecycle, hidden, hideStatus, hideNavigation) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val types = (if (hideStatus) WindowInsetsCompat.Type.statusBars() else 0) or
            (if (hideNavigation) WindowInsetsCompat.Type.navigationBars() else 0)
        val previousBehavior = controller?.systemBarsBehavior
        fun applyVisibility() {
            if (hidden && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(types)
            } else controller?.show(types)
        }
        val observer = LifecycleEventObserver { _, _ -> applyVisibility() }
        lifecycle.addObserver(observer)
        applyVisibility()
        onDispose {
            lifecycle.removeObserver(observer)
            controller?.show(types)
            if (previousBehavior != null) controller?.systemBarsBehavior = previousBehavior
        }
    }
}
