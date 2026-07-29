package dev.minios.ocremote.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class SessionCategory(
    val id: String,
    val name: String,
    val color: String,
    val icon: String,
)
