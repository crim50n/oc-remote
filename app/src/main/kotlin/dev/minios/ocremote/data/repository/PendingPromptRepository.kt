package dev.minios.ocremote.data.repository

import android.content.Context
import dev.minios.ocremote.logging.AppLogger as Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.minios.ocremote.data.api.ModelSelection
import dev.minios.ocremote.data.api.PromptPart
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val PENDING_PROMPTS_TAG = "PendingPromptRepository"
private const val PENDING_PROMPTS_FILE = "pending_prompts.json"

@Serializable
data class PendingPromptRecord(
    val messageId: String,
    val sessionId: String,
    val parts: List<PromptPart>,
    val model: ModelSelection? = null,
    val agent: String? = null,
    val variant: String? = null,
    val directory: String? = null,
    val createdAt: Long,
)

@Singleton
class PendingPromptRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val file: File get() = File(context.filesDir, PENDING_PROMPTS_FILE)
    private var records: MutableMap<String, PendingPromptRecord>? = null

    @Synchronized
    fun getForSession(sessionId: String): List<PendingPromptRecord> =
        ensureLoaded().values.filter { it.sessionId == sessionId }.sortedBy { it.createdAt }

    @Synchronized
    fun save(record: PendingPromptRecord) {
        ensureLoaded()[record.messageId] = record
        persist()
    }

    @Synchronized
    fun remove(messageId: String) {
        if (ensureLoaded().remove(messageId) != null) persist()
    }

    private fun ensureLoaded(): MutableMap<String, PendingPromptRecord> {
        records?.let { return it }
        records = try {
            file.takeIf { it.exists() }?.readText()?.takeIf { it.isNotBlank() }
                ?.let { json.decodeFromString<Map<String, PendingPromptRecord>>(it).toMutableMap() }
                ?: mutableMapOf()
        } catch (e: Exception) {
            Log.e(PENDING_PROMPTS_TAG, "Failed to load pending prompts", e)
            mutableMapOf()
        }
        return records!!
    }

    private fun persist() {
        try {
            file.writeText(json.encodeToString(ensureLoaded()))
        } catch (e: Exception) {
            Log.e(PENDING_PROMPTS_TAG, "Failed to persist pending prompts", e)
        }
    }
}
