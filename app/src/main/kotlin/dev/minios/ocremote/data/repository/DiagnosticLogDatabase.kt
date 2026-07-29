package dev.minios.ocremote.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class DiagnosticLogDatabase(
    context: Context,
    private val json: Json,
) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                level TEXT NOT NULL,
                category TEXT NOT NULL,
                message TEXT NOT NULL,
                details TEXT NOT NULL,
                byte_size INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX logs_timestamp ON logs(timestamp)")
        db.execSQL("CREATE INDEX logs_level_timestamp ON logs(level, timestamp)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS logs")
        onCreate(db)
    }

    fun insert(entries: List<DiagnosticLogEntry>, now: Long = System.currentTimeMillis()) {
        if (entries.isEmpty()) return
        writableDatabase.inTransaction {
            entries.forEach { entry ->
                val details = json.encodeToString(entry.details)
                insertOrThrow(
                    "logs",
                    null,
                    ContentValues().apply {
                        put("timestamp", entry.timestamp)
                        put("level", entry.level)
                        put("category", entry.category)
                        put("message", entry.message)
                        put("details", details)
                        put("byte_size", entry.estimatedByteSize(details))
                    },
                )
            }
            prune(now)
        }
    }

    fun latest(limit: Int = VISIBLE_ENTRY_LIMIT): List<DiagnosticLogEntry> {
        val result = mutableListOf<DiagnosticLogEntry>()
        readableDatabase.query(
            "logs",
            arrayOf("timestamp", "level", "category", "message", "details"),
            null,
            null,
            null,
            null,
            "timestamp DESC, id DESC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += DiagnosticLogEntry(
                    timestamp = cursor.getLong(0),
                    level = cursor.getString(1),
                    category = cursor.getString(2),
                    message = cursor.getString(3),
                    details = runCatching {
                        json.decodeFromString<Map<String, String>>(cursor.getString(4))
                    }.getOrDefault(emptyMap()),
                )
            }
        }
        return result.asReversed()
    }

    fun isEmpty(): Boolean = readableDatabase.rawQuery("SELECT 1 FROM logs LIMIT 1", null).use { !it.moveToFirst() }

    fun clear() {
        writableDatabase.delete("logs", null, null)
    }

    private fun SQLiteDatabase.prune(now: Long) {
        delete(
            "logs",
            "timestamp < ? AND level NOT IN ('ERROR', 'FATAL')",
            arrayOf((now - ORDINARY_RETENTION_MS).toString()),
        )
        delete(
            "logs",
            "timestamp < ? AND level IN ('ERROR', 'FATAL')",
            arrayOf((now - ERROR_RETENTION_MS).toString()),
        )
        execSQL(
            "DELETE FROM logs WHERE level = 'FATAL' AND id NOT IN " +
                "(SELECT id FROM logs WHERE level = 'FATAL' ORDER BY timestamp DESC, id DESC LIMIT $CRASH_LIMIT)",
        )

        var totalBytes = rawQuery("SELECT COALESCE(SUM(byte_size), 0) FROM logs", null).use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }
        while (totalBytes > MAX_PERSISTENT_BYTES) {
            val removedBytes = rawQuery(
                "SELECT COALESCE(SUM(byte_size), 0) FROM " +
                    "(SELECT byte_size FROM logs ORDER BY timestamp, id LIMIT $PRUNE_BATCH_SIZE)",
                null,
            ).use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(0)
            }
            execSQL(
                "DELETE FROM logs WHERE id IN " +
                    "(SELECT id FROM logs ORDER BY timestamp, id LIMIT $PRUNE_BATCH_SIZE)",
            )
            if (removedBytes <= 0L) break
            totalBytes -= removedBytes
        }
    }

    private inline fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun DiagnosticLogEntry.estimatedByteSize(encodedDetails: String): Int =
        (level.length + category.length + message.length + encodedDetails.length) * 2 + 32

    companion object {
        const val DATABASE_NAME = "diagnostics.db"
        private const val DATABASE_VERSION = 1
        private const val VISIBLE_ENTRY_LIMIT = 1000
        private const val MAX_PERSISTENT_BYTES = 10L * 1024L * 1024L
        private const val ORDINARY_RETENTION_MS = 3L * 24L * 60L * 60L * 1000L
        private const val ERROR_RETENTION_MS = 21L * 24L * 60L * 60L * 1000L
        private const val CRASH_LIMIT = 50
        private const val PRUNE_BATCH_SIZE = 100
    }
}
