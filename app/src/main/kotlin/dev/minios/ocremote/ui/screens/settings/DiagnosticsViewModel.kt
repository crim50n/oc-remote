package dev.minios.ocremote.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.repository.DiagnosticLogEntry
import dev.minios.ocremote.data.repository.DiagnosticLogRepository
import dev.minios.ocremote.logging.AppLogger
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiagnosticsExport(
    val entries: List<DiagnosticLogEntry>,
    val totalEntryCount: Int,
    val text: String,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val repository: DiagnosticLogRepository,
) : ViewModel() {
    val entries: StateFlow<List<DiagnosticLogEntry>> = repository.entries.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )
    val logLevel: StateFlow<String> = repository.logLevel.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "INFO",
    )
    val exportEntryLimit: StateFlow<Int> = repository.exportEntryLimit.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        DiagnosticLogRepository.DEFAULT_EXPORT_ENTRY_LIMIT,
    )

    suspend fun export(limit: Int): DiagnosticsExport {
        AppLogger.flush()
        val allEntries = entries.value
        val exportedEntries = allEntries.takeLast(limit)
        return DiagnosticsExport(
            entries = exportedEntries,
            totalEntryCount = allEntries.size,
            text = DiagnosticLogRepository.export(exportedEntries, exportedEntries.size),
        )
    }

    fun droppedEntryCount(): Long = AppLogger.droppedEntryCount()

    fun clear() {
        viewModelScope.launch { repository.clear() }
    }

    fun setLogLevel(level: String) {
        viewModelScope.launch { repository.setLogLevel(level) }
    }

    fun setExportEntryLimit(limit: Int) {
        viewModelScope.launch { repository.setExportEntryLimit(limit) }
    }
}
