package dev.minios.ocremote.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import dev.minios.ocremote.R
import dev.minios.ocremote.data.update.UpdateInstaller

@Composable
fun rememberUpdateInstallLauncher(
    onInstallerLaunched: () -> Unit,
    onError: ((Throwable) -> Unit)? = null,
): (String) -> Unit {
    val context = LocalContext.current
    val currentOnInstallerLaunched = rememberUpdatedState(onInstallerLaunched)
    val currentOnError = rememberUpdatedState(onError)
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }

    fun launchInstaller(apkPath: String) {
        try {
            context.startActivity(UpdateInstaller.createInstallIntent(context, apkPath))
            pendingPath = null
            currentOnInstallerLaunched.value()
        } catch (error: Exception) {
            pendingPath = null
            if (currentOnError.value != null) {
                currentOnError.value?.invoke(error)
            } else {
                Toast.makeText(context, R.string.update_installer_launch_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        pendingPath?.let { apkPath ->
            if (UpdateInstaller.canRequestPackageInstalls(context)) {
                launchInstaller(apkPath)
            }
        }
    }

    return remember {
        { apkPath ->
            pendingPath = apkPath
            if (UpdateInstaller.canRequestPackageInstalls(context)) {
                launchInstaller(apkPath)
            } else {
                unknownSourcesLauncher.launch(UpdateInstaller.unknownSourcesSettingsIntent(context))
            }
        }
    }
}
