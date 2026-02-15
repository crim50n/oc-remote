package dev.minios.ocremote.domain.model

import kotlinx.serialization.Serializable

/**
 * Session Status - indicates if session is processing or idle
 */
@Serializable
sealed class SessionStatus {
    @Serializable
    data object Idle : SessionStatus()
    
    @Serializable
    data object Busy : SessionStatus()
    
    @Serializable
    data class Retry(
        val attempt: Int,
        val message: String,
        val next: Long // Timestamp of next retry
    ) : SessionStatus()
}
