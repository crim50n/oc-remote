package dev.minios.ocremote.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide settings stored in DataStore.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {

    companion object {
        private val USE_NATIVE_UI_KEY = booleanPreferencesKey("use_native_ui")
    }

    /**
     * When true, deep-links and the primary "open" action use the native Chat UI.
     * When false, they use the WebView.
     * Default: true (native).
     */
    val useNativeUi: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[USE_NATIVE_UI_KEY] ?: true
    }

    suspend fun setUseNativeUi(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[USE_NATIVE_UI_KEY] = value
        }
    }
}
