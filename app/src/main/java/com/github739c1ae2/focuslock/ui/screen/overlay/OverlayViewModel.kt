package com.github739c1ae2.focuslock.ui.screen.overlay

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.database.ActiveLockSession
import com.github739c1ae2.focuslock.database.AppRuleMode
import com.github739c1ae2.focuslock.database.LockRepository
import com.github739c1ae2.focuslock.engine.EngineAction
import com.github739c1ae2.focuslock.engine.EngineState
import com.github739c1ae2.focuslock.engine.LockEngine
import com.github739c1ae2.focuslock.engine.SessionState
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration


data class AllowedAppInfo(
    val packageName: String,
    val appName: String
)

sealed class AllowedAppListState {
    object Loading : AllowedAppListState()
    data class Loaded(val apps: List<AllowedAppInfo>) : AllowedAppListState()
}

sealed interface OverlayState {
    object Hidden : OverlayState
    data class Locked(val session: ActiveLockSession) : OverlayState
    data class Warning(val remaining: Duration) : OverlayState
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class OverlayViewModel @AssistedInject constructor(
    @ApplicationContext private val context: Context,
    @Assisted private val lockEngine: LockEngine,
    private val repository: LockRepository,
) : ViewModel() {

    val overlayState: StateFlow<OverlayState> = lockEngine.engineState
        .map { state ->
            when (state) {
                is EngineState.Idle -> OverlayState.Hidden
                is EngineState.InSession -> {
                    when (state.sessionState) {
                        is SessionState.Warning -> OverlayState.Warning(state.sessionState.remaining)
                        is SessionState.Locked -> OverlayState.Locked(state.session)
                        is SessionState.Paused,
                        is SessionState.Allowed -> OverlayState.Hidden
                    }
                }
            }
        }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = OverlayState.Hidden
        )

    val allowedApps: StateFlow<AllowedAppListState> = lockEngine.engineState
        .filterIsInstance<EngineState.InSession>()
        .map { it.session.profileId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            flow {
                emit(AllowedAppListState.Loading)
                val apps = loadAllowedApps(id)
                emit(AllowedAppListState.Loaded(apps))
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AllowedAppListState.Loaded(emptyList())
        )


    suspend fun loadAllowedApps(profileId: Long): List<AllowedAppInfo> {
        val profile = repository.getCompleteProfile(profileId)
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(intent, 0)
            .filter {
                val rule = profile.rules[it.activityInfo.packageName]
                val appInfoFlags = it.activityInfo.applicationInfo.flags
                val isSystemApp = (appInfoFlags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (rule != null) {
                    if (rule.appliedAdapterId != null) {
                        true
                    } else if (isSystemApp) {
                        profile.systemAppMode == AppRuleMode.WHITELIST
                    } else {
                        profile.userAppMode == AppRuleMode.WHITELIST
                    }
                } else {
                    if (isSystemApp) {
                        profile.systemAppMode == AppRuleMode.BLACKLIST
                    } else {
                        profile.userAppMode == AppRuleMode.BLACKLIST
                    }
                }
            }
            .map {
                val packageName = it.activityInfo.packageName
                AllowedAppInfo(
                    packageName,
                    it.loadLabel(pm).toString(),
                )
            }
    }

    fun onLaunchApp(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun onRequestPause(duration: Duration) {
        lockEngine.onActionReceived(EngineAction.RequestPause(duration))
    }

    fun onRequestUnlock() {
        lockEngine.onActionReceived(EngineAction.RequestUnlock)
    }


    @AssistedFactory
    interface Factory {
        fun create(lockEngine: LockEngine): OverlayViewModel
    }

}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface OverlayViewModelEntryPoint {
    fun getOverlayViewModelFactory(): OverlayViewModel.Factory
}
