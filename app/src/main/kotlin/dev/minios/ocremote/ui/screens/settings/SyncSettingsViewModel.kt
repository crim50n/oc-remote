package dev.minios.ocremote.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.minios.ocremote.data.repository.SyncBackend
import dev.minios.ocremote.data.repository.SyncConfig
import dev.minios.ocremote.data.repository.SyncRepository
import dev.minios.ocremote.data.repository.SyncTargetConfig
import dev.minios.ocremote.data.sync.SyncWorkScheduler
import dev.minios.ocremote.logging.AppLogger as Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SyncUiOperation { NONE, SAVING, SYNCING }

@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
    private val repository: SyncRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val state = repository.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = dev.minios.ocremote.data.repository.SyncState(),
    )

    private val _operation = MutableStateFlow(SyncUiOperation.NONE)
    val operation = _operation.asStateFlow()

    private val _operationError = MutableStateFlow<String?>(null)
    val operationError = _operationError.asStateFlow()

    fun saveConfiguration(
        primaryBackend: SyncBackend,
        gistEnabled: Boolean,
        gistEndpoint: String,
        token: String,
        webDavEnabled: Boolean,
        webDavEndpoint: String,
        webDavUsername: String,
        webDavPassword: String,
        documentEnabled: Boolean,
        documentUri: String,
        documentGrantFlags: Int,
        includePasswords: Boolean,
        passphrase: String,
        autoSync: Boolean,
    ) = runOperation(SyncUiOperation.SAVING) {
        val uri = documentUri.takeIf { documentEnabled && documentGrantFlags != 0 }?.let(Uri::parse)
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, documentGrantFlags)
        }
        try {
            repository.configure(
                config = SyncConfig(
                    primaryBackend = primaryBackend,
                    gist = SyncTargetConfig(
                        enabled = gistEnabled,
                        endpoint = gistEndpoint,
                    ),
                    webDav = SyncTargetConfig(
                        enabled = webDavEnabled,
                        endpoint = webDavEndpoint,
                        username = webDavUsername,
                    ),
                    document = SyncTargetConfig(
                        enabled = documentEnabled,
                        endpoint = documentUri,
                    ),
                    autoSync = autoSync,
                    includeEncryptedPasswords = includePasswords,
                ),
                githubToken = token.takeIf(String::isNotBlank),
                webDavPassword = webDavPassword.takeIf(String::isNotBlank),
                syncPassphrase = passphrase.takeIf(String::isNotBlank),
            )
        } catch (e: Exception) {
            if (uri != null) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
            }
            throw e
        }
        SyncWorkScheduler.update(context, autoSync, primaryBackend)
    }

    fun synchronize() = runOperation(SyncUiOperation.SYNCING) { repository.syncNow() }

    fun forceUpload() = runOperation(SyncUiOperation.SYNCING) { repository.forceUpload() }

    fun forceDownload(backend: SyncBackend? = null) = runOperation(SyncUiOperation.SYNCING) {
        repository.forceDownload(backend)
    }

    fun disconnect() = runOperation(SyncUiOperation.SAVING) {
        repository.disconnect()
        SyncWorkScheduler.update(context, false)
    }

    fun clearOperationError() {
        _operationError.value = null
    }

    private fun runOperation(operation: SyncUiOperation, block: suspend () -> Unit) {
        if (_operation.value != SyncUiOperation.NONE) return
        viewModelScope.launch {
            _operation.value = operation
            _operationError.value = null
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "Settings sync operation failed", e)
                _operationError.value = e.message ?: "Sync failed"
            } finally {
                _operation.value = SyncUiOperation.NONE
            }
        }
    }

    private companion object {
        const val TAG = "SyncSettingsViewModel"
    }
}
