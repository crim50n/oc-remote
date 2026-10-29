package dev.minios.ocremote.ui.screens.settings

import dev.minios.ocremote.data.repository.SyncBackend

internal data class SyncConnectionDraft(
    val backend: SyncBackend,
    val gistEndpoint: String,
    val token: String,
    val webDavEndpoint: String,
    val username: String,
    val password: String,
    val documentUri: String,
    val documentGrantFlags: Int,
    val includePasswords: Boolean,
    val passphrase: String,
)
