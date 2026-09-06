package dev.minios.ocremote.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

@Serializable
enum class SyncConflictArea {
    SETTINGS,
    SERVERS,
    CATEGORIES,
    CATEGORY_ASSIGNMENTS,
    FAVORITES,
    HIDDEN_MODELS,
}

@Serializable
data class SyncConflictDifference(
    val area: SyncConflictArea,
    val localCount: Int,
    val remoteCount: Int,
    val changedCount: Int? = null,
)

@Serializable
data class SyncConflictSummary(
    val id: String,
    val detectedAt: Long,
    val localUpdatedAt: Long,
    val remoteUpdatedAt: Long,
    val localGeneration: Long,
    val remoteGeneration: Long,
    val differences: List<SyncConflictDifference>,
    val encryptedPasswordsHidden: Boolean,
)

internal fun buildSyncConflictSummary(
    local: SyncPayload,
    remote: SyncPayload,
    identity: String,
    json: Json,
    now: Long = System.currentTimeMillis(),
): SyncConflictSummary {
    val differences = buildList {
        val localSettings = json.encodeToJsonElement(local.settings).jsonObject
        val remoteSettings = json.encodeToJsonElement(remote.settings).jsonObject
        val changedSettings = (localSettings.keys + remoteSettings.keys).count {
            localSettings[it] != remoteSettings[it]
        }
        if (changedSettings > 0) {
            add(SyncConflictDifference(SyncConflictArea.SETTINGS, changedSettings, changedSettings, changedSettings))
        }
        addIfDifferent(SyncConflictArea.SERVERS, local.servers, remote.servers)
        addIfDifferent(SyncConflictArea.CATEGORIES, local.sessionCategories, remote.sessionCategories)
        addIfDifferent(
            SyncConflictArea.CATEGORY_ASSIGNMENTS,
            local.sessionCategoryAssignments,
            remote.sessionCategoryAssignments,
            local.sessionCategoryAssignments.values.sumOf(Map<String, String>::size),
            remote.sessionCategoryAssignments.values.sumOf(Map<String, String>::size),
        )
        val localFavorites = listOf(
            local.favoriteSessionIds,
            local.crossServerFavoriteOrder,
            local.favoriteSessionSnapshots,
        )
        val remoteFavorites = listOf(
            remote.favoriteSessionIds,
            remote.crossServerFavoriteOrder,
            remote.favoriteSessionSnapshots,
        )
        if (localFavorites != remoteFavorites) {
            add(
                SyncConflictDifference(
                    SyncConflictArea.FAVORITES,
                    local.favoriteSessionIds.orEmpty().values.sumOf(List<String>::size),
                    remote.favoriteSessionIds.orEmpty().values.sumOf(List<String>::size),
                ),
            )
        }
        addIfDifferent(
            SyncConflictArea.HIDDEN_MODELS,
            local.hiddenModels,
            remote.hiddenModels,
            local.hiddenModels.orEmpty().values.sumOf(Set<String>::size),
            remote.hiddenModels.orEmpty().values.sumOf(Set<String>::size),
        )
    }
    val id = MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray())
        .joinToString("") { "%02x".format(it) }
    return SyncConflictSummary(
        id = id,
        detectedAt = now,
        localUpdatedAt = local.updatedAt,
        remoteUpdatedAt = remote.updatedAt,
        localGeneration = local.generation,
        remoteGeneration = remote.generation,
        differences = differences,
        encryptedPasswordsHidden = local.encryptedSecrets != null || remote.encryptedSecrets != null,
    )
}

private fun <T> MutableList<SyncConflictDifference>.addIfDifferent(
    area: SyncConflictArea,
    local: T,
    remote: T,
    localCount: Int = (local as? Collection<*>)?.size ?: (local as? Map<*, *>)?.size ?: 0,
    remoteCount: Int = (remote as? Collection<*>)?.size ?: (remote as? Map<*, *>)?.size ?: 0,
) {
    if (local != remote) add(SyncConflictDifference(area, localCount, remoteCount))
}
