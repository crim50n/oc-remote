package dev.minios.ocremote.ui.navigation

import android.net.Uri
import android.util.Log
import dev.minios.ocremote.BuildConfig
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.R
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.minios.ocremote.SessionDeepLink
import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.data.repository.ServerRepository
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.ui.screens.chat.ChatScreen
import dev.minios.ocremote.ui.screens.home.HomeScreen
import dev.minios.ocremote.ui.screens.sessions.SessionListScreen
import dev.minios.ocremote.ui.screens.settings.SettingsScreen
import dev.minios.ocremote.ui.screens.webview.WebViewScreen
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.firstOrNull
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "NavGraph"

/**
 * Main navigation graph for the app
 */
@Composable
fun NavGraph(
    deepLinkFlow: SharedFlow<SessionDeepLink>,
    sharedImagesFlow: SharedFlow<List<Uri>>,
    settingsRepository: SettingsRepository,
    serverRepository: ServerRepository,
    eventReducer: EventReducer
) {
    val navController = rememberNavController()
    
    // Use native UI by default (WebView is legacy)
    val useNativeUi = true
    
    // Flow to tell the *existing* WebView to navigate to a new URL
    // (used when deep-link arrives while WebView is already on screen)
    val webViewNavigateFlow = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }

    // ============ Share Target Picker state ============
    var showSharePicker by remember { mutableStateOf(false) }
    var pendingShareUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    // Target session that should receive the shared images (null = not yet chosen)
    var pendingShareSessionId by remember { mutableStateOf<String?>(null) }
    // Data for the picker dialog
    var sharePickerServers by remember { mutableStateOf<List<ServerConfig>>(emptyList()) }
    var sharePickerSessions by remember { mutableStateOf<List<Session>>(emptyList()) }
    var sharePickerServerSessions by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }

    // Listen for shared images
    LaunchedEffect(Unit) {
        sharedImagesFlow.collect { uris ->
            if (uris.isEmpty()) return@collect
            Log.i(TAG, "Shared images received: ${uris.size} URIs")

            // Store pending URIs (will be consumed by the target ChatScreen)
            pendingShareUris = uris
            pendingShareSessionId = null

            // If we're already in a ChatScreen, target the current session directly
            val currentRoute = navController.currentDestination?.route
            if (currentRoute?.startsWith("chat") == true) {
                val currentSessionId = navController.currentBackStackEntry
                    ?.arguments?.getString("sessionId")
                    ?.let { URLDecoder.decode(it, "UTF-8") }
                if (currentSessionId != null) {
                    Log.i(TAG, "Already in ChatScreen for session $currentSessionId, targeting it directly")
                    pendingShareSessionId = currentSessionId
                    return@collect
                }
            }

            // Otherwise, show the session picker
            sharePickerServers = serverRepository.servers.firstOrNull() ?: emptyList()
            sharePickerSessions = eventReducer.sessions.value
            sharePickerServerSessions = eventReducer.serverSessions.value
            showSharePicker = true
        }
    }

    // Share Target Picker Dialog
    if (showSharePicker && pendingShareUris.isNotEmpty()) {
        ShareTargetPickerDialog(
            servers = sharePickerServers,
            sessions = sharePickerSessions,
            serverSessions = sharePickerServerSessions,
            imageCount = pendingShareUris.size,
            onSelectSession = { server, session ->
                showSharePicker = false
                pendingShareSessionId = session.id
                val route = Screen.Chat.createRoute(
                    serverUrl = server.url,
                    username = server.username,
                    password = server.password ?: "",
                    serverName = server.displayName,
                    serverId = server.id,
                    sessionId = session.id
                )
                Log.i(TAG, "Share → navigating to session ${session.id} on ${server.displayName}")
                navController.navigate(route) { launchSingleTop = true }
            },
            onNewSession = { server ->
                showSharePicker = false
                // Navigate to session list — user can create a new session there.
                // Images remain in the flow and will be consumed when ChatScreen opens.
                val route = Screen.SessionList.createRoute(
                    serverUrl = server.url,
                    username = server.username,
                    password = server.password ?: "",
                    serverName = server.displayName,
                    serverId = server.id
                )
                Log.i(TAG, "Share → navigating to session list on ${server.displayName}")
                navController.navigate(route) { launchSingleTop = true }
            },
            onDismiss = {
                showSharePicker = false
                pendingShareUris = emptyList()
            }
        )
    }

    // Listen for deep-link events from notification taps
    LaunchedEffect(Unit) {
        deepLinkFlow.collect { deepLink ->
            val currentRoute = navController.currentDestination?.route
            if (BuildConfig.DEBUG) Log.d(TAG, "Deep-link received: sessionPath=${deepLink.sessionPath}, currentRoute=$currentRoute, useNativeUi=$useNativeUi")
            
            if (useNativeUi) {
                // ---- Native UI path ----
                // Deep-links carry a sessionPath like /L2hvbWUv.../session/<sessionId>
                // Extract the sessionId from the path if present
                val sessionId = deepLink.sessionPath
                    .trimEnd('/')
                    .substringAfterLast("/session/", "")
                    .takeIf { it.isNotBlank() }

                if (sessionId != null) {
                    // Navigate directly into the chat for this session
                    val route = Screen.Chat.createRoute(
                        serverUrl = deepLink.serverUrl,
                        username = deepLink.username,
                        password = deepLink.password,
                        serverName = deepLink.serverName,
                        serverId = "", // not available from deep-link; ViewModel handles it
                        sessionId = sessionId
                    )
                    Log.i(TAG, "Deep-link → native Chat: $route")
                    navController.navigate(route) { launchSingleTop = true }
                } else {
                    // No specific session — open session list (placeholder; the
                    // user can also just stay on Home if preferred)
                    Log.i(TAG, "Deep-link has no sessionId, ignoring native path")
                }
            } else {
                // ---- WebView path (legacy) ----
                val isWebViewOnScreen = currentRoute?.startsWith("webview") == true
                
                if (isWebViewOnScreen && deepLink.sessionPath.isNotBlank()) {
                    val newUrl = deepLink.serverUrl.trimEnd('/') + deepLink.sessionPath
                    Log.i(TAG, "WebView already on screen, navigating in-place to: $newUrl")
                    webViewNavigateFlow.tryEmit(newUrl)
                } else {
                    val route = Screen.WebView.createRoute(
                        serverUrl = deepLink.serverUrl,
                        username = deepLink.username,
                        password = deepLink.password,
                        serverName = deepLink.serverName,
                        initialPath = deepLink.sessionPath
                    )
                    Log.i(TAG, "Deep-link → WebView: $route")
                    navController.navigate(route) { launchSingleTop = true }
                }
            }
        }
    }
    
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        // ============ Home Screen ============
        composable(Screen.Home.route) {
            HomeScreen(
                onNavigateToSessions = { serverUrl, username, password, serverName, serverId ->
                    navController.navigate(
                        Screen.SessionList.createRoute(serverUrl, username, password, serverName, serverId)
                    )
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }
        
        // ============ Settings Screen ============
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        // ============ WebView Screen (legacy) ============
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
        
        // ============ Session List Screen (native) ============
        composable(
            route = "sessions?serverUrl={serverUrl}&username={username}&password={password}&serverName={serverName}&serverId={serverId}",
            arguments = listOf(
                navArgument("serverUrl") { type = NavType.StringType },
                navArgument("username") { type = NavType.StringType },
                navArgument("password") { type = NavType.StringType },
                navArgument("serverName") { type = NavType.StringType },
                navArgument("serverId") { type = NavType.StringType }
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
            val serverId = URLDecoder.decode(
                backStackEntry.arguments?.getString("serverId") ?: "", "UTF-8"
            )

            SessionListScreen(
                onNavigateToChat = { sessionId ->
                    navController.navigate(
                        Screen.Chat.createRoute(
                            serverUrl = serverUrl,
                            username = username,
                            password = password,
                            serverName = serverName,
                            serverId = serverId,
                            sessionId = sessionId
                        )
                    )
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        
        // ============ Chat Screen (native) ============
        composable(
            route = "chat?serverUrl={serverUrl}&username={username}&password={password}&serverName={serverName}&serverId={serverId}&sessionId={sessionId}",
            arguments = listOf(
                navArgument("serverUrl") { type = NavType.StringType },
                navArgument("username") { type = NavType.StringType },
                navArgument("password") { type = NavType.StringType },
                navArgument("serverName") { type = NavType.StringType },
                navArgument("serverId") { type = NavType.StringType },
                navArgument("sessionId") { type = NavType.StringType }
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
            val serverId = URLDecoder.decode(
                backStackEntry.arguments?.getString("serverId") ?: "", "UTF-8"
            )
            val sessionId = URLDecoder.decode(
                backStackEntry.arguments?.getString("sessionId") ?: "", "UTF-8"
            )

            // Only pass shared images to the targeted session, then clear them
            val imagesForThisSession = if (pendingShareSessionId == sessionId && pendingShareUris.isNotEmpty()) {
                pendingShareUris
            } else {
                emptyList()
            }
            
            ChatScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToSession = { newSessionId ->
                    val route = Screen.Chat.createRoute(
                        serverUrl = serverUrl,
                        username = username,
                        password = password,
                        serverName = serverName,
                        serverId = serverId,
                        sessionId = newSessionId
                    )
                    navController.navigate(route) {
                        // Pop current chat so back goes to session list, not old session
                        popUpTo("sessions?serverUrl={serverUrl}&username={username}&password={password}&serverName={serverName}&serverId={serverId}") {
                            inclusive = false
                        }
                    }
                },
                onOpenInWebView = {
                    // Build the session path: /<base64url(directory)>/session/<sessionId>
                    val session = eventReducer.sessions.value.find { it.id == sessionId }
                    val dir = session?.directory ?: ""
                    val encodedDir = android.util.Base64.encodeToString(
                        dir.toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP
                    ).replace('+', '-').replace('/', '_').replace("=", "")
                    val sessionPath = "/$encodedDir/session/$sessionId"
                    val route = Screen.WebView.createRoute(
                        serverUrl = serverUrl,
                        username = username,
                        password = password,
                        serverName = serverName,
                        initialPath = sessionPath
                    )
                    navController.navigate(route) { launchSingleTop = true }
                },
                initialSharedImages = imagesForThisSession,
                onSharedImagesConsumed = {
                    pendingShareUris = emptyList()
                    pendingShareSessionId = null
                }
            )
        }
    }
}

/**
 * Dialog shown when images are shared into the app via ACTION_SEND.
 * Lists recent sessions from servers that have SSE data loaded,
 * grouped by server. User taps a session to open it with the shared image(s).
 */
@Composable
private fun ShareTargetPickerDialog(
    servers: List<ServerConfig>,
    sessions: List<Session>,
    serverSessions: Map<String, Set<String>>,
    imageCount: Int,
    onSelectSession: (server: ServerConfig, session: Session) -> Unit,
    onNewSession: (server: ServerConfig) -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    // Build list of (server, session) pairs, sorted by most recently updated session
    data class PickerItem(val server: ServerConfig, val session: Session)

    val items = remember(servers, sessions, serverSessions) {
        val result = mutableListOf<PickerItem>()
        for (server in servers) {
            val sessionIds = serverSessions[server.id] ?: continue
            val serverSessionList = sessions
                .filter { it.id in sessionIds && !it.isArchived && it.parentId == null }
                .sortedByDescending { it.time.updated }
                .take(15)
            for (session in serverSessionList) {
                result.add(PickerItem(server, session))
            }
        }
        result.sortedByDescending { it.session.time.updated }
    }

    // Servers that have sessions loaded (for the "New session" option)
    val activeServers = remember(servers, serverSessions) {
        servers.filter { serverSessions.containsKey(it.id) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.7f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.share_send_image_to),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (imageCount == 1) 
                                stringResource(R.string.image_count_single)
                            else 
                                stringResource(R.string.image_count_multiple, imageCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                }

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                if (items.isEmpty()) {
                    // No connected servers / sessions
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.CloudOff,
                                contentDescription = null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                            Text(
                                text = stringResource(R.string.share_no_connected_servers),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Text(
                                text = stringResource(R.string.share_connect_first),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }
                    }
                } else {
                    // Session list
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(items, key = { "${it.server.id}/${it.session.id}" }) { item ->
                            val projectName = item.session.directory
                                .trimEnd('/')
                                .substringAfterLast('/')
                                .ifEmpty { null }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectSession(item.server, item.session) }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Chat,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    // Session title
                                    Text(
                                        text = item.session.title ?: stringResource(R.string.session_untitled),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    // Project + server info
                                    val subtitle = buildString {
                                        if (projectName != null) append(projectName)
                                        if (activeServers.size > 1) {
                                            if (isNotEmpty()) append(" · ")
                                            append(item.server.displayName)
                                        }
                                    }
                                    if (subtitle.isNotBlank()) {
                                        Text(
                                            text = subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                // Date
                                Text(
                                    text = dateFormat.format(Date(item.session.time.updated)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }

                // "New session" buttons per active server
                if (activeServers.isNotEmpty()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    for (server in activeServers) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNewSession(server) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                            Text(
                                text = if (activeServers.size > 1)
                                    stringResource(R.string.sessions_new_on_server, server.displayName)
                                else
                                    stringResource(R.string.sessions_new_short),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
