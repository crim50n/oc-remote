package dev.minios.ocremote.ui.navigation

import com.composables.icons.lucide.*
import dev.minios.ocremote.ui.components.mirrorForRtl

import android.net.Uri
import android.content.Intent
import dev.minios.ocremote.logging.AppLogger as Log
import dev.minios.ocremote.BuildConfig
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
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
import androidx.navigation.compose.currentBackStackEntryAsState
import dev.minios.ocremote.data.repository.SessionWorkspaceRepository
import dev.minios.ocremote.data.repository.SessionTab
import dev.minios.ocremote.data.repository.WorkspaceTab
import dev.minios.ocremote.data.repository.PageTab
import androidx.navigation.navArgument
import dev.minios.ocremote.SessionDeepLink
import dev.minios.ocremote.data.repository.EventReducer
import dev.minios.ocremote.data.repository.ServerRepository
import dev.minios.ocremote.data.repository.normalizeServerUrl
import dev.minios.ocremote.data.repository.SettingsRepository
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.Session
import dev.minios.ocremote.domain.model.SessionCategory
import dev.minios.ocremote.ui.screens.chat.ChatScreen
import dev.minios.ocremote.ui.screens.files.WorkspaceFilesScreen
import dev.minios.ocremote.ui.screens.home.HomeScreen
import dev.minios.ocremote.ui.screens.about.AboutScreen
import dev.minios.ocremote.ui.screens.sessions.SessionListScreen
import dev.minios.ocremote.ui.screens.sessions.CrossServerSessionsScreen
import dev.minios.ocremote.ui.screens.settings.SettingsScreen
import dev.minios.ocremote.ui.components.isAmoledTheme
import dev.minios.ocremote.ui.components.AppPrimaryButton
import dev.minios.ocremote.ui.components.sessionCategoryColor
import dev.minios.ocremote.ui.components.sessionCategoryIcon
import dev.minios.ocremote.ui.screens.settings.DiagnosticsScreen
import dev.minios.ocremote.ui.screens.server.ServerModelFilterScreen
import dev.minios.ocremote.ui.screens.server.ServerMcpScreen
import dev.minios.ocremote.ui.screens.server.ServerProvidersScreen
import dev.minios.ocremote.ui.screens.server.ServerSettingsScreen
import dev.minios.ocremote.ui.screens.webview.WebViewScreen
import dev.minios.ocremote.service.OpenCodeConnectionService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.combine
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "NavGraph"

internal fun connectedShareServers(
    servers: List<ServerConfig>,
    connectedServerIds: Set<String>,
): List<ServerConfig> = servers.filter { it.id in connectedServerIds }

internal data class SharePickerItem(
    val server: ServerConfig,
    val session: Session,
    val isFavorite: Boolean,
    val favoriteIndex: Int?,
    val category: SessionCategory?,
)

internal data class SharePickerServerPreferences(
    val favoriteIds: List<String>,
    val categoryAssignments: Map<String, String>,
)

internal fun shouldReopenSharePicker(
    waitingForConnection: Boolean,
    pickerVisible: Boolean,
    hasPendingAttachments: Boolean,
    targetSessionId: String?,
    hasConnectedServers: Boolean,
): Boolean = waitingForConnection && !pickerVisible && hasPendingAttachments &&
    targetSessionId == null && hasConnectedServers

internal fun buildSharePickerItems(
    servers: List<ServerConfig>,
    sessions: List<Session>,
    serverSessions: Map<String, Set<String>>,
    connectedServerIds: Set<String>,
    preferencesByServer: Map<String, SharePickerServerPreferences>,
    favoriteOrder: List<String>,
    categories: List<SessionCategory>,
): List<SharePickerItem> {
    val storedIndices = favoriteOrder.withIndex().associate { it.value to it.index }
    val categoriesById = categories.associateBy(SessionCategory::id)
    return servers.asSequence()
        .filter { it.id in connectedServerIds }
        .flatMapIndexed { serverIndex, server ->
            val sessionIds = serverSessions[server.id].orEmpty()
            val preferences = preferencesByServer[server.id]
                ?: SharePickerServerPreferences(emptyList(), emptyMap())
            val favoriteIndices = preferences.favoriteIds.withIndex().associate { it.value to it.index }
            val candidates = sessions.filter { session ->
                session.id in sessionIds && !session.isArchived && session.parentId == null
            }
            candidates
                .sortedWith(
                    compareByDescending<Session> { it.id in favoriteIndices }
                        .thenBy { favoriteIndices[it.id] ?: Int.MAX_VALUE }
                        .thenByDescending { it.time.updated },
                )
                .take(maxOf(15, candidates.count { it.id in favoriteIndices }))
                .map { session ->
                    val key = "${server.id}:${session.id}"
                    val localIndex = favoriteIndices[session.id]
                    SharePickerItem(
                        server = server,
                        session = session,
                        isFavorite = localIndex != null,
                        favoriteIndex = localIndex?.let {
                            storedIndices[key] ?: favoriteOrder.size + serverIndex * 10_000 + it
                        },
                        category = preferences.categoryAssignments[session.id]?.let(categoriesById::get),
                    )
                }
        }
        .sortedWith(
            compareByDescending<SharePickerItem>(SharePickerItem::isFavorite)
                .thenBy { it.favoriteIndex ?: Int.MAX_VALUE }
                .thenByDescending { it.session.time.updated },
        )
        .toList()
}

