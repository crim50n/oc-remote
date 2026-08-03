package dev.minios.ocremote.data.sync

data class RemoteSyncFile(
    val content: String,
    val revision: String?,
    val resolvedEndpoint: String? = null,
)

interface SyncTransport {
    suspend fun read(): RemoteSyncFile?
    suspend fun write(content: String, expectedRevision: String? = null, create: Boolean = false): String?
}

class SyncHttpException(message: String) : Exception(message)

enum class SyncDecision { MISSING_UPLOAD, UP_TO_DATE, PULL_REMOTE, PUSH_LOCAL, CONFLICT }
enum class BackupSyncDecision { CREATE, UP_TO_DATE, UPDATE, DIVERGED }

fun decideSync(
    remoteExists: Boolean,
    storedRevision: String?,
    remoteRevision: String?,
    storedLocalHash: String?,
    localHash: String,
): SyncDecision {
    if (!remoteExists) return SyncDecision.MISSING_UPLOAD
    if (storedRevision == null || storedLocalHash == null) return SyncDecision.CONFLICT
    val remoteChanged = storedRevision != remoteRevision
    val localChanged = storedLocalHash != localHash
    return when {
        remoteChanged && localChanged -> SyncDecision.CONFLICT
        remoteChanged -> SyncDecision.PULL_REMOTE
        localChanged -> SyncDecision.PUSH_LOCAL
        else -> SyncDecision.UP_TO_DATE
    }
}

fun decideBackupSync(
    remoteExists: Boolean,
    acknowledgedContentHash: String?,
    remoteContentHash: String?,
    canonicalContentHash: String,
): BackupSyncDecision {
    if (!remoteExists) return BackupSyncDecision.CREATE
    if (remoteContentHash == canonicalContentHash) return BackupSyncDecision.UP_TO_DATE
    if (acknowledgedContentHash != null && remoteContentHash == acknowledgedContentHash) {
        return BackupSyncDecision.UPDATE
    }
    return BackupSyncDecision.DIVERGED
}
