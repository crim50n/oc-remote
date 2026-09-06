package dev.minios.ocremote.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.composables.icons.lucide.*

@Composable
fun backIcon(): ImageVector = if (LocalLayoutDirection.current == LayoutDirection.Ltr) {
    Lucide.ArrowLeft
} else {
    Lucide.ArrowRight
}

@Composable
fun forwardIcon(): ImageVector = if (LocalLayoutDirection.current == LayoutDirection.Ltr) {
    Lucide.ArrowRight
} else {
    Lucide.ArrowLeft
}

@Composable
fun forwardChevronIcon(): ImageVector = if (LocalLayoutDirection.current == LayoutDirection.Ltr) {
    Lucide.ChevronRight
} else {
    Lucide.ChevronLeft
}

@Composable
fun undoIcon(): ImageVector = if (LocalLayoutDirection.current == LayoutDirection.Ltr) {
    Lucide.Undo2
} else {
    Lucide.Redo2
}

@Composable
fun Modifier.mirrorForRtl(): Modifier = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
    graphicsLayer(scaleX = -1f)
} else {
    this
}
