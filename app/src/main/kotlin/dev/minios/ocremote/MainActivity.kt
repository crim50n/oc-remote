package dev.minios.ocremote

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.data.repository.ServerRepository
import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.service.OpenCodeConnectionService
import dev.minios.ocremote.ui.navigation.NavGraph
import dev.minios.ocremote.ui.theme.OpenCodeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    
    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var serverRepository: ServerRepository

    @Inject
    lateinit var eventReducer: EventReducer
    
    /**
     * Shared flow for deep-link events from notification taps.
     * NavGraph subscribes and navigates to WebView when a value is emitted.
     */
    private val _deepLinkFlow = MutableSharedFlow<SessionDeepLink>(extraBufferCapacity = 1)
    val deepLinkFlow = _deepLinkFlow.asSharedFlow()

    /**
     * Shared flow for images received via ACTION_SEND / ACTION_SEND_MULTIPLE.
     * NavGraph / ChatScreen consumes these to pre-populate attachments.
     * Uses replay=1 so a late subscriber (ChatScreen opened after share) still gets the URIs.
     */
    private val _sharedImagesFlow = MutableSharedFlow<List<Uri>>(replay = 1)
    val sharedImagesFlow = _sharedImagesFlow.asSharedFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // Apply language preference
        lifecycleScope.launch {
            settingsRepository.appLanguage.collect { languageCode ->
                val localeList = if (languageCode.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(languageCode)
                }
                AppCompatDelegate.setApplicationLocales(localeList)
            }
        }
        
        // Handle notification tap that launched the activity
        handleSessionIntent(intent)
        // Handle image share that launched the activity
        handleShareIntent(intent)
        
        setContent {
            // Collect theme preference
            val appTheme by settingsRepository.appTheme.collectAsState(initial = "system")
            
            // Determine if dark theme should be used
            val systemDarkTheme = isSystemInDarkTheme()
            val darkTheme = when (appTheme) {
                "light" -> false
                "dark" -> true
                else -> systemDarkTheme
            }
            
            OpenCodeTheme(darkTheme = darkTheme) {
                
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
                    NavGraph(
                        deepLinkFlow = deepLinkFlow,
                        sharedImagesFlow = sharedImagesFlow,
                        settingsRepository = settingsRepository,
                        serverRepository = serverRepository,
                        eventReducer = eventReducer
                    )
                }
            }
        }
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle notification tap when activity is already running
        handleSessionIntent(intent)
        // Handle image share when activity is already running
        handleShareIntent(intent)
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

    /**
     * Handle ACTION_SEND and ACTION_SEND_MULTIPLE with image content.
     * Extracts image URIs and emits them via [sharedImagesFlow].
     * The URIs are content:// URIs that remain readable while the Activity is alive.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return

        val uris = mutableListOf<Uri>()

        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("image/") == true) {
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    uri?.let { uris.add(it) }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (intent.type?.startsWith("image/") == true) {
                    val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                    }
                    list?.let { uris.addAll(it) }
                }
            }
            else -> return
        }

        if (uris.isNotEmpty()) {
            // Take persistable read permission so URIs survive configuration changes
            for (uri in uris) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    // Not all providers support persistable permissions — that's OK,
                    // the temporary grant from the share intent is still valid.
                }
            }
            Log.i(TAG, "Received ${uris.size} shared image(s)")
            _sharedImagesFlow.tryEmit(uris)
        }
    }
}
