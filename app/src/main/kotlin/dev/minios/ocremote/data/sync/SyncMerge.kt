package dev.minios.ocremote.data.sync

import dev.minios.ocremote.data.repository.isPortableSyncServerUrl
import dev.minios.ocremote.data.repository.normalizeServerUrl
import dev.minios.ocremote.domain.model.FavoriteSessionSnapshot
import dev.minios.ocremote.domain.model.SessionCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

internal data class SemanticSyncData(
    val payload: SyncPayload,
    val passwords: Map<String, String>,
)

internal data class SyncMergeResult(
    val data: SemanticSyncData,
    val conflictCounts: Map<SyncConflictArea, Int>,
) {
    val hasConflicts: Boolean get() = conflictCounts.isNotEmpty()
}

internal enum class SyncConflictPreference { LOCAL, REMOTE }

private data class ScopedKey(val serverId: String, val valueId: String)

private sealed interface MapEntry<out T> {
    data object Missing : MapEntry<Nothing>
    data class Present<T>(val value: T) : MapEntry<T>
}

internal fun canonicalizeSyncData(
    payload: SyncPayload,
    passwords: Map<String, String>,
    fallback: SemanticSyncData? = null,
): SemanticSyncData {
    val portableServers = payload.servers.filter { isPortableSyncServerUrl(it.url) }
    require(portableServers.map(SyncServer::id).distinct().size == portableServers.size) {
        "Sync data contains duplicate server IDs"
    }
    val normalizedUrls = portableServers.map { normalizeServerUrl(it.url) }
    require(normalizedUrls.distinct().size == normalizedUrls.size) {
        "Sync data contains duplicate server URLs"
    }
    require(payload.sessionCategories.map(SessionCategory::id).distinct().size == payload.sessionCategories.size) {
        "Sync data contains duplicate category IDs"
    }
    val idMapping = portableServers.associate { it.id to canonicalServerId(it.url) }
    val servers = portableServers.map { server ->
        server.copy(
            id = idMapping.getValue(server.id),
            url = normalizeServerUrl(server.url),
        )
    }
    val knownIds = idMapping.keys
    val canonicalIds = idMapping.values.toSet()

    fun remapOuterMap(source: Map<String, Map<String, String>>): Map<String, Map<String, String>> = source
        .filterKeys { it in knownIds }
        .filterValues { it.isNotEmpty() }
        .mapValues { (_, values) -> values.toSortedMap() }
        .mapKeys { (id, _) -> idMapping.getValue(id) }
        .toSortedMap()

    fun remapListMap(source: Map<String, List<String>>): Map<String, List<String>> = source
        .filterKeys { it in knownIds }
        .filterValues { it.isNotEmpty() }
        .mapKeys { (id, _) -> idMapping.getValue(id) }
        .toSortedMap()

    fun remapSetMap(source: Map<String, Set<String>>): Map<String, Set<String>> = source
        .filterKeys { it in knownIds }
        .filterValues { it.isNotEmpty() }
        .mapValues { (_, values) -> values.toSortedSet() }
        .mapKeys { (id, _) -> idMapping.getValue(id) }
        .toSortedMap()

    fun remapScopedKey(key: String): String? {
        val sourceId = key.substringBefore(':')
        val valueId = key.substringAfter(':', missingDelimiterValue = "")
        if (valueId.isBlank()) return null
        return idMapping[sourceId]?.let { "$it:$valueId" }
    }

    val fallbackPayload = fallback?.payload
    val canonical = payload.copy(
        generation = 0,
        parentGeneration = null,
        updatedAt = 0,
        writerDeviceId = "",
        settings = materializeSettings(payload.settings, fallbackPayload?.settings),
        sessionCategoryAssignments = remapOuterMap(payload.sessionCategoryAssignments),
        favoriteSessionIds = payload.favoriteSessionIds
            ?.let(::remapListMap)
            ?: fallbackPayload?.favoriteSessionIds?.filterKeys { it in canonicalIds }
            ?: emptyMap(),
        crossServerFavoriteOrder = payload.crossServerFavoriteOrder
            ?.mapNotNull(::remapScopedKey)
            ?: fallbackPayload?.crossServerFavoriteOrder?.filter { it.substringBefore(':') in canonicalIds }
            ?: emptyList(),
        favoriteSessionSnapshots = payload.favoriteSessionSnapshots
            ?.mapNotNull { (key, value) -> remapScopedKey(key)?.let { it to value } }
            ?.toMap()
            ?.toSortedMap()
            ?: fallbackPayload?.favoriteSessionSnapshots?.filterKeys { it.substringBefore(':') in canonicalIds }
            ?: emptyMap(),
        hiddenModels = payload.hiddenModels
            ?.let(::remapSetMap)
            ?: fallbackPayload?.hiddenModels?.filterKeys { it in canonicalIds }
            ?: emptyMap(),
        servers = servers,
        passwordsIncluded = null,
        encryptedSecrets = null,
    )
    val passwordsAreAuthoritative = payload.passwordsIncluded == true || payload.encryptedSecrets != null
    val canonicalPasswords = if (!passwordsAreAuthoritative && fallback != null) {
        fallback.passwords.filterKeys { it in canonicalIds }
    } else {
        passwords
            .filterKeys { it in knownIds }
            .mapKeys { (id, _) -> idMapping.getValue(id) }
            .toSortedMap()
    }
    return SemanticSyncData(canonical, canonicalPasswords)
}

