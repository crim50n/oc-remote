package dev.minios.ocremote.ui.components

import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/** The visible frame tracks rotation, multi-window and IME, not the physical display size. */
@Composable
fun rememberVisibleWindowSize(): DpSize {
    val view = LocalView.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    var width by remember(view) { mutableIntStateOf(0) }
    var height by remember(view) { mutableIntStateOf(0) }
    DisposableEffect(view) {
        val rect = Rect()
        fun update() {
            view.getWindowVisibleDisplayFrame(rect)
            width = rect.width()
            height = rect.height()
        }
        val listener = ViewTreeObserver.OnGlobalLayoutListener { update() }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        update()
        onDispose { if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return with(density) {
        DpSize(if (width > 0) width.toDp() else configuration.screenWidthDp.dp,
            if (height > 0) height.toDp() else configuration.screenHeightDp.dp)
    }
}

fun Modifier.readableContentWidth(maxWidth: Dp = 840.dp): Modifier =
    fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = maxWidth)

internal fun composerExtraHeight(visibleHeight: Dp): Dp =
    (visibleHeight * 0.22f).coerceIn(48.dp, 160.dp)
