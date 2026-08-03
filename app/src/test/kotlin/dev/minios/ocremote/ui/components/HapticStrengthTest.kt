package dev.minios.ocremote.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class HapticStrengthTest {
    @Test
    fun parsesPersistedStrengthAndFallsBackToMedium() {
        assertEquals(HapticStrength.LIGHT, HapticStrength.from("light"))
        assertEquals(HapticStrength.MEDIUM, HapticStrength.from("medium"))
        assertEquals(HapticStrength.STRONG, HapticStrength.from("strong"))
        assertEquals(HapticStrength.MEDIUM, HapticStrength.from("unknown"))
    }
}
