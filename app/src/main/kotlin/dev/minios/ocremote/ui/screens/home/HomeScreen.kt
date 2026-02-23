package dev.minios.ocremote.ui.screens.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat
import dev.minios.ocremote.R
import dev.minios.ocremote.data.repository.LocalServerManager
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.ui.theme.StatusConnected
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer

/** Pulsing dots loading indicator — 3 dots that scale up/down in sequence. */
@Composable
private fun PulsingDotsIndicator(
    modifier: Modifier = Modifier,
    dotSize: androidx.compose.ui.unit.Dp = 10.dp,
    dotSpacing: androidx.compose.ui.unit.Dp = 8.dp,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val transition = rememberInfiniteTransition(label = "pulsing_dots")
    val scales2 = (0..2).map { index ->
        transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 1200
                    val offset = index * 150
                    0.4f at 0 + offset
                    1.0f at 300 + offset
                    0.4f at 600 + offset
                    0.4f at 1200
                },
                repeatMode = RepeatMode.Restart
            ),
            label = "dot_scale_$index"
        )
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(dotSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        scales2.forEach { scale ->
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        alpha = 0.3f + 0.7f * ((scale.value - 0.4f) / 0.6f)
                    }
                    .background(color, CircleShape)
            )
        }
    }
}

/**
 * Home Screen - Server list and management
 * 
 * Each server card has Connect/Disconnect/Sessions buttons.
 * Multiple servers can be connected simultaneously.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSessions: (serverUrl: String, username: String, password: String, serverName: String, serverId: String) -> Unit = { _, _, _, _, _ -> },
    onNavigateToServerSettings: (serverUrl: String, username: String, password: String, serverName: String, serverId: String) -> Unit = { _, _, _, _, _ -> },
    onNavigateToSettings: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    // Track battery optimization status, re-check when app resumes
    var isBatteryOptimized by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                isBatteryOptimized = !pm.isIgnoringBatteryOptimizations(context.packageName)
                viewModel.refreshLocalRuntimeState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // We need to track which server requested notification permission so we
    // can resume the connect flow after the permission dialog.
    var pendingConnectServerId by remember { mutableStateOf<String?>(null) }
    var pendingLocalStart by remember { mutableStateOf(false) }
    var showLocalProxyDialog by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Whether granted or denied, proceed with connection
        pendingConnectServerId?.let { viewModel.connectToServer(it) }
        pendingConnectServerId = null
    }

    val runCommandPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingLocalStart) {
            viewModel.startLocalServer(context)
        } else if (!granted) {
            Toast.makeText(context, R.string.home_local_permission_required, Toast.LENGTH_LONG).show()
        }
        pendingLocalStart = false
    }

    fun requestNotificationPermissionAndConnect(serverId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pendingConnectServerId = serverId
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.connectToServer(serverId)
        }
    }

    fun requestRunCommandPermissionAndStartLocal() {
        val permissionState = ContextCompat.checkSelfPermission(
            context,
            "com.termux.permission.RUN_COMMAND",
        )
        if (permissionState == PackageManager.PERMISSION_GRANTED) {
            viewModel.startLocalServer(context)
            return
        }

        pendingLocalStart = true
        runCommandPermissionLauncher.launch("com.termux.permission.RUN_COMMAND")
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = { viewModel.showAddServerDialog() }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.home_add_server))
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                    IconButton(onClick = onNavigateToAbout) {
                        Icon(Icons.Default.Info, contentDescription = stringResource(R.string.about_title))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    PulsingDotsIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        dotSize = 12.dp,
                        dotSpacing = 8.dp
                    )
                }
                else -> {
                    val localServer = uiState.servers.firstOrNull { it.url == LocalServerManager.LOCAL_SERVER_URL }
                    val remoteServers = uiState.servers.filterNot { it.url == LocalServerManager.LOCAL_SERVER_URL }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Battery optimization warning banner
                        if (isBatteryOptimized) {
                            item(key = "__battery_banner") {
                                BatteryOptimizationBanner(
                                    onDisable = {
                                        val intent = Intent(
                                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                        context.startActivity(intent)
                                    }
                                )
                            }
                        }

                        if (uiState.showLocalRuntime) {
                            item(key = "__local_runtime") {
                                LocalRuntimeCard(
                                    termuxInstalled = uiState.termuxInstalled,
                                    runtimeStatus = uiState.localRuntimeStatus,
                                    statusMessage = uiState.localRuntimeMessage,
                                    fixCommand = uiState.localRuntimeFixCommand,
                                    needsOverlaySettings = uiState.localRuntimeNeedsOverlaySettings,
                                    localServerConnected = localServer?.id in uiState.connectedServerIds,
                                    localServerConnecting = localServer?.id in uiState.connectingServerIds,
                                    localServerConnectionError = localServer?.id?.let { uiState.connectionErrors[it] },
                                    showLocalServerSettings = localServer?.id in uiState.serverSettingsReadyIds,
                                    localProxyEnabled = uiState.localProxyEnabled,
                                    onStart = { requestRunCommandPermissionAndStartLocal() },
                                    onStop = { viewModel.stopLocalServer(context) },
                                    onSetup = {
                                        val setupCommand = uiState.setupCommand ?: viewModel.getLocalSetupCommand()
                                        clipboardManager.setText(AnnotatedString(setupCommand))
                                        Toast.makeText(context, R.string.home_local_setup_copied, Toast.LENGTH_SHORT).show()
                                        viewModel.setupLocalServer(context)
                                    },
                                    onCopyFixCommand = { command ->
                                        clipboardManager.setText(AnnotatedString(command))
                                        Toast.makeText(context, R.string.home_local_fix_command_copied, Toast.LENGTH_SHORT).show()
                                    },
                                    onOpenTermuxOverlaySettings = {
                                        val intent = Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:com.termux"),
                                        )
                                        context.startActivity(intent)
                                    },
                                    onOpenLocalSessions = {
                                        localServer?.let { server ->
                                            onNavigateToSessions(
                                                server.url,
                                                server.username,
                                                server.password ?: "",
                                                server.displayName,
                                                server.id,
                                            )
                                        }
                                    },
                                    onOpenLocalServerSettings = {
                                        localServer?.let { server ->
                                            onNavigateToServerSettings(
                                                server.url,
                                                server.username,
                                                server.password ?: "",
                                                server.displayName,
                                                server.id,
                                            )
                                        }
                                    },
                                    onOpenLocalProxySettings = {
                                        showLocalProxyDialog = true
                                    },
                                    onInstallTermux = {
                                        val intent = Intent(
                                            Intent.ACTION_VIEW,
                                            Uri.parse("https://f-droid.org/packages/com.termux/")
                                        )
                                        context.startActivity(intent)
                                    },
                                )
                            }
                        }

                        if (remoteServers.isEmpty()) {
                            item(key = "__empty_servers") {
                                val hasLocalCard = uiState.showLocalRuntime
                                EmptyServersView(
                                    onAddServer = { viewModel.showAddServerDialog() },
                                    modifier = if (hasLocalCard) {
                                        Modifier.fillParentMaxHeight(0.5f)
                                    } else {
                                        Modifier.fillParentMaxHeight(0.8f)
                                    }
                                )
                            }
                        }

                        items(remoteServers, key = { it.id }) { server ->
                            ServerCard(
                                server = server,
                                isConnected = server.id in uiState.connectedServerIds,
                                isConnecting = server.id in uiState.connectingServerIds,
                                connectionError = uiState.connectionErrors[server.id],
                                showServerSettings = server.id in uiState.serverSettingsReadyIds,
                                onConnect = { requestNotificationPermissionAndConnect(server.id) },
                                onDisconnect = { viewModel.disconnectFromServer(server.id) },
                                onOpenSessions = {
                                    onNavigateToSessions(
                                        server.url,
                                        server.username,
                                        server.password ?: "",
                                        server.displayName,
                                        server.id
                                    )
                                },
                                onServerSettings = {
                                    onNavigateToServerSettings(
                                        server.url,
                                        server.username,
                                        server.password ?: "",
                                        server.displayName,
                                        server.id
                                    )
                                },
                                onEdit = { viewModel.showEditServerDialog(server) },
                                onDelete = { viewModel.deleteServer(server.id) }
                            )
                        }
                    }
                }
            }
        }

        // Add/Edit Server Dialog
        if (uiState.showAddServerDialog) {
            ServerDialog(
                server = uiState.editingServer,
                onDismiss = { viewModel.hideServerDialog() },
                onSave = { name, url, username, password, autoConnect ->
                    viewModel.saveServer(name, url, username, password, autoConnect)
                }
            )
        }

        if (showLocalProxyDialog) {
            LocalProxyDialog(
                enabled = uiState.localProxyEnabled,
                proxyUrl = uiState.localProxyUrl,
                onDismiss = { showLocalProxyDialog = false },
                onSave = { enabled, url ->
                    viewModel.setLocalProxyEnabled(enabled)
                    viewModel.setLocalProxyUrl(url)
                    showLocalProxyDialog = false
                },
            )
        }
    }
}

@Composable
private fun LocalRuntimeCard(
    termuxInstalled: Boolean,
    runtimeStatus: LocalRuntimeStatus,
    statusMessage: String?,
    fixCommand: String?,
    needsOverlaySettings: Boolean,
    localServerConnected: Boolean,
    localServerConnecting: Boolean,
    localServerConnectionError: String?,
    showLocalServerSettings: Boolean,
    localProxyEnabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSetup: () -> Unit,
    onCopyFixCommand: (String) -> Unit,
    onOpenTermuxOverlaySettings: () -> Unit,
    onOpenLocalSessions: () -> Unit,
    onOpenLocalServerSettings: () -> Unit,
    onOpenLocalProxySettings: () -> Unit,
    onInstallTermux: () -> Unit,
) {
    val isAmoled = MaterialTheme.colorScheme.background == Color.Black &&
        MaterialTheme.colorScheme.surface == Color.Black
    val cardContainerColor = if (isAmoled) {
        Color.Black
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val cardContentColor = if (isAmoled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = cardContainerColor,
        ),
        border = if (isAmoled) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val compactActive = runtimeStatus == LocalRuntimeStatus.Running &&
                localServerConnected &&
                localServerConnectionError.isNullOrBlank()

            // Header row with title and status chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.home_local_server_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = cardContentColor,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenLocalProxySettings) {
                        Icon(
                            Icons.Default.Language,
                            contentDescription = stringResource(R.string.home_local_proxy_settings),
                            tint = if (localProxyEnabled) MaterialTheme.colorScheme.primary else cardContentColor,
                        )
                    }
                    if (showLocalServerSettings) {
                        IconButton(onClick = onOpenLocalServerSettings) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = stringResource(R.string.settings_title),
                                tint = cardContentColor,
                            )
                        }
                    }
                }
            }

            // Description (hide when fully active to keep card compact)
            if (!compactActive) {
                Text(
                    text = stringResource(R.string.home_local_server_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = cardContentColor.copy(alpha = 0.85f),
                )
            }

            // Status / error message
            if (!statusMessage.isNullOrBlank()) {
                Text(
                    text = statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (runtimeStatus == LocalRuntimeStatus.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        cardContentColor
                    },
                )
            }

            // Fix command copy button (for errors with a known fix)
            if (runtimeStatus == LocalRuntimeStatus.Error && !fixCommand.isNullOrBlank()) {
                OutlinedButton(
                    onClick = { onCopyFixCommand(fixCommand) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (isAmoled) {
                        ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Black,
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                    border = if (isAmoled) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
                    } else {
                        ButtonDefaults.outlinedButtonBorder
                    },
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_local_copy_fix_command))
                }
            }

            if (runtimeStatus == LocalRuntimeStatus.Error && needsOverlaySettings) {
                OutlinedButton(
                    onClick = onOpenTermuxOverlaySettings,
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (isAmoled) {
                        ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Black,
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                    border = if (isAmoled) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
                    } else {
                        ButtonDefaults.outlinedButtonBorder
                    },
                ) {
                    Icon(Icons.Default.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_local_open_termux_overlay_settings))
                }
            }

            // --- Action area based on status ---
            when {
                // Termux not installed — show install button
                !termuxInstalled -> {
                    OutlinedButton(
                        onClick = onInstallTermux,
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (isAmoled) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.Black,
                                contentColor = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                        border = if (isAmoled) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
                        } else {
                            ButtonDefaults.outlinedButtonBorder
                        },
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_local_install_termux))
                    }
                }

                // Needs setup — show setup command and Setup button
                runtimeStatus == LocalRuntimeStatus.NeedsSetup -> {
                    Text(
                        text = stringResource(R.string.home_local_setup_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = cardContentColor.copy(alpha = 0.85f),
                    )
                    Button(
                        onClick = onSetup,
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (isAmoled) {
                            ButtonDefaults.buttonColors(
                                containerColor = Color.Black,
                                contentColor = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                        border = if (isAmoled) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
                        } else {
                            null
                        },
                    ) {
                        Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_local_setup))
                    }

                    OutlinedButton(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (isAmoled) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.Black,
                                contentColor = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                        border = if (isAmoled) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
                        } else {
                            ButtonDefaults.outlinedButtonBorder
                        },
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.home_local_start))
                    }
                }

                // Running or Starting or Stopping — show stop button
                runtimeStatus == LocalRuntimeStatus.Running ||
                    runtimeStatus == LocalRuntimeStatus.Starting ||
                    runtimeStatus == LocalRuntimeStatus.Stopping -> {
                    val actionLabel = when (runtimeStatus) {
                        LocalRuntimeStatus.Starting -> stringResource(R.string.home_local_status_starting)
                        LocalRuntimeStatus.Stopping -> stringResource(R.string.home_local_status_stopping)
                        else -> stringResource(R.string.home_local_stop)
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (localServerConnected) {
                            Button(
                                onClick = onOpenLocalSessions,
                                modifier = Modifier.fillMaxWidth(),
                                colors = if (isAmoled) {
                                    ButtonDefaults.buttonColors(
                                        containerColor = Color.Black,
                                        contentColor = MaterialTheme.colorScheme.primary,
                                    )
                                } else {
                                    ButtonDefaults.buttonColors()
                                },
                                border = if (isAmoled) {
                                    BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
                                } else {
                                    null
                                },
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.home_local_open_sessions))
                            }
                        }

                        OutlinedButton(
                            onClick = onStop,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = runtimeStatus == LocalRuntimeStatus.Running,
                            colors = if (isAmoled) {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = Color.Black,
                                    contentColor = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            },
                            border = if (isAmoled) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
                            } else {
                                ButtonDefaults.outlinedButtonBorder
                            },
                        ) {
                            if (runtimeStatus == LocalRuntimeStatus.Starting || runtimeStatus == LocalRuntimeStatus.Stopping) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(actionLabel)
                        }
                    }
                }

                // Stopped or Error — show start button
                else -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onStart,
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (isAmoled) {
                                ButtonDefaults.buttonColors(
                                    containerColor = Color.Black,
                                    contentColor = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                ButtonDefaults.buttonColors()
                            },
                            border = if (isAmoled) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
                            } else {
                                null
                            },
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.home_local_start))
                        }

                        OutlinedButton(
                            onClick = onSetup,
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (isAmoled) {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = Color.Black,
                                    contentColor = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            },
                            border = if (isAmoled) {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
                            } else {
                                ButtonDefaults.outlinedButtonBorder
                            },
                        ) {
                            Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.home_local_setup))
                        }
                    }
                }
            }

            if (
                runtimeStatus != LocalRuntimeStatus.Running &&
                runtimeStatus != LocalRuntimeStatus.Starting &&
                runtimeStatus != LocalRuntimeStatus.Stopping &&
                (localServerConnected || localServerConnecting)
            ) {
                if (!compactActive) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }

                if (!localServerConnectionError.isNullOrBlank()) {
                    Text(
                        text = localServerConnectionError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                OutlinedButton(
                    onClick = onOpenLocalSessions,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = localServerConnected,
                    colors = if (isAmoled) {
                        ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Black,
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                    border = if (isAmoled) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
                    } else {
                        ButtonDefaults.outlinedButtonBorder
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.home_local_open_sessions))
                }
            }
        }
    }
}

@Composable
private fun LocalProxyDialog(
    enabled: Boolean,
    proxyUrl: String,
    onDismiss: () -> Unit,
    onSave: (enabled: Boolean, proxyUrl: String) -> Unit,
) {
    var localEnabled by remember(enabled) { mutableStateOf(enabled) }
    var localProxyUrl by remember(proxyUrl) { mutableStateOf(proxyUrl) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_local_proxy_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = stringResource(R.string.home_local_proxy_enable))
                    Switch(
                        checked = localEnabled,
                        onCheckedChange = { localEnabled = it },
                    )
                }

                if (localEnabled) {
                    OutlinedTextField(
                        value = localProxyUrl,
                        onValueChange = { localProxyUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.home_local_proxy_url_label)) },
                        placeholder = { Text("http://127.0.0.1:8080") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                        ),
                    )
                }

                Text(
                    text = stringResource(R.string.home_local_proxy_no_proxy_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(localEnabled, localProxyUrl)
                },
            ) {
                Text(stringResource(R.string.server_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun EmptyServersView(
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )
            Text(
                text = stringResource(R.string.home_no_servers),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
            Button(onClick = onAddServer) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_add_server))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerCard(
    server: ServerConfig,
    isConnected: Boolean,
    isConnecting: Boolean,
    connectionError: String?,
    showServerSettings: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSessions: () -> Unit,
    onServerSettings: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val isAmoled = MaterialTheme.colorScheme.background == Color.Black && MaterialTheme.colorScheme.surface == Color.Black
    val cardContainerColor = if (isAmoled) {
        Color.Black
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val cardContentColor = if (isConnected && !isAmoled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = cardContainerColor
        ),
        border = if (isAmoled) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
        } else {
            null
        }
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header row: name, URL, status, menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = cardContentColor
                    )
                    Text(
                        text = server.url,
                        style = MaterialTheme.typography.bodyMedium,
                        color = cardContentColor.copy(alpha = 0.7f)
                    )
                    if (isConnected) {
                        Text(
                            text = stringResource(R.string.home_server_health_good),
                            style = MaterialTheme.typography.labelSmall,
                            color = StatusConnected
                        )
                    } else if (isConnecting) {
                        Text(
                            text = stringResource(R.string.home_connecting),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showServerSettings) {
                        IconButton(onClick = onServerSettings) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.server_settings_title))
                        }
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                            border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.home_edit)) },
                                onClick = {
                                    showMenu = false
                                    onEdit()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Edit, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.server_delete)) },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                }
                            )
                        }
                    }
                }
            }

            // Connection error
            if (connectionError != null) {
                Text(
                    text = connectionError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Action buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isConnected) {
                    Button(
                        onClick = onOpenSessions,
                        modifier = Modifier.weight(1f),
                        colors = if (isAmoled) {
                            ButtonDefaults.buttonColors(
                                containerColor = Color.Black,
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            ButtonDefaults.buttonColors()
                        },
                        border = if (isAmoled) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
                        } else {
                            null
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.sessions_title), maxLines = 1)
                    }
                }
            }
            if (isConnected) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (isAmoled) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = Color.Black,
                                contentColor = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                        border = if (isAmoled) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
                        } else {
                            ButtonDefaults.outlinedButtonBorder
                        }
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.home_disconnect), maxLines = 1)
                    }
                }
            }
            if (!isConnected) {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isConnecting,
                    colors = if (isAmoled) {
                        ButtonDefaults.buttonColors(
                            containerColor = Color.Black,
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                    border = if (isAmoled) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f))
                    } else {
                        null
                    }
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.home_connecting))
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.home_connect))
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryOptimizationBanner(
    onDisable: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.BatteryAlert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_battery_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = stringResource(R.string.home_battery_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                )
            }
            FilledTonalButton(onClick = onDisable) {
                Text(stringResource(R.string.home_fix))
            }
        }
    }
}
