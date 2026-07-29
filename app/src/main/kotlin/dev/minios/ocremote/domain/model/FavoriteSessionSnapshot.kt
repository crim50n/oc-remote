package dev.minios.ocremote.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class FavoriteSessionSnapshot(
    val id: String,
    val projectId: String,
    val directory: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toSession() = Session(
        id = id,
        projectId = projectId,
        directory = directory,
        title = title,
        time = Session.Time(created = createdAt, updated = updatedAt),
    )

    companion object {
        fun from(session: Session) = FavoriteSessionSnapshot(
            id = session.id,
            projectId = session.projectId,
            directory = session.directory,
            title = session.title,
            createdAt = session.time.created,
            updatedAt = session.time.updated,
        )
    }
}
