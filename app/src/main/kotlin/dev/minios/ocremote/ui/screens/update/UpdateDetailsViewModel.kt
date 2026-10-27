package dev.minios.ocremote.ui.screens.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.update.AvailableUpdate
import dev.minios.ocremote.data.update.UpdateRepository
import dev.minios.ocremote.data.update.UpdateState
import dev.minios.ocremote.logging.AppLogger as Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReleaseNotesState(
    val release: AvailableUpdate? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
)

@HiltViewModel
class UpdateDetailsViewModel @Inject constructor(private val repository: UpdateRepository) : ViewModel() {
    val updateState = repository.state
    private val _notes = MutableStateFlow(ReleaseNotesState())
    val notes = _notes.asStateFlow()
    private var notesJob: Job? = null

    init { loadNotes() }

    fun loadNotes() {
        if (notesJob?.isActive == true) return
        notesJob = viewModelScope.launch {
            try {
                if (updateState.value !is UpdateState.Available && updateState.value !is UpdateState.Error) repository.restore()
                val state = updateState.value
                val release = when (state) {
                    is UpdateState.Available -> state.release
                    is UpdateState.Error -> state.release
                    else -> null
                } ?: _notes.value.release
                _notes.value = ReleaseNotesState(release, loading = release?.releaseNotes == null)
                if (release == null) {
                    _notes.value = ReleaseNotesState(loading = false, failed = true)
                } else if (release.releaseNotes == null) {
                    _notes.value = ReleaseNotesState(repository.loadReleaseNotes(release), loading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("UpdateDetails", "Could not load release notes", e)
                _notes.value = _notes.value.copy(loading = false, failed = true)
            }
        }
    }

    fun prepareInstall(release: AvailableUpdate) { viewModelScope.launch { repository.prepareInstall(release) } }
    fun installerLaunched() = repository.markInstallerLaunched()
}