internal fun mergeSyncData(
    base: SemanticSyncData,
    local: SemanticSyncData,
    remote: SemanticSyncData,
    json: Json,
    conflictPreference: SyncConflictPreference? = null,
): SyncMergeResult {
    val conflicts = mutableMapOf<SyncConflictArea, Int>()
    fun conflict(area: SyncConflictArea) {
        conflicts[area] = conflicts.getOrDefault(area, 0) + 1
    }

    fun <T> mergeValue(area: SyncConflictArea, baseValue: T, localValue: T, remoteValue: T): T = when {
        localValue == remoteValue -> localValue
        localValue == baseValue -> remoteValue
        remoteValue == baseValue -> localValue
        else -> {
            when (conflictPreference) {
                SyncConflictPreference.LOCAL -> localValue
                SyncConflictPreference.REMOTE -> remoteValue
                null -> {
                    conflict(area)
                    localValue
                }
            }
        }
    }

    fun <K, V> mergeMap(
        area: SyncConflictArea,
        baseMap: Map<K, V>,
        localMap: Map<K, V>,
        remoteMap: Map<K, V>,
    ): Map<K, V> {
        val result = linkedMapOf<K, V>()
        (baseMap.keys + localMap.keys + remoteMap.keys).forEach { key ->
            val baseEntry = baseMap.entry(key)
            val localEntry = localMap.entry(key)
            val remoteEntry = remoteMap.entry(key)
            val merged = mergeValue(area, baseEntry, localEntry, remoteEntry)
            if (merged is MapEntry.Present) result[key] = merged.value
        }
        return result
    }

    fun mergeServers(
        baseMap: Map<String, SyncServer>,
        localMap: Map<String, SyncServer>,
        remoteMap: Map<String, SyncServer>,
    ): Map<String, SyncServer> = buildMap {
        (baseMap.keys + localMap.keys + remoteMap.keys).forEach { key ->
            val baseValue = baseMap[key]
            val localValue = localMap[key]
            val remoteValue = remoteMap[key]
            val merged = when {
                localValue == remoteValue -> localValue
                localValue == baseValue -> remoteValue
                remoteValue == baseValue -> localValue
                baseValue != null && localValue != null && remoteValue != null -> baseValue.copy(
                    name = mergeValue(SyncConflictArea.SERVERS, baseValue.name, localValue.name, remoteValue.name),
                    username = mergeValue(
                        SyncConflictArea.SERVERS,
                        baseValue.username,
                        localValue.username,
                        remoteValue.username,
                    ),
                    autoConnect = mergeValue(
                        SyncConflictArea.SERVERS,
                        baseValue.autoConnect,
                        localValue.autoConnect,
                        remoteValue.autoConnect,
                    ),
                )
                else -> when (conflictPreference) {
                    SyncConflictPreference.LOCAL -> localValue
                    SyncConflictPreference.REMOTE -> remoteValue
                    null -> {
                        conflict(SyncConflictArea.SERVERS)
                        localValue
                    }
                }
            }
            if (merged != null) put(key, merged)
        }
    }

    fun mergeCategories(
        baseMap: Map<String, SessionCategory>,
        localMap: Map<String, SessionCategory>,
        remoteMap: Map<String, SessionCategory>,
    ): Map<String, SessionCategory> = buildMap {
        (baseMap.keys + localMap.keys + remoteMap.keys).forEach { key ->
            val baseValue = baseMap[key]
            val localValue = localMap[key]
            val remoteValue = remoteMap[key]
            val merged = when {
                localValue == remoteValue -> localValue
                localValue == baseValue -> remoteValue
                remoteValue == baseValue -> localValue
                baseValue != null && localValue != null && remoteValue != null -> baseValue.copy(
                    name = mergeValue(
                        SyncConflictArea.CATEGORIES,
                        baseValue.name,
                        localValue.name,
                        remoteValue.name,
                    ),
                    color = mergeValue(
                        SyncConflictArea.CATEGORIES,
                        baseValue.color,
                        localValue.color,
                        remoteValue.color,
                    ),
                    icon = mergeValue(
                        SyncConflictArea.CATEGORIES,
                        baseValue.icon,
                        localValue.icon,
                        remoteValue.icon,
                    ),
                )
                else -> when (conflictPreference) {
                    SyncConflictPreference.LOCAL -> localValue
                    SyncConflictPreference.REMOTE -> remoteValue
                    null -> {
                        conflict(SyncConflictArea.CATEGORIES)
                        localValue
                    }
                }
            }
            if (merged != null) put(key, merged)
        }
    }

    val baseSettings = json.encodeToJsonElement(base.payload.settings).jsonObject
    val localSettings = json.encodeToJsonElement(local.payload.settings).jsonObject
    val remoteSettings = json.encodeToJsonElement(remote.payload.settings).jsonObject
    val mergedSettingsJson = JsonObject(
        (baseSettings.keys + localSettings.keys + remoteSettings.keys).associateWith { key ->
            mergeValue(
                SyncConflictArea.SETTINGS,
                baseSettings.getValue(key),
                localSettings.getValue(key),
                remoteSettings.getValue(key),
            )
        },
    )
    val mergedSettings = json.decodeFromJsonElement<SyncSettings>(mergedSettingsJson)

    val baseServers = base.payload.servers.associateBy(SyncServer::id)
    val localServers = local.payload.servers.associateBy(SyncServer::id)
    val remoteServers = remote.payload.servers.associateBy(SyncServer::id)
    val mergedServersById = mergeServers(baseServers, localServers, remoteServers).toMutableMap()
    baseServers.keys.forEach { serverId ->
        val localDeleted = serverId !in localServers
        val remoteDeleted = serverId !in remoteServers
        val survivingSideChangedDependencies = when {
            localDeleted && !remoteDeleted -> serverDependenciesChanged(base, remote, serverId)
            remoteDeleted && !localDeleted -> serverDependenciesChanged(base, local, serverId)
            else -> false
        }
        if (survivingSideChangedDependencies) {
            when (conflictPreference) {
                SyncConflictPreference.LOCAL -> localServers[serverId]?.let { mergedServersById[serverId] = it }
                    ?: mergedServersById.remove(serverId)
                SyncConflictPreference.REMOTE -> remoteServers[serverId]?.let { mergedServersById[serverId] = it }
                    ?: mergedServersById.remove(serverId)
                null -> {
                    conflict(SyncConflictArea.SERVERS)
                    localServers[serverId]?.let { mergedServersById[serverId] = it }
                        ?: mergedServersById.remove(serverId)
                }
            }
        }
    }
    val mergedServerOrder = mergeOrder(
        area = SyncConflictArea.SERVERS,
        base = base.payload.servers.map(SyncServer::id),
        local = local.payload.servers.map(SyncServer::id),
        remote = remote.payload.servers.map(SyncServer::id),
        retained = mergedServersById.keys,
        onConflict = ::conflict,
        conflictPreference = conflictPreference,
    )
    val mergedServers = mergedServerOrder.mapNotNull(mergedServersById::get)

    val baseCategories = base.payload.sessionCategories.associateBy(SessionCategory::id)
    val localCategories = local.payload.sessionCategories.associateBy(SessionCategory::id)
    val remoteCategories = remote.payload.sessionCategories.associateBy(SessionCategory::id)
    val mergedCategoriesById = mergeCategories(baseCategories, localCategories, remoteCategories).toMutableMap()
    baseCategories.keys.forEach { categoryId ->
        val localDeleted = categoryId !in localCategories
        val remoteDeleted = categoryId !in remoteCategories
        val survivingSideChangedAssignments = when {
            localDeleted && !remoteDeleted -> categoryAssignmentsChanged(base, remote, categoryId)
            remoteDeleted && !localDeleted -> categoryAssignmentsChanged(base, local, categoryId)
            else -> false
        }
        if (survivingSideChangedAssignments) {
            when (conflictPreference) {
                SyncConflictPreference.LOCAL -> localCategories[categoryId]?.let { mergedCategoriesById[categoryId] = it }
                    ?: mergedCategoriesById.remove(categoryId)
                SyncConflictPreference.REMOTE -> remoteCategories[categoryId]?.let {
                    mergedCategoriesById[categoryId] = it
                } ?: mergedCategoriesById.remove(categoryId)
                null -> {
                    conflict(SyncConflictArea.CATEGORIES)
                    localCategories[categoryId]?.let { mergedCategoriesById[categoryId] = it }
                        ?: mergedCategoriesById.remove(categoryId)
                }
            }
        }
    }
    val mergedCategoryOrder = mergeOrder(
        area = SyncConflictArea.CATEGORIES,
        base = base.payload.sessionCategories.map(SessionCategory::id),
        local = local.payload.sessionCategories.map(SessionCategory::id),
        remote = remote.payload.sessionCategories.map(SessionCategory::id),
        retained = mergedCategoriesById.keys,
        onConflict = ::conflict,
        conflictPreference = conflictPreference,
    )
    val mergedCategories = mergedCategoryOrder.mapNotNull(mergedCategoriesById::get)

    fun flattenAssignments(source: Map<String, Map<String, String>>): Map<ScopedKey, String> = buildMap {
        source.forEach { (serverId, assignments) ->
            assignments.forEach { (sessionId, categoryId) -> put(ScopedKey(serverId, sessionId), categoryId) }
        }
    }
    fun expandAssignments(source: Map<ScopedKey, String>): Map<String, Map<String, String>> = source.entries
        .groupBy({ it.key.serverId }, { it.key.valueId to it.value })
        .mapValues { (_, entries) -> entries.toMap() }

    val mergedAssignments = expandAssignments(
        mergeMap(
            SyncConflictArea.CATEGORY_ASSIGNMENTS,
            flattenAssignments(base.payload.sessionCategoryAssignments),
            flattenAssignments(local.payload.sessionCategoryAssignments),
            flattenAssignments(remote.payload.sessionCategoryAssignments),
        ),
    )
    fun flattenFavorites(source: Map<String, List<String>>): Map<ScopedKey, Boolean> = buildMap {
        source.forEach { (serverId, sessions) -> sessions.forEach { put(ScopedKey(serverId, it), true) } }
    }
    val mergedFavoriteMembers = mergeMap(
        SyncConflictArea.FAVORITES,
        flattenFavorites(base.payload.favoriteSessionIds.orEmpty()),
        flattenFavorites(local.payload.favoriteSessionIds.orEmpty()),
        flattenFavorites(remote.payload.favoriteSessionIds.orEmpty()),
    ).keys
    val favoriteServerIds = mergedFavoriteMembers.mapTo(linkedSetOf(), ScopedKey::serverId)
    val mergedFavorites = favoriteServerIds.associateWith { serverId ->
        val retainedSessions = mergedFavoriteMembers
            .filter { it.serverId == serverId }
            .mapTo(linkedSetOf(), ScopedKey::valueId)
        mergeOrder(
            area = SyncConflictArea.FAVORITES,
            base = base.payload.favoriteSessionIds.orEmpty()[serverId].orEmpty(),
            local = local.payload.favoriteSessionIds.orEmpty()[serverId].orEmpty(),
            remote = remote.payload.favoriteSessionIds.orEmpty()[serverId].orEmpty(),
            retained = retainedSessions,
            onConflict = ::conflict,
            conflictPreference = conflictPreference,
        )
    }
    val retainedFavoriteKeys = mergedFavoriteMembers.mapTo(linkedSetOf()) { "${it.serverId}:${it.valueId}" }
    val mergedCrossOrder = mergeOrder(
        area = SyncConflictArea.FAVORITES,
        base = base.payload.crossServerFavoriteOrder.orEmpty(),
        local = local.payload.crossServerFavoriteOrder.orEmpty(),
        remote = remote.payload.crossServerFavoriteOrder.orEmpty(),
        retained = retainedFavoriteKeys,
        onConflict = ::conflict,
        conflictPreference = conflictPreference,
    )
    val mergedSnapshots = mergeSnapshotMap(
        base.payload.favoriteSessionSnapshots.orEmpty(),
        local.payload.favoriteSessionSnapshots.orEmpty(),
        remote.payload.favoriteSessionSnapshots.orEmpty(),
    )

    fun flattenHidden(source: Map<String, Set<String>>): Map<ScopedKey, Boolean> = buildMap {
        source.forEach { (serverId, models) -> models.forEach { put(ScopedKey(serverId, it), true) } }
    }
    fun expandHidden(source: Map<ScopedKey, Boolean>): Map<String, Set<String>> = source.keys
        .groupBy(ScopedKey::serverId, ScopedKey::valueId)
        .mapValues { (_, models) -> models.toSortedSet() }
    val mergedHidden = expandHidden(
        mergeMap(
            SyncConflictArea.HIDDEN_MODELS,
            flattenHidden(base.payload.hiddenModels.orEmpty()),
            flattenHidden(local.payload.hiddenModels.orEmpty()),
            flattenHidden(remote.payload.hiddenModels.orEmpty()),
        ),
    )
    val mergedPasswords = mergeMap(
        SyncConflictArea.PASSWORDS,
        base.passwords,
        local.passwords,
        remote.passwords,
    )

    val retainedServerIds = mergedServersById.keys
    val retainedCategoryIds = mergedCategoriesById.keys
    val filteredAssignments = mergedAssignments
        .filterKeys { it in retainedServerIds }
        .mapValues { (_, assignments) -> assignments.filterValues { it in retainedCategoryIds } }
    val mergedPayload = base.payload.copy(
        settings = mergedSettings,
        sessionCategories = mergedCategories,
        sessionCategoryAssignments = filteredAssignments,
        favoriteSessionIds = mergedFavorites.filterKeys { it in retainedServerIds },
        crossServerFavoriteOrder = mergedCrossOrder.filter { it.substringBefore(':') in retainedServerIds },
        favoriteSessionSnapshots = mergedSnapshots.filterKeys { it in retainedFavoriteKeys },
        hiddenModels = mergedHidden.filterKeys { it in retainedServerIds },
        servers = mergedServers,
    )
    return SyncMergeResult(
        canonicalizeSyncData(
            mergedPayload,
            mergedPasswords.filterKeys { it in retainedServerIds },
        ),
        conflicts,
    )
}

