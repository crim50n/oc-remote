package dev.minios.ocremote.data.repository

import dev.minios.ocremote.logging.AppLogger as Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.domain.model.ServerConfig
import dev.minios.ocremote.domain.model.ServerHealth
import dev.minios.ocremote.data.sync.SyncServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ServerRepository"
private const val SERVERS_KEY = "servers"

internal fun normalizeServerUrl(url: String): String = url.trim().trimEnd('/')

internal data class ServerMergeResult(
    val servers: List<ServerConfig>,
    val idMapping: Map<String, String>,
)

internal fun mergeSyncServers(
    current: List<ServerConfig>,
    remote: List<SyncServer>,
    passwords: Map<String, String>,
    idGenerator: () -> String = { UUID.randomUUID().toString() },
): ServerMergeResult {
    val mergedServers = current.toMutableList()
    val usedIds = current.mapTo(mutableSetOf()) { it.id }
    val idMapping = mutableMapOf<String, String>()
    remote.forEach { source ->
        val normalized = normalizeServerUrl(source.url)
        val existingIndex = mergedServers.indexOfFirst { normalizeServerUrl(it.url) == normalized }
        val existing = mergedServers.getOrNull(existingIndex)
        val password = passwords[source.id]
        val merged = if (existing != null) {
            existing.copy(
                url = normalized,
                name = source.name,
                username = source.username,
                autoConnect = source.autoConnect,
                password = password ?: existing.password,
            )
        } else {
            val id = source.id.takeIf { it !in usedIds } ?: idGenerator()
            usedIds += id
            ServerConfig(id, normalized, source.username, password, source.name, source.autoConnect)
        }
        if (existingIndex >= 0) mergedServers[existingIndex] = merged else mergedServers += merged
        idMapping[source.id] = merged.id
    }
    return ServerMergeResult(mergedServers, idMapping)
}

data class LocalServerUpsertResult(
    val server: ServerConfig,
    val removedServerIds: List<String>,
)

internal fun upsertLocalServerConfig(
    current: List<ServerConfig>,
    localUrl: String,
    username: String,
    password: String?,
    defaultName: String,
    idGenerator: () -> String = { UUID.randomUUID().toString() },
): Pair<List<ServerConfig>, LocalServerUpsertResult> {
    val normalized = normalizeServerUrl(localUrl)
    val matches = current.filter { normalizeServerUrl(it.url) == normalized }
    if (matches.isEmpty()) {
        val server = ServerConfig(
            id = idGenerator(),
            url = normalized,
            username = username,
            password = password,
            name = defaultName,
            autoConnect = false,
        )
        return (current + server) to LocalServerUpsertResult(server, emptyList())
    }
    val canonical = matches.first()
    val merged = canonical.copy(
        url = normalized,
        username = username,
        password = password,
        name = canonical.name ?: matches.firstNotNullOfOrNull { it.name } ?: defaultName,
        autoConnect = matches.any { it.autoConnect },
        lastConnected = matches.mapNotNull { it.lastConnected }.maxOrNull(),
        isHealthy = matches.any { it.isHealthy },
    )
    val duplicateIds = matches.drop(1).map(ServerConfig::id)
    val updated = current.mapNotNull { server ->
        when (server.id) {
            canonical.id -> merged
            in duplicateIds -> null
            else -> server
        }
    }
    return updated to LocalServerUpsertResult(merged, duplicateIds)
}

/**
 * Server Repository - manages saved OpenCode servers
 * 
 * Uses DataStore to persist server configurations
 */
