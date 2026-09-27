package com.github739c1ae2.focuslock.ui.screen.settings

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.datastore.AppSettingsManager
import com.github739c1ae2.focuslock.guard.AccessibilityGuard
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
    val hideFromRecents: Boolean = false,
    val autoEnableAccessibility: Boolean = false
)

sealed interface SettingsUiEvent {
    object RequestSystemAlertWindowPermission : SettingsUiEvent
    object RequestWriteSecureSettingsPermission : SettingsUiEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsManager: AppSettingsManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val uiState: StateFlow<SettingsUiState> =
        combine(
            settingsManager.useApplicationOverlayEnabled,
            settingsManager.hideFromRecentsEnabled,
            settingsManager.autoEnableAccessibilityEnabled
        ) { useApplicationOverlay, hideFromRecents, autoEnableAccessibility ->

            SettingsUiState(
                useApplicationOverlay = useApplicationOverlay && Settings.canDrawOverlays(context),
                hideFromRecents = hideFromRecents,
                autoEnableAccessibility = autoEnableAccessibility
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

    fun toggleUseApplicationOverlay(enabled: Boolean, emitRequest: Boolean = true) {
        viewModelScope.launch {
            if (enabled && !Settings.canDrawOverlays(context)) {
                if (emitRequest) {
                    eventFlow.emit(SettingsUiEvent.RequestSystemAlertWindowPermission)
                }
                return@launch
            }
            settingsManager.setUseApplicationOverlay(enabled)
        }
    }

    fun toggleAutoEnableAccessibility(enabled: Boolean, emitRequest: Boolean = true) {
        viewModelScope.launch {
            if (enabled && !AccessibilityGuard.hasWriteSecureSettingsPermission()) {
                if (emitRequest) {
                    eventFlow.emit(SettingsUiEvent.RequestWriteSecureSettingsPermission)
                }
                return@launch
            }
            settingsManager.setAutoEnableAccessibility(enabled)
        }
    }

}