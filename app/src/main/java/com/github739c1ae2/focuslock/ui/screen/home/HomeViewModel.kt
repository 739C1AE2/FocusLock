package com.github739c1ae2.focuslock.ui.screen.home

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.database.GLOBAL_PROFILE_ID
import com.github739c1ae2.focuslock.database.LockRepository
import com.github739c1ae2.focuslock.database.ProfileEntity
import com.github739c1ae2.focuslock.database.QuickLockEntity
import com.github739c1ae2.focuslock.engine.EngineState
import com.github739c1ae2.focuslock.engine.LockEngine
import com.github739c1ae2.focuslock.engine.ServiceState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UiState(
    val selectedProfileId: Long,
    val selectedMinutes: Int
)

enum class EngineServiceState {
    Stopped,
    Idle,
    InSession
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: LockRepository
) : ViewModel() {

    val serviceState: StateFlow<EngineServiceState> = LockEngine.serviceState
        .map { state ->
            when (state) {
                is ServiceState.Stopped -> EngineServiceState.Stopped
                is ServiceState.Running -> when (state.engineState) {
                    is EngineState.Idle -> EngineServiceState.Idle
                    is EngineState.InSession -> EngineServiceState.InSession
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = EngineServiceState.Stopped
        )

    val profiles: StateFlow<List<ProfileEntity>> = repository.observeAllProfiles()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val uiState: StateFlow<UiState>
        field = MutableStateFlow(UiState(
            selectedProfileId = GLOBAL_PROFILE_ID,
            selectedMinutes = 30
        ))


    fun openSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    fun updateSelectedProfile(profileId: Long) {
        uiState.value = uiState.value.copy(selectedProfileId = profileId)
    }

    fun updateSelectedMinutes(minutes: Int) {
        uiState.value = uiState.value.copy(selectedMinutes = minutes)
    }

    fun startQuickLock() {
        val state = uiState.value
        val currentTimestamp = System.currentTimeMillis()
        val endTimestamp = currentTimestamp + state.selectedMinutes * 60 * 1000L
        viewModelScope.launch {
            repository.startQuickLock(
                QuickLockEntity(
                    startTimestamp = currentTimestamp,
                    endTimestamp = endTimestamp,
                    profileId = state.selectedProfileId
                )
            )
        }
    }

}
