package dev.minios.ocremote.data.sync

import dev.minios.ocremote.domain.model.FavoriteSessionSnapshot
import dev.minios.ocremote.domain.model.SessionCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class SyncPayload(
    val version: Int = VERSION,
    val generation: Long = 0,
    val parentGeneration: Long? = null,
    val updatedAt: Long = 0,
    val writerDeviceId: String = "",
    val settings: SyncSettings = SyncSettings(),
    val sessionCategories: List<SessionCategory> = emptyList(),
    val sessionCategoryAssignments: Map<String, Map<String, String>> = emptyMap(),
    val favoriteSessionIds: Map<String, List<String>>? = null,
    val crossServerFavoriteOrder: List<String>? = null,
    val favoriteSessionSnapshots: Map<String, FavoriteSessionSnapshot>? = null,
    val hiddenModels: Map<String, Set<String>>? = null,
    val servers: List<SyncServer> = emptyList(),
    val encryptedSecrets: EncryptedSecrets? = null,
) {
    companion object {
        const val VERSION = 1
    }
}

/** Explicit allowlist of non-sensitive, device-independent preferences. */
@Serializable
data class SyncSettings(
    val appLanguage: String = "",
    val appTheme: String = "system",
    val dynamicColor: Boolean = false,
    val chatFontSize: String = "medium",
    val notificationsEnabled: Boolean = true,
    val initialMessageCount: Int = 50,
    val messageHistoryResponseLimitMb: Int = 24,
    val recentDirectoryCount: Int = 20,
    val codeWordWrap: Boolean = false,
    val confirmBeforeSend: Boolean = false,
    val amoledDark: Boolean = false,
    val compactMessages: Boolean = false,
    val collapseTools: Boolean = false,
    val expandReasoning: Boolean = false,
    val showTurnDividers: Boolean = true,
    val groupSessionsByProject: Boolean = false,
    val hapticFeedback: Boolean = true,
    val hapticStrength: String = "medium",
    val hapticDurationMillis: Int? = null,
    val hapticAmplitude: Int? = null,
    val reconnectMode: String = "normal",
    val backgroundWakeLock: Boolean = true,
    val keepScreenOn: Boolean = false,
    val silentNotifications: Boolean = false,
    val compressImageAttachments: Boolean = true,
    val imageAttachmentMaxLongSide: Int = 1440,
    val imageAttachmentWebpQuality: Int = 60,
    val terminalFontSize: Float = 13f,
    val showLocalRuntime: Boolean? = null,
    val diagnosticLogLevel: String? = null,
    val showTerminalPanelHint: Boolean? = null,
)

@Serializable
data class SyncServer(
    val id: String,
    val url: String,
    val name: String? = null,
    val username: String = "opencode",
    val autoConnect: Boolean = false,
)

@Serializable
data class EncryptedSecrets(
    val algorithm: String = "AES-256-GCM",
    val kdf: String = "PBKDF2WithHmacSHA256",
    val iterations: Int,
    val salt: String,
    val iv: String,
    val ciphertext: String,
)

@Serializable
internal data class PasswordSecrets(val passwords: Map<String, String>)

internal fun Json.decodeSyncPayload(content: String): SyncPayload {
    val explicitVersion = runCatching {
        parseToJsonElement(content).jsonObject["version"]?.jsonPrimitive
            ?.takeUnless { it.isString }
            ?.intOrNull
    }.getOrNull()
    require(explicitVersion == SyncPayload.VERSION) { "Unsupported or missing sync payload version" }
    return decodeFromString(content)
}
