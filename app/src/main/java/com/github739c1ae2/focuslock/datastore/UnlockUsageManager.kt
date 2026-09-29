package com.github739c1ae2.focuslock.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github739c1ae2.focuslock.database.ActiveLockSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnlockUsageManager @Inject constructor(
    @UnlockUsageDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        const val MAX_FORCE_UNLOCKS_PER_MONTH = 3
        const val MAX_PAUSE_DURATION_SECONDS_PER_SESSION = 3 * 60
        val FORCE_UNLOCK_MONTH_KEY = stringPreferencesKey("force_unlock_month")
        val FORCE_UNLOCK_COUNT_KEY = intPreferencesKey("force_unlock_count")
        val PAUSE_SESSION_END_TIMESTAMP_KEY = stringPreferencesKey("pause_session_end_timestamp")
        val PAUSE_DURATION_SECONDS_KEY = intPreferencesKey("pause_duration_seconds")
    }

    private fun getCurrentMonth(): String {
        return YearMonth.now().toString()
    }

    suspend fun getRemainingForceUnlocks(): Int {
        val prefs = dataStore.data.first()
        val currentMonth = getCurrentMonth()
        val storedMonth = prefs[FORCE_UNLOCK_MONTH_KEY]
        val storedCount = prefs[FORCE_UNLOCK_COUNT_KEY] ?: 0
        return if (storedMonth == currentMonth) {
            MAX_FORCE_UNLOCKS_PER_MONTH - storedCount
        } else {
            MAX_FORCE_UNLOCKS_PER_MONTH
        }
    }

    suspend fun incrementForceUnlockCount() {
        val currentMonth = getCurrentMonth()
        dataStore.edit { prefs ->
            val storedMonth = prefs[FORCE_UNLOCK_MONTH_KEY]
            val storedCount = prefs[FORCE_UNLOCK_COUNT_KEY] ?: 0
            if (storedMonth == currentMonth) {
                prefs[FORCE_UNLOCK_COUNT_KEY] =
                    (storedCount + 1).coerceAtMost(MAX_FORCE_UNLOCKS_PER_MONTH)
            } else {
                prefs[FORCE_UNLOCK_MONTH_KEY] = currentMonth
                prefs[FORCE_UNLOCK_COUNT_KEY] = 1
            }
        }
    }

    val remainingPauseDurationSeconds: Flow<Int> = dataStore.data.map { prefs ->
        MAX_PAUSE_DURATION_SECONDS_PER_SESSION - (prefs[PAUSE_DURATION_SECONDS_KEY] ?: 0)
    }

    suspend fun updateSession(session: ActiveLockSession) {
        dataStore.edit { prefs ->
            val oldSession = prefs[PAUSE_SESSION_END_TIMESTAMP_KEY]?.toLongOrNull() ?: 0L
            if (oldSession == session.endTimeMillis) {
                return@edit
            }
            prefs[PAUSE_SESSION_END_TIMESTAMP_KEY] = session.endTimeMillis.toString()
            prefs[PAUSE_DURATION_SECONDS_KEY] = 0
        }
    }

    suspend fun incrementPauseDuration(seconds: Int) {
        dataStore.edit { prefs ->
            val currentDuration = prefs[PAUSE_DURATION_SECONDS_KEY] ?: 0
            prefs[PAUSE_DURATION_SECONDS_KEY] =
                (currentDuration + seconds).coerceAtMost(MAX_PAUSE_DURATION_SECONDS_PER_SESSION)
        }
    }

}