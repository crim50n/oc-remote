package dev.minios.ocremote.ui.navigation

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal fun encodeNavigationArgument(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

/**
 * Navigation routes for the app
 */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object CrossServerSessions : Screen("cross_server_sessions")
    
    data object WebView : Screen("webview") {
        fun createRoute(
            serverUrl: String,
            username: String,
            password: String,
            serverName: String,
            initialPath: String = ""
        ): String {
            val encodedUrl = encodeNavigationArgument(serverUrl)
            val encodedUsername = encodeNavigationArgument(username)
            val encodedPassword = encodeNavigationArgument(password)
            val encodedName = encodeNavigationArgument(serverName)
            val encodedPath = encodeNavigationArgument(initialPath)
            return "webview?serverUrl=$encodedUrl&username=$encodedUsername&password=$encodedPassword&serverName=$encodedName&initialPath=$encodedPath"
        }
    }
    
    data object SessionList : Screen("sessions") {
        fun createRoute(
            serverUrl: String,
            username: String,
            password: String,
            serverName: String,
            serverId: String,
            allowSelfSigned: Boolean = false,
        ): String {
            val encodedUrl = encodeNavigationArgument(serverUrl)
            val encodedUsername = encodeNavigationArgument(username)
            val encodedPassword = encodeNavigationArgument(password)
            val encodedName = encodeNavigationArgument(serverName)
            val encodedServerId = encodeNavigationArgument(serverId)
            return "sessions?serverUrl=$encodedUrl&username=$encodedUsername&password=$encodedPassword&serverName=$encodedName&serverId=$encodedServerId&allowSelfSigned=$allowSelfSigned"
        }
    }
    
    data object Chat : Screen("chat") {
        fun createRoute(
            serverUrl: String,
            username: String,
            password: String,
            serverName: String,
            serverId: String,
            sessionId: String,
            openTerminal: Boolean = false,
            allowSelfSigned: Boolean = false,
        ): String {
            val encodedUrl = encodeNavigationArgument(serverUrl)
            val encodedUsername = encodeNavigationArgument(username)
            val encodedPassword = encodeNavigationArgument(password)
            val encodedName = encodeNavigationArgument(serverName)
            val encodedServerId = encodeNavigationArgument(serverId)
            val encodedSessionId = encodeNavigationArgument(sessionId)
            return "chat?serverUrl=$encodedUrl&username=$encodedUsername&password=$encodedPassword&serverName=$encodedName&serverId=$encodedServerId&sessionId=$encodedSessionId&openTerminal=$openTerminal&allowSelfSigned=$allowSelfSigned"
        }
    }

    data object ServerSettings : Screen("server_settings") {
        fun createRoute(
            serverUrl: String,
            username: String,
            password: String,
            serverName: String,
            serverId: String,
            allowSelfSigned: Boolean = false,
        ): String {
            val encodedUrl = encodeNavigationArgument(serverUrl)
            val encodedUsername = encodeNavigationArgument(username)
            val encodedPassword = encodeNavigationArgument(password)
            val encodedName = encodeNavigationArgument(serverName)
            val encodedServerId = encodeNavigationArgument(serverId)
            return "server_settings?serverUrl=$encodedUrl&username=$encodedUsername&password=$encodedPassword&serverName=$encodedName&serverId=$encodedServerId&allowSelfSigned=$allowSelfSigned"
        }
    }

    data object ServerProviders : Screen("server_providers") {
        fun createRoute(
            serverUrl: String,
            username: String,
            password: String,
            serverName: String,
            serverId: String,
            allowSelfSigned: Boolean = false,
        ): String {
            val encodedUrl = encodeNavigationArgument(serverUrl)
            val encodedUsername = encodeNavigationArgument(username)
            val encodedPassword = encodeNavigationArgument(password)
            val encodedName = encodeNavigationArgument(serverName)
            val encodedServerId = encodeNavigationArgument(serverId)
            return "server_providers?serverUrl=$encodedUrl&username=$encodedUsername&password=$encodedPassword&serverName=$encodedName&serverId=$encodedServerId&allowSelfSigned=$allowSelfSigned"
        }
    }

    data object ServerModelFilter : Screen("server_model_filter") {
        fun createRoute(
            serverUrl: String,
            username: String,
            password: String,
            serverName: String,
            serverId: String,
            allowSelfSigned: Boolean = false,
        ): String {
            val encodedUrl = encodeNavigationArgument(serverUrl)
            val encodedUsername = encodeNavigationArgument(username)
            val encodedPassword = encodeNavigationArgument(password)
            val encodedName = encodeNavigationArgument(serverName)
            val encodedServerId = encodeNavigationArgument(serverId)
            return "server_model_filter?serverUrl=$encodedUrl&username=$encodedUsername&password=$encodedPassword&serverName=$encodedName&serverId=$encodedServerId&allowSelfSigned=$allowSelfSigned"
        }
    }

    data object ServerMcp : Screen("server_mcp") {
        fun createRoute(
            serverUrl: String,
            username: String,
            password: String,
            serverName: String,
            serverId: String,
            allowSelfSigned: Boolean = false,
        ): String {
            val encodedUrl = encodeNavigationArgument(serverUrl)
            val encodedUsername = encodeNavigationArgument(username)
            val encodedPassword = encodeNavigationArgument(password)
            val encodedName = encodeNavigationArgument(serverName)
            val encodedServerId = encodeNavigationArgument(serverId)
            return "server_mcp?serverUrl=$encodedUrl&username=$encodedUsername&password=$encodedPassword&serverName=$encodedName&serverId=$encodedServerId&allowSelfSigned=$allowSelfSigned"
        }
    }
    
    data object Settings : Screen("settings")
    data object SyncSettings : Screen("sync_settings")
    data object Diagnostics : Screen("diagnostics")
    data object About : Screen("about")
}
