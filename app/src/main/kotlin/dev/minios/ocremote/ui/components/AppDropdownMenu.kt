package dev.minios.ocremote.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.dp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin

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
    var mounted by remember { mutableStateOf(false) }
    val opacity = remember { Animatable(0f) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val maxHeight = (rememberVisibleWindowSize().height - 32.dp).coerceAtLeast(48.dp)
    LaunchedEffect(expanded) {
        if (expanded) {
            pendingAction = null
            mounted = true
            opacity.animateTo(1f, tween(200))
        } else if (mounted) {
            // Keep the popup even if dismissal interrupts its entrance animation.
            opacity.animateTo(0f, tween(220))
            mounted = false
            val action = pendingAction
            pendingAction = null
            action?.invoke()
        }
    }
    if (expanded || mounted) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = onDismissRequest,
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Box(Modifier.graphicsLayer {
                alpha = opacity.value
                scaleX = 0.96f + 0.04f * opacity.value
                scaleY = scaleX
                transformOrigin = TransformOrigin(0.5f, 0f)
                compositingStrategy = CompositingStrategy.Offscreen
            }) {
            Surface(
                modifier = modifier.heightIn(max = maxHeight),
                color = if (isAmoledTheme()) Color.Black else containerColor,
                border = if (isAmoledTheme()) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                tonalElevation = 0.dp,
                shape = MaterialTheme.shapes.extraSmall,
                shadowElevation = if (isAmoledTheme()) 0.dp else 8.dp,
            ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
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
