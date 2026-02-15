package dev.minios.ocremote.domain.model

import kotlinx.serialization.Serializable

/**
 * Tool State - lifecycle of a tool call
 */
@Serializable
sealed class ToolState {
    @Serializable
    data class Pending(
        val input: String,
        val raw: String? = null
    ) : ToolState()
    
    @Serializable
    data class Running(
        val input: String,
        val title: String? = null,
        val metadata: Map<String, String>? = null,
        val time: Time
    ) : ToolState() {
        @Serializable
        data class Time(val start: Long)
    }
    
    @Serializable
    data class Completed(
        val input: String,
        val output: String,
        val title: String? = null,
        val metadata: Map<String, String>? = null,
        val time: Time,
        val attachments: List<Attachment>? = null
    ) : ToolState() {
        @Serializable
        data class Time(val start: Long, val end: Long)
        
        @Serializable
        data class Attachment(
            val type: String,
            val data: String
        )
    }
    
    @Serializable
    data class Error(
        val input: String,
        val error: String,
        val time: Time
    ) : ToolState() {
        @Serializable
        data class Time(val start: Long, val end: Long)
    }
}
