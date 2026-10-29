package dev.minios.ocremote.ui.screens.chat

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test

class TerminalKeyboardPolicyTest {
    @Test fun terminalDisablesPredictionWithoutChangingTheEnterAction() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        configureTerminalEditorInfo(info)
        assertTrue(info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertEquals(0, info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        assertEquals(0, info.inputType and InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE)
        assertEquals(EditorInfo.IME_ACTION_SEND, info.imeOptions and EditorInfo.IME_MASK_ACTION)
        assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
    }
}
