package dev.minios.ocremote.ui.screens.settings

import android.content.Intent
import android.content.ClipData
import android.os.Build
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.minios.ocremote.R
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.data.repository.DiagnosticLogRepository
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.minios.ocremote.ui.components.appPopupBorder
import dev.minios.ocremote.ui.components.appPopupContainerColor
import dev.minios.ocremote.ui.components.isAmoledTheme
import dev.minios.ocremote.ui.components.AppDialog
import dev.minios.ocremote.ui.components.AppPrimaryButton
import dev.minios.ocremote.ui.components.AppSecondaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onNavigateBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsState()
    val logLevel by viewModel.logLevel.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val isAmoled = isAmoledTheme()
    val scope = rememberCoroutineScope()
    var showActionsMenu by remember { mutableStateOf(false) }
    var showLevelDialog by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    val logLevelLabel = stringResource(
        when (logLevel) {
            "ERROR" -> R.string.diagnostics_level_error
            "WARN" -> R.string.diagnostics_level_warn
            "DEBUG" -> R.string.diagnostics_level_debug
            else -> R.string.diagnostics_level_info
        },
    )

    suspend fun exportText(): String = buildString {
        val timeRange = entries.takeIf { it.isNotEmpty() }?.let {
            "${java.time.Instant.ofEpochMilli(it.first().timestamp)}..${java.time.Instant.ofEpochMilli(it.last().timestamp)}"
        } ?: "empty"
        appendLine("OC Remote diagnostics")
        appendLine("Generated: ${java.time.Instant.now()}")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Persistent log level: $logLevel")
        appendLine("Time range: $timeRange")
        appendLine("Dropped queue entries: ${viewModel.droppedEntryCount()}")
        appendLine("Included: lifecycle, connection, REST/SSE result classes, reducer transitions, updates, and crashes; no chat or terminal payloads")
        appendLine()
        append(viewModel.export().ifBlank { context.getString(R.string.diagnostics_empty) })
    }

    fun shareAsFile() {
        scope.launch {
            val text = exportText()
            val file = withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
                directory.listFiles()?.forEach { it.delete() }
                File(directory, "oc-remote-diagnostics-${System.currentTimeMillis()}.txt").apply { writeText(text) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri("OC Remote diagnostics", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    context.getString(R.string.diagnostics_share),
                ),
            )
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = ::shareAsFile, enabled = entries.isNotEmpty()) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.diagnostics_share))
                    }
                    Box {
                        IconButton(onClick = { showActionsMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                        }
                        DropdownMenu(
                            expanded = showActionsMenu,
                            onDismissRequest = { showActionsMenu = false },
                            modifier = Modifier.appPopupBorder(),
                            containerColor = appPopupContainerColor(),
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.diagnostics_copy)) },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                enabled = entries.isNotEmpty(),
                                onClick = {
                                    scope.launch { clipboard.setText(AnnotatedString(exportText())) }
                                    showActionsMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.diagnostics_clear),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                enabled = entries.isNotEmpty(),
                                onClick = {
                                    showActionsMenu = false
                                    showClearConfirmation = true
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Surface(
                onClick = { showLevelDialog = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                shape = RoundedCornerShape(16.dp),
                color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainer,
                border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.diagnostics_log_level), style = MaterialTheme.typography.titleSmall)
                        Text(logLevelLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(entries.size.toString(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (entries.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(16.dp))
                    Text(
                        stringResource(R.string.diagnostics_empty),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        stringResource(R.string.diagnostics_empty_desc),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries.asReversed()) { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainer,
                            ),
                            border = if (isAmoled) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "${DateFormat.getDateTimeInstance().format(Date(entry.timestamp))} · ${entry.level} · ${entry.category}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(entry.message, style = MaterialTheme.typography.bodyMedium)
                                entry.details.toSortedMap().forEach { (key, value) ->
                                    Text(
                                        "$key=$value",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirmation) {
        AppDialog(onDismissRequest = { showClearConfirmation = false }) {
            Text(
                text = stringResource(R.string.diagnostics_clear_confirm_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp),
            )
            Text(
                text = stringResource(R.string.diagnostics_clear_confirm_message),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                AppSecondaryButton(onClick = { showClearConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
                AppPrimaryButton(
                    onClick = {
                        showClearConfirmation = false
                        viewModel.clear()
                    },
                    destructive = true,
                ) {
                    Text(stringResource(R.string.diagnostics_clear))
                }
            }
        }
    }

    if (showLevelDialog) {
        SettingsPickerDialog(
            title = stringResource(R.string.diagnostics_log_level),
            options = DiagnosticLogRepository.LOG_LEVELS.map { level ->
                level to stringResource(
                    when (level) {
                        "ERROR" -> R.string.diagnostics_level_error
                        "WARN" -> R.string.diagnostics_level_warn
                        "DEBUG" -> R.string.diagnostics_level_debug
                        else -> R.string.diagnostics_level_info
                    },
                )
            },
            selectedKey = logLevel,
            onSelect = { level ->
                viewModel.setLogLevel(level)
                showLevelDialog = false
            },
            onDismiss = { showLevelDialog = false },
            maxHeight = 460,
        )
    }
}
