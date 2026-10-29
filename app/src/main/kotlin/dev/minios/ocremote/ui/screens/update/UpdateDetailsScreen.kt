package dev.minios.ocremote.ui.screens.update
import dev.minios.ocremote.ui.components.readableContentWidth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import dev.minios.ocremote.R
import dev.minios.ocremote.data.update.UpdatePolicy
import dev.minios.ocremote.data.update.UpdateState
import dev.minios.ocremote.ui.components.AppPrimaryButton
import dev.minios.ocremote.ui.components.backIcon
import dev.minios.ocremote.ui.components.rememberInteractiveMarkdownLinks
import dev.minios.ocremote.ui.components.rememberUpdateInstallLauncher

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateDetailsScreen(onNavigateBack: () -> Unit, showTopBar: Boolean = true, viewModel: UpdateDetailsViewModel = hiltViewModel()) {
    val notes by viewModel.notes.collectAsState()
    val update by viewModel.updateState.collectAsState()
    val release = notes.release
    val uriHandler = LocalUriHandler.current
    val launchInstaller = rememberUpdateInstallLauncher(viewModel::installerLaunched)
    val ready = update as? UpdateState.ReadyToInstall
    val lifecycleState by androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    LaunchedEffect(ready?.apkPath, lifecycleState) {
        if (lifecycleState == androidx.lifecycle.Lifecycle.State.RESUMED) ready?.let { launchInstaller(it.apkPath) }
    }
    val downloading = update as? UpdateState.Downloading
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            if (showTopBar) TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.update_whats_new))
                        release?.let { Text(stringResource(R.string.about_version, it.versionName),
                            style = MaterialTheme.typography.bodySmall) }
                    }
                },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(backIcon(), stringResource(R.string.back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            if (release != null) Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.readableContentWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    if (downloading != null) {
                        val progress = downloading.progressPercent
                        if (progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(if (progress == null) stringResource(R.string.update_downloading)
                            else stringResource(R.string.update_downloading_percent, progress))
                    }
                    if (update is UpdateState.Error) Text(stringResource(R.string.update_prepare_error), color = MaterialTheme.colorScheme.error)
                    AppPrimaryButton(
                        modifier = Modifier.fillMaxWidth(), enabled = downloading == null,
                        onClick = {
                            if (ready != null) launchInstaller(ready.apkPath)
                            else if (UpdatePolicy.isInstallable(release)) viewModel.prepareInstall(release)
                            else uriHandler.openUri(release.releaseUrl)
                        },
                    ) {
                        Text(stringResource(when {
                            downloading != null -> R.string.update_downloading
                            ready != null -> R.string.update_opening_installer
                            !UpdatePolicy.isInstallable(release) -> R.string.update_open_release
                            update is UpdateState.Error -> R.string.update_retry
                            else -> R.string.update_download_and_install
                        }))
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).readableContentWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!showTopBar && release != null) Text(stringResource(R.string.about_version, release.versionName),
                style = MaterialTheme.typography.titleSmall)
            release?.publishedAt?.let { timestamp ->
                val date = remember(timestamp) {
                    runCatching { java.time.OffsetDateTime.parse(timestamp).toLocalDate().format(
                        java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)) }.getOrNull()
                }
                if (date != null) Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                notes.loading -> CircularProgressIndicator()
                notes.failed -> {
                    Text(stringResource(R.string.update_notes_error))
                    TextButton(onClick = viewModel::loadNotes) { Text(stringResource(R.string.retry)) }
                }
                release?.releaseNotes.isNullOrBlank() -> Text(stringResource(R.string.update_notes_empty))
                else -> {
                    val body = MaterialTheme.typography.bodyMedium
                    val heading = MaterialTheme.typography.titleSmall
                    Markdown(
                        content = release?.releaseNotes.orEmpty(),
                        annotator = rememberInteractiveMarkdownLinks(),
                        typography = markdownTypography(h1 = heading, h2 = heading, h3 = heading, h4 = heading,
                            h5 = heading, h6 = heading, text = body, paragraph = body, bullet = body, list = body, ordered = body),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
