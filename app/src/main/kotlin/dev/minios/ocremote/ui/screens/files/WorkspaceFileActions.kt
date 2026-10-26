package dev.minios.ocremote.ui.screens.files

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import java.net.URLConnection

data class WorkspaceDownloadDestination(val name: String, val mime: String)
data class DownloadedWorkspaceFile(val uri: String, val mime: String, val name: String, val path: String)
data class WorkspaceTransfer(val name: String, val bytes: Long = 0, val total: Long? = null)
sealed interface WorkspaceFileEvent {
    data class Saved(val file: DownloadedWorkspaceFile) : WorkspaceFileEvent
    data class Open(val file: DownloadedWorkspaceFile, val share: Boolean) : WorkspaceFileEvent
    data class Error(val message: String?) : WorkspaceFileEvent
    data class UploadDestination(val source: String, val path: String) : WorkspaceFileEvent
    data object Uploaded : WorkspaceFileEvent
}

internal fun workspaceMime(name: String, provided: String? = null, text: Boolean = false): String {
    // APKs are often served or saved by a document provider as application/octet-stream.
    if (name.endsWith(".apk", ignoreCase = true)) return "application/vnd.android.package-archive"
    return provided?.substringBefore(';')?.takeUnless { it == "application/octet-stream" || it.isBlank() }
        ?: URLConnection.guessContentTypeFromName(name)
        ?: if (text) "text/plain" else "application/octet-stream"
}

internal fun workspaceOpenIntent(file: DownloadedWorkspaceFile, share: Boolean): Intent {
    val uri = Uri.parse(file.uri)
    return Intent(if (share) Intent.ACTION_SEND else Intent.ACTION_VIEW).apply {
        if (share) { type = file.mime; putExtra(Intent.EXTRA_STREAM, uri) }
        else setDataAndType(uri, file.mime)
        clipData = ClipData.newRawUri(file.name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

class WorkspaceDownloadContract : ActivityResultContract<WorkspaceDownloadDestination, Uri?>() {
    override fun createIntent(context: Context, input: WorkspaceDownloadDestination) = Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType(input.mime).putExtra(Intent.EXTRA_TITLE, input.name)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