private fun materializeSettings(source: SyncSettings, fallback: SyncSettings?): SyncSettings {
    val hapticDefaults = when (source.hapticStrength) {
        "light" -> 18 to 80
        "strong" -> 48 to 255
        else -> 30 to 160
    }
    return source.copy(
        messageHistoryResponseLimitMb = source.messageHistoryResponseLimitMb.coerceIn(8, 128),
        recentDirectoryCount = source.recentDirectoryCount.coerceIn(5, 50),
        hideToolDetails = source.hideToolDetails ?: fallback?.hideToolDetails ?: false,
        hapticDurationMillis = (source.hapticDurationMillis ?: hapticDefaults.first).coerceIn(5, 100),
        hapticAmplitude = (source.hapticAmplitude ?: hapticDefaults.second).coerceIn(1, 255),
        imageAttachmentMaxLongSide = source.imageAttachmentMaxLongSide.coerceIn(0, 4096),
        imageAttachmentWebpQuality = source.imageAttachmentWebpQuality.coerceIn(1, 100),
        terminalFontSize = source.terminalFontSize.coerceIn(6f, 20f),
        showLocalRuntime = source.showLocalRuntime ?: fallback?.showLocalRuntime ?: true,
        diagnosticLogLevel = source.diagnosticLogLevel ?: fallback?.diagnosticLogLevel ?: "INFO",
        showTerminalPanelHint = source.showTerminalPanelHint ?: fallback?.showTerminalPanelHint ?: true,
    )
}

