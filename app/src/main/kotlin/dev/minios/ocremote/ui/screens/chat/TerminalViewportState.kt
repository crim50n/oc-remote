package dev.minios.ocremote.ui.screens.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.mutableStateOf

/** Survives switching between docked and expanded views of the same PTY. */
internal class TerminalViewportState {
    val scroll = ScrollState(0)
    val follow = mutableStateOf(true)
    val input = TerminalDesktopInputState()
}
