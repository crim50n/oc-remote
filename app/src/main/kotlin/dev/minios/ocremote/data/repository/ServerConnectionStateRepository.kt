package dev.minios.ocremote.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerConnectionStateRepository @Inject constructor() {
    private val _connectedServerIds = MutableStateFlow<Set<String>>(emptySet())
    val connectedServerIds: StateFlow<Set<String>> = _connectedServerIds.asStateFlow()
    private val _connectingServerIds = MutableStateFlow<Set<String>>(emptySet())
    val connectingServerIds: StateFlow<Set<String>> = _connectingServerIds.asStateFlow()

    fun updateConnectedServerIds(serverIds: Set<String>) {
        _connectedServerIds.value = serverIds
    }

    fun updateConnectingServerIds(serverIds: Set<String>) {
        _connectingServerIds.value = serverIds
    }
}
