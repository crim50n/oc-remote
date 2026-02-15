package dev.minios.ocremote.domain.model

import kotlinx.serialization.Serializable

/**
 * Session - represents an OpenCode conversation session
 */
@Serializable
data class Session(
    val id: String,
    val directory: String,
    val title: String?,
    val parentId: String? = null,
    val archived: Boolean = false,
    val shared: Boolean = false,
    val shareId: String? = null,
    val time: Time
) {
    @Serializable
    data class Time(
        val created: Long,
        val updated: Long
    )
    
    val createdAt: Long
        get() = time.created
}

/**
 * Session with its current status and last message
 */
data class SessionWithStatus(
    val session: Session,
    val status: SessionStatus,
    val lastMessageData: MessageWithParts? = null
) {
    val lastMessage: MessageWithParts?
        get() = lastMessageData
}
