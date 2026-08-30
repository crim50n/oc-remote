package dev.minios.ocremote.ui.screens.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.minios.ocremote.BuildConfig
import dev.minios.ocremote.R
import dev.minios.ocremote.data.update.UpdateState
import dev.minios.ocremote.data.update.UpdatePolicy
import dev.minios.ocremote.ui.components.AppCardShape
import dev.minios.ocremote.ui.components.AppPrimaryButton
import dev.minios.ocremote.ui.components.appAmoledBorder
import dev.minios.ocremote.ui.components.isAmoledTheme
import dev.minios.ocremote.ui.components.rememberUpdateInstallLauncher
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val version = BuildConfig.VERSION_NAME
    val updateState by viewModel.updateState.collectAsState()
    val launchInstaller = rememberUpdateInstallLauncher(viewModel::installerLaunched)
    val isAmoled = isAmoledTheme()
    val cardColor = if (isAmoled) Color.Black else MaterialTheme.colorScheme.surfaceContainer

    val readyUpdate = updateState as? UpdateState.ReadyToInstall
    LaunchedEffect(readyUpdate?.apkPath) {
        readyUpdate?.let { launchInstaller(it.apkPath) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            // App name
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(4.dp))

            // Version
            Text(
                text = stringResource(R.string.about_version, version),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // Description
            Text(
                text = stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(4.dp))

            // Unofficial notice
            Text(
                text = stringResource(R.string.about_unofficial),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = AppCardShape,
                colors = CardDefaults.outlinedCardColors(containerColor = cardColor),
                border = appAmoledBorder() ?: BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_check_updates)) },
                    supportingContent = {
                        Text(
                            when (val state = updateState) {
                                UpdateState.Idle -> stringResource(R.string.about_check_updates_desc)
                                UpdateState.Checking -> stringResource(R.string.update_checking)
                                UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
                                is UpdateState.Downloading -> state.progressPercent?.let {
                                    stringResource(R.string.update_downloading_percent, it)
                                } ?: stringResource(R.string.update_downloading)
                                is UpdateState.ReadyToInstall -> stringResource(R.string.update_available_message, state.release.versionName)
                                is UpdateState.Available -> stringResource(R.string.update_available_message, state.release.versionName)
                                is UpdateState.Error -> if (state.release != null) {
                                    stringResource(R.string.update_prepare_error)
                                } else {
                                    stringResource(R.string.update_error)
                                }
                            },
                        )
                    },
                    leadingContent = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                    trailingContent = {
                        if (updateState == UpdateState.Checking) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                val buttonModifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
                when (val state = updateState) {
                    UpdateState.Idle, UpdateState.UpToDate -> AppPrimaryButton(
                        onClick = viewModel::checkForUpdates,
                        modifier = buttonModifier,
                    ) { Text(stringResource(R.string.about_check_updates)) }

                    UpdateState.Checking -> AppPrimaryButton(
                        onClick = {},
                        enabled = false,
                        modifier = buttonModifier,
                    ) { Text(stringResource(R.string.about_check_updates)) }

                    is UpdateState.Downloading -> AppPrimaryButton(
                        onClick = {},
                        enabled = false,
                        modifier = buttonModifier,
                    ) { Text(stringResource(R.string.update_downloading)) }

                    is UpdateState.ReadyToInstall -> AppPrimaryButton(
                        onClick = { launchInstaller(state.apkPath) },
                        modifier = buttonModifier,
                    ) { Text(stringResource(R.string.update_opening_installer)) }

                    is UpdateState.Available -> AppPrimaryButton(
                        onClick = {
                            if (UpdatePolicy.isInstallable(state.release)) {
                                viewModel.prepareInstall(state.release)
                            } else {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.release.releaseUrl)))
                            }
                        },
                        modifier = buttonModifier,
                    ) {
                        Text(
                            stringResource(
                                if (UpdatePolicy.isInstallable(state.release)) {
                                    R.string.update_download_and_install
                                } else {
                                    R.string.update_open_release
                                },
                            ),
                        )
                    }

                    is UpdateState.Error -> AppPrimaryButton(
                        onClick = {
                            state.release?.let { release ->
                                if (UpdatePolicy.isInstallable(release)) {
                                    viewModel.prepareInstall(release)
                                } else {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.releaseUrl)))
                                }
                            } ?: viewModel.checkForUpdates()
                        },
                        modifier = buttonModifier,
                    ) {
                        Text(
                            stringResource(
                                when {
                                    state.release == null -> R.string.about_check_updates
                                    UpdatePolicy.isInstallable(state.release) -> R.string.update_retry
                                    else -> R.string.update_open_release
                                },
                            ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Links
            val githubUrl = stringResource(R.string.about_github_url)
            val opencodeUrl = stringResource(R.string.about_opencode_url)

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = AppCardShape,
                colors = CardDefaults.outlinedCardColors(
                    containerColor = cardColor,
                ),
                border = appAmoledBorder() ?: BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                val itemColors = ListItemDefaults.colors(containerColor = Color.Transparent)
                // GitHub repo
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_github)) },
                    supportingContent = {
                        Text(githubUrl, style = MaterialTheme.typography.bodySmall)
                    },
                    leadingContent = {
                        Icon(Icons.Default.Code, contentDescription = null)
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    },
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
                    },
                    colors = itemColors,
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                // OpenCode project
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_opencode)) },
                    supportingContent = {
                        Text(opencodeUrl, style = MaterialTheme.typography.bodySmall)
                    },
                    leadingContent = {
                        Icon(Icons.Default.Code, contentDescription = null)
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    },
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(opencodeUrl)))
                    },
                    colors = itemColors,
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                )

                // License
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_license)) },
                    supportingContent = {
                        Text(stringResource(R.string.about_license_value))
                    },
                    leadingContent = {
                        Icon(Icons.Default.Description, contentDescription = null)
                    },
                    colors = itemColors,
                )
            }
        }
    }
}
