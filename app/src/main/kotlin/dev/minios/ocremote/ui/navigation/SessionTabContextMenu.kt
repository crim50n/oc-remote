package dev.minios.ocremote.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.composables.icons.lucide.*
import dev.minios.ocremote.R
import dev.minios.ocremote.data.repository.SessionTab
import dev.minios.ocremote.data.repository.WorkspaceTab
import dev.minios.ocremote.ui.components.AppDropdownMenu
import dev.minios.ocremote.ui.components.AppDropdownMenuItem

enum class SessionTabAction { OPEN_RIGHT, RENAME, RELOAD, PIN, CLOSE, CLOSE_OTHERS, CLOSE_RIGHT, REOPEN, HISTORY, OPEN_MENU }

@Composable
internal fun SessionTabContextMenu(
    tab: WorkspaceTab, expanded: Boolean, connected: Boolean, canRestore: Boolean,
    onDismiss: () -> Unit, onAction: (SessionTabAction) -> Unit,
    vertical: Boolean = false,
) {
    AppDropdownMenu(expanded, onDismiss) {
        AppDropdownMenuItem(text = { Text(stringResource(if (vertical) R.string.tabs_open_after else R.string.tabs_open_right)) },
            leadingIcon = { Icon(Lucide.Plus, null) }, onClick = { onAction(SessionTabAction.OPEN_RIGHT) })
        if (tab is SessionTab) {
        AppDropdownMenuItem(text = { Text(stringResource(R.string.menu_rename_session)) },
            leadingIcon = { Icon(Lucide.Pencil, null) }, enabled = connected,
            onClick = { onAction(SessionTabAction.RENAME) })
        AppDropdownMenuItem(text = { Text(stringResource(R.string.menu_reload_session)) },
            leadingIcon = { Icon(Lucide.RefreshCw, null) }, enabled = connected,
            onClick = { onAction(SessionTabAction.RELOAD) })
        }
        AppDropdownMenuItem(text = { Text(stringResource(if (tab.pinned) R.string.tabs_unpin else R.string.tabs_pin)) },
            leadingIcon = { Icon(Lucide.Pin, null) }, onClick = { onAction(SessionTabAction.PIN) })
        HorizontalDivider()
        AppDropdownMenuItem(text = { Text(stringResource(R.string.session_tabs_close)) },
            onClick = { onAction(SessionTabAction.CLOSE) })
        AppDropdownMenuItem(text = { Text(stringResource(R.string.tabs_close_others)) },
            onClick = { onAction(SessionTabAction.CLOSE_OTHERS) })
        AppDropdownMenuItem(text = { Text(stringResource(if (vertical) R.string.tabs_close_below else R.string.tabs_close_right)) },
            onClick = { onAction(SessionTabAction.CLOSE_RIGHT) })
        HorizontalDivider()
        AppDropdownMenuItem(text = { Text(stringResource(R.string.session_tabs_restore)) }, enabled = canRestore,
            onClick = { onAction(SessionTabAction.REOPEN) })
        AppDropdownMenuItem(text = { Text(stringResource(R.string.tabs_recently_closed)) }, enabled = canRestore,
            onClick = { onAction(SessionTabAction.HISTORY) })
    }
}
