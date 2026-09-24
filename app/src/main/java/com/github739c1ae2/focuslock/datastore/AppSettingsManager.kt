package com.github739c1ae2.focuslock.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSettingsManager @Inject constructor(
    @AppSettingsDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        val HIDE_FROM_RECENTS_KEY = booleanPreferencesKey("hide_from_recents")
        val USE_APPLICATION_OVERLAY_KEY = booleanPreferencesKey("use_application_overlay")
    }

    val hideFromRecentsEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[HIDE_FROM_RECENTS_KEY] ?: false
    }

    suspend fun setHideFromRecents(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[HIDE_FROM_RECENTS_KEY] = enabled
        }
    }

    val useApplicationOverlayEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[USE_APPLICATION_OVERLAY_KEY] ?: false
    }

    suspend fun setUseApplicationOverlay(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[USE_APPLICATION_OVERLAY_KEY] = enabled
        }
    }
}