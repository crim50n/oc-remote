package dev.minios.ocremote.ui.navigation

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.minios.ocremote.SessionDeepLink
import dev.minios.ocremote.ui.screens.home.HomeScreen
import dev.minios.ocremote.ui.screens.webview.WebViewScreen
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.net.URLDecoder

private const val TAG = "NavGraph"

/**
 * Main navigation graph for the app
 */
@Composable
fun NavGraph(deepLinkFlow: SharedFlow<SessionDeepLink>) {
    val navController = rememberNavController()
    
    // Flow to tell the *existing* WebView to navigate to a new URL
    // (used when deep-link arrives while WebView is already on screen)
    val webViewNavigateFlow = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }
    
    // Listen for deep-link events from notification taps
    LaunchedEffect(Unit) {
        deepLinkFlow.collect { deepLink ->
            val currentRoute = navController.currentDestination?.route
            Log.d(TAG, "Deep-link received: sessionPath=${deepLink.sessionPath}, currentRoute=$currentRoute")
            
            val isWebViewOnScreen = currentRoute?.startsWith("webview") == true
            
            if (isWebViewOnScreen && deepLink.sessionPath.isNotBlank()) {
                // WebView is already showing — directly tell it to load the new URL
                val newUrl = deepLink.serverUrl.trimEnd('/') + deepLink.sessionPath
                Log.i(TAG, "WebView already on screen, navigating in-place to: $newUrl")
                webViewNavigateFlow.tryEmit(newUrl)
            } else {
                // WebView is not on screen — navigate to it via Jetpack Navigation
                val route = Screen.WebView.createRoute(
                    serverUrl = deepLink.serverUrl,
                    username = deepLink.username,
                    password = deepLink.password,
                    serverName = deepLink.serverName,
                    initialPath = deepLink.sessionPath
                )
                Log.i(TAG, "Navigating to WebView: $route")
                navController.navigate(route) {
                    launchSingleTop = true
                }
            }
        }
    }
    
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToWebView = { serverUrl, username, password, serverName ->
                    navController.navigate(
                        Screen.WebView.createRoute(serverUrl, username, password, serverName)
                    )
                }
            )
        }
        
        composable(
            route = "webview?serverUrl={serverUrl}&username={username}&password={password}&serverName={serverName}&initialPath={initialPath}",
            arguments = listOf(
                navArgument("serverUrl") { 
                    type = NavType.StringType
                    nullable = false
                },
                navArgument("username") { 
                    type = NavType.StringType
                    nullable = false
                },
                navArgument("password") { 
                    type = NavType.StringType
                    nullable = false
                },
                navArgument("serverName") { 
                    type = NavType.StringType
                    nullable = false
                },
                navArgument("initialPath") {
                    type = NavType.StringType
                    defaultValue = ""
                }
            )
        ) { backStackEntry ->
            val serverUrl = URLDecoder.decode(
                backStackEntry.arguments?.getString("serverUrl") ?: "", "UTF-8"
            )
            val username = URLDecoder.decode(
                backStackEntry.arguments?.getString("username") ?: "", "UTF-8"
            )
            val password = URLDecoder.decode(
                backStackEntry.arguments?.getString("password") ?: "", "UTF-8"
            )
            val serverName = URLDecoder.decode(
                backStackEntry.arguments?.getString("serverName") ?: "", "UTF-8"
            )
            val initialPath = URLDecoder.decode(
                backStackEntry.arguments?.getString("initialPath") ?: "", "UTF-8"
            )
            
            WebViewScreen(
                serverUrl = serverUrl,
                username = username,
                password = password,
                serverName = serverName,
                initialPath = initialPath,
                navigateUrlFlow = webViewNavigateFlow,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
