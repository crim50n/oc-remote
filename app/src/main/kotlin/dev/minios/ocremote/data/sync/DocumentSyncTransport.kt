package dev.minios.ocremote.data.sync

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest

internal fun documentRevision(content: String): String = "sha256:" + MessageDigest.getInstance("SHA-256")
    .digest(content.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

internal fun requireExpectedDocumentRevision(expected: String, actual: String?) {
    if (expected != actual) throw SyncHttpException("The selected sync file changed during synchronization")
}

class DocumentSyncTransport(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : SyncTransport {
    override suspend fun read(): RemoteSyncFile? = withContext(Dispatchers.IO) {
        requirePersistedAccess()
        val content = try {
            resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val value = reader.readText()
                require(value.length <= MAX_DOCUMENT_CHARS) { "The selected sync file is too large" }
                value
            } ?: throw IOException("The selected sync file cannot be opened")
        } catch (e: SecurityException) {
            throw SyncPermissionException(PERMISSION_ERROR, e)
        }
        if (content.isBlank()) null else RemoteSyncFile(content, documentRevision(content), uri.toString())
    }

    override suspend fun write(content: String, expectedRevision: String?, create: Boolean): String? =
        withContext(Dispatchers.IO) {
            requirePersistedAccess()
            val current = read()
            if (create) {
                require(current == null) { "The selected sync file already contains data" }
            } else if (expectedRevision != null) {
                requireExpectedDocumentRevision(expectedRevision, current?.revision)
            }
            try {
                resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                    writer.write(content)
                } ?: throw IOException("The selected sync file cannot be opened for writing")
            } catch (e: SecurityException) {
                throw SyncPermissionException(PERMISSION_ERROR, e)
            }
            documentRevision(content)
        }

    private fun requirePersistedAccess() {
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        if (permission?.isReadPermission != true || !permission.isWritePermission) {
            throw SyncPermissionException(PERMISSION_ERROR)
        }
    }

    companion object {
        const val PERMISSION_ERROR = "Access to the selected sync file was revoked. Choose the file again."
        private const val MAX_DOCUMENT_CHARS = 5 * 1024 * 1024
    }
}
