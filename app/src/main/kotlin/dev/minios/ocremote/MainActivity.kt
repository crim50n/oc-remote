package dev.minios.ocremote

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import dev.minios.ocremote.service.OpenCodeConnectionService
import dev.minios.ocremote.ui.navigation.NavGraph
import dev.minios.ocremote.ui.theme.OpenCodeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

private const val TAG = "MainActivity"

/**
 * Pending deep-link info from notification tap.
 * NavGraph picks this up to navigate to WebView with the correct session URL.
 */
data class SessionDeepLink(
    val serverUrl: String,
    val username: String,
    val password: String,
    val serverName: String,
    val sessionPath: String  // e.g. /L2hvbWUv.../session/abc123
)

/**
 * Main Activity - Single Activity architecture with Jetpack Compose
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    /**
     * Shared flow for deep-link events from notification taps.
     * NavGraph subscribes and navigates to WebView when a value is emitted.
     */
    private val _deepLinkFlow = MutableSharedFlow<SessionDeepLink>(extraBufferCapacity = 1)
    val deepLinkFlow = _deepLinkFlow.asSharedFlow()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Handle notification tap that launched the activity
        handleSessionIntent(intent)
        
        setContent {
            OpenCodeTheme {
                val darkTheme = isSystemInDarkTheme()
                
                // Set status bar color based on theme
                SideEffect {
                    val window = this.window
                    window.statusBarColor = Color.Transparent.toArgb()
                    window.navigationBarColor = Color.Transparent.toArgb()
                    
                    val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                    insetsController.isAppearanceLightStatusBars = !darkTheme
                    insetsController.isAppearanceLightNavigationBars = !darkTheme
                }
                
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    NavGraph(deepLinkFlow = deepLinkFlow)
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle notification tap when activity is already running
        handleSessionIntent(intent)
    }
    
    private fun handleSessionIntent(intent: Intent?) {
        if (intent?.action != OpenCodeConnectionService.ACTION_OPEN_SESSION) return
        
        val serverUrl = intent.getStringExtra(OpenCodeConnectionService.EXTRA_SERVER_URL) ?: return
        val username = intent.getStringExtra(OpenCodeConnectionService.EXTRA_SERVER_USERNAME) ?: ""
        val password = intent.getStringExtra(OpenCodeConnectionService.EXTRA_SERVER_PASSWORD) ?: ""
        val serverName = intent.getStringExtra(OpenCodeConnectionService.EXTRA_SERVER_NAME) ?: serverUrl
        val sessionPath = intent.getStringExtra(OpenCodeConnectionService.EXTRA_SESSION_PATH) ?: ""
        
        Log.i(TAG, "Session deep-link: $serverUrl$sessionPath")
        
        _deepLinkFlow.tryEmit(
            SessionDeepLink(
                serverUrl = serverUrl,
                username = username,
                password = password,
                serverName = serverName,
                sessionPath = sessionPath
            )
        )
    }
}