/**
 * Main navigation graph for the app
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NavGraph(
    deepLinkFlow: MutableSharedFlow<SessionDeepLink>,
    syncSettingsFlow: MutableSharedFlow<Unit>,
    sharedAttachmentsFlow: SharedFlow<List<Uri>>,
    settingsRepository: SettingsRepository,
    serverRepository: ServerRepository,
    sessionWorkspace: SessionWorkspaceRepository,
    eventReducer: EventReducer,
    connectedServerIds: Set<String>,
    connectingServerIds: Set<String>,
) {
    val navController = rememberNavController()
    val sessionFeatures = dev.minios.ocremote.ui.components.LocalSessionFeatures.current
    val context = LocalContext.current
    val workspace by sessionWorkspace.state.collectAsState()
    val openTabs = workspace.tabs
    val currentEntry by navController.currentBackStackEntryAsState()
    var showTabs by remember { mutableStateOf(false) }
    var openSessionPicker by remember { mutableStateOf(false) }
    var insertionAnchor by remember { mutableStateOf<String?>(null) }
    var terminalModeActive by remember { mutableStateOf(false) }
    val desktopSnackbar = remember { SnackbarHostState() }
    val navigationScope = rememberCoroutineScope()
    var desktopUndoJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val tabsVisibility = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    tabsVisibility.targetState = showTabs && sessionFeatures.tabs
    var showClosedTabs by remember { mutableStateOf(false) }
    var pendingTabAction by remember { mutableStateOf<Pair<String, SessionTabAction>?>(null) }
    fun connectServer(serverId: String, serverName: String, serverUrl: String, username: String, password: String) {
        val intent = Intent(context, OpenCodeConnectionService::class.java).apply {
            putExtra("server_id", serverId)
            putExtra("server_name", serverName)
            putExtra("server_url", serverUrl)
            putExtra("server_username", username)
            putExtra("server_password", password)
        }
        ContextCompat.startForegroundService(context, intent)
    }
    
    // Use native UI by default (WebView is legacy)
    val useNativeUi = true
    
    // Flow to tell the *existing* WebView to navigate to a new URL
    // (used when deep-link arrives while WebView is already on screen)
    val webViewNavigateFlow = remember { MutableSharedFlow<String>(extraBufferCapacity = 1) }

    // ============ Share Target Picker state ============
    var showSharePicker by remember { mutableStateOf(false) }
    var pendingShareUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    // Target session that should receive the shared attachments (null = not yet chosen)
    var pendingShareSessionId by remember { mutableStateOf<String?>(null) }
    var pendingShareServerId by remember { mutableStateOf<String?>(null) }
    var reopenSharePickerAfterConnect by remember { mutableStateOf(false) }
    var addServerRequest by remember { mutableIntStateOf(0) }
    val sharePickerServers by serverRepository.servers.collectAsState(initial = emptyList())
    val sharePickerSessions by eventReducer.sessions.collectAsState()
    val sessionStatuses by eventReducer.sessionStatuses.collectAsState()
    val pendingInteractions by eventReducer.pendingInteractions.collectAsState()
    val sharePickerServerSessions by eventReducer.serverSessions.collectAsState()
    val sharePickerFavoriteOrder by settingsRepository.crossServerFavoriteOrder.collectAsState(initial = emptyList())
    val sharePickerCategories by settingsRepository.sessionCategories.collectAsState(initial = emptyList())
    val sharePickerPreferences by produceState<Map<String, SharePickerServerPreferences>>(
        initialValue = emptyMap(),
        key1 = sharePickerServers,
    ) {
        if (sharePickerServers.isEmpty()) {
            value = emptyMap()
        } else {
            combine(
                sharePickerServers.map { server ->
                    combine(
                        settingsRepository.favoriteSessionIds(server.id),
                        settingsRepository.sessionCategoryAssignments(server.id),
                    ) { favoriteIds, assignments ->
                        server.id to SharePickerServerPreferences(favoriteIds, assignments)
                    }
                },
            ) { values -> values.toMap() }.collect { value = it }
        }
    }
    val currentConnectedServerIds by rememberUpdatedState(connectedServerIds)

    val currentPage = pageTabForEntry(currentEntry, sharePickerServers, context)

    fun activateTab(tab: WorkspaceTab) {
        if (tab is PageTab) {
            val route = workspacePageRoute(tab, sharePickerServers) ?: return
            val server = sharePickerServers.firstOrNull { it.id == tab.serverId }
            if (server != null && server.id !in connectedServerIds && server.id !in connectingServerIds) {
                connectServer(server.id, server.displayName, server.url, server.username, server.password.orEmpty())
            }
            if (pageTabForEntry(navController.currentBackStackEntry, sharePickerServers, context)?.key != tab.key) {
                // Different page keys can share a route definition (e.g. two project folders).
                // They need separate SavedStateHandles/ViewModels, not singleTop argument replacement.
                if (!navController.popBackStack(route, false)) navController.navigate(route)
            }
            sessionWorkspace.openPage(tab)
            showTabs = false
            return
        }
        tab as SessionTab
        val server = sharePickerServers.firstOrNull { it.id == tab.serverId } ?: return
        if (server.id !in connectedServerIds && server.id !in connectingServerIds) {
            connectServer(server.id, server.displayName, server.url, server.username, server.password.orEmpty())
        }
        val route = Screen.Chat.createRoute(server.url, server.username, server.password.orEmpty(),
            server.displayName, server.id, tab.sessionId)
        val entry = navController.currentBackStackEntry
        if (entry?.destination?.route?.startsWith("chat?") == true) {
            if (entry.arguments?.getString("serverId") == tab.serverId &&
                entry.arguments?.getString("sessionId") == tab.sessionId) {
                showTabs = false
                return
            }
            navController.popBackStack()
        }
        navController.navigate(route) { launchSingleTop = true }
        showTabs = false
    }

    fun restoreClosedTab(key: String? = null, sessionsOnly: Boolean = false) {
        val entry = sessionWorkspace.state.value.closed.lastOrNull { entry ->
            (key == null || entry.tab.key == key) && (!sessionsOnly || entry.tab is SessionTab) && workspaceTabAvailable(entry.tab, sharePickerServers)
        } ?: return
        sessionWorkspace.restoreClosed(entry.tab.key)?.let(::activateTab)
    }

    fun closeTabs(keys: Set<String>, keepSwitcher: Boolean = false, notify: Boolean = true) {
        if (keys.isEmpty()) return
        val entry = navController.currentBackStackEntry
        val activeKey = pageTabForEntry(entry, sharePickerServers, context)?.key
            ?: entry?.takeIf { it.destination.route?.startsWith("chat?") == true }?.arguments
                ?.let { "${it.getString("serverId")}:${it.getString("sessionId")}" }
        val nextKey = sessionWorkspace.closeTabs(keys, activeKey, sessionsOnly = keepSwitcher)
        if (activeKey != nextKey) {
            val available = sessionWorkspace.tabs.filter { (!keepSwitcher || it is SessionTab) && workspaceTabAvailable(it, sharePickerServers) }
            val next = available.firstOrNull { it.key == nextKey } ?: available.firstOrNull()
            if (next != null) activateTab(next) else navController.popBackStack(Screen.Home.route, false)
        }
        if (keepSwitcher) showTabs = true
        if (!notify) return
        desktopUndoJob?.cancel()
        desktopSnackbar.currentSnackbarData?.dismiss()
        desktopUndoJob = navigationScope.launch {
            val message = if (keys.size == 1) R.string.session_tab_closed else R.string.tabs_closed
            if (desktopSnackbar.showSnackbar(context.getString(message), context.getString(R.string.chat_restore),
                    duration = SnackbarDuration.Short) == SnackbarResult.ActionPerformed) {
                keys.forEach { sessionWorkspace.restoreClosed(it) }
                sessionWorkspace.tabs.firstOrNull { it.key == activeKey }?.let(::activateTab)
            }
        }
    }

    fun tabAction(tab: WorkspaceTab, action: SessionTabAction) {
        val kind = when (action) {
            SessionTabAction.CLOSE -> dev.minios.ocremote.data.repository.TabCloseKind.SINGLE
            SessionTabAction.CLOSE_OTHERS -> dev.minios.ocremote.data.repository.TabCloseKind.OTHERS
            SessionTabAction.CLOSE_RIGHT -> dev.minios.ocremote.data.repository.TabCloseKind.RIGHT
            else -> null
        }
        if (kind != null) { closeTabs(sessionWorkspace.state.value.closeKeys(tab.key, kind, sessionsOnly = showTabs), showTabs); return }
        when (action) {
            SessionTabAction.OPEN_RIGHT -> { insertionAnchor = tab.key; openSessionPicker = true; showTabs = true }
            SessionTabAction.PIN -> sessionWorkspace.pin(tab.key, !tab.pinned)
            SessionTabAction.REOPEN -> restoreClosedTab(sessionsOnly = showTabs)
            SessionTabAction.HISTORY -> showClosedTabs = true
            else -> { activateTab(tab); pendingTabAction = tab.key to action }
        }
    }

    LaunchedEffect(currentEntry, sessionFeatures.tabs) {
        if (currentEntry?.destination?.route?.startsWith("chat?") != true) terminalModeActive = false
        if (!sessionFeatures.tabs) {
            showTabs = false
            return@LaunchedEffect
        }
        val entry = currentEntry ?: return@LaunchedEffect
        if (entry.destination.route?.startsWith("chat?") == true) {
            val serverId = entry.arguments?.getString("serverId").orEmpty()
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            if (serverId.isNotBlank() && sessionId.isNotBlank()) {
                sessionWorkspace.open(serverId, sessionId,
                    sharePickerSessions.firstOrNull { it.id == sessionId }?.title.orEmpty())
                val retained = sessionWorkspace.tabs.filterIsInstance<SessionTab>().sortedByDescending { it.lastActive }
                    .take(3).mapTo(mutableSetOf()) { it.sessionId }
                sessionWorkspace.tabs.filterIsInstance<SessionTab>().filter { it.sessionId !in retained }.forEach { tab ->
                    val busy = sessionStatuses[tab.sessionId]
                    if (tab.serverId in connectedServerIds &&
                        busy !is dev.minios.ocremote.domain.model.SessionStatus.Busy &&
                        busy !is dev.minios.ocremote.domain.model.SessionStatus.Retry &&
                        pendingInteractions.none { it.sessionId == tab.sessionId } &&
                        eventReducer.promptDeliveries.value.values.none { it.sessionId == tab.sessionId }) {
                        eventReducer.clearSessionHistory(tab.sessionId)
                    }
                }
            }
        }
    }

    @Composable
    fun TabsOverlay() {
    androidx.compose.animation.AnimatedVisibility(
        visibleState = tabsVisibility,
        modifier = Modifier.fillMaxSize(),
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
            androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(220), initialScale = 0.97f),
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)) +
            androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(180), targetScale = 0.98f),
    ) {
        SessionTabsSheet(
            tabs = openTabs.filterIsInstance<SessionTab>(),
            servers = sharePickerServers,
            sessions = sharePickerSessions,
            statuses = sessionStatuses,
            attentionSessionIds = pendingInteractions.mapTo(mutableSetOf()) { it.sessionId },
            connectedServerIds = connectedServerIds,
            activeKey = currentPage?.key ?: currentEntry?.arguments?.let { "${it.getString("serverId")}:${it.getString("sessionId")}" },
            canUndo = workspace.closed.any { it.tab is SessionTab },
            onSelect = { tab ->
                insertionAnchor?.let { sessionWorkspace.openAfter(tab, it) }
                insertionAnchor = null
                activateTab(tab)
            },
            onClose = { tab -> closeTabs(setOf(tab.key), keepSwitcher = true, notify = false) },
            onUndo = { restoreClosedTab(sessionsOnly = true) },
            onMove = { tab, delta -> sessionWorkspace.moveSession(tab.key, delta) },
            onAdd = {
                showTabs = false
                navController.navigate(Screen.Home.route) { popUpTo(Screen.Home.route); launchSingleTop = true }
            },
            onDismiss = { showTabs = false; insertionAnchor = null },
            onOrder = sessionWorkspace::reorderSessions,
            serverSessions = sharePickerServerSessions,
            openSessionPicker = openSessionPicker,
            onSessionPickerOpened = { openSessionPicker = false },
            onPickerDismissed = { insertionAnchor = null },
            onTabAction = ::tabAction,
            onHistory = { showClosedTabs = true },
        )
    }
    }

    // Listen for shared attachments
    LaunchedEffect(Unit) {
        sharedAttachmentsFlow.collect { uris ->
            if (uris.isEmpty()) return@collect
            Log.i(TAG, "Shared attachments received: ${uris.size} URIs")

            // Store pending URIs (will be consumed by the target ChatScreen)
            pendingShareUris = uris
            pendingShareSessionId = null
            pendingShareServerId = null
            reopenSharePickerAfterConnect = false

            // If we're already in a ChatScreen, target the current session directly
            val currentRoute = navController.currentDestination?.route
            if (currentRoute?.startsWith("chat") == true) {
                val currentSessionId = navController.currentBackStackEntry
                    ?.arguments?.getString("sessionId")
                val currentServerId = navController.currentBackStackEntry
                    ?.arguments?.getString("serverId")
                if (currentSessionId != null && currentServerId in currentConnectedServerIds) {
                    Log.i(TAG, "Already in ChatScreen for session $currentSessionId, targeting it directly")
                    pendingShareSessionId = currentSessionId
                    pendingShareServerId = currentServerId
                    return@collect
                }
            }

            // Otherwise, show the session picker. Its server/session data stays reactive.
            showSharePicker = true
        }
    }

    // Keep a shared attachment pending while the user connects a server from Home.
    LaunchedEffect(
        reopenSharePickerAfterConnect,
        showSharePicker,
        pendingShareUris,
        pendingShareSessionId,
        connectedServerIds,
    ) {
        if (shouldReopenSharePicker(
                waitingForConnection = reopenSharePickerAfterConnect,
                pickerVisible = showSharePicker,
                hasPendingAttachments = pendingShareUris.isNotEmpty(),
                targetSessionId = pendingShareSessionId,
                hasConnectedServers = connectedServerIds.isNotEmpty(),
            )
        ) {
            reopenSharePickerAfterConnect = false
            showSharePicker = true
        }
    }

    // Share Target Picker Dialog
    if (showSharePicker && pendingShareUris.isNotEmpty()) {
        ShareTargetPickerDialog(
            servers = sharePickerServers,
            sessions = sharePickerSessions,
            serverSessions = sharePickerServerSessions,
            connectedServerIds = connectedServerIds,
            preferencesByServer = sharePickerPreferences.mapValues { (_, preferences) ->
                preferences.copy(
                    favoriteIds = if (sessionFeatures.favorites) preferences.favoriteIds else emptyList(),
                    categoryAssignments = if (sessionFeatures.categories) preferences.categoryAssignments else emptyMap(),
                )
            },
            favoriteOrder = if (sessionFeatures.favorites) sharePickerFavoriteOrder else emptyList(),
            categories = if (sessionFeatures.categories) sharePickerCategories else emptyList(),
            attachmentCount = pendingShareUris.size,
            onSelectSession = { server, session ->
                showSharePicker = false
                reopenSharePickerAfterConnect = false
                pendingShareSessionId = session.id
                pendingShareServerId = server.id
                val route = Screen.Chat.createRoute(
                    serverUrl = server.url,
                    username = server.username,
                    password = server.password ?: "",
                    serverName = server.displayName,
                    serverId = server.id,
                    sessionId = session.id
                )
                Log.i(TAG, "Share → navigating to session ${session.id} on ${server.displayName}")
                if (navController.currentDestination?.route?.startsWith("chat?") == true) navController.popBackStack()
                navController.navigate(route) { launchSingleTop = true }
            },
            onNewSession = { server ->
                showSharePicker = false
                reopenSharePickerAfterConnect = false
                // Navigate to session list — user can create a new session there.
                // Attachments remain pending and will be consumed when ChatScreen opens.
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
            onManageServers = {
                showSharePicker = false
                reopenSharePickerAfterConnect = connectedServerIds.isEmpty()
                navController.navigate(Screen.Home.route) {
                    launchSingleTop = true
                    popUpTo(Screen.Home.route)
                }
                if (sharePickerServers.isEmpty()) addServerRequest++
            },
            onDismiss = {
                showSharePicker = false
                reopenSharePickerAfterConnect = false
                pendingShareUris = emptyList()
            }
        )
    }

    // Listen for deep-link events from notification taps
    LaunchedEffect(Unit) {
        deepLinkFlow.collect { deepLink ->
            // Consume the event so it's not replayed on recomposition
            deepLinkFlow.resetReplayCache()
            val currentRoute = navController.currentDestination?.route
            if (BuildConfig.DEBUG) Log.d(TAG, "Deep-link received: sessionPath=${deepLink.sessionPath}, sessionId=${deepLink.sessionId}, currentRoute=$currentRoute, useNativeUi=$useNativeUi")
            
            if (useNativeUi) {
                // ---- Native UI path ----
                // Deep-links carry a sessionPath like /L2hvbWUv.../session/<sessionId>
                // Extract the sessionId from the path if present, fall back to raw sessionId
                val sessionId = deepLink.sessionPath
                    .trimEnd('/')
                    .substringAfterLast("/session/", "")
                    .takeIf { it.isNotBlank() }
                    ?: deepLink.sessionId.takeIf { it.isNotBlank() }

                if (sessionId != null) {
                    // Navigate directly into the chat for this session
                    val route = Screen.Chat.createRoute(
                        serverUrl = deepLink.serverUrl,
                        username = deepLink.username,
                        password = deepLink.password,
                        serverName = deepLink.serverName,
                        serverId = deepLink.serverId,
                        sessionId = sessionId
                    )
                    val currentSessionId = navController.currentBackStackEntry
                        ?.arguments
                        ?.getString("sessionId")

                    Log.i(
                        TAG,
                        "Deep-link → native Chat: targetSession=$sessionId currentSession=$currentSessionId"
                    )

                    if (currentRoute?.startsWith("chat") == true && (currentSessionId != sessionId ||
                            navController.currentBackStackEntry?.arguments?.getString("serverId") != deepLink.serverId)) {
                        // Replace current chat screen when switching sessions from notification.
                        // launchSingleTop alone can keep the same top chat destination and skip
                        // visible transition to a different session.
                        navController.popBackStack()
                        navController.navigate(route)
                    } else {
                        navController.navigate(route) { launchSingleTop = true }
                    }
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

    LaunchedEffect(Unit) {
        syncSettingsFlow.collect {
            syncSettingsFlow.resetReplayCache()
            navController.navigate(Screen.SyncSettings.route) { launchSingleTop = true }
        }
    }
    
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // Keep the desktop strip stable when the search field opens the soft keyboard.
    val desktopTabs = maxWidth >= 840.dp
    LaunchedEffect(currentPage, desktopTabs, sessionFeatures.tabs) {
        if (desktopTabs && sessionFeatures.tabs && currentPage != null) sessionWorkspace.openPage(currentPage)
    }
    val sessionActive = currentEntry?.destination?.route?.startsWith("chat?") == true
    val activeTabKey = currentPage?.key ?: currentEntry?.arguments?.let { "${it.getString("serverId")}:${it.getString("sessionId")}" }
    Row(Modifier.fillMaxSize().onPreviewKeyEvent { event ->
        if (!desktopTabs || !sessionFeatures.tabs || terminalModeActive || !event.isCtrlPressed || event.type != KeyEventType.KeyDown) false
        else when (event.key) {
            Key.Tab -> {
                val available = openTabs.filter { workspaceTabAvailable(it, sharePickerServers) }
                adjacentSessionTab(available, activeTabKey, event.isShiftPressed)?.let(::activateTab)
                true
            }
            Key.W -> {
                if (event.nativeKeyEvent.repeatCount == 0) openTabs.firstOrNull { it.key == activeTabKey }?.let { closeTabs(setOf(it.key)) }
                true
            }
            Key.T -> if (event.isShiftPressed) { restoreClosedTab(); true } else false
            else -> false
        }
    }) {
    Column(Modifier.weight(1f).fillMaxHeight().then(if (desktopTabs) Modifier.statusBarsPadding() else Modifier)) {
    if (desktopTabs) DesktopSessionTabs(
        tabs = if (sessionFeatures.tabs) openTabs else emptyList(), servers = sharePickerServers, statuses = sessionStatuses,
        attention = pendingInteractions.mapTo(mutableSetOf()) { it.sessionId }, connected = connectedServerIds,
        activeKey = activeTabKey, onSelect = ::activateTab, onClose = { closeTabs(setOf(it.key)) },
        onOrder = sessionWorkspace::reorder,
        onAdd = { insertionAnchor = null; openSessionPicker = true; showTabs = true },
        onHistory = { showClosedTabs = true },
        onMenu = { activeTabKey?.let { pendingTabAction = it to SessionTabAction.OPEN_MENU } },
        onTabAction = ::tabAction, canRestore = workspace.closed.isNotEmpty(), sessions = sharePickerSessions,
        tabsEnabled = sessionFeatures.tabs, sessionActive = sessionActive,
        onSettings = { navController.navigate(Screen.Settings.route) { launchSingleTop = true } },
        onAbout = { navController.navigate(Screen.About.route) { launchSingleTop = true } },
        onServers = { navController.navigate(Screen.Home.route) { popUpTo(Screen.Home.route); launchSingleTop = true } },
        onAddServer = {
            addServerRequest++
            navController.navigate(Screen.Home.route) { popUpTo(Screen.Home.route); launchSingleTop = true }
        },
    )
    NavHost(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        // ============ Home Screen ============
        composable(Screen.UpdateDetails.route) {
            dev.minios.ocremote.ui.screens.update.UpdateDetailsScreen(onNavigateBack = { navController.popBackStack() },
                showTopBar = !desktopTabs || !sessionFeatures.tabs)
        }
        composable(Screen.Home.route) {
            HomeScreen(
                desktopMode = desktopTabs,
                onOpenUpdateDetails = { navController.navigate(Screen.UpdateDetails.route) { launchSingleTop = true } },
                openTabCount = openTabs.count { it is SessionTab },
                onOpenTabs = { openSessionPicker = false; showTabs = true },
                addServerRequest = addServerRequest,
                onAddServerRequestHandled = { addServerRequest = 0 },
                onNavigateToSessions = { serverUrl, username, password, serverName, serverId ->
                    navController.navigate(
                        Screen.SessionList.createRoute(serverUrl, username, password, serverName, serverId)
                    )
                },
                onNavigateToCrossServerSessions = {
                    navController.navigate(Screen.CrossServerSessions.route)
                },
                onNavigateToServerSettings = { serverUrl, username, password, serverName, serverId ->
                    navController.navigate(
                        Screen.ServerSettings.createRoute(serverUrl, username, password, serverName, serverId)
                    )
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToAbout = {
                    navController.navigate(Screen.About.route)
                }
            )
        }

        composable(Screen.CrossServerSessions.route) {
            CrossServerSessionsScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenSession = { item ->
                    navController.navigate(
                        Screen.Chat.createRoute(
                            serverUrl = item.server.url,
                            username = item.server.username,
                            password = item.server.password.orEmpty(),
                            serverName = item.server.displayName,
                            serverId = item.server.id,
                            sessionId = item.session.id,
                        ),
                    )
                },
                onConnectServer = { server ->
                    val intent = Intent(context, OpenCodeConnectionService::class.java).apply {
                        putExtra("server_id", server.id)
                        putExtra("server_name", server.name)
                        putExtra("server_url", server.url)
                        putExtra("server_username", server.username)
                        putExtra("server_password", server.password)
                    }
                    ContextCompat.startForegroundService(context, intent)
                },
            )
        }
        
        // ============ Settings Screen ============
        composable(Screen.Settings.route) {
            SettingsScreen(
                showTopBar = !desktopTabs || !sessionFeatures.tabs,
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToDiagnostics = { navController.navigate(Screen.Diagnostics.route) },
                onNavigateToSync = { navController.navigate(Screen.SyncSettings.route) },
            )
        }

        composable(Screen.SyncSettings.route) {
            dev.minios.ocremote.ui.screens.settings.SyncSettingsScreen(
                showTopBar = !desktopTabs || !sessionFeatures.tabs,
                onNavigateBack = { navController.popBackStack() },
            )
        }

        composable(Screen.Diagnostics.route) {
            DiagnosticsScreen(onNavigateBack = { navController.popBackStack() })
        }

        composable(
            route = "server_settings?serverUrl={serverUrl}&username={username}&password={password}&serverName={serverName}&serverId={serverId}",
            arguments = listOf(
                navArgument("serverUrl") { type = NavType.StringType },
                navArgument("username") { type = NavType.StringType },
                navArgument("password") { type = NavType.StringType },
                navArgument("serverName") { type = NavType.StringType },
                navArgument("serverId") { type = NavType.StringType; defaultValue = "" },
            )
        ) {
            val serverUrl = it.arguments?.getString("serverUrl").orEmpty()
            val username = it.arguments?.getString("username").orEmpty()
            val password = it.arguments?.getString("password").orEmpty()
            val serverName = it.arguments?.getString("serverName").orEmpty()
            val serverId = it.arguments?.getString("serverId").orEmpty()
            ServerSettingsScreen(
                isServerConnected = serverId in connectedServerIds,
                isServerConnecting = serverId in connectingServerIds,
                onConnectServer = {
                    connectServer(serverId, serverName, serverUrl, username, password)
                },
                onNavigateBack = { navController.popBackStack() },
                onOpenProviders = {
                    navController.navigate(
                        Screen.ServerProviders.createRoute(
                            serverUrl = serverUrl,
                            username = username,
                            password = password,
                            serverName = serverName,
                            serverId = serverId
                        )
                    )
                },
                onOpenModels = {
                    navController.navigate(
                        Screen.ServerModelFilter.createRoute(
                            serverUrl = serverUrl,
                            username = username,
                            password = password,
                            serverName = serverName,
                            serverId = serverId
                        )
                    )
                },
                onOpenMcp = {
                    navController.navigate(
                        Screen.ServerMcp.createRoute(
                            serverUrl = serverUrl,
                            username = username,
                            password = password,
                            serverName = serverName,
                            serverId = serverId,
                        )
                    )
                }
            )
        }

        composable(
            route = "server_providers?serverUrl={serverUrl}&username={username}&password={password}&serverName={serverName}&serverId={serverId}",
            arguments = listOf(
                navArgument("serverUrl") { type = NavType.StringType },
                navArgument("username") { type = NavType.StringType },
                navArgument("password") { type = NavType.StringType },
                navArgument("serverName") { type = NavType.StringType },
                navArgument("serverId") { type = NavType.StringType; defaultValue = "" },
            )
        ) { backStackEntry ->
            val serverUrl = backStackEntry.arguments?.getString("serverUrl").orEmpty()
            val username = backStackEntry.arguments?.getString("username").orEmpty()
            val password = backStackEntry.arguments?.getString("password").orEmpty()
            val serverName = backStackEntry.arguments?.getString("serverName").orEmpty()
            val serverId = backStackEntry.arguments?.getString("serverId").orEmpty()
            ServerProvidersScreen(
                isServerConnected = serverId in connectedServerIds,
                isServerConnecting = serverId in connectingServerIds,
                onConnectServer = {
                    connectServer(serverId, serverName, serverUrl, username, password)
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "server_model_filter?serverUrl={serverUrl}&username={username}&password={password}&serverName={serverName}&serverId={serverId}",
            arguments = listOf(
                navArgument("serverUrl") { type = NavType.StringType },
                navArgument("username") { type = NavType.StringType },
                navArgument("password") { type = NavType.StringType },
                navArgument("serverName") { type = NavType.StringType },
                navArgument("serverId") { type = NavType.StringType },
            )
        ) { backStackEntry ->
            val serverUrl = backStackEntry.arguments?.getString("serverUrl").orEmpty()
            val username = backStackEntry.arguments?.getString("username").orEmpty()
            val password = backStackEntry.arguments?.getString("password").orEmpty()
            val serverName = backStackEntry.arguments?.getString("serverName").orEmpty()
            val serverId = backStackEntry.arguments?.getString("serverId").orEmpty()
            ServerModelFilterScreen(
                isServerConnected = serverId in connectedServerIds,
                isServerConnecting = serverId in connectingServerIds,
                onConnectServer = {
                    connectServer(serverId, serverName, serverUrl, username, password)
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "server_mcp?serverUrl={serverUrl}&username={username}&password={password}&serverName={serverName}&serverId={serverId}",
            arguments = listOf(
                navArgument("serverUrl") { type = NavType.StringType },
                navArgument("username") { type = NavType.StringType },
                navArgument("password") { type = NavType.StringType },
                navArgument("serverName") { type = NavType.StringType },
                navArgument("serverId") { type = NavType.StringType },
            )
        ) { backStackEntry ->
            val serverUrl = backStackEntry.arguments?.getString("serverUrl").orEmpty()
            val username = backStackEntry.arguments?.getString("username").orEmpty()
            val password = backStackEntry.arguments?.getString("password").orEmpty()
            val serverName = backStackEntry.arguments?.getString("serverName").orEmpty()
            val serverId = backStackEntry.arguments?.getString("serverId").orEmpty()
            ServerMcpScreen(
                isServerConnected = serverId in connectedServerIds,
                isServerConnecting = serverId in connectingServerIds,
                onConnectServer = {
                    connectServer(serverId, serverName, serverUrl, username, password)
                },
                onNavigateBack = { navController.popBackStack() },
            )
        }

        // ============ About Screen ============
        composable(Screen.About.route) {
            AboutScreen(
                showTopBar = !desktopTabs || !sessionFeatures.tabs,
                onOpenUpdateDetails = { navController.navigate(Screen.UpdateDetails.route) { launchSingleTop = true } },
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
            val serverUrl = backStackEntry.arguments?.getString("serverUrl").orEmpty()
            val username = backStackEntry.arguments?.getString("username").orEmpty()
            val password = backStackEntry.arguments?.getString("password").orEmpty()
            val serverName = backStackEntry.arguments?.getString("serverName").orEmpty()
            val initialPath = backStackEntry.arguments?.getString("initialPath").orEmpty()
            val resolvedServer = sharePickerServers.firstOrNull {
                normalizeServerUrl(it.url) == normalizeServerUrl(serverUrl)
            }
            val effectiveServerId = resolvedServer?.id.orEmpty()
            
            WebViewScreen(
                serverUrl = serverUrl,
                username = username,
                password = password,
                serverName = serverName,
                isServerConnected = effectiveServerId in connectedServerIds,
                isServerConnecting = effectiveServerId in connectingServerIds,
                onConnectServer = {
                    connectServer(effectiveServerId, serverName, serverUrl, username, password)
                },
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
            val serverUrl = backStackEntry.arguments?.getString("serverUrl").orEmpty()
            val username = backStackEntry.arguments?.getString("username").orEmpty()
            val password = backStackEntry.arguments?.getString("password").orEmpty()
            val serverName = backStackEntry.arguments?.getString("serverName").orEmpty()
            val serverId = backStackEntry.arguments?.getString("serverId").orEmpty()

            SessionListScreen(
                isServerConnected = serverId in connectedServerIds,
                isServerConnecting = serverId in connectingServerIds,
                onConnectServer = {
                    connectServer(serverId, serverName, serverUrl, username, password)
                },
                onNavigateToChat = { sessionId, openTerminal ->
                    navController.navigate(
                        Screen.Chat.createRoute(
                            serverUrl = serverUrl,
                            username = username,
                            password = password,
                            serverName = serverName,
                            serverId = serverId,
                            sessionId = sessionId,
                            openTerminal = openTerminal
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
            route = "chat?serverUrl={serverUrl}&username={username}&password={password}&serverName={serverName}&serverId={serverId}&sessionId={sessionId}&openTerminal={openTerminal}",
            arguments = listOf(
                navArgument("serverUrl") { type = NavType.StringType },
                navArgument("username") { type = NavType.StringType },
                navArgument("password") { type = NavType.StringType },
                navArgument("serverName") { type = NavType.StringType },
                navArgument("serverId") { type = NavType.StringType },
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("openTerminal") { type = NavType.BoolType; defaultValue = false }
            )
        ) { backStackEntry ->
            val serverUrl = backStackEntry.arguments?.getString("serverUrl").orEmpty()
            val username = backStackEntry.arguments?.getString("username").orEmpty()
            val password = backStackEntry.arguments?.getString("password").orEmpty()
            val serverName = backStackEntry.arguments?.getString("serverName").orEmpty()
            val serverId = backStackEntry.arguments?.getString("serverId").orEmpty()
            val sessionId = backStackEntry.arguments?.getString("sessionId").orEmpty()
            val openTerminal = backStackEntry.arguments?.getBoolean("openTerminal") ?: false

            // Only pass shared attachments to the targeted session, then clear them
            val attachmentsForThisSession = if (pendingShareSessionId == sessionId && pendingShareServerId == serverId && pendingShareUris.isNotEmpty()) {
                pendingShareUris
            } else {
                emptyList()
            }
            
            ChatScreen(
                requestedTabAction = pendingTabAction?.takeIf { it.first == "$serverId:$sessionId" }?.second,
                onTabActionHandled = { if (pendingTabAction?.first == "$serverId:$sessionId") pendingTabAction = null },
                onOpenAppSettings = { navController.navigate(Screen.Settings.route) { launchSingleTop = true } },
                onOpenServers = { navController.navigate(Screen.Home.route) { popUpTo(Screen.Home.route); launchSingleTop = true } },
                onOpenAbout = { navController.navigate(Screen.About.route) { launchSingleTop = true } },
                onAddServer = {
                    addServerRequest++
                    navController.navigate(Screen.Home.route) { popUpTo(Screen.Home.route); launchSingleTop = true }
                },
                desktopTabsVisible = desktopTabs,
                onTerminalFocusChange = {
                    if (navController.currentBackStackEntry?.id == backStackEntry.id) terminalModeActive = it
                },
                tabsOverlayVisible = tabsVisibility.currentState || tabsVisibility.targetState,
                openTabCount = openTabs.count { it is SessionTab },
                onOpenTabs = { openSessionPicker = false; showTabs = true },
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
                    navController.popBackStack()
                    navController.navigate(route)
                },
                onNavigateToChildSession = { childSessionId ->
                    navController.popBackStack()
                    navController.navigate(
                        Screen.Chat.createRoute(
                            serverUrl = serverUrl,
                            username = username,
                            password = password,
                            serverName = serverName,
                            serverId = serverId,
                            sessionId = childSessionId,
                        ),
                    )
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
                onOpenWorkspace = { directory ->
                    navController.navigate(
                        Screen.WorkspaceFiles.createRoute(
                            serverUrl = serverUrl,
                            username = username,
                            password = password,
                            directory = directory,
                            serverId = serverId,
                        ),
                    )
                },
                onOpenServerFile = { directory, href ->
                    navController.navigate(Screen.WorkspaceFiles.createRoute(
                        serverUrl, username, password, directory, filePath = href, serverId = serverId,
                    ))
                },
                onManageModels = {
                    navController.navigate(
                        Screen.ServerModelFilter.createRoute(
                            serverUrl = serverUrl,
                            username = username,
                            password = password,
                            serverName = serverName,
                            serverId = serverId,
                        ),
                    )
                },
                initialSharedAttachments = attachmentsForThisSession,
                onSharedAttachmentsConsumed = {
                    pendingShareUris = emptyList()
                    pendingShareSessionId = null
                    pendingShareServerId = null
                },
                startInTerminalMode = openTerminal,
                isServerConnected = serverId in connectedServerIds,
                isServerConnecting = serverId in connectingServerIds,
                onConnectServer = {
                    connectServer(serverId, serverName, serverUrl, username, password)
                },
            )
        }

        composable(
            route = "workspace_files?serverUrl={serverUrl}&username={username}&password={password}&directory={directory}&filePath={filePath}&serverId={serverId}",
            arguments = listOf(
                navArgument("serverUrl") { type = NavType.StringType },
                navArgument("username") { type = NavType.StringType },
                navArgument("password") { type = NavType.StringType },
                navArgument("directory") { type = NavType.StringType },
                navArgument("filePath") { type = NavType.StringType; defaultValue = "" },
                navArgument("serverId") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStackEntry ->
            val serverUrl = backStackEntry.arguments?.getString("serverUrl").orEmpty()
            val username = backStackEntry.arguments?.getString("username").orEmpty()
            val password = backStackEntry.arguments?.getString("password").orEmpty()
            val server = sharePickerServers.firstOrNull {
                normalizeServerUrl(it.url) == normalizeServerUrl(serverUrl)
            }
            val effectiveServerId = server?.id.orEmpty()
            WorkspaceFilesScreen(
                isServerConnected = effectiveServerId in connectedServerIds,
                isServerConnecting = effectiveServerId in connectingServerIds,
                onConnectServer = {
                    connectServer(effectiveServerId, server?.displayName.orEmpty(), serverUrl, username, password)
                },
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
    }
    }
    TabsOverlay()
    if (showClosedTabs) ClosedTabsDialog(if (desktopTabs) workspace.closed else workspace.closed.filter { it.tab is SessionTab }, sharePickerServers,
        onRestore = { showClosedTabs = false; restoreClosedTab(it) }, onDismiss = { showClosedTabs = false })
    dev.minios.ocremote.ui.components.AppSnackbarHost(desktopSnackbar,
        Modifier.align(Alignment.BottomCenter).navigationBarsPadding().widthIn(max = 560.dp))
    }
}

/**
 * Dialog shown when attachments are shared into the app via ACTION_SEND.
 * Lists recent sessions from servers that have SSE data loaded,
 * grouped by server. User taps a session to open it with the shared attachment(s).
 */
