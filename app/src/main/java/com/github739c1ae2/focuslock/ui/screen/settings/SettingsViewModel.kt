package com.github739c1ae2.focuslock.ui.screen.settings

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.datastore.AppSettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val useApplicationOverlay: Boolean = false,
    val hideFromRecents: Boolean = false
)

sealed interface SettingsUiEvent {
    object RequestSystemAlertWindowPermission : SettingsUiEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsManager: AppSettingsManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> =
        combine(
            settingsManager.useApplicationOverlayEnabled,
            settingsManager.hideFromRecentsEnabled
        ) { useApplicationOverlay, hideFromRecents ->

            SettingsUiState(
                useApplicationOverlay = useApplicationOverlay && Settings.canDrawOverlays(context),
                hideFromRecents = hideFromRecents
            )

        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SettingsUiState()
        )

    val eventFlow: SharedFlow<SettingsUiEvent>
        field = kotlinx.coroutines.flow.MutableSharedFlow<SettingsUiEvent>()

    fun toggleHideFromRecents(enabled: Boolean) {
        viewModelScope.launch {
            settingsManager.setHideFromRecents(enabled)
        }
    }

    fun toggleUseApplicationOverlay(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !Settings.canDrawOverlays(context)) {
                eventFlow.emit(SettingsUiEvent.RequestSystemAlertWindowPermission)
                return@launch
            }
            settingsManager.setUseApplicationOverlay(enabled)
        }
    }

}