package dev.minios.ocremote.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.minios.ocremote.data.sync.BackupSyncDecision
import dev.minios.ocremote.data.sync.EncryptedSecrets
import dev.minios.ocremote.data.sync.GistSyncTransport
import dev.minios.ocremote.data.sync.LocalSyncSecretStore
import dev.minios.ocremote.data.sync.PasswordCrypto
import dev.minios.ocremote.data.sync.PasswordSecrets
import dev.minios.ocremote.data.sync.RemoteSyncFile
import dev.minios.ocremote.data.sync.SyncDecision
import dev.minios.ocremote.data.sync.SyncPayload
import dev.minios.ocremote.data.sync.SyncTransport
import dev.minios.ocremote.data.sync.WebDavSyncTransport
import dev.minios.ocremote.data.sync.decideBackupSync
import dev.minios.ocremote.data.sync.decideSync
import dev.minios.ocremote.data.sync.decodeSyncPayload
import io.ktor.client.HttpClient
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

enum class SyncBackend { NONE, GIST, WEBDAV }
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
    val autoSync: Boolean = false,
    val includeEncryptedPasswords: Boolean = false,
) {
    fun target(backend: SyncBackend): SyncTargetConfig = when (backend) {
        SyncBackend.GIST -> gist
        SyncBackend.WEBDAV -> webDav
        SyncBackend.NONE -> SyncTargetConfig()
    }

    val enabledBackends: List<SyncBackend>
        get() = buildList {
            if (gist.enabled) add(SyncBackend.GIST)
            if (webDav.enabled) add(SyncBackend.WEBDAV)
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
    val lastLocalPayloadHash: String? = null,
    val generation: Long = 0,
    val lastSyncTimestamp: Long? = null,
    val status: SyncStatus = SyncStatus.DISCONNECTED,
    val error: String? = null,
) {
    fun backendState(backend: SyncBackend): BackendSyncState = when (backend) {
        SyncBackend.GIST -> gistState
        SyncBackend.WEBDAV -> webDavState
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

        dataStore.edit { preferences ->
            val gistIdentityChanged = targetIdentityChanged(preferences, SyncBackend.GIST, config.gist)
            val webDavIdentityChanged = targetIdentityChanged(preferences, SyncBackend.WEBDAV, config.webDav)
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
            preferences[AUTO_SYNC] = config.autoSync
            preferences[INCLUDE_PASSWORDS] = config.includeEncryptedPasswords
            if (gistIdentityChanged) clearBackendMetadata(preferences, SyncBackend.GIST)
            if (webDavIdentityChanged) clearBackendMetadata(preferences, SyncBackend.WEBDAV)
            val overallStatus = configuredOverallStatus(preferences, config)
            preferences[STATUS] = overallStatus.name
            if (overallStatus == SyncStatus.IDLE) {
                preferences.remove(ERROR)
            }
            if (passphraseChanged) preferences.remove(LOCAL_HASH)
            removeLegacyConfig(preferences)
        }
    }

    suspend fun syncNow(): SyncDecision = syncMutex.withLock {
        val current = configuredState()
        val primary = current.config.primaryBackend
        saveStatus(SyncStatus.SYNCING)
        saveBackendStatus(primary, BackendSyncStatus.SYNCING)
        try {
            val local = snapshot(current.config.includeEncryptedPasswords)
            val localHash = localPayloadHash(local, current.config.includeEncryptedPasswords)
            val primaryTransport = transport(primary, current.config.target(primary))
            val remote = primaryTransport.read()
            val primaryState = current.backendState(primary)
            val remoteContentHash = remote?.content?.let(::contentHash)
            val storedMarker = primaryState.remoteRevision ?: primaryState.acknowledgedContentHash
            val remoteMarker = remote?.revision ?: remoteContentHash
            val decision = decideSync(
                remoteExists = remote != null,
                storedRevision = storedMarker,
                remoteRevision = remoteMarker,
                storedLocalHash = current.lastLocalPayloadHash,
                localHash = localHash,
            )
            val canonical = when (decision) {
                SyncDecision.MISSING_UPLOAD,
                SyncDecision.PUSH_LOCAL,
                -> uploadPrimary(current, primaryTransport, remote, local, localHash)

                SyncDecision.PULL_REMOTE -> importPrimary(current, remote!!)
                SyncDecision.UP_TO_DATE -> CanonicalSync(
                    content = remote!!.content,
                    localHash = localHash,
                    generation = decodePayload(remote.content).generation,
                    remote = remote,
                )

                SyncDecision.CONFLICT -> {
                    saveBackendStatus(primary, BackendSyncStatus.CONFLICT)
                    saveStatus(SyncStatus.CONFLICT, "Local and primary sync data both need a choice")
                    return@withLock decision
                }
            }
            saveCanonical(primary, canonical)
            val backupHealthy = reconcileOtherBackends(current.config, primary, canonical, force = false)
            saveStatus(if (backupHealthy) SyncStatus.IDLE else SyncStatus.PARTIAL)
            decision
        } catch (e: Exception) {
            saveBackendStatus(primary, BackendSyncStatus.ERROR, e.message ?: "Sync failed")
            saveStatus(SyncStatus.ERROR, e.message ?: "Sync failed")
            throw e
        }
    }

    suspend fun forceUpload() = syncMutex.withLock {
        val current = configuredState()
        val primary = current.config.primaryBackend
        saveStatus(SyncStatus.SYNCING)
        saveBackendStatus(primary, BackendSyncStatus.SYNCING)
        try {
            val local = snapshot(current.config.includeEncryptedPasswords)
            val localHash = localPayloadHash(local, current.config.includeEncryptedPasswords)
            val transport = transport(primary, current.config.target(primary))
            val remote = transport.read()
            val canonical = uploadPrimary(current, transport, remote, local, localHash)
            saveCanonical(primary, canonical)
            val backupHealthy = reconcileOtherBackends(current.config, primary, canonical, force = true)
            saveStatus(if (backupHealthy) SyncStatus.IDLE else SyncStatus.PARTIAL)
        } catch (e: Exception) {
            saveBackendStatus(primary, BackendSyncStatus.ERROR, e.message ?: "Sync failed")
            saveStatus(SyncStatus.ERROR, e.message ?: "Sync failed")
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
            val canonical = importPrimary(current, remote)
            saveCanonical(selected, canonical)
            val othersHealthy = reconcileOtherBackends(current.config, selected, canonical, force = true)
            saveStatus(if (othersHealthy) SyncStatus.IDLE else SyncStatus.PARTIAL)
        } catch (e: Exception) {
            saveBackendStatus(selected, BackendSyncStatus.ERROR, e.message ?: "Sync failed")
            saveStatus(SyncStatus.ERROR, e.message ?: "Sync failed")
            throw e
        }
    }

    suspend fun disconnect() = syncMutex.withLock {
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
            SyncBackend.entries.filterNot { it == SyncBackend.NONE }.forEach { backend ->
                preferences.remove(targetEnabledKey(backend))
                preferences.remove(targetEndpointKey(backend))
                preferences.remove(targetUsernameKey(backend))
                clearBackendMetadata(preferences, backend)
            }
            removeLegacyConfig(preferences)
            preferences[STATUS] = SyncStatus.DISCONNECTED.name
        }
    }

    private suspend fun uploadPrimary(
        current: SyncState,
        transport: SyncTransport,
        remote: RemoteSyncFile?,
        local: SyncPayload,
        localHash: String,
    ): CanonicalSync {
        val generation = current.generation + 1
        val payload = local.copy(
            generation = generation,
            parentGeneration = current.generation.takeIf { it > 0 },
            updatedAt = System.currentTimeMillis(),
            writerDeviceId = ensureDeviceId(),
        )
        val content = json.encodeToString(payload)
        val verified = writeAndVerify(transport, content, remote)
        return CanonicalSync(content, localHash, generation, verified)
    }

    private suspend fun importPrimary(
        current: SyncState,
        remote: RemoteSyncFile,
    ): CanonicalSync {
        val payload = decodePayload(remote.content)
        val passwords = if (current.config.includeEncryptedPasswords) {
            decryptPasswords(payload.encryptedSecrets)
        } else {
            emptyMap()
        }
        dataStore.edit { preferences ->
            val serverIdMapping = serverRepository.importSyncServersTo(
                preferences,
                payload.servers,
                passwords,
            )
            settingsRepository.applySyncSettingsTo(
                preferences,
                payload.settings,
                payload.sessionCategories,
            )
            settingsRepository.applySyncSessionCategoryAssignmentsTo(
                preferences,
                payload.sessionCategoryAssignments,
                serverIdMapping,
            )
            settingsRepository.applySyncSessionCollectionsTo(
                preferences = preferences,
                favoriteSessionIds = payload.favoriteSessionIds,
                crossServerFavoriteOrder = payload.crossServerFavoriteOrder,
                favoriteSessionSnapshots = payload.favoriteSessionSnapshots,
                hiddenModels = payload.hiddenModels,
                serverIdMapping = serverIdMapping,
            )
            diagnosticLogRepository.applyLogLevelTo(preferences, payload.settings.diagnosticLogLevel)
        }
        settingsRepository.updateSynchronousLocale(payload.settings.appLanguage)
        return CanonicalSync(
            content = remote.content,
            // Hash the selected remote data, not a post-import snapshot that could include
            // concurrent local edits. Such edits must remain pending for the next upload.
            localHash = localPayloadHash(payload, current.config.includeEncryptedPasswords),
            generation = payload.generation,
            remote = remote,
        )
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
        val servers = serverRepository.syncServersSnapshotFrom(preferences)
        val serverIds = servers.map { it.id }
        val passwords = if (includePasswords) {
            serverRepository.serverConfigsFrom(preferences).filter { it.id in serverIds }.mapNotNull { server ->
                server.password?.let { server.id to it }
            }.toMap()
        } else {
            emptyMap()
        }
        val encrypted = if (passwords.isEmpty()) null else {
            val passphrase = secretStore.get(LocalSyncSecretStore.SecretKey.SYNC_PASSPHRASE)
                ?: throw IllegalStateException("A sync passphrase is required to include passwords")
            val chars = passphrase.toCharArray()
            try {
                PasswordCrypto.encrypt(json.encodeToString(PasswordSecrets(passwords)).toByteArray(), chars)
            } finally {
                chars.fill('\u0000')
            }
        }
        return SyncPayload(
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
            encryptedSecrets = encrypted,
        )
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

        SyncBackend.NONE -> error("Sync is not configured")
    }

    private suspend fun configuredState(): SyncState = state.first().also {
        check(it.config.primaryBackend != SyncBackend.NONE) { "Sync is not configured" }
    }

    private suspend fun saveCanonical(backend: SyncBackend, canonical: CanonicalSync) {
        dataStore.edit { preferences ->
            writeBackendSuccess(preferences, backend, canonical.remote)
            preferences[LOCAL_HASH] = canonical.localHash
            preferences[GENERATION] = canonical.generation
            preferences[LAST_SYNC] = System.currentTimeMillis()
            preferences.remove(ERROR)
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

    private fun contentHash(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun hasSecret(key: LocalSyncSecretStore.SecretKey): Boolean = !secretStore.get(key).isNullOrBlank()

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
            SyncConfig(
                primaryBackend = primary,
                gist = storedGist.copy(enabled = primary == SyncBackend.GIST),
                webDav = storedWebDav.copy(enabled = primary == SyncBackend.WEBDAV),
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
        return SyncState(
            config = config,
            hasGithubToken = hasSecret(LocalSyncSecretStore.SecretKey.GITHUB_TOKEN),
            hasWebDavPassword = hasSecret(LocalSyncSecretStore.SecretKey.WEBDAV_PASSWORD),
            hasSyncPassphrase = hasSecret(LocalSyncSecretStore.SecretKey.SYNC_PASSPHRASE),
            gistState = readBackendState(preferences, SyncBackend.GIST, gist.enabled, legacyBackend),
            webDavState = readBackendState(preferences, SyncBackend.WEBDAV, webDav.enabled, legacyBackend),
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
            preferences[targetEndpointKey(backend)].orEmpty() != target.endpoint.trim()
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
    )

    private companion object {
        val PRIMARY_BACKEND = stringPreferencesKey("sync_primary_backend")
        val AUTO_SYNC = booleanPreferencesKey("sync_auto")
        val INCLUDE_PASSWORDS = booleanPreferencesKey("sync_include_encrypted_passwords")
        val LOCAL_HASH = stringPreferencesKey("sync_local_payload_hash_v1")
        val GENERATION = longPreferencesKey("sync_generation")
        val DEVICE_ID = stringPreferencesKey("sync_device_id")
        val LAST_SYNC = longPreferencesKey("sync_last_timestamp_v1")
        val STATUS = stringPreferencesKey("sync_status")
        val ERROR = stringPreferencesKey("sync_error")

        val LEGACY_BACKEND = stringPreferencesKey("sync_backend")
        val LEGACY_ENDPOINT = stringPreferencesKey("sync_endpoint")
        val LEGACY_USERNAME = stringPreferencesKey("sync_username")
        val LEGACY_REVISION = stringPreferencesKey("sync_remote_revision")
        val LEGACY_LOCAL_HASH = stringPreferencesKey("sync_local_payload_hash")
        val LEGACY_LAST_SYNC = longPreferencesKey("sync_last_timestamp")
    }
}
