package dev.minios.ocremote.ui.navigation

import android.content.Context
import androidx.navigation.NavBackStackEntry
import com.composables.icons.lucide.*
import dev.minios.ocremote.R
import dev.minios.ocremote.data.repository.PageTab
import dev.minios.ocremote.data.repository.WorkspacePage
import dev.minios.ocremote.data.repository.WorkspaceTab
import dev.minios.ocremote.data.repository.normalizeServerUrl
import dev.minios.ocremote.domain.model.ServerConfig

internal fun workspacePageIcon(page: WorkspacePage) = when (page) {
    WorkspacePage.HOME, WorkspacePage.SESSIONS -> Lucide.Server
    WorkspacePage.FAVORITES -> Lucide.Star
    WorkspacePage.SETTINGS, WorkspacePage.SERVER_SETTINGS -> Lucide.Settings
    WorkspacePage.SYNC -> Lucide.CloudCog
    WorkspacePage.DIAGNOSTICS -> Lucide.Bug
    WorkspacePage.ABOUT -> Lucide.Info
    WorkspacePage.UPDATES -> Lucide.Download
    WorkspacePage.FILES -> Lucide.FolderOpen
    else -> Lucide.Boxes
}

internal fun workspaceTabAvailable(tab: WorkspaceTab, servers: List<ServerConfig>): Boolean =
    tab.serverId == null || servers.any { it.id == tab.serverId }

private fun pageRouteName(page: WorkspacePage): String = when (page) {
    WorkspacePage.HOME -> Screen.Home.route
    WorkspacePage.FAVORITES -> Screen.CrossServerSessions.route
    WorkspacePage.SETTINGS -> Screen.Settings.route
    WorkspacePage.SYNC -> Screen.SyncSettings.route
    WorkspacePage.DIAGNOSTICS -> Screen.Diagnostics.route
    WorkspacePage.ABOUT -> Screen.About.route
    WorkspacePage.UPDATES -> Screen.UpdateDetails.route
    WorkspacePage.SESSIONS -> Screen.SessionList.route
    WorkspacePage.SERVER_SETTINGS -> Screen.ServerSettings.route
    WorkspacePage.PROVIDERS -> Screen.ServerProviders.route
    WorkspacePage.MODELS -> Screen.ServerModelFilter.route
    WorkspacePage.MCP -> Screen.ServerMcp.route
    WorkspacePage.FILES -> Screen.WorkspaceFiles.route
}

private fun pageTitle(page: WorkspacePage): Int = when (page) {
    WorkspacePage.HOME -> R.string.menu_servers_sessions
    WorkspacePage.FAVORITES -> R.string.cross_sessions_favorites
    WorkspacePage.SETTINGS -> R.string.settings_title
    WorkspacePage.SYNC -> R.string.sync_title
    WorkspacePage.DIAGNOSTICS -> R.string.diagnostics_title
    WorkspacePage.ABOUT -> R.string.about_title
    WorkspacePage.UPDATES -> R.string.update_whats_new
    WorkspacePage.SESSIONS -> R.string.sessions_title
    WorkspacePage.SERVER_SETTINGS -> R.string.server_settings_title
    WorkspacePage.PROVIDERS -> R.string.server_settings_providers
    WorkspacePage.MODELS -> R.string.server_settings_model_filter
    WorkspacePage.MCP -> R.string.server_mcp_title
    WorkspacePage.FILES -> R.string.menu_workspace_files
}

internal fun pageTabForEntry(entry: NavBackStackEntry?, servers: List<ServerConfig>, context: Context): PageTab? {
    val name = entry?.destination?.route?.substringBefore('?') ?: return null
    val page = WorkspacePage.entries.firstOrNull { pageRouteName(it) == name } ?: return null
    val arguments = entry.arguments
    val serverId = if (page.serverScoped) {
        arguments?.getString("serverId")?.takeIf { it.isNotBlank() }
            ?: servers.firstOrNull { normalizeServerUrl(it.url) == normalizeServerUrl(arguments?.getString("serverUrl").orEmpty()) }?.id
            ?: return null
    } else null
    val directory = if (page == WorkspacePage.FILES) arguments?.getString("directory").orEmpty() else ""
    val filePath = if (page == WorkspacePage.FILES) arguments?.getString("filePath")?.takeIf { it.isNotBlank() } else null
    val fileName = filePath?.let { dev.minios.ocremote.data.api.parseServerFileLink(it)?.path }
        ?.replace('\\', '/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    val folderName = directory.replace('\\', '/').trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }
    val title = if (page == WorkspacePage.FILES) fileName ?: folderName ?: context.getString(pageTitle(page))
        else context.getString(pageTitle(page))
    return PageTab(page, title, serverId, directory, filePath)
}

/** Resolve credentials at navigation time, never from persisted tab metadata. */
internal fun workspacePageRoute(tab: PageTab, servers: List<ServerConfig>): String? {
    if (!tab.page.serverScoped) return pageRouteName(tab.page)
    val server = servers.firstOrNull { it.id == tab.serverId } ?: return null
    val password = server.password.orEmpty()
    return when (tab.page) {
        WorkspacePage.SESSIONS -> Screen.SessionList.createRoute(server.url, server.username, password, server.displayName, server.id)
        WorkspacePage.SERVER_SETTINGS -> Screen.ServerSettings.createRoute(server.url, server.username, password, server.displayName, server.id)
        WorkspacePage.PROVIDERS -> Screen.ServerProviders.createRoute(server.url, server.username, password, server.displayName, server.id)
        WorkspacePage.MODELS -> Screen.ServerModelFilter.createRoute(server.url, server.username, password, server.displayName, server.id)
        WorkspacePage.MCP -> Screen.ServerMcp.createRoute(server.url, server.username, password, server.displayName, server.id)
        WorkspacePage.FILES -> Screen.WorkspaceFiles.createRoute(server.url, server.username, password, tab.directory, tab.filePath.orEmpty(), server.id)
        else -> null
    }
}