private fun canonicalServerId(url: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(normalizeServerUrl(url).toByteArray())
    return "server-" + digest.joinToString("") { "%02x".format(it) }
}

private fun <K, V> Map<K, V>.entry(key: K): MapEntry<V> =
    if (containsKey(key)) MapEntry.Present(getValue(key)) else MapEntry.Missing

private fun mergeOrder(
    area: SyncConflictArea,
    base: List<String>,
    local: List<String>,
    remote: List<String>,
    retained: Set<String>,
    onConflict: (SyncConflictArea) -> Unit,
    conflictPreference: SyncConflictPreference?,
): List<String> {
    val baseCore = base.filter { it in retained }
    val localCore = local.filter { it in baseCore }
    val remoteCore = remote.filter { it in baseCore }
    val mergedCore = when {
        localCore == remoteCore -> localCore
        localCore == baseCore -> remoteCore
        remoteCore == baseCore -> localCore
        else -> {
            when (conflictPreference) {
                SyncConflictPreference.LOCAL -> localCore
                SyncConflictPreference.REMOTE -> remoteCore
                null -> {
                    onConflict(area)
                    localCore
                }
            }
        }
    }
    val localAdditions = local.filter { it in retained && it !in baseCore }.distinct()
    val remoteAdditions = remote.filter { it in retained && it !in baseCore }.distinct()
    val additions = when {
        localAdditions.isEmpty() -> remoteAdditions
        remoteAdditions.isEmpty() -> localAdditions
        else -> (localAdditions + remoteAdditions).distinct().sorted()
    }
    return (mergedCore + additions + retained).distinct().filter { it in retained }
}

