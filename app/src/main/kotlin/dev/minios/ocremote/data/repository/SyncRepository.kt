package dev.minios.ocremote.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.minios.ocremote.data.sync.BackupSyncDecision
import dev.minios.ocremote.data.sync.EncryptedSecrets
import dev.minios.ocremote.data.sync.DocumentSyncTransport
import dev.minios.ocremote.data.sync.GistSyncTransport
import dev.minios.ocremote.data.sync.LocalSyncSecretStore
import dev.minios.ocremote.data.sync.PasswordCrypto
import dev.minios.ocremote.data.sync.PasswordSecrets
import dev.minios.ocremote.data.sync.RemoteSyncFile
import dev.minios.ocremote.data.sync.SyncDecision
import dev.minios.ocremote.data.sync.SyncConflictNotifier
import dev.minios.ocremote.data.sync.SyncConflictSummary
import dev.minios.ocremote.data.sync.SyncConflictPreference
import dev.minios.ocremote.data.sync.SemanticSyncData
import dev.minios.ocremote.data.sync.SyncPayload
import dev.minios.ocremote.data.sync.SyncTransport
import dev.minios.ocremote.data.sync.canonicalizeSyncData
import dev.minios.ocremote.data.sync.mergeSyncData
import dev.minios.ocremote.data.sync.WebDavSyncTransport
import dev.minios.ocremote.data.sync.decideBackupSync
import dev.minios.ocremote.data.sync.decideSync
import dev.minios.ocremote.data.sync.decodeSyncPayload
import dev.minios.ocremote.data.sync.buildSyncConflictSummary
import io.ktor.client.HttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class SyncBackend { NONE, GIST, WEBDAV, DOCUMENT }
enum class SyncStatus { DISCONNECTED, IDLE, SYNCING, CONFLICT, PARTIAL, ERROR }
enum class BackendSyncStatus { DISABLED, IDLE, SYNCING, CONFLICT, DIVERGED, ERROR }

data class SyncTargetConfig(
    val enabled: Boolean = false,
    val endpoint: String = "",
    val username: String = "",
)

data class SyncConfig(
    val primaryBackend: SyncBackend = SyncBackend.NONE,
    val gist: SyncTargetConfig = SyncTargetConfig(),
    val webDav: SyncTargetConfig = SyncTargetConfig(),
    val document: SyncTargetConfig = SyncTargetConfig(),
    val autoSync: Boolean = false,
    val includeEncryptedPasswords: Boolean = false,
) {
    fun target(backend: SyncBackend): SyncTargetConfig = when (backend) {
        SyncBackend.GIST -> gist
        SyncBackend.WEBDAV -> webDav
        SyncBackend.DOCUMENT -> document
        SyncBackend.NONE -> SyncTargetConfig()
    }

    val enabledBackends: List<SyncBackend>
        get() = buildList {
            if (gist.enabled) add(SyncBackend.GIST)
            if (webDav.enabled) add(SyncBackend.WEBDAV)
            if (document.enabled) add(SyncBackend.DOCUMENT)
        }
}

data class BackendSyncState(
    val status: BackendSyncStatus = BackendSyncStatus.DISABLED,
    val remoteRevision: String? = null,
    val acknowledgedContentHash: String? = null,
    val lastSyncTimestamp: Long? = null,
    val error: String? = null,
)

data class SyncState(
    val config: SyncConfig = SyncConfig(),
    val hasGithubToken: Boolean = false,
    val hasWebDavPassword: Boolean = false,
    val hasSyncPassphrase: Boolean = false,
    val gistState: BackendSyncState = BackendSyncState(),
    val webDavState: BackendSyncState = BackendSyncState(),
    val documentState: BackendSyncState = BackendSyncState(),
    val lastLocalPayloadHash: String? = null,
    val generation: Long = 0,
    val lastSyncTimestamp: Long? = null,
    val status: SyncStatus = SyncStatus.DISCONNECTED,
    val error: String? = null,
    val conflictSummary: SyncConflictSummary? = null,
) {
    fun backendState(backend: SyncBackend): BackendSyncState = when (backend) {
        SyncBackend.GIST -> gistState
        SyncBackend.WEBDAV -> webDavState
        SyncBackend.DOCUMENT -> documentState
        SyncBackend.NONE -> BackendSyncState()
    }
}

internal fun migrateSingleBackendSyncConfig(
    backend: SyncBackend,
    endpoint: String,
    username: String,
    autoSync: Boolean,
    includeEncryptedPasswords: Boolean,
): SyncConfig = SyncConfig(
    primaryBackend = backend,
    gist = SyncTargetConfig(
        enabled = backend == SyncBackend.GIST,
        endpoint = endpoint.takeIf { backend == SyncBackend.GIST }.orEmpty(),
    ),
    webDav = SyncTargetConfig(
        enabled = backend == SyncBackend.WEBDAV,
        endpoint = endpoint.takeIf { backend == SyncBackend.WEBDAV }.orEmpty(),
        username = username.takeIf { backend == SyncBackend.WEBDAV }.orEmpty(),
    ),
    autoSync = autoSync,
    includeEncryptedPasswords = includeEncryptedPasswords,
)

internal fun requireSingleSyncStorage(config: SyncConfig) {
    require(config.primaryBackend != SyncBackend.NONE) { "Choose a sync storage" }
    require(config.enabledBackends.size == 1 && config.target(config.primaryBackend).enabled) {
        "Choose exactly one sync storage"
    }
}

