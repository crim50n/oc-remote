package dev.minios.ocremote.ui.screens.home

internal fun resolveServerSettingsReadyIds(
    readyIds: Set<String>,
    connectedIds: Set<String>,
    serverId: String,
    probeSucceeded: Boolean,
): Set<String> {
    return if (probeSucceeded && serverId in connectedIds) {
        readyIds + serverId
    } else {
        readyIds - serverId
    }
}
