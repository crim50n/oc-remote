package dev.minios.ocremote.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerActionTest {
    @Test
    fun busyWithEmptyDraftShowsStop() {
        assertEquals(
            ComposerAction.STOP,
            composerAction(isBusy = true, isSending = false, hasDraft = false, isShellMode = false),
        )
    }

    @Test
    fun busyWithDraftShowsSend() {
        assertEquals(
            ComposerAction.SEND,
            composerAction(isBusy = true, isSending = false, hasDraft = true, isShellMode = false),
        )
    }

    @Test
    fun shellCommandCannotBeSentWhileBusy() {
        assertEquals(
            ComposerAction.DISABLED,
            composerAction(isBusy = true, isSending = false, hasDraft = true, isShellMode = true),
        )
    }

    @Test
    fun activeRequestDisablesButton() {
        assertEquals(
            ComposerAction.DISABLED,
            composerAction(isBusy = true, isSending = true, hasDraft = false, isShellMode = false),
        )
    }
}
