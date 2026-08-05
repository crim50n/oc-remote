package dev.minios.ocremote.ui.screens.server

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.minios.ocremote.data.api.McpStatus
import dev.minios.ocremote.data.api.OpenCodeApi
import dev.minios.ocremote.data.api.ServerConnection
import dev.minios.ocremote.logging.AppLogger as Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "ServerMcpViewModel"

data class McpServerItem(
    val name: String,
    val status: String,
    val error: String? = null,
)

data class ServerMcpUiState(
    val servers: List<McpServerItem> = emptyList(),
    val loadingName: String? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
)

internal fun mcpItems(statuses: Map<String, McpStatus>): List<McpServerItem> = statuses
    .map { (name, status) -> McpServerItem(name, status.status, status.error) }
    .sortedBy { it.name.lowercase() }

@HiltViewModel
class ServerMcpViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val api: OpenCodeApi,
) : ViewModel() {
    private val conn = ServerConnection.from(
        savedStateHandle.get<String>("serverUrl").orEmpty(),
        savedStateHandle.get<String>("username").orEmpty(),
        savedStateHandle.get<String>("password").orEmpty().ifEmpty { null },
        allowSelfSigned = savedStateHandle.get<Boolean>("allowSelfSigned") ?: false,
    )

    private val _uiState = MutableStateFlow(ServerMcpUiState())
    val uiState: StateFlow<ServerMcpUiState> = _uiState.asStateFlow()

    private val _authorizationUrls = MutableSharedFlow<String>()
    val authorizationUrls: SharedFlow<String> = _authorizationUrls.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                _uiState.update {
                    it.copy(
                        servers = mcpItems(api.getMcpStatus(conn)),
                        isLoading = false,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load MCP status", e)
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Failed to load MCP status") }
            }
        }
    }

    fun connect(name: String) = updateConnection(name, connect = true)

    fun disconnect(name: String) = updateConnection(name, connect = false)

    private fun updateConnection(name: String, connect: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(loadingName = name, error = null) }
            try {
                if (connect) api.connectMcp(conn, name) else api.disconnectMcp(conn, name)
                _uiState.update {
                    it.copy(
                        servers = mcpItems(api.getMcpStatus(conn)),
                        loadingName = null,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to ${if (connect) "connect" else "disconnect"} MCP $name", e)
                _uiState.update { it.copy(loadingName = null, error = e.message ?: "MCP action failed") }
            }
        }
    }

    fun authenticate(name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(loadingName = name, error = null) }
            try {
                _authorizationUrls.emit(api.startMcpAuth(conn, name).authorizationUrl)
                _uiState.update { it.copy(loadingName = null) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to authenticate MCP $name", e)
                _uiState.update { it.copy(loadingName = null, error = e.message ?: "MCP authentication failed") }
            }
        }
    }
}