private fun mergeSnapshotMap(
    base: Map<String, FavoriteSessionSnapshot>,
    local: Map<String, FavoriteSessionSnapshot>,
    remote: Map<String, FavoriteSessionSnapshot>,
): Map<String, FavoriteSessionSnapshot> = buildMap {
    (base.keys + local.keys + remote.keys).forEach { key ->
        val baseValue = base[key]
        val localValue = local[key]
        val remoteValue = remote[key]
        val selected = when {
            localValue == remoteValue -> localValue
            localValue == baseValue -> remoteValue
            remoteValue == baseValue -> localValue
            localValue == null -> remoteValue
            remoteValue == null -> localValue
            localValue.updatedAt > remoteValue.updatedAt -> localValue
            remoteValue.updatedAt > localValue.updatedAt -> remoteValue
            snapshotTieBreakKey(localValue) >= snapshotTieBreakKey(remoteValue) -> localValue
            else -> remoteValue
        }
        if (selected != null) put(key, selected)
    }
}

private fun snapshotTieBreakKey(snapshot: FavoriteSessionSnapshot): String = buildString {
    append(snapshot.id)
    append('\u0000')
    append(snapshot.projectId)
    append('\u0000')
    append(snapshot.directory)
    append('\u0000')
    append(snapshot.title.orEmpty())
    append('\u0000')
    append(snapshot.createdAt)
}

