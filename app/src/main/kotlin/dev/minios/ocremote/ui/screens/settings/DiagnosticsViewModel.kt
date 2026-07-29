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

    suspend fun export(): String {
        AppLogger.flush()
        return DiagnosticLogRepository.export(entries.value)
    }

    fun droppedEntryCount(): Long = AppLogger.droppedEntryCount()

    fun clear() {
        viewModelScope.launch { repository.clear() }
    }

    fun setLogLevel(level: String) {
        viewModelScope.launch { repository.setLogLevel(level) }
    }
}
