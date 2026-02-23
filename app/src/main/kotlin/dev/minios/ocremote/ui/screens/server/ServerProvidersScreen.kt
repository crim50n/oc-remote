package dev.minios.ocremote.ui.screens.server

import android.util.Log
import android.widget.Toast
import dev.minios.ocremote.BuildConfig
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.minios.ocremote.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerProvidersScreen(
    onNavigateBack: () -> Unit,
    viewModel: ServerSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isAmoled = MaterialTheme.colorScheme.background == Color.Black && MaterialTheme.colorScheme.surface == Color.Black
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val popularProviders = listOf("opencode", "anthropic", "github-copilot", "openai", "google", "openrouter", "vercel")
    val connected = uiState.providers.filter { it.connected && (it.providerId != "opencode" || it.hasPaidModels) }
    val connectedIds = connected.map { it.providerId }.toSet()
    val available = uiState.providers
        .filter { it.providerId !in connectedIds }
        .sortedWith(
            compareBy<ProviderToggle> { popularProviders.indexOf(it.providerId).takeIf { idx -> idx >= 0 } ?: Int.MAX_VALUE }
                .thenBy { it.providerName.lowercase() }
        )
    var connectProvider by remember { mutableStateOf<ProviderToggle?>(null) }
    var apiKeyProvider by remember { mutableStateOf<ProviderToggle?>(null) }
    var apiKey by remember { mutableStateOf("") }
    var oauthCode by remember { mutableStateOf("") }
    var oauthBrowserOpened by remember { mutableStateOf(false) }

    // Close method picker only after OAuth flow actually starts.
    LaunchedEffect(uiState.pendingOauth?.providerId, connectProvider?.providerId) {
        val pendingForCurrent = uiState.pendingOauth?.providerId
        if (pendingForCurrent != null && pendingForCurrent == connectProvider?.providerId) {
            connectProvider = null
        }
    }

    LaunchedEffect(uiState.pendingOauth?.providerId) {
        oauthBrowserOpened = false
    }

    // Auto-close OAuth dialog when provider becomes connected (e.g. after browser auto callback)
    LaunchedEffect(connectedIds, uiState.pendingOauth?.providerId) {
        val pendingId = uiState.pendingOauth?.providerId
        if (pendingId != null && pendingId in connectedIds) {
            viewModel.cancelProviderOauth()
        }
    }

    // If headless method is unavailable and we fell back to browser OAuth,
    // open browser automatically to keep the flow one-tap.
    LaunchedEffect(uiState.pendingOauth?.providerId, uiState.pendingOauth?.fallbackFromHeadless, oauthBrowserOpened) {
        val pending = uiState.pendingOauth ?: return@LaunchedEffect
        if (pending.fallbackFromHeadless && !oauthBrowserOpened && pending.authorization.url.isNotBlank()) {
            oauthBrowserOpened = true
            uriHandler.openUri(pending.authorization.url)
        }
    }

    // When the user returns from the browser, always reload providers.
    // If auth already completed on the server, connectedIds effect closes the dialog.
    // If not, the dialog stays open and the user can continue manually.
    DisposableEffect(lifecycleOwner, uiState.pendingOauth?.providerId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val pending = uiState.pendingOauth
                if (BuildConfig.DEBUG) Log.d("ProvidersScreen", "ON_RESUME: browserOpened=$oauthBrowserOpened, pending=${pending?.providerId}, isSaving=${uiState.isSaving}")
                if (oauthBrowserOpened && pending != null && !uiState.isSaving) {
                    if (pending.authorization.method == "code") {
                        viewModel.loadProviders()
                    } else {
                        viewModel.completeProviderOauth(null)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    connectProvider?.let { provider ->
        val methods = uiState.authMethods[provider.providerId].orEmpty().ifEmpty {
            listOf(dev.minios.ocremote.data.api.ProviderAuthMethod(type = "api", label = stringResource(R.string.server_settings_auth_method_api)))
        }
        BasicAlertDialog(onDismissRequest = { connectProvider = null }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)) else null,
                tonalElevation = if (isAmoled) 0.dp else 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.server_settings_connect_provider, provider.providerName),
                        style = MaterialTheme.typography.titleLarge,
                    )

                    methods.forEachIndexed { idx, method ->
                        Button(
                            onClick = {
                                if (method.type == "api") {
                                    connectProvider = null
                                    apiKeyProvider = provider
                                } else {
                                    viewModel.startProviderOauth(provider.providerId, idx)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isSaving,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(method.label)
                        }
                    }

                    if (!uiState.error.isNullOrBlank()) {
                        Text(
                            text = uiState.error!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { connectProvider = null }) { Text(stringResource(R.string.cancel)) }
                    }
                }
            }
        }
    }

    apiKeyProvider?.let { provider ->
        BasicAlertDialog(onDismissRequest = {
            apiKeyProvider = null
            apiKey = ""
        }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)) else null,
                tonalElevation = if (isAmoled) 0.dp else 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.server_settings_api_key_title, provider.providerName), style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.server_settings_api_key_placeholder)) },
                        singleLine = true,
                        colors = if (isAmoled) {
                            androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Black,
                                unfocusedContainerColor = Color.Black,
                                disabledContainerColor = Color.Black,
                            )
                        } else androidx.compose.material3.OutlinedTextFieldDefaults.colors()
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            apiKeyProvider = null
                            apiKey = ""
                        }) { Text(stringResource(R.string.cancel)) }
                        TextButton(
                            onClick = {
                                viewModel.connectProviderApi(provider.providerId, apiKey)
                                apiKeyProvider = null
                                apiKey = ""
                            },
                            enabled = apiKey.isNotBlank() && !uiState.isSaving
                        ) { Text(stringResource(R.string.connect)) }
                    }
                }
            }
        }
    }

    uiState.pendingOauth?.let { pending ->
        val deviceCode = remember(pending.authorization.instructions) {
            extractOAuthDeviceCode(pending.authorization.instructions)
        }
        BasicAlertDialog(onDismissRequest = {
            oauthCode = ""
            viewModel.cancelProviderOauth()
        }) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surface,
                border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)) else null,
                tonalElevation = if (isAmoled) 0.dp else 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.server_settings_oauth_title, pending.providerName),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    if (deviceCode != null) {
                        // Show localized hint + prominent code chip
                        Text(
                            text = stringResource(R.string.server_settings_oauth_device_code_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isAmoled) {
                                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.3f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            border = BorderStroke(
                                1.dp,
                                if (isAmoled) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboard.setText(AnnotatedString(deviceCode))
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.server_settings_oauth_code_copied),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = deviceCode,
                                    style = MaterialTheme.typography.headlineSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 2.sp,
                                    ),
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Center,
                                )
                                Icon(
                                    Icons.Default.ContentCopy,
                                    contentDescription = stringResource(R.string.server_settings_oauth_copy_code),
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                )
                            }
                        }
                    } else if (pending.authorization.method != "code" && pending.authorization.url.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.server_settings_oauth_browser_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else if (pending.authorization.instructions.isNotBlank()) {
                        // No structured data extracted — show raw instructions as fallback
                        Text(
                            text = pending.authorization.instructions,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (pending.fallbackFromHeadless) {
                        Text(
                            text = stringResource(R.string.server_settings_oauth_headless_fallback),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    if (pending.authorization.url.isNotBlank()) {
                        Button(
                            onClick = {
                                oauthBrowserOpened = true
                                uriHandler.openUri(pending.authorization.url)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(stringResource(R.string.server_settings_oauth_open_browser))
                        }
                    }
                    if (pending.authorization.method == "code") {
                        OutlinedTextField(
                            value = oauthCode,
                            onValueChange = { oauthCode = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.server_settings_oauth_code_placeholder)) },
                            singleLine = true,
                            colors = if (isAmoled) {
                                androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = Color.Black,
                                    unfocusedContainerColor = Color.Black,
                                    disabledContainerColor = Color.Black,
                                )
                            } else androidx.compose.material3.OutlinedTextFieldDefaults.colors()
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = {
                            oauthCode = ""
                            viewModel.cancelProviderOauth()
                        }) { Text(stringResource(R.string.cancel)) }
                        if (pending.authorization.method == "code") {
                            TextButton(
                                onClick = {
                                    viewModel.completeProviderOauth(oauthCode)
                                    oauthCode = ""
                                },
                                enabled = oauthCode.isNotBlank() && !uiState.isSaving
                            ) { Text(stringResource(R.string.server_settings_oauth_complete)) }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.server_settings_providers)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!uiState.error.isNullOrBlank()) {
                item {
                    Text(
                        text = uiState.error!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (connected.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.server_settings_providers_connected),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                items(connected, key = { it.providerId }) { provider ->
                    ProviderRow(
                        provider = provider,
                        onConnect = { viewModel.clearError(); connectProvider = provider },
                        onDisconnect = { viewModel.disconnectProvider(provider.providerId) },
                        showConnect = false,
                        canDisconnect = provider.source != "env",
                        isSaving = uiState.isSaving,
                        isAmoled = isAmoled,
                        showSource = true
                    )
                }
            }

            if (available.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.padding(top = 4.dp))
                    Text(
                        text = stringResource(R.string.server_settings_providers_available),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                items(available, key = { it.providerId }) { provider ->
                    ProviderRow(
                        provider = provider,
                        onConnect = { viewModel.clearError(); connectProvider = provider },
                        onDisconnect = { viewModel.disconnectProvider(provider.providerId) },
                        showConnect = true,
                        canDisconnect = false,
                        isSaving = uiState.isSaving,
                        isAmoled = isAmoled,
                        showSource = false
                    )
                }
            }
        }
    }
}

private fun extractOAuthDeviceCode(instructions: String): String? {
    val codePattern = Regex("\\b[A-Z0-9]{3,}(?:-[A-Z0-9]{3,})+\\b")
    return codePattern.find(instructions)?.value
}

@Composable
private fun ProviderRow(
    provider: ProviderToggle,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    showConnect: Boolean,
    canDisconnect: Boolean,
    isSaving: Boolean,
    isAmoled: Boolean,
    showSource: Boolean
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = provider.providerName,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = provider.providerId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
                if (showSource) {
                    provider.source?.let { src ->
                        Text(
                            text = when (src) {
                                "env" -> stringResource(R.string.server_settings_provider_source_env)
                                "api" -> stringResource(R.string.server_settings_provider_source_api)
                                "config" -> stringResource(R.string.server_settings_provider_source_config)
                                "custom" -> stringResource(R.string.server_settings_provider_source_custom)
                                else -> stringResource(R.string.server_settings_provider_source_other)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                if (showConnect) {
                    TextButton(onClick = onConnect, enabled = !isSaving) {
                        Text(stringResource(R.string.connect))
                    }
                } else if (canDisconnect) {
                    TextButton(onClick = onDisconnect, enabled = !isSaving) {
                        Text(stringResource(R.string.disconnect))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.server_settings_provider_env_connected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }
}
