package dev.minios.ocremote.ui.screens.chat

internal enum class ComposerAction {
    SEND,
    STOP,
    DISABLED,
}

internal fun composerAction(
    isBusy: Boolean,
    isSending: Boolean,
    hasDraft: Boolean,
    isShellMode: Boolean,
): ComposerAction {
    if (isSending) return ComposerAction.DISABLED
    if (isBusy && !hasDraft) return ComposerAction.STOP
    if (hasDraft && (!isShellMode || !isBusy)) return ComposerAction.SEND
    return ComposerAction.DISABLED
}
