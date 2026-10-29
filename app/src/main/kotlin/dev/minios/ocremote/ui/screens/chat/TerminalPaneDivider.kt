package dev.minios.ocremote.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R

internal val TerminalDividerWidth = 12.dp

@Composable
internal fun TerminalPaneDivider(onDrag: (Float) -> Unit) {
    val context = LocalContext.current
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val description = stringResource(R.string.terminal_resize_pane)
    val cursor = remember(context) {
        PointerIcon(android.view.PointerIcon.getSystemIcon(context, android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
    }
    Box(
        Modifier.width(TerminalDividerWidth).fillMaxHeight()
            .hoverable(interactions).pointerHoverIcon(cursor)
            .semantics { contentDescription = description }
            .draggable(rememberDraggableState(onDrag), Orientation.Horizontal,
                onDragStarted = { dragging = true }, onDragStopped = { dragging = false }),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(
            if (hovered || dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ))
    }
}
