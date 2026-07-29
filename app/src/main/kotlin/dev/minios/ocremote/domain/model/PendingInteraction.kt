package dev.minios.ocremote.domain.model

sealed interface PendingInteraction {
    val id: String
    val sessionId: String

    data class Permission(val request: SseEvent.PermissionAsked) : PendingInteraction {
        override val id: String = request.id
        override val sessionId: String = request.sessionId
    }

    data class Question(val request: SseEvent.QuestionAsked) : PendingInteraction {
        override val id: String = request.id
        override val sessionId: String = request.sessionId
    }
}
