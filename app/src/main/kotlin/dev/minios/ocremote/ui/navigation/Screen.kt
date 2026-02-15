package dev.minios.ocremote.ui.navigation

import java.net.URLEncoder

/**
 * Navigation routes for the app
 */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    
    data object WebView : Screen("webview") {
        fun createRoute(
            serverUrl: String,
            username: String,
            password: String,
            serverName: String,
            initialPath: String = ""
        ): String {
            val encodedUrl = URLEncoder.encode(serverUrl, "UTF-8")
            val encodedUsername = URLEncoder.encode(username, "UTF-8")
            val encodedPassword = URLEncoder.encode(password, "UTF-8")
            val encodedName = URLEncoder.encode(serverName, "UTF-8")
            val encodedPath = URLEncoder.encode(initialPath, "UTF-8")
            return "webview?serverUrl=$encodedUrl&username=$encodedUsername&password=$encodedPassword&serverName=$encodedName&initialPath=$encodedPath"
        }
    }
}
