package dev.minios.ocremote.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val useNativeUi: StateFlow<Boolean> = settingsRepository.useNativeUi
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    fun setUseNativeUi(value: Boolean) {
        viewModelScope.launch {
            settingsRepository.setUseNativeUi(value)
        }
    }
}
