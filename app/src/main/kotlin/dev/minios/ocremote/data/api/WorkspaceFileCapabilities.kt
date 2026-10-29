package dev.minios.ocremote.data.api

data class WorkspaceFileCapabilities(
    val rawDownload: Boolean = false,
    val write: Boolean = false,
    val textSearch: Boolean = false,
    val gitStatus: Boolean = false,
    val committedDiff: Boolean = false,
)

internal fun workspaceFileCapabilities(protocol: ServerProtocol) = when (protocol) {
    ServerProtocol.V1 -> WorkspaceFileCapabilities(textSearch = true, gitStatus = true)
    ServerProtocol.V2 -> WorkspaceFileCapabilities(rawDownload = true, write = true, gitStatus = true, committedDiff = true)
}