private fun serverDependenciesChanged(
    base: SemanticSyncData,
    side: SemanticSyncData,
    serverId: String,
): Boolean {
    fun scopedValues(payload: SyncPayload) = payload.favoriteSessionSnapshots.orEmpty()
        .filterKeys { it.substringBefore(':') == serverId }
    fun scopedOrder(payload: SyncPayload) = payload.crossServerFavoriteOrder.orEmpty()
        .filter { it.substringBefore(':') == serverId }
    return base.payload.sessionCategoryAssignments[serverId].orEmpty() !=
        side.payload.sessionCategoryAssignments[serverId].orEmpty() ||
        base.payload.favoriteSessionIds.orEmpty()[serverId].orEmpty() !=
        side.payload.favoriteSessionIds.orEmpty()[serverId].orEmpty() ||
        scopedOrder(base.payload) != scopedOrder(side.payload) ||
        scopedValues(base.payload) != scopedValues(side.payload) ||
        base.payload.hiddenModels.orEmpty()[serverId].orEmpty() !=
        side.payload.hiddenModels.orEmpty()[serverId].orEmpty() ||
        base.passwords.entry(serverId) != side.passwords.entry(serverId)
}

private fun categoryAssignmentsChanged(
    base: SemanticSyncData,
    side: SemanticSyncData,
    categoryId: String,
): Boolean {
    fun assignments(data: SemanticSyncData): Set<ScopedKey> = buildSet {
        data.payload.sessionCategoryAssignments.forEach { (serverId, values) ->
            values.filterValues { it == categoryId }.keys.forEach { sessionId ->
                add(ScopedKey(serverId, sessionId))
            }
        }
    }
    return assignments(base) != assignments(side)
}
