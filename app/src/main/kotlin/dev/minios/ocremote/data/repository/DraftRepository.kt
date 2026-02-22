package dev.minios.ocremote.data.repository

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DraftRepository"
private const val DRAFTS_FILE = "session_drafts.json"

/**
 * A single draft: text + attachment URIs + confirmed @file paths for a session.
 */
@Serializable
data class Draft(
    val text: String = "",
    val imageUris: List<String> = emptyList(),
    val confirmedFilePaths: List<String> = emptyList(),
    val selectedAgent: String? = null,
    val selectedVariant: String? = null,
) {
    val isEmpty: Boolean
        get() = text.isBlank() &&
                imageUris.isEmpty() &&
                confirmedFilePaths.isEmpty() &&
                selectedAgent.isNullOrBlank() &&
                selectedVariant.isNullOrBlank()
}

/**
 * Persists per-session message drafts (text + attachment URIs + @file mentions)
 * so they survive navigation, app restarts, and WebUI detours.
 *
 * Storage: JSON file in app internal storage. Kept simple — no Room dependency.
 */
@Singleton
class DraftRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file: File get() = File(context.filesDir, DRAFTS_FILE)

    /** In-memory cache, loaded lazily. */
    private var drafts: MutableMap<String, Draft>? = null

    private fun ensureLoaded(): MutableMap<String, Draft> {
        drafts?.let { return it }
        val loaded = try {
            val content = file.takeIf { it.exists() }?.readText()
            if (content.isNullOrBlank()) {
                mutableMapOf()
            } else {
                json.decodeFromString<Map<String, Draft>>(content).toMutableMap()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load drafts, starting fresh: ${e.message}")
            mutableMapOf()
        }
        drafts = loaded
        return loaded
    }

    /**
     * Get the draft for a session (or null if none exists).
     */
    fun getDraft(sessionId: String): Draft? {
        val d = ensureLoaded()[sessionId]
        return if (d != null && !d.isEmpty) d else null
    }

    /**
     * Save a draft for a session. Removes the entry if the draft is empty.
     */
    fun saveDraft(sessionId: String, draft: Draft) {
        val map = ensureLoaded()
        if (draft.isEmpty) {
            map.remove(sessionId)
        } else {
            map[sessionId] = draft
        }
        persist(map)
    }

    /**
     * Clear the draft for a session (e.g. after sending).
     */
    fun clearDraft(sessionId: String) {
        val map = ensureLoaded()
        if (map.remove(sessionId) != null) {
            persist(map)
        }
    }

    private fun persist(map: Map<String, Draft>) {
        try {
            file.writeText(json.encodeToString(map))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist drafts: ${e.message}")
        }
    }
}