@Singleton
class ServerRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val api: OpenCodeApi,
    private val json: Json
) {
    
    private val serversKey = stringPreferencesKey(SERVERS_KEY)
    
    /**
     * Get all saved servers as Flow
     */
    val servers: Flow<List<ServerConfig>> = dataStore.data.map { preferences ->
        val serversJson = preferences[serversKey] ?: "[]"
        try {
            json.decodeFromString<List<ServerConfig>>(serversJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode servers", e)
            emptyList()
        }
    }
    
    /**
     * Get all servers (alias for servers Flow)
     */
    fun getAllServers(): Flow<List<ServerConfig>> = servers
    
    /**
     * Add a new server
     */
    suspend fun addServer(
        url: String,
        username: String = "opencode",
        password: String? = null,
        name: String? = null,
        autoConnect: Boolean = false
    ): ServerConfig {
        val server = ServerConfig(
            id = UUID.randomUUID().toString(),
            url = url.trimEnd('/'),
            username = username,
            password = password,
            name = name,
            autoConnect = autoConnect,
            lastConnected = null,
            isHealthy = false
        )
        
        dataStore.edit { preferences ->
            preferences[serversKey] = json.encodeToString(readServers(preferences) + server)
        }
        
        return server
    }
    
    /**
     * Update a server
     */
    suspend fun updateServer(server: ServerConfig) {
        dataStore.edit { preferences ->
            preferences[serversKey] = json.encodeToString(readServers(preferences).map {
                if (it.id == server.id) server else it
            })
        }
    }

    suspend fun setAutoConnect(serverId: String, autoConnect: Boolean) {
        dataStore.edit { preferences ->
            preferences[serversKey] = json.encodeToString(readServers(preferences).map { server ->
                if (server.id == serverId) server.copy(autoConnect = autoConnect) else server
            })
        }
    }
    
    /**
     * Delete a server
     */
    suspend fun deleteServer(serverId: String) {
        dataStore.edit { preferences ->
            preferences[serversKey] = json.encodeToString(readServers(preferences).filter { it.id != serverId })
        }
    }
    
    /**
     * Check server health
     */
    suspend fun checkHealth(server: ServerConfig): Result<ServerHealth> {
        return try {
            val conn = ServerConnection.from(server.url, server.username, server.password)
            val health = api.getHealth(conn)
            
            // Update server health status
            val updatedServer = server.copy(
                isHealthy = health.healthy,
                lastConnected = System.currentTimeMillis()
            )
            updateServer(updatedServer)
            
            Result.success(health)
        } catch (e: Exception) {
            Log.e(TAG, "Server health check failed", e)
            
            // Mark as unhealthy
            val updatedServer = server.copy(isHealthy = false)
            updateServer(updatedServer)
            
            Result.failure(e)
        }
    }
    
    /**
     * Check server health (alias returning boolean)
     */
    suspend fun checkServerHealth(server: ServerConfig): Boolean {
        return checkHealth(server).getOrNull()?.healthy == true
    }
    
    /**
     * Get server by ID
     */
    suspend fun getServer(serverId: String): ServerConfig? {
        return servers.firstOrNull()?.find { it.id == serverId }
    }

    suspend fun syncServersSnapshot(): List<SyncServer> = (servers.firstOrNull() ?: emptyList()).map {
        SyncServer(it.id, it.url.trimEnd('/'), it.name, it.username, it.autoConnect)
    }

    internal fun syncServersSnapshotFrom(preferences: Preferences): List<SyncServer> =
        readServers(preferences).map {
            SyncServer(it.id, normalizeServerUrl(it.url), it.name, it.username, it.autoConnect)
        }

    internal fun serverConfigsFrom(preferences: Preferences): List<ServerConfig> = readServers(preferences)

    /** Merges by normalized URL, intentionally retaining no remote runtime fields. */
    suspend fun importSyncServers(remote: List<SyncServer>, passwords: Map<String, String>): Map<String, String> {
        var mapping = emptyMap<String, String>()
        dataStore.edit { preferences ->
            mapping = importSyncServersTo(preferences, remote, passwords)
        }
        return mapping
    }

    suspend fun upsertLocalServer(
        url: String,
        username: String,
        password: String?,
        defaultName: String,
    ): LocalServerUpsertResult {
        lateinit var result: LocalServerUpsertResult
        dataStore.edit { preferences ->
            val (servers, upsert) = upsertLocalServerConfig(
                current = readServers(preferences),
                localUrl = url,
                username = username,
                password = password,
                defaultName = defaultName,
            )
            preferences[serversKey] = json.encodeToString(servers)
            result = upsert
        }
        return result
    }

    internal fun importSyncServersTo(
        preferences: MutablePreferences,
        remote: List<SyncServer>,
        passwords: Map<String, String>,
    ): Map<String, String> {
        val result = mergeSyncServers(readServers(preferences), remote, passwords)
        preferences[serversKey] = json.encodeToString(result.servers)
        return result.idMapping
    }
    
    // ============ Private ============
    
    private fun readServers(preferences: Preferences): List<ServerConfig> {
        return preferences[serversKey]?.let { encoded ->
            runCatching { json.decodeFromString<List<ServerConfig>>(encoded) }.getOrDefault(emptyList())
        }.orEmpty()
    }
}