@Singleton
class SyncRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val settingsRepository: SettingsRepository,
    private val serverRepository: ServerRepository,
    private val diagnosticLogRepository: DiagnosticLogRepository,
    private val secretStore: LocalSyncSecretStore,
    private val client: HttpClient,
    private val json: Json,
    private val conflictNotifier: SyncConflictNotifier,
    @ApplicationContext private val context: Context,
) {
    private val syncMutex = Mutex()

    val state: Flow<SyncState> = dataStore.data.map(::stateFrom)

    suspend fun configure(
        config: SyncConfig,
        githubToken: String? = null,
        webDavPassword: String? = null,
        syncPassphrase: String? = null,
    ) = syncMutex.withLock {
        requireSingleSyncStorage(config)
        val previousDocumentEndpoint = dataStore.data.first()[targetEndpointKey(SyncBackend.DOCUMENT)].orEmpty()
        if (config.gist.enabled) {
            require(config.gist.endpoint.isBlank() || GistSyncTransport.parseGistId(config.gist.endpoint) != null) {
                "Enter a valid Gist ID or URL"
            }
            require(!githubToken.isNullOrBlank() || hasSecret(LocalSyncSecretStore.SecretKey.GITHUB_TOKEN)) {
                "GitHub token is required"
            }
        }
        if (config.webDav.enabled) {
            require(config.webDav.endpoint.isNotBlank()) { "A WebDAV file URL is required" }
            require(!webDavPassword.isNullOrBlank() || hasSecret(LocalSyncSecretStore.SecretKey.WEBDAV_PASSWORD)) {
                "WebDAV password is required"
            }
        }
        if (config.document.enabled) {
            val uri = Uri.parse(config.document.endpoint.trim())
            require(uri.scheme == CONTENT_RESOLVER_SCHEME) { "Choose a sync file through the system file picker" }
            val permission = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
            require(permission?.isReadPermission == true && permission.isWritePermission) {
                DocumentSyncTransport.PERMISSION_ERROR
            }
        }
        val storedPassphrase = secretStore.get(LocalSyncSecretStore.SecretKey.SYNC_PASSPHRASE)
        if (config.includeEncryptedPasswords) {
            require(!syncPassphrase.isNullOrBlank() || !storedPassphrase.isNullOrBlank()) {
                "A sync passphrase is required to include passwords"
            }
        }
        val passphraseChanged = !syncPassphrase.isNullOrBlank() && syncPassphrase != storedPassphrase
        githubToken?.takeIf(String::isNotBlank)?.let {
            secretStore.put(LocalSyncSecretStore.SecretKey.GITHUB_TOKEN, it)
        }
        webDavPassword?.takeIf(String::isNotBlank)?.let {
            secretStore.put(LocalSyncSecretStore.SecretKey.WEBDAV_PASSWORD, it)
        }
        syncPassphrase?.takeIf(String::isNotBlank)?.let {
            secretStore.put(LocalSyncSecretStore.SecretKey.SYNC_PASSPHRASE, it)
        }
        if (!config.includeEncryptedPasswords) {
            secretStore.put(LocalSyncSecretStore.SecretKey.SYNC_PASSPHRASE, null)
        }

        var conflictCleared = false
        dataStore.edit { preferences ->
            val previousPrimary = runCatching {
                SyncBackend.valueOf(preferences[PRIMARY_BACKEND] ?: SyncBackend.NONE.name)
            }.getOrDefault(SyncBackend.NONE)
            val gistIdentityChanged = targetIdentityChanged(preferences, SyncBackend.GIST, config.gist)
            val webDavIdentityChanged = targetIdentityChanged(preferences, SyncBackend.WEBDAV, config.webDav)
            val documentIdentityChanged = targetIdentityChanged(preferences, SyncBackend.DOCUMENT, config.document)
            if (preferences[PRIMARY_BACKEND] == null) {
                val legacyBackend = runCatching {
                    SyncBackend.valueOf(preferences[LEGACY_BACKEND] ?: SyncBackend.NONE.name)
                }.getOrDefault(SyncBackend.NONE)
                if (legacyBackend != SyncBackend.NONE) {
                    preferences[LEGACY_REVISION]?.let {
                        preferences[backendRevisionKey(legacyBackend)] = it
                    }
                    preferences[LEGACY_LAST_SYNC]?.let {
                        preferences[backendLastSyncKey(legacyBackend)] = it
                    }
                }
                preferences[LEGACY_LOCAL_HASH]?.let { preferences[LOCAL_HASH] = it }
                preferences[LEGACY_LAST_SYNC]?.let { preferences[LAST_SYNC] = it }
            }
            preferences[PRIMARY_BACKEND] = config.primaryBackend.name
            writeTargetConfig(preferences, SyncBackend.GIST, config.gist, gistIdentityChanged)
            writeTargetConfig(preferences, SyncBackend.WEBDAV, config.webDav, webDavIdentityChanged)
            writeTargetConfig(preferences, SyncBackend.DOCUMENT, config.document, documentIdentityChanged)
            preferences[AUTO_SYNC] = config.autoSync
            preferences[INCLUDE_PASSWORDS] = config.includeEncryptedPasswords
            if (gistIdentityChanged) clearBackendMetadata(preferences, SyncBackend.GIST)
            if (webDavIdentityChanged) clearBackendMetadata(preferences, SyncBackend.WEBDAV)
            if (documentIdentityChanged) clearBackendMetadata(preferences, SyncBackend.DOCUMENT)
            val overallStatus = configuredOverallStatus(preferences, config)
            preferences[STATUS] = overallStatus.name
            if (overallStatus == SyncStatus.IDLE) {
                preferences.remove(ERROR)
            }
            if (passphraseChanged) {
                preferences.remove(LOCAL_HASH)
                preferences.remove(BASELINE_PAYLOAD)
            }
            removeLegacyConfig(preferences)
            if (
                previousPrimary != config.primaryBackend || gistIdentityChanged ||
                webDavIdentityChanged || documentIdentityChanged
            ) {
                preferences.remove(CONFLICT_SUMMARY)
                preferences.remove(BASELINE_PAYLOAD)
                conflictCleared = true
            }
        }
        if (conflictCleared) conflictNotifier.cancel()
        if (
            previousDocumentEndpoint.isNotBlank() &&
            previousDocumentEndpoint != config.document.endpoint.trim()
        ) {
            releaseDocumentPermission(Uri.parse(previousDocumentEndpoint))
        }
    }

    suspend fun syncNow(): SyncDecision = syncMutex.withLock {
        val current = configuredState()
        val primary = current.config.primaryBackend
        saveStatus(SyncStatus.SYNCING)
        saveBackendStatus(primary, BackendSyncStatus.SYNCING)
        try {
            val includePasswords = current.config.includeEncryptedPasswords
            val local = snapshot(includePasswords)
            val localData = semanticData(local, fallback = null, includePasswords = includePasswords)
            val localHash = semanticHash(localData, includePasswords)
            val primaryTransport = transport(primary, current.config.target(primary))
            val remote = primaryTransport.read()
            val primaryState = current.backendState(primary)
            val remoteContentHash = remote?.content?.let(::contentHash)
            val storedMarker = primaryState.remoteRevision ?: primaryState.acknowledgedContentHash
            val remoteMarker = remote?.revision ?: remoteContentHash
            val decision: SyncDecision
            val canonical: CanonicalSync
            if (remote == null) {
                decision = SyncDecision.MISSING_UPLOAD
                canonical = uploadSemantic(current, primaryTransport, null, localData, includePasswords)
            } else {
                val remotePayload = decodePayload(remote.content)
                val baselineData = loadBaseline(includePasswords)
                val remoteData = semanticData(
                    remotePayload,
                    fallback = baselineData ?: localData,
                    includePasswords = includePasswords,
                )
                if (localData == remoteData) {
                    decision = SyncDecision.UP_TO_DATE
                    canonical = acceptRemote(
                        current,
                        primaryTransport,
                        remote,
                        remotePayload,
                        localData,
                        includePasswords,
                    )
                } else if (baselineData == null) {
                    decision = decideSync(
                        remoteExists = true,
                        storedRevision = storedMarker,
                        remoteRevision = remoteMarker,
                        storedLocalHash = current.lastLocalPayloadHash,
                        localHash = localPayloadHash(local, includePasswords),
                    )
                    canonical = when (decision) {
                        SyncDecision.PULL_REMOTE -> {
                            applySemanticPreservingConcurrent(localData, remoteData, includePasswords)
                            acceptRemote(
                                current,
                                primaryTransport,
                                remote,
                                remotePayload,
                                remoteData,
                                includePasswords,
                            )
                        }
                        SyncDecision.PUSH_LOCAL -> uploadSemantic(
                            current,
                            primaryTransport,
                            remote,
                            localData,
                            includePasswords,
                        )
                        SyncDecision.CONFLICT -> {
                            saveSyncConflict(
                                current = current,
                                primary = primary,
                                local = localData,
                                remote = remoteData,
                                remotePayload = remotePayload,
                                localHash = localHash,
                                remoteMarker = remoteMarker,
                                storedMarker = storedMarker,
                            )
                            return@withLock decision
                        }
                        SyncDecision.UP_TO_DATE -> acceptRemote(
                            current,
                            primaryTransport,
                            remote,
                            remotePayload,
                            remoteData,
                            includePasswords,
                        )
                        SyncDecision.MISSING_UPLOAD -> error("Remote sync data unexpectedly disappeared")
                    }
                } else {
                    val merged = mergeSyncData(baselineData, localData, remoteData, json)
                    if (merged.hasConflicts) {
                        decision = SyncDecision.CONFLICT
                        saveSyncConflict(
                            current = current,
                            primary = primary,
                            local = localData,
                            remote = remoteData,
                            remotePayload = remotePayload,
                            localHash = localHash,
                            remoteMarker = remoteMarker,
                            storedMarker = storedMarker,
                            conflictCounts = merged.conflictCounts,
                        )
                        return@withLock decision
                    }
                    if (merged.data == remoteData) {
                        decision = if (localData == remoteData) SyncDecision.UP_TO_DATE else SyncDecision.PULL_REMOTE
                        if (localData != merged.data) {
                            applySemanticPreservingConcurrent(localData, merged.data, includePasswords)
                        }
                        canonical = acceptRemote(
                            current,
                            primaryTransport,
                            remote,
                            remotePayload,
                            merged.data,
                            includePasswords,
                        )
                    } else {
                        decision = SyncDecision.PUSH_LOCAL
                        canonical = uploadSemantic(
                            current,
                            primaryTransport,
                            remote,
                            merged.data,
                            includePasswords,
                        )
                        if (localData != merged.data) {
                            applySemanticPreservingConcurrent(localData, merged.data, includePasswords)
                        }
                    }
                }
            }
            saveCanonical(primary, canonical)
            val backupHealthy = reconcileOtherBackends(current.config, primary, canonical, force = false)
            saveStatus(if (backupHealthy) SyncStatus.IDLE else SyncStatus.PARTIAL)
            decision
        } catch (e: Exception) {
            if (current.conflictSummary != null) {
                saveBackendStatus(primary, BackendSyncStatus.CONFLICT, e.message ?: "Sync failed")
                saveStatus(SyncStatus.CONFLICT, e.message ?: "Sync failed")
            } else {
                saveBackendStatus(primary, BackendSyncStatus.ERROR, e.message ?: "Sync failed")
                saveStatus(SyncStatus.ERROR, e.message ?: "Sync failed")
            }
            throw e
        }
    }

    suspend fun forceUpload() = syncMutex.withLock {
        val current = configuredState()
        val primary = current.config.primaryBackend
        saveStatus(SyncStatus.SYNCING)
        saveBackendStatus(primary, BackendSyncStatus.SYNCING)
        try {
            val includePasswords = current.config.includeEncryptedPasswords
            val local = snapshot(includePasswords)
            val localData = semanticData(local, fallback = null, includePasswords = includePasswords)
            val transport = transport(primary, current.config.target(primary))
            val remote = transport.read()
            val baselineData = loadBaseline(includePasswords)
            val resolvedData = if (remote != null && baselineData != null) {
                val remoteData = semanticData(decodePayload(remote.content), baselineData, includePasswords)
                mergeSyncData(
                    baselineData,
                    localData,
                    remoteData,
                    json,
                    SyncConflictPreference.LOCAL,
                ).data
            } else {
                localData
            }
            val canonical = uploadSemantic(current, transport, remote, resolvedData, includePasswords)
            if (resolvedData != localData) {
                applySemanticPreservingConcurrent(localData, resolvedData, includePasswords)
            }
            saveCanonical(primary, canonical)
            val backupHealthy = reconcileOtherBackends(current.config, primary, canonical, force = true)
            saveStatus(if (backupHealthy) SyncStatus.IDLE else SyncStatus.PARTIAL)
        } catch (e: Exception) {
            if (current.conflictSummary != null) {
                saveBackendStatus(primary, BackendSyncStatus.CONFLICT, e.message ?: "Sync failed")
                saveStatus(SyncStatus.CONFLICT, e.message ?: "Sync failed")
            } else {
                saveBackendStatus(primary, BackendSyncStatus.ERROR, e.message ?: "Sync failed")
                saveStatus(SyncStatus.ERROR, e.message ?: "Sync failed")
            }
            throw e
        }
    }

    suspend fun forceDownload(source: SyncBackend? = null) = syncMutex.withLock {
        val current = configuredState()
        val selected = source ?: current.config.primaryBackend
        require(selected in current.config.enabledBackends) { "The selected sync storage is not enabled" }
        saveStatus(SyncStatus.SYNCING)
        saveBackendStatus(selected, BackendSyncStatus.SYNCING)
        try {
            val sourceTransport = transport(selected, current.config.target(selected))
            val remote = sourceTransport.read() ?: throw IllegalStateException("No remote sync file exists")
            val includePasswords = current.config.includeEncryptedPasswords
            val localData = semanticData(snapshot(includePasswords), null, includePasswords)
            val remotePayload = decodePayload(remote.content)
            val baselineData = loadBaseline(includePasswords)
            val remoteData = semanticData(remotePayload, baselineData ?: localData, includePasswords)
            val resolvedData = if (baselineData != null) {
                mergeSyncData(
                    baselineData,
                    localData,
                    remoteData,
                    json,
                    SyncConflictPreference.REMOTE,
                ).data
            } else {
                remoteData
            }
            val canonical = if (resolvedData == remoteData) {
                acceptRemote(
                    current,
                    sourceTransport,
                    remote,
                    remotePayload,
                    resolvedData,
                    includePasswords,
                )
            } else {
                uploadSemantic(current, sourceTransport, remote, resolvedData, includePasswords)
            }
            applySemanticPreservingConcurrent(localData, resolvedData, includePasswords)
            saveCanonical(selected, canonical)
            val othersHealthy = reconcileOtherBackends(current.config, selected, canonical, force = true)
            saveStatus(if (othersHealthy) SyncStatus.IDLE else SyncStatus.PARTIAL)
        } catch (e: Exception) {
            if (current.conflictSummary != null) {
                saveBackendStatus(selected, BackendSyncStatus.CONFLICT, e.message ?: "Sync failed")
                saveStatus(SyncStatus.CONFLICT, e.message ?: "Sync failed")
            } else {
                saveBackendStatus(selected, BackendSyncStatus.ERROR, e.message ?: "Sync failed")
                saveStatus(SyncStatus.ERROR, e.message ?: "Sync failed")
            }
            throw e
        }
    }

    suspend fun disconnect() = syncMutex.withLock {
        val documentUri = state.first().config.document.endpoint.takeIf(String::isNotBlank)?.let(Uri::parse)
        secretStore.clearAll()
        dataStore.edit { preferences ->
            preferences.remove(PRIMARY_BACKEND)
            preferences.remove(AUTO_SYNC)
            preferences.remove(INCLUDE_PASSWORDS)
            preferences.remove(LOCAL_HASH)
            preferences.remove(GENERATION)
            preferences.remove(DEVICE_ID)
            preferences.remove(LAST_SYNC)
            preferences.remove(STATUS)
            preferences.remove(ERROR)
            preferences.remove(CONFLICT_SUMMARY)
            preferences.remove(BASELINE_PAYLOAD)
            SyncBackend.entries.filterNot { it == SyncBackend.NONE }.forEach { backend ->
                preferences.remove(targetEnabledKey(backend))
                preferences.remove(targetEndpointKey(backend))
                preferences.remove(targetUsernameKey(backend))
                clearBackendMetadata(preferences, backend)
            }
            removeLegacyConfig(preferences)
            preferences[STATUS] = SyncStatus.DISCONNECTED.name
        }
        documentUri?.let { uri ->
            releaseDocumentPermission(uri)
        }
        conflictNotifier.cancel()
    }

    private suspend fun uploadSemantic(
        current: SyncState,
        transport: SyncTransport,
        remote: RemoteSyncFile?,
        data: SemanticSyncData,
        includePasswords: Boolean,
    ): CanonicalSync {
        val remoteGeneration = remote?.content?.let(::decodePayload)?.generation ?: 0
        val generation = maxOf(current.generation, remoteGeneration) + 1
        val payload = encodeSemanticPayload(
            data = data,
            generation = generation,
            parentGeneration = remoteGeneration.takeIf { it > 0 } ?: current.generation.takeIf { it > 0 },
            updatedAt = System.currentTimeMillis(),
            writerDeviceId = ensureDeviceId(),
            includePasswords = includePasswords,
        )
        val content = json.encodeToString(payload)
        val verified = writeAndVerify(transport, content, remote)
        return CanonicalSync(
            content = content,
            localHash = semanticHash(data, includePasswords),
            generation = generation,
            remote = verified,
            baselineContent = content,
        )
    }

    private fun canonicalFromRemote(
        remote: RemoteSyncFile,
        remotePayload: SyncPayload,
        data: SemanticSyncData,
        includePasswords: Boolean,
    ): CanonicalSync {
        val baselinePayload = encodeSemanticPayload(
            data = data,
            generation = remotePayload.generation,
            parentGeneration = remotePayload.parentGeneration,
            updatedAt = remotePayload.updatedAt,
            writerDeviceId = remotePayload.writerDeviceId,
            includePasswords = includePasswords,
        )
        return CanonicalSync(
            content = remote.content,
            localHash = semanticHash(data, includePasswords),
            generation = remotePayload.generation,
            remote = remote,
            baselineContent = json.encodeToString(baselinePayload),
        )
    }

    private suspend fun acceptRemote(
        current: SyncState,
        transport: SyncTransport,
        remote: RemoteSyncFile,
        remotePayload: SyncPayload,
        data: SemanticSyncData,
        includePasswords: Boolean,
    ): CanonicalSync = if (requiresMaterialization(remotePayload, includePasswords)) {
        uploadSemantic(current, transport, remote, data, includePasswords)
    } else {
        canonicalFromRemote(remote, remotePayload, data, includePasswords)
    }

    private fun requiresMaterialization(payload: SyncPayload, includePasswords: Boolean): Boolean =
        payload.favoriteSessionIds == null ||
            payload.crossServerFavoriteOrder == null ||
            payload.favoriteSessionSnapshots == null ||
            payload.hiddenModels == null ||
            payload.settings.hideToolDetails == null ||
            payload.settings.showLocalRuntime == null ||
            payload.settings.diagnosticLogLevel == null ||
            payload.settings.showTerminalPanelHint == null ||
            (includePasswords && payload.passwordsIncluded != true)

    private suspend fun applySemanticPreservingConcurrent(
        expectedLocal: SemanticSyncData,
        accepted: SemanticSyncData,
        includePasswords: Boolean,
    ) {
        lateinit var applied: SemanticSyncData
        dataStore.edit { preferences ->
            val current = semanticSnapshotFrom(preferences, includePasswords)
            applied = if (current == expectedLocal) {
                accepted
            } else {
                mergeSyncData(
                    expectedLocal,
                    current,
                    accepted,
                    json,
                    SyncConflictPreference.LOCAL,
                ).data
            }
            applySemanticTo(preferences, applied, includePasswords)
        }
        settingsRepository.updateSynchronousLocale(applied.payload.settings.appLanguage)
    }

    private fun applySemanticTo(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        data: SemanticSyncData,
        includePasswords: Boolean,
    ) {
        val serverIdMapping = serverRepository.replaceSyncServersTo(
            preferences,
            data.payload.servers,
            data.passwords,
            passwordsAuthoritative = includePasswords,
        )
        settingsRepository.applySyncSettingsTo(
            preferences,
            data.payload.settings,
            data.payload.sessionCategories,
        )
        settingsRepository.applySyncSessionCategoryAssignmentsTo(
            preferences,
            data.payload.servers.associate { server ->
                server.id to data.payload.sessionCategoryAssignments[server.id].orEmpty()
            },
            serverIdMapping,
        )
        settingsRepository.applySyncSessionCollectionsTo(
            preferences = preferences,
            favoriteSessionIds = data.payload.favoriteSessionIds,
            crossServerFavoriteOrder = data.payload.crossServerFavoriteOrder,
            favoriteSessionSnapshots = data.payload.favoriteSessionSnapshots,
            hiddenModels = data.payload.hiddenModels,
            serverIdMapping = serverIdMapping,
        )
        diagnosticLogRepository.applyLogLevelTo(preferences, data.payload.settings.diagnosticLogLevel)
    }

    private fun semanticData(
        payload: SyncPayload,
        fallback: SemanticSyncData?,
        includePasswords: Boolean,
    ): SemanticSyncData {
        val passwords = if (includePasswords) decryptPasswords(payload.encryptedSecrets) else emptyMap()
        return canonicalizeSyncData(payload, passwords, fallback)
    }

    private fun encodeSemanticPayload(
        data: SemanticSyncData,
        generation: Long,
        parentGeneration: Long?,
        updatedAt: Long,
        writerDeviceId: String,
        includePasswords: Boolean,
    ): SyncPayload {
        val encrypted = if (includePasswords) {
            encryptPasswords(data.passwords)
        } else {
            null
        }
        return data.payload.copy(
            generation = generation,
            parentGeneration = parentGeneration,
            updatedAt = updatedAt,
            writerDeviceId = writerDeviceId,
            passwordsIncluded = includePasswords,
            encryptedSecrets = encrypted,
        )
    }

    private fun encryptPasswords(passwords: Map<String, String>): EncryptedSecrets {
        val passphrase = secretStore.get(LocalSyncSecretStore.SecretKey.SYNC_PASSPHRASE)
            ?: throw IllegalStateException("A sync passphrase is required to include passwords")
        val chars = passphrase.toCharArray()
        return try {
            PasswordCrypto.encrypt(json.encodeToString(PasswordSecrets(passwords)).toByteArray(), chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    private suspend fun loadBaseline(includePasswords: Boolean): SemanticSyncData? {
        val content = dataStore.data.first()[BASELINE_PAYLOAD] ?: return null
        return runCatching {
            semanticData(decodePayload(content), fallback = null, includePasswords = includePasswords)
        }.getOrNull()
    }

    private suspend fun saveSyncConflict(
        current: SyncState,
        primary: SyncBackend,
        local: SemanticSyncData,
        remote: SemanticSyncData,
        remotePayload: SyncPayload,
        localHash: String,
        remoteMarker: String?,
        storedMarker: String?,
        conflictCounts: Map<dev.minios.ocremote.data.sync.SyncConflictArea, Int>? = null,
    ) {
        val summaryTimestamp = System.currentTimeMillis()
        val summary = buildSyncConflictSummary(
            local = local.payload.copy(generation = current.generation, updatedAt = summaryTimestamp),
            remote = remote.payload.copy(
                generation = remotePayload.generation,
                updatedAt = remotePayload.updatedAt,
            ),
            identity = "$primary\u0000$localHash\u0000${remoteMarker.orEmpty()}\u0000${storedMarker.orEmpty()}",
            json = json,
            now = summaryTimestamp,
            conflictCounts = conflictCounts,
        )
        saveConflict(primary, summary)
        if (current.conflictSummary?.id != summary.id) conflictNotifier.notifyConflict()
    }

    private suspend fun reconcileOtherBackends(
        config: SyncConfig,
        source: SyncBackend,
        canonical: CanonicalSync,
        force: Boolean,
    ): Boolean {
        val canonicalHash = contentHash(canonical.content)
        var healthy = true
        config.enabledBackends.filterNot { it == source }.forEach { backend ->
            saveBackendStatus(backend, BackendSyncStatus.SYNCING)
            try {
                val current = state.first()
                val transport = transport(backend, current.config.target(backend))
                val remote = transport.read()
                val decision = if (force) {
                    if (remote == null) BackupSyncDecision.CREATE else BackupSyncDecision.UPDATE
                } else {
                    decideBackupSync(
                        remoteExists = remote != null,
                        acknowledgedContentHash = current.backendState(backend).acknowledgedContentHash,
                        remoteContentHash = remote?.content?.let(::contentHash),
                        canonicalContentHash = canonicalHash,
                    )
                }
                when (decision) {
                    BackupSyncDecision.CREATE,
                    BackupSyncDecision.UPDATE,
                    -> {
                        val verified = writeAndVerify(transport, canonical.content, remote)
                        saveBackendSuccess(backend, verified)
                    }

                    BackupSyncDecision.UP_TO_DATE -> saveBackendSuccess(backend, remote!!)
                    BackupSyncDecision.DIVERGED -> {
                        healthy = false
                        saveBackendStatus(
                            backend,
                            BackendSyncStatus.DIVERGED,
                            "Backup changed independently; choose which version to keep",
                        )
                    }
                }
            } catch (e: Exception) {
                healthy = false
                saveBackendStatus(backend, BackendSyncStatus.ERROR, e.message ?: "Backup sync failed")
            }
        }
        return healthy
    }

    private suspend fun writeAndVerify(
        transport: SyncTransport,
        content: String,
        expectedRemote: RemoteSyncFile?,
    ): RemoteSyncFile {
        transport.write(content, expectedRemote?.revision, create = expectedRemote == null)
        val verified = transport.read() ?: throw IllegalStateException("Sync file disappeared after upload")
        require(verified.content == content) { "Uploaded sync data could not be verified" }
        return verified
    }

    private suspend fun snapshot(includePasswords: Boolean): SyncPayload {
        val preferences = dataStore.data.first()
        val semantic = semanticSnapshotFrom(preferences, includePasswords)
        val encrypted = if (includePasswords) encryptPasswords(semantic.passwords) else null
        return semantic.payload.copy(passwordsIncluded = includePasswords, encryptedSecrets = encrypted)
    }

    private fun semanticSnapshotFrom(
        preferences: Preferences,
        includePasswords: Boolean,
    ): SemanticSyncData {
        val servers = serverRepository.syncServersSnapshotFrom(preferences)
        val serverIds = servers.map { it.id }
        val passwords = if (includePasswords) {
            serverRepository.serverConfigsFrom(preferences).filter { it.id in serverIds }.mapNotNull { server ->
                server.password?.let { server.id to it }
            }.toMap()
        } else {
            emptyMap()
        }
        val payload = SyncPayload(
            settings = settingsRepository.syncSettingsSnapshotFrom(preferences).copy(
                diagnosticLogLevel = diagnosticLogRepository.logLevelFrom(preferences),
            ),
            sessionCategories = settingsRepository.syncSessionCategoriesFrom(preferences),
            sessionCategoryAssignments = settingsRepository.syncSessionCategoryAssignmentsSnapshotFrom(
                preferences,
                serverIds,
            ),
            favoriteSessionIds = settingsRepository.syncFavoriteSessionIdsFrom(preferences, serverIds),
            crossServerFavoriteOrder = settingsRepository.syncCrossServerFavoriteOrderFrom(preferences, serverIds),
            favoriteSessionSnapshots = settingsRepository.syncFavoriteSessionSnapshotsFrom(preferences, serverIds),
            hiddenModels = settingsRepository.syncHiddenModelsFrom(preferences, serverIds),
            servers = servers,
            passwordsIncluded = includePasswords,
        )
        return canonicalizeSyncData(payload, passwords)
    }

    private fun decodePayload(content: String): SyncPayload {
        return json.decodeSyncPayload(content)
    }

    private fun decryptPasswords(envelope: EncryptedSecrets?): Map<String, String> {
        if (envelope == null) return emptyMap()
        val passphrase = secretStore.get(LocalSyncSecretStore.SecretKey.SYNC_PASSPHRASE)
            ?: throw IllegalStateException("A sync passphrase is required to import encrypted passwords")
        val chars = passphrase.toCharArray()
        return try {
            json.decodeFromString<PasswordSecrets>(PasswordCrypto.decrypt(envelope, chars).decodeToString()).passwords
        } finally {
            chars.fill('\u0000')
        }
    }

    private fun transport(backend: SyncBackend, target: SyncTargetConfig): SyncTransport = when (backend) {
        SyncBackend.GIST -> GistSyncTransport(
            client,
            json,
            secretStore.get(LocalSyncSecretStore.SecretKey.GITHUB_TOKEN)
                ?: error("GitHub token is missing"),
            target.endpoint,
        )

        SyncBackend.WEBDAV -> WebDavSyncTransport(
            client,
            target.endpoint,
            target.username,
            secretStore.get(LocalSyncSecretStore.SecretKey.WEBDAV_PASSWORD)
                ?: error("WebDAV password is missing"),
        )

        SyncBackend.DOCUMENT -> DocumentSyncTransport(context.contentResolver, Uri.parse(target.endpoint))

        SyncBackend.NONE -> error("Sync is not configured")
    }

    private suspend fun configuredState(): SyncState = state.first().also {
        check(it.config.primaryBackend != SyncBackend.NONE) { "Sync is not configured" }
    }

    private suspend fun saveCanonical(backend: SyncBackend, canonical: CanonicalSync) {
        dataStore.edit { preferences ->
            writeBackendSuccess(preferences, backend, canonical.remote)
            preferences[LOCAL_HASH] = canonical.localHash
            preferences[BASELINE_PAYLOAD] = canonical.baselineContent
            preferences[GENERATION] = canonical.generation
            preferences[LAST_SYNC] = System.currentTimeMillis()
            preferences.remove(ERROR)
            preferences.remove(CONFLICT_SUMMARY)
        }
        conflictNotifier.cancel()
    }

    private suspend fun saveConflict(backend: SyncBackend, summary: SyncConflictSummary) {
        dataStore.edit { preferences ->
            preferences[backendStatusKey(backend)] = BackendSyncStatus.CONFLICT.name
            preferences.remove(backendErrorKey(backend))
            preferences[STATUS] = SyncStatus.CONFLICT.name
            preferences[ERROR] = "Local and primary sync data both need a choice"
            preferences[CONFLICT_SUMMARY] = json.encodeToString(SyncConflictSummary.serializer(), summary)
        }
    }

    private suspend fun saveBackendSuccess(backend: SyncBackend, remote: RemoteSyncFile) {
        dataStore.edit { preferences -> writeBackendSuccess(preferences, backend, remote) }
    }

    private fun writeBackendSuccess(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        backend: SyncBackend,
        remote: RemoteSyncFile,
    ) {
        preferences[backendStatusKey(backend)] = BackendSyncStatus.IDLE.name
        remote.revision?.let { preferences[backendRevisionKey(backend)] = it }
            ?: preferences.remove(backendRevisionKey(backend))
        preferences[backendContentHashKey(backend)] = contentHash(remote.content)
        preferences[backendLastSyncKey(backend)] = System.currentTimeMillis()
        remote.resolvedEndpoint?.let { preferences[targetEndpointKey(backend)] = it }
        preferences.remove(backendErrorKey(backend))
    }

    private suspend fun saveBackendStatus(
        backend: SyncBackend,
        status: BackendSyncStatus,
        error: String? = null,
    ) = dataStore.edit { preferences ->
        preferences[backendStatusKey(backend)] = status.name
        error?.let { preferences[backendErrorKey(backend)] = it }
            ?: preferences.remove(backendErrorKey(backend))
    }

    private suspend fun saveStatus(status: SyncStatus, error: String? = null) = dataStore.edit { preferences ->
        preferences[STATUS] = status.name
        error?.let { preferences[ERROR] = it } ?: preferences.remove(ERROR)
    }

    private suspend fun ensureDeviceId(): String {
        val existing = dataStore.data.first()[DEVICE_ID]
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        dataStore.edit { preferences ->
            if (preferences[DEVICE_ID].isNullOrBlank()) preferences[DEVICE_ID] = generated
        }
        return dataStore.data.first()[DEVICE_ID] ?: generated
    }

    private fun localPayloadHash(payload: SyncPayload, includePasswords: Boolean): String {
        val passwordFingerprint = if (includePasswords) {
            decryptPasswords(payload.encryptedSecrets)
                .toSortedMap()
                .entries
                .joinToString("\u0001") { (id, password) -> "$id\u0000$password" }
        } else {
            "excluded"
        }
        val canonical = json.encodeToString(
            payload.copy(
                generation = 0,
                parentGeneration = null,
                updatedAt = 0,
                writerDeviceId = "",
                encryptedSecrets = null,
            ),
        ) + "\u0002passwords=$includePasswords\u0002" + passwordFingerprint
        return contentHash(canonical)
    }

    private fun semanticHash(data: SemanticSyncData, includePasswords: Boolean): String {
        val passwordFingerprint = if (includePasswords) {
            data.passwords.toSortedMap().entries.joinToString("\u0001") { (id, password) -> "$id\u0000$password" }
        } else {
            "excluded"
        }
        return contentHash(
            json.encodeToString(data.payload.copy(encryptedSecrets = null)) +
                "\u0002passwords=$includePasswords\u0002$passwordFingerprint",
        )
    }

    private fun contentHash(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun hasSecret(key: LocalSyncSecretStore.SecretKey): Boolean = !secretStore.get(key).isNullOrBlank()

    private fun releaseDocumentPermission(uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private fun stateFrom(preferences: Preferences): SyncState {
        val legacyBackend = runCatching {
            SyncBackend.valueOf(preferences[LEGACY_BACKEND] ?: SyncBackend.NONE.name)
        }.getOrDefault(SyncBackend.NONE)
        val hasNewConfig = preferences[PRIMARY_BACKEND] != null
        val primary = if (hasNewConfig) {
            runCatching { SyncBackend.valueOf(preferences[PRIMARY_BACKEND] ?: SyncBackend.NONE.name) }
                .getOrDefault(SyncBackend.NONE)
        } else {
            legacyBackend
        }
        val config = if (hasNewConfig) {
            val storedGist = readTargetConfig(preferences, SyncBackend.GIST)
            val storedWebDav = readTargetConfig(preferences, SyncBackend.WEBDAV)
            val storedDocument = readTargetConfig(preferences, SyncBackend.DOCUMENT)
            SyncConfig(
                primaryBackend = primary,
                gist = storedGist.copy(enabled = primary == SyncBackend.GIST),
                webDav = storedWebDav.copy(enabled = primary == SyncBackend.WEBDAV),
                document = storedDocument.copy(enabled = primary == SyncBackend.DOCUMENT),
                autoSync = preferences[AUTO_SYNC] ?: false,
                includeEncryptedPasswords = preferences[INCLUDE_PASSWORDS] ?: false,
            )
        } else {
            migrateSingleBackendSyncConfig(
                backend = legacyBackend,
                endpoint = preferences[LEGACY_ENDPOINT].orEmpty(),
                username = preferences[LEGACY_USERNAME].orEmpty(),
                autoSync = preferences[AUTO_SYNC] ?: false,
                includeEncryptedPasswords = preferences[INCLUDE_PASSWORDS] ?: false,
            )
        }
        val gist = config.gist
        val webDav = config.webDav
        val document = config.document
        return SyncState(
            config = config,
            hasGithubToken = hasSecret(LocalSyncSecretStore.SecretKey.GITHUB_TOKEN),
            hasWebDavPassword = hasSecret(LocalSyncSecretStore.SecretKey.WEBDAV_PASSWORD),
            hasSyncPassphrase = hasSecret(LocalSyncSecretStore.SecretKey.SYNC_PASSPHRASE),
            gistState = readBackendState(preferences, SyncBackend.GIST, gist.enabled, legacyBackend),
            webDavState = readBackendState(preferences, SyncBackend.WEBDAV, webDav.enabled, legacyBackend),
            documentState = readBackendState(preferences, SyncBackend.DOCUMENT, document.enabled, legacyBackend),
            lastLocalPayloadHash = preferences[LOCAL_HASH] ?: preferences[LEGACY_LOCAL_HASH],
            generation = preferences[GENERATION] ?: 0,
            lastSyncTimestamp = preferences[LAST_SYNC] ?: preferences[LEGACY_LAST_SYNC],
            status = runCatching {
                SyncStatus.valueOf(preferences[STATUS] ?: if (primary == SyncBackend.NONE) {
                    SyncStatus.DISCONNECTED.name
                } else {
                    SyncStatus.IDLE.name
                })
            }.getOrDefault(SyncStatus.DISCONNECTED),
            error = preferences[ERROR],
            conflictSummary = preferences[CONFLICT_SUMMARY]?.let { encoded ->
                runCatching { json.decodeFromString(SyncConflictSummary.serializer(), encoded) }.getOrNull()
            },
        )
    }

    private fun readTargetConfig(preferences: Preferences, backend: SyncBackend) = SyncTargetConfig(
        enabled = preferences[targetEnabledKey(backend)] ?: false,
        endpoint = preferences[targetEndpointKey(backend)].orEmpty(),
        username = preferences[targetUsernameKey(backend)].orEmpty(),
    )

    private fun readBackendState(
        preferences: Preferences,
        backend: SyncBackend,
        enabled: Boolean,
        legacyBackend: SyncBackend,
    ): BackendSyncState {
        val legacy = preferences[PRIMARY_BACKEND] == null && backend == legacyBackend
        return BackendSyncState(
            status = runCatching {
                BackendSyncStatus.valueOf(
                    preferences[backendStatusKey(backend)] ?: if (enabled) {
                        BackendSyncStatus.IDLE.name
                    } else {
                        BackendSyncStatus.DISABLED.name
                    },
                )
            }.getOrDefault(if (enabled) BackendSyncStatus.IDLE else BackendSyncStatus.DISABLED),
            remoteRevision = preferences[backendRevisionKey(backend)]
                ?: preferences[LEGACY_REVISION].takeIf { legacy },
            acknowledgedContentHash = preferences[backendContentHashKey(backend)],
            lastSyncTimestamp = preferences[backendLastSyncKey(backend)]
                ?: preferences[LEGACY_LAST_SYNC].takeIf { legacy },
            error = preferences[backendErrorKey(backend)],
        )
    }

    private fun targetIdentityChanged(
        preferences: Preferences,
        backend: SyncBackend,
        target: SyncTargetConfig,
    ): Boolean {
        if (preferences[PRIMARY_BACKEND] == null) {
            val legacyBackend = runCatching {
                SyncBackend.valueOf(preferences[LEGACY_BACKEND] ?: SyncBackend.NONE.name)
            }.getOrDefault(SyncBackend.NONE)
            if (legacyBackend == backend) {
                return !target.enabled || preferences[LEGACY_ENDPOINT].orEmpty() != target.endpoint.trim()
            }
        }
        return preferences[targetEnabledKey(backend)] != target.enabled ||
            preferences[targetEndpointKey(backend)].orEmpty() != target.endpoint.trim() ||
            preferences[targetUsernameKey(backend)].orEmpty() != target.username.trim()
    }

    private fun writeTargetConfig(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        backend: SyncBackend,
        target: SyncTargetConfig,
        identityChanged: Boolean,
    ) {
        preferences[targetEnabledKey(backend)] = target.enabled
        preferences[targetEndpointKey(backend)] = target.endpoint.trim()
        preferences[targetUsernameKey(backend)] = target.username.trim()
        if (!target.enabled) {
            preferences[backendStatusKey(backend)] = BackendSyncStatus.DISABLED.name
        } else if (identityChanged) {
            preferences[backendStatusKey(backend)] = BackendSyncStatus.IDLE.name
        }
    }

    private fun configuredOverallStatus(
        preferences: Preferences,
        config: SyncConfig,
    ): SyncStatus {
        val primaryStatus = runCatching {
            BackendSyncStatus.valueOf(
                preferences[backendStatusKey(config.primaryBackend)] ?: BackendSyncStatus.IDLE.name,
            )
        }.getOrDefault(BackendSyncStatus.IDLE)
        if (primaryStatus == BackendSyncStatus.CONFLICT) return SyncStatus.CONFLICT
        if (primaryStatus == BackendSyncStatus.ERROR) return SyncStatus.ERROR
        val hasUnhealthyBackup = config.enabledBackends
            .filterNot { it == config.primaryBackend }
            .map { backend ->
                runCatching {
                    BackendSyncStatus.valueOf(
                        preferences[backendStatusKey(backend)] ?: BackendSyncStatus.IDLE.name,
                    )
                }.getOrDefault(BackendSyncStatus.IDLE)
            }
            .any { it in setOf(BackendSyncStatus.CONFLICT, BackendSyncStatus.DIVERGED, BackendSyncStatus.ERROR) }
        return if (hasUnhealthyBackup) SyncStatus.PARTIAL else SyncStatus.IDLE
    }

    private fun clearBackendMetadata(
        preferences: androidx.datastore.preferences.core.MutablePreferences,
        backend: SyncBackend,
    ) {
        preferences.remove(backendRevisionKey(backend))
        preferences.remove(backendContentHashKey(backend))
        preferences.remove(backendLastSyncKey(backend))
        preferences.remove(backendErrorKey(backend))
    }

    private fun removeLegacyConfig(preferences: androidx.datastore.preferences.core.MutablePreferences) {
        preferences.remove(LEGACY_BACKEND)
        preferences.remove(LEGACY_ENDPOINT)
        preferences.remove(LEGACY_USERNAME)
        preferences.remove(LEGACY_REVISION)
        preferences.remove(LEGACY_LOCAL_HASH)
        preferences.remove(LEGACY_LAST_SYNC)
    }

    private fun backendPrefix(backend: SyncBackend): String = when (backend) {
        SyncBackend.GIST -> "sync_gist"
        SyncBackend.WEBDAV -> "sync_webdav"
        SyncBackend.DOCUMENT -> "sync_document"
        SyncBackend.NONE -> error("NONE has no sync metadata")
    }

    private fun targetEnabledKey(backend: SyncBackend) = booleanPreferencesKey("${backendPrefix(backend)}_enabled")
    private fun targetEndpointKey(backend: SyncBackend) = stringPreferencesKey("${backendPrefix(backend)}_endpoint")
    private fun targetUsernameKey(backend: SyncBackend) = stringPreferencesKey("${backendPrefix(backend)}_username")
    private fun backendRevisionKey(backend: SyncBackend) = stringPreferencesKey("${backendPrefix(backend)}_revision")
    private fun backendContentHashKey(backend: SyncBackend) = stringPreferencesKey("${backendPrefix(backend)}_content_hash")
    private fun backendLastSyncKey(backend: SyncBackend) = longPreferencesKey("${backendPrefix(backend)}_last_sync")
    private fun backendStatusKey(backend: SyncBackend) = stringPreferencesKey("${backendPrefix(backend)}_status")
    private fun backendErrorKey(backend: SyncBackend) = stringPreferencesKey("${backendPrefix(backend)}_error")

    private data class CanonicalSync(
        val content: String,
        val localHash: String,
        val generation: Long,
        val remote: RemoteSyncFile,
        val baselineContent: String,
    )

    private companion object {
        const val CONTENT_RESOLVER_SCHEME = "content"
        val PRIMARY_BACKEND = stringPreferencesKey("sync_primary_backend")
        val AUTO_SYNC = booleanPreferencesKey("sync_auto")
        val INCLUDE_PASSWORDS = booleanPreferencesKey("sync_include_encrypted_passwords")
        val LOCAL_HASH = stringPreferencesKey("sync_local_payload_hash_v1")
        val GENERATION = longPreferencesKey("sync_generation")
        val DEVICE_ID = stringPreferencesKey("sync_device_id")
        val LAST_SYNC = longPreferencesKey("sync_last_timestamp_v1")
        val STATUS = stringPreferencesKey("sync_status")
        val ERROR = stringPreferencesKey("sync_error")
        val CONFLICT_SUMMARY = stringPreferencesKey("sync_conflict_summary")
        val BASELINE_PAYLOAD = stringPreferencesKey("sync_semantic_baseline_v1")

        val LEGACY_BACKEND = stringPreferencesKey("sync_backend")
        val LEGACY_ENDPOINT = stringPreferencesKey("sync_endpoint")
        val LEGACY_USERNAME = stringPreferencesKey("sync_username")
        val LEGACY_REVISION = stringPreferencesKey("sync_remote_revision")
        val LEGACY_LOCAL_HASH = stringPreferencesKey("sync_local_payload_hash")
        val LEGACY_LAST_SYNC = longPreferencesKey("sync_last_timestamp")
    }
}
