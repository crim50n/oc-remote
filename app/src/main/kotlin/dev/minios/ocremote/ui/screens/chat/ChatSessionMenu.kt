package dev.minios.ocremote.ui.screens.chat

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.composables.icons.lucide.*
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.AppDropdownMenu
import dev.minios.ocremote.ui.components.AppDropdownMenuItem
import dev.minios.ocremote.ui.components.isAmoledTheme

internal enum class SessionMenuAction {
    ATTACH, COMPRESS_IMAGES, TERMINAL, FILES, WEB, RELOAD, RENAME, NEW, FORK,
    COMPACT, REVIEW, SHARE, UNSHARE, EXPORT, FULL_WIDTH, SETTINGS, SERVERS, ABOUT, ADD_SERVER,
}

@Composable
internal fun ChatSessionMenu(
    expanded: Boolean, onDismiss: () -> Unit, onAction: (SessionMenuAction) -> Unit,
    sessionActions: Boolean, connected: Boolean, supportsSharing: Boolean, shared: Boolean,
    compressImages: Boolean, desktop: Boolean, fullWidth: Boolean,
) {
    val colors = if (isAmoledTheme()) SwitchDefaults.colors(
        checkedThumbColor = MaterialTheme.colorScheme.primary,
        checkedTrackColor = Color.Black, checkedBorderColor = MaterialTheme.colorScheme.primary,
        uncheckedTrackColor = Color.Black, uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant,
    ) else SwitchDefaults.colors()
    @Composable
    fun Item(label: Int, icon: ImageVector, action: SessionMenuAction, enabled: Boolean = true, checked: Boolean? = null) {
        AppDropdownMenuItem(text = { Text(stringResource(label)) }, leadingIcon = { Icon(icon, null) },
            trailingIcon = checked?.let { { Switch(it, onCheckedChange = null, colors = colors) } },
            enabled = enabled, onClick = { onAction(action) })
    }
    AppDropdownMenu(expanded, onDismiss) {
        if (sessionActions) {
            Item(R.string.chat_attach, Lucide.Paperclip, SessionMenuAction.ATTACH)
            Item(R.string.menu_compress_images, Lucide.Scaling, SessionMenuAction.COMPRESS_IMAGES, checked = compressImages)
            Item(R.string.tool_terminal, Lucide.Terminal, SessionMenuAction.TERMINAL)
            Item(R.string.menu_workspace_files, Lucide.FolderOpen, SessionMenuAction.FILES)
            Item(R.string.menu_open_in_web, Lucide.Globe, SessionMenuAction.WEB)
            HorizontalDivider()
            Item(R.string.menu_reload_session, Lucide.RefreshCw, SessionMenuAction.RELOAD, connected)
            Item(R.string.menu_rename_session, Lucide.Pencil, SessionMenuAction.RENAME, connected)
            Item(R.string.menu_new_session, Lucide.Plus, SessionMenuAction.NEW, connected)
            Item(R.string.menu_fork_session, Lucide.GitFork, SessionMenuAction.FORK, connected)
            HorizontalDivider()
            Item(R.string.menu_compact_session, Lucide.Shrink, SessionMenuAction.COMPACT, connected)
            Item(R.string.menu_review_changes, Lucide.ClipboardCheck, SessionMenuAction.REVIEW, connected)
            if (shared) Item(R.string.cmd_unshare, Lucide.Unlink, SessionMenuAction.UNSHARE, connected && supportsSharing)
            else Item(R.string.menu_share_session, Lucide.Share2, SessionMenuAction.SHARE, connected && supportsSharing)
            Item(R.string.menu_export_session, Lucide.FileDown, SessionMenuAction.EXPORT, connected)
        }
        if (desktop) {
            if (sessionActions) HorizontalDivider()
            Item(R.string.chat_use_window_width, Lucide.Maximize2, SessionMenuAction.FULL_WIDTH, checked = fullWidth)
            Item(R.string.menu_servers_sessions, Lucide.Server, SessionMenuAction.SERVERS)
            Item(R.string.home_add_server, Lucide.Plus, SessionMenuAction.ADD_SERVER)
            Item(R.string.settings_title, Lucide.Settings, SessionMenuAction.SETTINGS)
            Item(R.string.about_title, Lucide.Info, SessionMenuAction.ABOUT)
        }
    }
}