@Composable
private fun ShareTargetPickerDialog(
    servers: List<ServerConfig>,
    sessions: List<Session>,
    serverSessions: Map<String, Set<String>>,
    connectedServerIds: Set<String>,
    preferencesByServer: Map<String, SharePickerServerPreferences>,
    favoriteOrder: List<String>,
    categories: List<SessionCategory>,
    attachmentCount: Int,
    onSelectSession: (server: ServerConfig, session: Session) -> Unit,
    onNewSession: (server: ServerConfig) -> Unit,
    onManageServers: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val isAmoled = isAmoledTheme()

    val items = remember(
        servers,
        sessions,
        serverSessions,
        connectedServerIds,
        preferencesByServer,
        favoriteOrder,
        categories,
    ) {
        buildSharePickerItems(
            servers = servers,
            sessions = sessions,
            serverSessions = serverSessions,
            connectedServerIds = connectedServerIds,
            preferencesByServer = preferencesByServer,
            favoriteOrder = favoriteOrder,
            categories = categories,
        )
    }

    // Servers that have sessions loaded (for the "New session" option)
    val activeServers = remember(servers, connectedServerIds) {
        connectedShareServers(servers, connectedServerIds)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .widthIn(max = 840.dp).fillMaxWidth(0.92f)
                .widthIn(max = 560.dp)
                .then(
                    if (isAmoled) {
                        Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(28.dp),
                        )
                    } else {
                        Modifier
                    },
                ),
            shape = RoundedCornerShape(28.dp),
            color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerLowest,
            tonalElevation = if (isAmoled) 0.dp else 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.share_send_attachments_to),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (attachmentCount == 1)
                                stringResource(R.string.attachment_count_single)
                            else
                                stringResource(R.string.attachment_count_multiple, attachmentCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Lucide.X, contentDescription = stringResource(R.string.close))
                    }
                }

                when {
                    activeServers.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Surface(
                                modifier = Modifier.size(64.dp),
                                shape = RoundedCornerShape(20.dp),
                                color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.secondaryContainer,
                                border = if (isAmoled) {
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.72f))
                                } else null,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Lucide.CloudOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(30.dp),
                                        tint = if (isAmoled) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSecondaryContainer
                                        },
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.share_no_connected_servers),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = stringResource(R.string.share_no_servers_body),
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            AppPrimaryButton(
                                onClick = onManageServers,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                            ) {
                                Icon(
                                    imageVector = if (servers.isEmpty()) Lucide.Plus else Lucide.Server,
                                    contentDescription = null,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    stringResource(
                                        if (servers.isEmpty()) R.string.share_add_server else R.string.share_manage_servers,
                                    ),
                                )
                            }
                        }
                    }
                    else -> {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))

                        if (items.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(
                                    Lucide.MessageCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp).mirrorForRtl(),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    text = stringResource(R.string.share_no_sessions),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(R.string.share_create_session_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 440.dp),
                                contentPadding = PaddingValues(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                items(items, key = { "${it.server.id}/${it.session.id}" }) { item ->
                                    val projectName = item.session.directory
                                        .trimEnd('/')
                                        .substringAfterLast('/')
                                        .ifEmpty { null }
                                    val subtitle = buildString {
                                        if (projectName != null) append(projectName)
                                        if (activeServers.size > 1) {
                                            if (isNotEmpty()) append(" · ")
                                            append(item.server.displayName)
                                        }
                                    }

                                    Surface(
                                        onClick = { onSelectSession(item.server, item.session) },
                                        modifier = Modifier
                                            .fillMaxWidth(),
                                        shape = RoundedCornerShape(16.dp),
                                        color = if (isAmoled) Color.Black else Color.Transparent,
                                        border = BorderStroke(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant.copy(
                                                alpha = if (isAmoled) 1f else 0.55f,
                                            ),
                                        ),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Surface(
                                                modifier = Modifier.size(40.dp),
                                                shape = RoundedCornerShape(12.dp),
                                                color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.primaryContainer,
                                                border = if (isAmoled) {
                                                    BorderStroke(
                                                        1.dp,
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.72f),
                                                    )
                                                } else null,
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        Lucide.MessageCircle,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(20.dp).mirrorForRtl(),
                                                        tint = if (isAmoled) {
                                                            MaterialTheme.colorScheme.primary
                                                        } else {
                                                            MaterialTheme.colorScheme.onPrimaryContainer
                                                        },
                                                    )
                                                }
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    if (item.isFavorite) {
                                                        Icon(
                                                            Lucide.Star,
                                                            contentDescription = stringResource(R.string.session_favorite),
                                                            modifier = Modifier.size(14.dp),
                                                            tint = MaterialTheme.colorScheme.primary,
                                                        )
                                                    }
                                                    Text(
                                                        text = item.session.title
                                                            ?: stringResource(R.string.session_untitled),
                                                        modifier = Modifier.weight(1f),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                if (item.category != null || subtitle.isNotBlank()) {
                                                    Row(
                                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        item.category?.let { category ->
                                                            val categoryColor = sessionCategoryColor(category.color)
                                                            Icon(
                                                                sessionCategoryIcon(category.icon),
                                                                contentDescription = category.name,
                                                                modifier = Modifier.size(13.dp),
                                                                tint = categoryColor,
                                                            )
                                                            Text(
                                                                text = category.name,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = categoryColor,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                            )
                                                        }
                                                        if (subtitle.isNotBlank()) {
                                                            Text(
                                                                text = subtitle,
                                                                modifier = Modifier.weight(1f),
                                                                style = MaterialTheme.typography.bodySmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            Text(
                                                text = dateFormat.format(Date(item.session.time.updated)),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            for (server in activeServers) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { onNewSession(server) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        Lucide.Plus,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        text = if (activeServers.size > 1)
                                            stringResource(R.string.sessions_new_on_server, server.displayName)
                                        else
                                            stringResource(R.string.sessions_new_short),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
