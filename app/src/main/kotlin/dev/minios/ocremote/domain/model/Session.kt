package dev.minios.ocremote.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Session - represents an OpenCode conversation session.
 * Field names match the OpenCode API convention (uppercase ID suffixes).
 */
@Serializable
data class Session(
    val id: String,
    val slug: String = "",
    @SerialName("projectID") val projectId: String = "",
    val directory: String = "",
    @SerialName("parentID") val parentId: String? = null,
    val title: String? = null,
    val version: String = "",
    val time: Time,
    val summary: Summary? = null,
    val share: Share? = null,
    val permission: List<PermissionRule>? = null,
    val revert: Revert? = null
) {
    @Serializable
    data class Time(
        val created: Long,
        val updated: Long,
        val compacting: Long? = null,
        val archived: Long? = null
    )

    @Serializable
    data class Summary(
        val additions: Int = 0,
        val deletions: Int = 0,
        val files: Int = 0,
        val diffs: List<FileDiff>? = null
    )

    @Serializable
    data class Share(val url: String)

    @Serializable
    data class Revert(
        @SerialName("messageID") val messageId: String,
        @SerialName("partID") val partId: String? = null,
        val snapshot: String? = null,
        val diff: String? = null
    )

    @Serializable
    data class PermissionRule(
        val permission: String,
        val pattern: String = "*",
        val action: String = "ask"
    )

    val createdAt: Long
        get() = time.created

    val isArchived: Boolean
        get() = time.archived != null
}

/**
 * Session with its current status and last message.
 */
data class SessionWithStatus(
    val session: Session,
    val status: SessionStatus,
    val lastMessageData: MessageWithParts? = null
) {
    val lastMessage: MessageWithParts?
        get() = lastMessageData
}
