package dev.minios.ocremote.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.*

@Composable
internal fun tabMouseActions(key: String, onMenu: () -> Unit, onClose: () -> Unit): Modifier {
    val menu by rememberUpdatedState(onMenu)
    val close by rememberUpdatedState(onClose)
    return Modifier.pointerInput(key) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Press) continue
                when {
                    event.buttons.isSecondaryPressed -> { menu(); event.changes.forEach { it.consume() } }
                    event.buttons.isTertiaryPressed -> { close(); event.changes.forEach { it.consume() } }
                }
            }
        }
    }
}
