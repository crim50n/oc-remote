package dev.minios.ocremote.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import dev.minios.ocremote.R

/** Keep actions below the message so translated labels and large fonts cannot squeeze it. */
@Composable
fun AppSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        val shape = MaterialTheme.shapes.medium
        val visuals = data.visuals
        Snackbar(
            // The SnackbarData overload adds padding inside the supplied modifier, which places
            // a border around the margin. Use the content overload and put the margin first.
            modifier = Modifier.padding(12.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape),
            action = visuals.actionLabel?.let { label ->
                {
                    TextButton(onClick = data::performAction,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
                        Text(label)
                    }
                }
            },
            dismissAction = if (visuals.withDismissAction) {
                {
                    IconButton(onClick = data::dismiss) {
                        Icon(Lucide.X, stringResource(R.string.cancel))
                    }
                }
            } else null,
            actionOnNewLine = true,
            shape = shape,
            containerColor = if (isAmoledTheme()) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) { Text(visuals.message) }
    }
}
