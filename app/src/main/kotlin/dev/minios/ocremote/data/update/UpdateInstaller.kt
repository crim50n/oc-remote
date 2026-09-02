package dev.minios.ocremote.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

object UpdateInstaller {
    fun canRequestPackageInstalls(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()
    }

    fun unknownSourcesSettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri(),
        )
    }

    fun createInstallIntent(context: Context, apkPath: String): Intent {
        val apk = requireUpdateApk(context, apkPath)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun requireUpdateApk(context: Context, apkPath: String): File {
        val updatesDir = File(context.cacheDir, "updates").canonicalFile
        val apk = File(apkPath).canonicalFile
        require(apk.parentFile == updatesDir && apk.isFile && apk.extension == "apk") {
            "APK must be a file in the update cache directory"
        }
        return apk
    }
}
