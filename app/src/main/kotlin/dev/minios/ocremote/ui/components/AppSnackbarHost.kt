package dev.minios.ocremote.ui.components

import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Keep actions below the message so translated labels and large fonts cannot squeeze it. */
@Composable
fun AppSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        val shape = MaterialTheme.shapes.medium
        Snackbar(
            snackbarData = data,
            modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            actionOnNewLine = true,
            shape = shape,
            containerColor = if (isAmoledTheme()) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            actionColor = MaterialTheme.colorScheme.primary,
            dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
