package dev.minios.ocremote.ui.screens.chat

import java.util.concurrent.atomic.AtomicBoolean

internal class OncePerProcessHint {
    private val shown = AtomicBoolean(false)

    fun tryShow(): Boolean = shown.compareAndSet(false, true)
}

internal object TerminalPanelHintCoordinator {
    private val hint = OncePerProcessHint()

    fun tryShow(): Boolean = hint.tryShow()
}
