package dev.minios.ocremote.ui.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class AdaptiveWindowPolicyTest {
    @Test fun keyboardShrinksSuggestionBudgetWithoutMakingItUnusable() {
        val keyboardVisible = composerExtraHeight(240.dp)
        assertTrue(keyboardVisible < composerExtraHeight(800.dp))
        assertTrue(keyboardVisible >= 48.dp)
        assertTrue(keyboardVisible < 80.dp)
        assertEquals(160.dp, composerExtraHeight(1600.dp))
    }
}
