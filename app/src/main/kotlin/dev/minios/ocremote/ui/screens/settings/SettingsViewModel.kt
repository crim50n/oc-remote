package dev.minios.ocremote.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.repository.LocalServerManager
import dev.minios.ocremote.data.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    
    val appLanguage = settingsRepository.appLanguage.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ""
    )

    val appTheme = settingsRepository.appTheme.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "system"
    )

    val dynamicColor = settingsRepository.dynamicColor.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val chatFontSize = settingsRepository.chatFontSize.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "medium"
    )

    val notificationsEnabled = settingsRepository.notificationsEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val initialMessageCount = settingsRepository.initialMessageCount.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 50
    )

    val codeWordWrap = settingsRepository.codeWordWrap.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val confirmBeforeSend = settingsRepository.confirmBeforeSend.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val amoledDark = settingsRepository.amoledDark.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val compactMessages = settingsRepository.compactMessages.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val collapseTools = settingsRepository.collapseTools.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val hapticFeedback = settingsRepository.hapticFeedback.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val reconnectMode = settingsRepository.reconnectMode.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "normal"
    )

    val keepScreenOn = settingsRepository.keepScreenOn.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val showShellButton = settingsRepository.showShellButton.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val compressImageAttachments = settingsRepository.compressImageAttachments.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val imageAttachmentMaxLongSide = settingsRepository.imageAttachmentMaxLongSide.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 1440
    )

    val imageAttachmentWebpQuality = settingsRepository.imageAttachmentWebpQuality.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 60
    )

    val showLocalRuntime = settingsRepository.showLocalRuntime.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true
    )

    val silentNotifications = settingsRepository.silentNotifications.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false
    )

    val terminalFontSize = settingsRepository.terminalFontSize.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 13f
    )

    val localProxyEnabled = settingsRepository.localProxyEnabled.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false,
    )

    val localProxyUrl = settingsRepository.localProxyUrl.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "",
    )

    val localProxyNoProxy = settingsRepository.localProxyNoProxy.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LocalServerManager.DEFAULT_NO_PROXY_LIST,
    )

    val localServerAllowLan = settingsRepository.localServerAllowLan.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false,
    )

    val localServerPassword = settingsRepository.localServerPassword.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = "",
    )

    val localServerAutoStart = settingsRepository.localServerAutoStart.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false,
    )

    val localServerStartupTimeoutSec = settingsRepository.localServerStartupTimeoutSec.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = 30,
    )

    fun setLanguage(languageCode: String) {
        viewModelScope.launch {
            settingsRepository.setAppLanguage(languageCode)
        }
    }

    fun setTheme(theme: String) {
        viewModelScope.launch {
            settingsRepository.setAppTheme(theme)
        }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDynamicColor(enabled)
        }
    }

    fun setChatFontSize(size: String) {
        viewModelScope.launch {
            settingsRepository.setChatFontSize(size)
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setNotificationsEnabled(enabled)
        }
    }

    fun setInitialMessageCount(count: Int) {
        viewModelScope.launch {
            settingsRepository.setInitialMessageCount(count)
        }
    }

    fun setCodeWordWrap(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCodeWordWrap(enabled)
        }
    }

    fun setConfirmBeforeSend(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setConfirmBeforeSend(enabled)
        }
    }

    fun setAmoledDark(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAmoledDark(enabled)
        }
    }

    fun setCompactMessages(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCompactMessages(enabled)
        }
    }

    fun setCollapseTools(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCollapseTools(enabled)
        }
    }

    fun setHapticFeedback(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setHapticFeedback(enabled)
        }
    }

    fun setReconnectMode(mode: String) {
        viewModelScope.launch {
            settingsRepository.setReconnectMode(mode)
        }
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setKeepScreenOn(enabled)
        }
    }

    fun setSilentNotifications(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setSilentNotifications(enabled)
        }
    }

    fun setShowShellButton(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowShellButton(enabled)
        }
    }

    fun setCompressImageAttachments(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setCompressImageAttachments(enabled)
        }
    }

    fun setImageAttachmentMaxLongSide(px: Int) {
        viewModelScope.launch {
            settingsRepository.setImageAttachmentMaxLongSide(px)
        }
    }

    fun setImageAttachmentWebpQuality(quality: Int) {
        viewModelScope.launch {
            settingsRepository.setImageAttachmentWebpQuality(quality)
        }
    }

    fun setShowLocalRuntime(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setShowLocalRuntime(enabled)
        }
    }

    fun setTerminalFontSize(size: Float) {
        viewModelScope.launch {
            settingsRepository.setTerminalFontSize(size)
        }
    }

    fun setLocalProxyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLocalProxyEnabled(enabled)
        }
    }

    fun setLocalProxyUrl(url: String) {
        viewModelScope.launch {
            settingsRepository.setLocalProxyUrl(url)
        }
    }

    fun setLocalProxyNoProxy(value: String) {
        viewModelScope.launch {
            settingsRepository.setLocalProxyNoProxy(value)
        }
    }

    fun setLocalServerAllowLan(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLocalServerAllowLan(enabled)
        }
    }

    fun setLocalServerPassword(value: String) {
        viewModelScope.launch {
            settingsRepository.setLocalServerPassword(value)
        }
    }

    fun setLocalServerAutoStart(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setLocalServerAutoStart(enabled)
        }
    }

    fun setLocalServerStartupTimeoutSec(value: Int) {
        viewModelScope.launch {
            settingsRepository.setLocalServerStartupTimeoutSec(value)
        }
    }
}
