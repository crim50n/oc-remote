package dev.minios.ocremote.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import android.content.Context
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class DiagnosticLogEntry(
    val timestamp: Long,
    val level: String,
    val category: String,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

@Singleton
class DiagnosticLogRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val json: Json,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    private var database = DiagnosticLogDatabase(context, json)
    private val logKey = stringPreferencesKey("diagnostic_log")
    private val logLevelKey = stringPreferencesKey("diagnostic_log_level")
    private val _entries = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())

    val logLevel: Flow<String> = dataStore.data.map { it[logLevelKey] ?: "INFO" }

    val entries: Flow<List<DiagnosticLogEntry>> = _entries.asStateFlow()

    suspend fun initialize() = withContext(Dispatchers.IO) {
        val legacy = dataStore.data.map { it[logKey] }.first()
        if (withDatabaseRecovery { it.isEmpty() } && legacy != null) {
            val entries = runCatching { json.decodeFromString<List<DiagnosticLogEntry>>(legacy) }.getOrDefault(emptyList())
            withDatabaseRecovery { it.insert(entries.map(::sanitizeEntry)) }
        }
        if (legacy != null) dataStore.edit { it.remove(logKey) }
        refresh()
    }

    suspend fun record(
        level: String,
        category: String,
        message: String,
        details: Map<String, String> = emptyMap(),
    ) {
        recordBatch(
            listOf(
                DiagnosticLogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
                    category = category,
                    message = message,
                    details = details,
                ),
            ),
        )
    }

    suspend fun recordBatch(entries: List<DiagnosticLogEntry>) {
        if (entries.isEmpty()) return
        withContext(Dispatchers.IO) {
            withDatabaseRecovery { it.insert(entries.map(::sanitizeEntry)) }
            refresh()
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            withDatabaseRecovery { it.clear() }
            refresh()
        }
    }

    suspend fun setLogLevel(level: String) {
        dataStore.edit { it[logLevelKey] = level.takeIf { value -> value in LOG_LEVELS } ?: "INFO" }
    }

    companion object {
        val LOG_LEVELS = listOf("ERROR", "WARN", "INFO", "DEBUG")

        fun export(entries: List<DiagnosticLogEntry>): String = entries.map(::sanitizeEntry).joinToString("\n\n") { entry ->
            buildString {
                append(java.time.Instant.ofEpochMilli(entry.timestamp))
                append(" [${entry.level}] ${entry.category}: ${entry.message}")
                entry.details.toSortedMap().forEach { (key, value) -> append("\n$key=$value") }
            }
        }

        internal fun sanitize(value: String): String {
            return value
                .replace(Regex("(?im)^(authorization|proxy-authorization|cookie|set-cookie)\\s*[:=].*$"), "$1: [REDACTED]")
                .replace(Regex("(?i)(authorization\\s*[:=]\\s*)[^\\r\\n,]+"), "$1[REDACTED]")
                .replace(Regex("(?i)\\b(bearer|basic)\\s+[^\\s,]+"), "$1 [REDACTED]")
                .replace(Regex("(?i)(password|passwd|secret|client[_-]?secret|api[_-]?key|access[_-]?token|refresh[_-]?token|oauth[_-]?code|code[_-]?verifier|code[_-]?challenge|credential)(\\s*[\"']?\\s*[:=]\\s*[\"']?)[^\\s,;&\"'}]+"), "$1$2[REDACTED]")
                .replace(Regex("(?i)([?&](?:code|state|code_challenge|code_verifier|access_token|refresh_token|api_key|key)=)[^&\\s]+"), "$1[REDACTED]")
                .replace(Regex("(?i)(https?://)[^/@\\s]+:[^/@\\s]+@"), "$1[REDACTED]@")
                .replace(Regex("(?<![A-Za-z0-9])(?:\\d{1,3}\\.){3}\\d{1,3}(?![A-Za-z0-9])"), "[IP]")
                .replace(
                    Regex("(?i)(?<![A-F0-9:])(?:(?:[A-F0-9]{1,4}:){4,7}[A-F0-9]{0,4}|(?:[A-F0-9]{0,4}:){1,7}:[A-F0-9]{0,4})(?![A-F0-9:])"),
                    "[IP]",
                )
                .replace(Regex("(?:/home/|/Users/|/build/)[^\\s,;]+"), "[PATH]")
                .replace(Regex("(?i)[A-Z]:\\\\Users\\\\[^\\s,;]+"), "[PATH]")
                .take(1000)
        }

        internal fun sanitizeEntry(entry: DiagnosticLogEntry): DiagnosticLogEntry = entry.copy(
            category = sanitize(entry.category),
            message = sanitize(entry.message),
            details = entry.details.entries.take(MAX_DETAIL_FIELDS)
                .associate { sanitize(it.key) to sanitize(it.value) },
        )

        private const val MAX_DETAIL_FIELDS = 20
    }

    private fun refresh() {
        _entries.value = withDatabaseRecovery { it.latest() }
    }

    @Synchronized
    private fun <T> withDatabaseRecovery(block: (DiagnosticLogDatabase) -> T): T {
        return try {
            block(database)
        } catch (error: SQLiteException) {
            runCatching { database.close() }
            context.deleteDatabase(DiagnosticLogDatabase.DATABASE_NAME)
            database = DiagnosticLogDatabase(context, json)
            block(database)
        }
    }
}
