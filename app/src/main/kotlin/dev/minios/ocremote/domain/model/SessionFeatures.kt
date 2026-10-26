package dev.minios.ocremote.domain.model

data class SessionFeatures(
    val favorites: Boolean = true,
    val categories: Boolean = true,
    val tabs: Boolean = true,
)
