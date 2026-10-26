package dev.minios.ocremote.ui.components

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.dp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

private val LocalMenuAction = staticCompositionLocalOf<((() -> Unit) -> Unit)?> { null }

/** Keep the popup alive for its exit, then run actions that may navigate or remove its anchor. */
@Composable
fun AppDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    val visible = remember { MutableTransitionState(false) }
    visible.targetState = expanded
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val transition = rememberTransition(visible, label = "menu visibility")
    LaunchedEffect(expanded) {
        if (expanded) pendingAction = null
    }
    val alpha by transition.animateFloat(
        transitionSpec = { tween(if (targetState) 160 else 180) }, label = "menu opacity",
    ) { if (it) 1f else 0f }
    LaunchedEffect(visible.isIdle, visible.currentState) {
        if (visible.isIdle && !visible.currentState) {
            val action = pendingAction
            pendingAction = null
            action?.invoke()
        }
    }
    if (visible.currentState || visible.targetState) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = onDismissRequest,
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Surface(
                modifier = Modifier.graphicsLayer { this.alpha = alpha }.then(modifier),
                color = if (isAmoledTheme()) Color.Black else containerColor,
                border = if (isAmoledTheme()) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                tonalElevation = 0.dp,
                shape = MaterialTheme.shapes.extraSmall,
                shadowElevation = if (isAmoledTheme()) 0.dp else 8.dp,
            ) {
            Column(Modifier.padding(vertical = 8.dp)) {
            CompositionLocalProvider(LocalMenuAction provides { action ->
                if (expanded && pendingAction == null) {
                    pendingAction = action
                    onDismissRequest()
                }
            }) { content() }
            }
            }
        }
    }
}

@Composable
fun AppDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val dispatch = LocalMenuAction.current
    DropdownMenuItem(
        text = text,
        onClick = { if (dispatch != null) dispatch(onClick) else onClick() },
        modifier = modifier,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
    )
}
