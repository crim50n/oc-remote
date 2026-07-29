package dev.minios.ocremote.logging

import android.util.Log as AndroidLog
import dev.minios.ocremote.data.repository.DiagnosticLogEntry
import dev.minios.ocremote.data.repository.DiagnosticLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

object AppLogger {
    private sealed interface WriterCommand {
        data class Entry(val value: DiagnosticLogEntry) : WriterCommand
        data class Flush(val completion: CompletableDeferred<Boolean>) : WriterCommand
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val droppedEntries = AtomicLong(0)
    private val queue = Channel<WriterCommand>(
        capacity = 500,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { command ->
            when (command) {
                is WriterCommand.Entry -> droppedEntries.incrementAndGet()
                is WriterCommand.Flush -> command.completion.complete(false)
            }
        },
    )
    @Volatile private var repository: DiagnosticLogRepository? = null
    @Volatile private var minimumLevel = "INFO"

    fun initialize(logRepository: DiagnosticLogRepository) {
        if (repository != null) return
        repository = logRepository
        scope.launch {
            logRepository.logLevel.collect { minimumLevel = it }
        }
        scope.launch {
            logRepository.initialize()
            while (true) {
                when (val command = queue.receive()) {
                    is WriterCommand.Entry -> {
                        val batch = mutableListOf(command.value)
                        delay(150)
                        while (batch.size < 50) {
                            when (val next = queue.tryReceive().getOrNull() ?: break) {
                                is WriterCommand.Entry -> batch += next.value
                                is WriterCommand.Flush -> {
                                    val persisted = persistBatch(batch)
                                    batch.clear()
                                    next.completion.complete(persisted)
                                    break
                                }
                            }
                        }
                        if (batch.isNotEmpty()) persistBatch(batch)
                    }
                    is WriterCommand.Flush -> command.completion.complete(true)
                }
            }
        }
    }

    fun d(tag: String, message: String): Int = write("DEBUG", tag, message, null) {
        AndroidLog.d(tag, message)
    }
    fun d(tag: String, message: String, error: Throwable): Int = write("DEBUG", tag, message, error) {
        AndroidLog.d(tag, message, error)
    }

    fun i(tag: String, message: String): Int = write("INFO", tag, message, null) {
        AndroidLog.i(tag, message)
    }

    fun i(tag: String, message: String, error: Throwable): Int = write("INFO", tag, message, error) {
        AndroidLog.i(tag, message, error)
    }

    fun w(tag: String, message: String): Int = write("WARN", tag, message, null) {
        AndroidLog.w(tag, message)
    }

    fun w(tag: String, message: String, error: Throwable): Int = write("WARN", tag, message, error) {
        AndroidLog.w(tag, message, error)
    }

    fun e(tag: String, message: String): Int = write("ERROR", tag, message, null) {
        AndroidLog.e(tag, message)
    }

    fun e(tag: String, message: String, error: Throwable): Int = write("ERROR", tag, message, error) {
        AndroidLog.e(tag, message, error)
    }

    fun recordCrash(thread: Thread, error: Throwable) {
        val details = throwableDetails(error) + ("thread" to thread.name)
        val entry = DiagnosticLogEntry(
            timestamp = System.currentTimeMillis(),
            level = "FATAL",
            category = "Uncaught exception",
            message = error.message ?: error::class.java.simpleName,
            details = details,
        )
        queue.trySend(WriterCommand.Entry(entry))
        runBlocking(Dispatchers.IO) {
            flush(CRASH_FLUSH_TIMEOUT_MS)
        }
    }

    suspend fun flush(timeoutMillis: Long = 2_000L): Boolean {
        val completion = CompletableDeferred<Boolean>()
        queue.send(WriterCommand.Flush(completion))
        return withTimeoutOrNull(timeoutMillis) { completion.await() } ?: false
    }

    fun droppedEntryCount(): Long = droppedEntries.get()

    private inline fun write(
        level: String,
        tag: String,
        message: String,
        error: Throwable?,
        androidWrite: () -> Int,
    ): Int {
        val result = androidWrite()
        if (!shouldPersist(level)) return result
        queue.trySend(
            WriterCommand.Entry(
                DiagnosticLogEntry(
                timestamp = System.currentTimeMillis(),
                level = level,
                category = tag,
                message = message,
                details = error?.let(::throwableDetails).orEmpty(),
                ),
            ),
        )
        return result
    }

    private fun shouldPersist(level: String): Boolean {
        val priorities = mapOf("ERROR" to 0, "WARN" to 1, "INFO" to 2, "DEBUG" to 3)
        return (priorities[level] ?: 0) <= (priorities[minimumLevel] ?: 2)
    }

    private fun throwableDetails(error: Throwable): Map<String, String> {
        return buildMap {
            put("exception", error::class.java.name)
            error.cause?.let { put("cause", "${it::class.java.name}: ${it.message.orEmpty()}") }
            put("stack", AndroidLog.getStackTraceString(error).lineSequence().take(12).joinToString("\n"))
        }
    }

    private suspend fun persistBatch(batch: List<DiagnosticLogEntry>): Boolean {
        return try {
            repository?.recordBatch(batch)
            true
        } catch (error: Exception) {
            AndroidLog.e("AppLogger", "Persistent diagnostic write failed", error)
            false
        }
    }

    private const val CRASH_FLUSH_TIMEOUT_MS = 750L
}
