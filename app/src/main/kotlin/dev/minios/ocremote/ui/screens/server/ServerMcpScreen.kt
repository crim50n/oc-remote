package dev.minios.ocremote.ui.screens.server

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.minios.ocremote.R
import dev.minios.ocremote.ui.components.AppCardShape
import dev.minios.ocremote.ui.components.appAmoledBorder
import dev.minios.ocremote.ui.components.isAmoledTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerMcpScreen(
    onNavigateBack: () -> Unit,
    viewModel: ServerMcpViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val uriHandler = LocalUriHandler.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isAmoled = isAmoledTheme()

    LaunchedEffect(Unit) {
        viewModel.authorizationUrls.collect(uriHandler::openUri)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_mcp_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.server_mcp_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        when {
            state.isLoading && state.servers.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.error?.let { error ->
                        item("error") {
                            Surface(
                                color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.errorContainer,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f)),
                                shape = AppCardShape,
                            ) {
                                Text(
                                    text = error,
                                    modifier = Modifier.padding(14.dp),
                                    color = if (isAmoled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                        }
                    }
                    if (state.servers.isEmpty() && state.error == null) {
                        item("empty") {
                            Column(
                                modifier = Modifier.fillParentMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(stringResource(R.string.server_mcp_empty), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = stringResource(R.string.server_mcp_empty_desc),
                                    modifier = Modifier.padding(top = 8.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    items(state.servers, key = McpServerItem::name) { server ->
                        McpServerCard(
                            server = server,
                            loading = state.loadingName == server.name,
                            isAmoled = isAmoled,
                            onConnect = { viewModel.connect(server.name) },
                            onDisconnect = { viewModel.disconnect(server.name) },
                            onAuthenticate = { viewModel.authenticate(server.name) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun McpServerCard(
    server: McpServerItem,
    loading: Boolean,
    isAmoled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onAuthenticate: () -> Unit,
) {
    Card(
        shape = AppCardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = appAmoledBorder(0.65f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = mcpStatusText(server.status),
                        style = MaterialTheme.typography.labelMedium,
                        color = mcpStatusColor(server.status),
                    )
                }
                if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            server.error?.takeIf(String::isNotBlank)?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!loading) {
                when (server.status) {
                    "connected" -> OutlinedButton(onClick = onDisconnect) {
                        Text(stringResource(R.string.server_mcp_disconnect))
                    }
                    "needs_auth", "needs_client_registration" -> Button(onClick = onAuthenticate) {
                        Text(stringResource(R.string.server_mcp_authenticate))
                    }
                    else -> Button(onClick = onConnect) {
                        Text(stringResource(R.string.server_mcp_connect))
                    }
                }
            } else {
                Spacer(Modifier.size(1.dp))
            }
        }
    }
}

@Composable
private fun mcpStatusText(status: String): String = stringResource(
    when (status) {
        "connected" -> R.string.server_mcp_status_connected
        "disabled" -> R.string.server_mcp_status_disabled
        "failed" -> R.string.server_mcp_status_failed
        "needs_auth" -> R.string.server_mcp_status_needs_auth
        "needs_client_registration" -> R.string.server_mcp_status_needs_registration
        else -> R.string.server_mcp_status_unknown
    }
)

@Composable
private fun mcpStatusColor(status: String): Color = when (status) {
    "connected" -> MaterialTheme.colorScheme.primary
    "failed", "needs_client_registration" -> MaterialTheme.colorScheme.error
    "needs_auth" -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
