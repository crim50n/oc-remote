package dev.minios.ocremote.ui.screens.chat

internal fun shouldSuppressPartialMessages(
    loading: Boolean,
    messageCount: Int,
    sentDuringInitialLoad: Boolean,
): Boolean = loading && messageCount < 3 && !sentDuringInitialLoad
