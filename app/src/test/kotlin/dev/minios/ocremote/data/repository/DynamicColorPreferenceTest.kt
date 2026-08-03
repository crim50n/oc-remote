package dev.minios.ocremote.data.repository

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicColorPreferenceTest {
    @Test
    fun `material you is disabled when no preference is stored`() {
        assertFalse(SettingsRepository.dynamicColorEnabled(emptyPreferences()))
    }

    @Test
    fun `stored material you choice remains enabled`() {
        val preferences = mutablePreferencesOf(booleanPreferencesKey("dynamic_color") to true)

        assertTrue(SettingsRepository.dynamicColorEnabled(preferences))
    }
}
