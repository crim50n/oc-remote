package dev.minios.ocremote.ui.screens.settings

import com.composables.icons.lucide.*
import dev.minios.ocremote.ui.components.backIcon

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.minios.ocremote.R
import dev.minios.ocremote.data.repository.BackendSyncState
import dev.minios.ocremote.data.repository.BackendSyncStatus
import dev.minios.ocremote.data.repository.SyncBackend
import dev.minios.ocremote.data.repository.SyncStatus
import dev.minios.ocremote.data.sync.SyncConflictArea
import dev.minios.ocremote.data.sync.SyncConflictSummary
import dev.minios.ocremote.ui.components.AppCardShape
import dev.minios.ocremote.ui.components.AppDialog
import dev.minios.ocremote.ui.components.AppPrimaryButton
import dev.minios.ocremote.ui.components.AppSecondaryButton
import dev.minios.ocremote.ui.components.isAmoledTheme
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SyncSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val operation by viewModel.operation.collectAsState()
    val operationError by viewModel.operationError.collectAsState()
    val working = operation != SyncUiOperation.NONE
    val configured = state.config.primaryBackend != SyncBackend.NONE
    var selectedBackend by remember { mutableStateOf(SyncBackend.GIST) }
    var gistEndpoint by remember { mutableStateOf("") }
    var gistToken by remember { mutableStateOf("") }
    var gistTokenFocused by remember { mutableStateOf(false) }
    var webDavEndpoint by remember { mutableStateOf("") }
    var webDavUsername by remember { mutableStateOf("") }
    var webDavPassword by remember { mutableStateOf("") }
    var webDavPasswordFocused by remember { mutableStateOf(false) }
    var documentUri by remember { mutableStateOf("") }
    var documentName by remember { mutableStateOf("") }
    var documentGrantFlags by remember { mutableStateOf(0) }
    var documentPickerError by remember { mutableStateOf<String?>(null) }
    var includePasswords by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var passphraseFocused by remember { mutableStateOf(false) }
    var autoSync by remember { mutableStateOf(false) }
    var showVersionDialog by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    fun acceptDocument(uri: Uri, flags: Int) {
        val accessFlags = flags and DOCUMENT_ACCESS_FLAGS
        if (flags and DOCUMENT_REQUIRED_RESULT_FLAGS == DOCUMENT_REQUIRED_RESULT_FLAGS) {
            documentUri = uri.toString()
            documentName = documentDisplayName(context, uri)
            documentGrantFlags = accessFlags
            documentPickerError = null
        } else {
            documentPickerError = context.getString(R.string.sync_document_permission_error)
        }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> acceptDocument(uri, result.data?.flags ?: 0) }
        }
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> acceptDocument(uri, result.data?.flags ?: 0) }
        }
    }
    val isAmoled = isAmoledTheme()
    val switchColors = if (isAmoled) {
        SwitchDefaults.colors(
            checkedThumbColor = MaterialTheme.colorScheme.primary,
            checkedTrackColor = Color.Black,
            checkedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            uncheckedTrackColor = Color.Black,
            uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f),
        )
    } else {
        SwitchDefaults.colors()
    }
    val connectionChanged = selectedBackend != state.config.primaryBackend ||
        (selectedBackend == SyncBackend.GIST) != state.config.gist.enabled ||
        gistEndpoint.trim() != state.config.gist.endpoint ||
        gistToken.isNotBlank() ||
        (selectedBackend == SyncBackend.WEBDAV) != state.config.webDav.enabled ||
        webDavEndpoint.trim() != state.config.webDav.endpoint ||
        webDavUsername.trim() != state.config.webDav.username ||
        webDavPassword.isNotBlank() ||
        (selectedBackend == SyncBackend.DOCUMENT) != state.config.document.enabled ||
        documentUri != state.config.document.endpoint ||
        includePasswords != state.config.includeEncryptedPasswords ||
        passphrase.isNotBlank() ||
        autoSync != state.config.autoSync

    LaunchedEffect(
        state.config,
        state.hasGithubToken,
        state.hasWebDavPassword,
        state.hasSyncPassphrase,
    ) {
        selectedBackend = state.config.primaryBackend.takeIf { it != SyncBackend.NONE } ?: SyncBackend.GIST
        gistEndpoint = state.config.gist.endpoint
        webDavEndpoint = state.config.webDav.endpoint
        webDavUsername = state.config.webDav.username
        documentUri = state.config.document.endpoint
        documentName = state.config.document.endpoint.takeIf(String::isNotBlank)
            ?.let { documentDisplayName(context, Uri.parse(it)) }
            .orEmpty()
        documentGrantFlags = 0
        includePasswords = state.config.includeEncryptedPasswords
        autoSync = state.config.autoSync
        gistToken = ""
        webDavPassword = ""
        passphrase = ""
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sync_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(backIcon(), contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.sync_settings_desc_v2),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(stringResource(R.string.sync_common_settings), style = MaterialTheme.typography.titleSmall)
            ListItem(
                headlineContent = { Text(stringResource(R.string.sync_automatic)) },
                supportingContent = { Text(stringResource(R.string.sync_automatic_desc)) },
                leadingContent = { Icon(Lucide.CloudCog, contentDescription = null) },
                trailingContent = {
                    Switch(checked = autoSync, onCheckedChange = { autoSync = it }, colors = switchColors)
                },
                modifier = Modifier.clickable { autoSync = !autoSync },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.sync_encrypted_passwords)) },
                supportingContent = { Text(stringResource(R.string.sync_encrypted_passwords_desc)) },
                leadingContent = { Icon(Lucide.Lock, contentDescription = null) },
                trailingContent = {
                    Switch(
                        checked = includePasswords,
                        onCheckedChange = { includePasswords = it },
                        colors = switchColors,
                    )
                },
                modifier = Modifier.clickable { includePasswords = !includePasswords },
            )
            if (includePasswords) {
                SecretTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    focused = passphraseFocused,
                    onFocusChanged = { passphraseFocused = it },
                    stored = state.hasSyncPassphrase,
                    label = stringResource(R.string.sync_passphrase),
                    supportingText = stringResource(R.string.sync_passphrase_desc),
                )
            }

            HorizontalDivider()
            Text(stringResource(R.string.sync_backend), style = MaterialTheme.typography.titleSmall)
            Text(
                text = stringResource(R.string.sync_single_storage_desc_v2),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncBackendChip(
                    selected = selectedBackend == SyncBackend.GIST,
                    onClick = { selectedBackend = SyncBackend.GIST },
                    label = stringResource(R.string.sync_backend_gist),
                    isAmoled = isAmoled,
                )
                SyncBackendChip(
                    selected = selectedBackend == SyncBackend.WEBDAV,
                    onClick = { selectedBackend = SyncBackend.WEBDAV },
                    label = stringResource(R.string.sync_backend_webdav),
                    isAmoled = isAmoled,
                )
                SyncBackendChip(
                    selected = selectedBackend == SyncBackend.DOCUMENT,
                    onClick = { selectedBackend = SyncBackend.DOCUMENT },
                    label = stringResource(R.string.sync_backend_document),
                    isAmoled = isAmoled,
                )
            }

            when (selectedBackend) {
                SyncBackend.GIST -> {
                    SyncBackendCard(
                        title = stringResource(R.string.sync_backend_gist),
                        state = state.gistState,
                        configured = configured && state.config.primaryBackend == SyncBackend.GIST,
                        isAmoled = isAmoled,
                    ) {
                        OutlinedTextField(
                            value = gistEndpoint,
                            onValueChange = { gistEndpoint = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.sync_gist_id)) },
                            supportingText = { Text(stringResource(R.string.sync_gist_id_desc)) },
                            singleLine = true,
                        )
                        SecretTextField(
                            value = gistToken,
                            onValueChange = { gistToken = it },
                            focused = gistTokenFocused,
                            onFocusChanged = { gistTokenFocused = it },
                            stored = state.hasGithubToken,
                            label = stringResource(R.string.sync_github_token),
                            supportingText = stringResource(R.string.sync_credential_saved_hint),
                        )
                        if (state.config.gist.enabled && gistEndpoint.isNotBlank()) {
                            Row(
                                modifier = Modifier
                                    .clickable {
                                        val id = gistEndpoint.trimEnd('/').substringAfterLast('/')
                                        uriHandler.openUri("https://gist.github.com/$id")
                                    }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Lucide.ExternalLink, contentDescription = null)
                                Text(
                                    stringResource(R.string.sync_open_gist),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                SyncBackend.WEBDAV -> {
                    SyncBackendCard(
                        title = stringResource(R.string.sync_backend_webdav),
                        state = state.webDavState,
                        configured = configured && state.config.primaryBackend == SyncBackend.WEBDAV,
                        isAmoled = isAmoled,
                    ) {
                        OutlinedTextField(
                            value = webDavEndpoint,
                            onValueChange = { webDavEndpoint = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.sync_webdav_url)) },
                            supportingText = { Text(stringResource(R.string.sync_webdav_url_desc)) },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = webDavUsername,
                            onValueChange = { webDavUsername = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.sync_username)) },
                            singleLine = true,
                        )
                        SecretTextField(
                            value = webDavPassword,
                            onValueChange = { webDavPassword = it },
                            focused = webDavPasswordFocused,
                            onFocusChanged = { webDavPasswordFocused = it },
                            stored = state.hasWebDavPassword,
                            label = stringResource(R.string.sync_webdav_password),
                            supportingText = stringResource(R.string.sync_credential_saved_hint),
                        )
                    }
                }
                SyncBackend.DOCUMENT -> {
                    SyncBackendCard(
                        title = stringResource(R.string.sync_backend_document),
                        state = state.documentState,
                        configured = configured && state.config.primaryBackend == SyncBackend.DOCUMENT,
                        isAmoled = isAmoled,
                    ) {
                        Text(
                            text = stringResource(R.string.sync_document_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (documentUri.isNotBlank()) {
                            ListItem(
                                headlineContent = {
                                    Text(documentName.ifBlank { stringResource(R.string.sync_document_selected) })
                                },
                                supportingContent = { Text(stringResource(R.string.sync_document_access_persisted)) },
                                leadingContent = { Icon(Lucide.FileJson2, contentDescription = null) },
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.sync_document_not_selected),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        documentPickerError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            AppSecondaryButton(
                                onClick = { openDocument.launch(documentPickerIntent(Intent.ACTION_OPEN_DOCUMENT)) },
                                modifier = Modifier.weight(1f),
                                outlined = true,
                            ) {
                                Text(stringResource(R.string.sync_document_choose))
                            }
                            AppSecondaryButton(
                                onClick = {
                                    createDocument.launch(
                                        documentPickerIntent(Intent.ACTION_CREATE_DOCUMENT)
                                            .putExtra(Intent.EXTRA_TITLE, "OCRemote.json"),
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                outlined = true,
                            ) {
                                Text(stringResource(R.string.sync_document_create))
                            }
                        }
                    }
                }
                SyncBackend.NONE -> Unit
            }

            HorizontalDivider()
            val statusText = when (state.status) {
                SyncStatus.DISCONNECTED -> stringResource(R.string.sync_status_disconnected)
                SyncStatus.IDLE -> stringResource(R.string.sync_status_ready)
                SyncStatus.SYNCING -> stringResource(R.string.sync_status_syncing)
                SyncStatus.CONFLICT -> stringResource(R.string.sync_status_conflict)
                SyncStatus.PARTIAL -> stringResource(R.string.sync_status_partial)
                SyncStatus.ERROR -> stringResource(R.string.sync_status_error)
            }
            Text(stringResource(R.string.sync_status, statusText), style = MaterialTheme.typography.bodyMedium)
            state.lastSyncTimestamp?.let {
                Text(
                    text = stringResource(
                        R.string.sync_last_sync,
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            (operationError ?: state.error?.takeUnless { state.status == SyncStatus.CONFLICT })?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (state.status == SyncStatus.CONFLICT) {
                Text(
                    stringResource(R.string.sync_conflict_desc),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                state.conflictSummary?.let { summary ->
                    SyncConflictDetails(summary)
                }
                AppSecondaryButton(
                    onClick = { showVersionDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !working,
                    outlined = true,
                ) {
                    Text(stringResource(R.string.sync_choose_version))
                }
                AppPrimaryButton(
                    onClick = viewModel::forceUpload,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !working,
                ) {
                    Text(stringResource(R.string.sync_keep_local))
                }
            }

            AppPrimaryButton(
                onClick = {
                    viewModel.saveConfiguration(
                        primaryBackend = selectedBackend,
                        gistEnabled = selectedBackend == SyncBackend.GIST,
                        gistEndpoint = gistEndpoint,
                        token = gistToken,
                        webDavEnabled = selectedBackend == SyncBackend.WEBDAV,
                        webDavEndpoint = webDavEndpoint,
                        webDavUsername = webDavUsername,
                        webDavPassword = webDavPassword,
                        documentEnabled = selectedBackend == SyncBackend.DOCUMENT,
                        documentUri = documentUri,
                        documentGrantFlags = documentGrantFlags,
                        includePasswords = includePasswords,
                        passphrase = passphrase,
                        autoSync = autoSync,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !working && connectionChanged &&
                    (selectedBackend != SyncBackend.DOCUMENT || documentUri.isNotBlank()),
            ) {
                if (operation == SyncUiOperation.SAVING) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(stringResource(R.string.sync_status_saving))
                } else {
                    Text(stringResource(R.string.sync_save_connection))
                }
            }
            if (configured) {
                Text(
                    text = stringResource(R.string.sync_direction_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppPrimaryButton(
                    onClick = viewModel::synchronize,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !working && !connectionChanged,
                ) {
                    if (operation == SyncUiOperation.SYNCING) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(stringResource(R.string.sync_status_syncing))
                    } else {
                        Icon(Lucide.RefreshCw, contentDescription = null)
                        Spacer(Modifier.padding(horizontal = 4.dp))
                        Text(stringResource(R.string.sync_synchronize_changes))
                    }
                }
                if (state.status != SyncStatus.CONFLICT) {
                    AppSecondaryButton(
                        onClick = { showVersionDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !working,
                        outlined = true,
                    ) {
                        Text(stringResource(R.string.sync_choose_version))
                    }
                }
                AppSecondaryButton(
                    onClick = viewModel::disconnect,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !working,
                    outlined = true,
                ) {
                    Text(stringResource(R.string.sync_disconnect))
                }
            }
            Text(
                text = stringResource(R.string.sync_privacy_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }

    if (showVersionDialog) {
        AppDialog(onDismissRequest = { showVersionDialog = false }, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.sync_choose_version), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.sync_choose_version_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AppSecondaryButton(
                    onClick = {
                        showVersionDialog = false
                        viewModel.forceDownload(state.config.primaryBackend)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    outlined = true,
                ) {
                    Text(stringResource(R.string.sync_use_remote))
                }
                AppPrimaryButton(
                    onClick = {
                        showVersionDialog = false
                        viewModel.forceUpload()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.sync_keep_local))
                }
                AppSecondaryButton(
                    onClick = { showVersionDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun SyncConflictDetails(summary: SyncConflictSummary) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(R.string.sync_conflict_differences), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    R.string.sync_conflict_local_snapshot,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(summary.localUpdatedAt)),
                    summary.localGeneration,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(
                    R.string.sync_conflict_remote_snapshot,
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(summary.remoteUpdatedAt)),
                    summary.remoteGeneration,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            summary.differences.forEach { difference ->
                val area = stringResource(
                    when (difference.area) {
                        SyncConflictArea.SETTINGS -> R.string.sync_conflict_area_settings
                        SyncConflictArea.SERVERS -> R.string.sync_conflict_area_servers
                        SyncConflictArea.CATEGORIES -> R.string.sync_conflict_area_categories
                        SyncConflictArea.CATEGORY_ASSIGNMENTS -> R.string.sync_conflict_area_assignments
                        SyncConflictArea.FAVORITES -> R.string.sync_conflict_area_favorites
                        SyncConflictArea.HIDDEN_MODELS -> R.string.sync_conflict_area_hidden_models
                    },
                )
                Text(
                    text = if (difference.changedCount != null) {
                        stringResource(R.string.sync_conflict_changed_values, area, difference.changedCount)
                    } else {
                        stringResource(
                            R.string.sync_conflict_item_counts,
                            area,
                            difference.localCount,
                            difference.remoteCount,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (summary.encryptedPasswordsHidden) {
                Text(
                    stringResource(R.string.sync_conflict_passwords_hidden),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.sync_conflict_comparison_limit),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SyncBackendCard(
    title: String,
    state: BackendSyncState,
    configured: Boolean,
    isAmoled: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = AppCardShape,
        colors = CardDefaults.cardColors(
            containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainer,
        ),
        border = if (isAmoled) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Database, contentDescription = null)
                Text(
                    text = title,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            content()
            if (configured) BackendStatusText(state)
        }
    }
}

@Composable
private fun SyncBackendChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    isAmoled: Boolean,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = if (isAmoled) {
            FilterChipDefaults.filterChipColors(
                containerColor = Color.Black,
                selectedContainerColor = Color.Black,
                selectedLabelColor = MaterialTheme.colorScheme.primary,
            )
        } else {
            FilterChipDefaults.filterChipColors()
        },
        border = if (isAmoled) {
            BorderStroke(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            )
        } else {
            null
        },
    )
}

@Composable
private fun BackendStatusText(state: BackendSyncState) {
    val status = when (state.status) {
        BackendSyncStatus.DISABLED -> stringResource(R.string.sync_backend_status_disabled)
        BackendSyncStatus.IDLE -> stringResource(R.string.sync_status_ready)
        BackendSyncStatus.SYNCING -> stringResource(R.string.sync_status_syncing)
        BackendSyncStatus.CONFLICT -> stringResource(R.string.sync_status_conflict)
        BackendSyncStatus.DIVERGED -> stringResource(R.string.sync_backend_status_diverged)
        BackendSyncStatus.ERROR -> stringResource(R.string.sync_status_error)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = stringResource(R.string.sync_backend_status, status),
            style = MaterialTheme.typography.bodySmall,
            color = if (state.status in setOf(BackendSyncStatus.ERROR, BackendSyncStatus.DIVERGED)) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    focused: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    stored: Boolean,
    label: String,
    supportingText: String,
) {
    OutlinedTextField(
        value = if (stored && value.isBlank() && !focused) STORED_SECRET_PLACEHOLDER else value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { onFocusChanged(it.isFocused) },
        label = { Text(label) },
        supportingText = {
            Text(if (stored && value.isBlank()) stringResource(R.string.sync_credential_stored) else supportingText)
        },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
    )
}

private const val STORED_SECRET_PLACEHOLDER = "********"
private const val DOCUMENT_ACCESS_FLAGS =
    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
private const val DOCUMENT_REQUIRED_RESULT_FLAGS =
    DOCUMENT_ACCESS_FLAGS or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

private fun documentPickerIntent(action: String): Intent = Intent(action).apply {
    addCategory(Intent.CATEGORY_OPENABLE)
    type = "*/*"
    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
    addFlags(DOCUMENT_ACCESS_FLAGS or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}

private fun documentDisplayName(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }
}
