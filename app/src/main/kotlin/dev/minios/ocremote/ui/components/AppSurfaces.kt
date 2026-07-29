package dev.minios.ocremote.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val AppDialogShape = RoundedCornerShape(20.dp)
val AppPickerItemShape = RoundedCornerShape(12.dp)
val AppCardShape = RoundedCornerShape(12.dp)
val AppSearchShape = RoundedCornerShape(14.dp)
val LocalAmoledTheme = staticCompositionLocalOf { false }

@Composable
fun isAmoledTheme(): Boolean {
    return LocalAmoledTheme.current
}

@Composable
fun appDialogContainerColor(): Color {
    return if (isAmoledTheme()) Color.Black else AlertDialogDefaults.containerColor
}

@Composable
fun appPopupContainerColor(): Color {
    return if (isAmoledTheme()) Color.Black else MaterialTheme.colorScheme.surface
}

@Composable
fun appAmoledBorder(alpha: Float = 0.55f): BorderStroke? {
    return if (isAmoledTheme()) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = alpha))
    } else {
        null
    }
}

@Composable
fun appDialogElevation(): Dp = if (isAmoledTheme()) 0.dp else 6.dp

@Composable
fun appSelectedItemColor(): Color {
    return if (isAmoledTheme()) Color.Black else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
}

@Composable
fun Modifier.appPopupBorder(shape: Shape = RoundedCornerShape(8.dp)): Modifier {
    val border = appAmoledBorder() ?: return this
    return border(border, shape)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = AppDialogShape,
    content: @Composable ColumnScope.() -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        Surface(
            modifier = modifier,
            shape = shape,
            color = appDialogContainerColor(),
            border = appAmoledBorder(),
            tonalElevation = appDialogElevation(),
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun AppDialogActions(
    dismissText: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    destructiveConfirm: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        AppSecondaryButton(onClick = onDismiss) {
            Text(dismissText)
        }
        AppPrimaryButton(
            onClick = onConfirm,
            enabled = confirmEnabled,
            destructive = destructiveConfirm,
        ) {
            Text(confirmText)
        }
    }
}

@Composable
fun AppPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    if (isAmoledTheme()) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            border = BorderStroke(
                1.dp,
                if (enabled) accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.Black,
                contentColor = accent,
            ),
            content = content,
        )
    } else {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = if (destructive) {
                ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                ButtonDefaults.buttonColors()
            },
            content = content,
        )
    }
}

@Composable
fun AppSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    outlined: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val accent = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    if (outlined) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            border = BorderStroke(
                1.dp,
                if (enabled) accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (isAmoledTheme()) Color.Black else Color.Transparent,
                contentColor = accent,
            ),
            content = content,
        )
    } else {
        TextButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(contentColor = accent),
            content = content,
        )
    }
}
