package com.github739c1ae2.focuslock.ui.screen.profile

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.adapter.ConfigValue
import com.github739c1ae2.focuslock.database.AppRuleConfig
import com.github739c1ae2.focuslock.database.AppRuleMode
import com.github739c1ae2.focuslock.database.GLOBAL_PROFILE_ID
import com.github739c1ae2.focuslock.database.LockRepository
import com.github739c1ae2.focuslock.database.ProfileConfig
import com.github739c1ae2.focuslock.util.FormDraft
import com.github739c1ae2.focuslock.util.UiText
import com.github739c1ae2.focuslock.util.ValidatedField
import com.github739c1ae2.focuslock.util.sortTextBy
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RuleUiItem(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
    val appliedAdapterId: String?,
    val adapterConfig: Map<String, ConfigValue>
)

data class ProfileDraft(
    val id: Long,
    val name: ValidatedField<String>,
    val userAppMode: AppRuleMode,
    val systemAppMode: AppRuleMode,
    val appRules: List<RuleUiItem>,
) : FormDraft<ProfileDraft> {
    override val isValid: Boolean
        get() = name.isValid

    override fun toValidated(): ProfileDraft {
        val nameError = if (name.value.isBlank()) {
            UiText.StringResource(R.string.profile_name_empty_error)
        } else {
            null
        }
        return copy(name = name.copy(errorMessage = nameError))
    }

    fun toProfileConfig(): ProfileConfig {
        val rulesMap = appRules.associate { rule ->
            rule.packageName to AppRuleConfig(
                packageName = rule.packageName,
                appliedAdapterId = rule.appliedAdapterId,
                adapterConfig = rule.adapterConfig
            )
        }
        return ProfileConfig(
            profileId = id,
            name = name.value,
            userAppMode = userAppMode,
            systemAppMode = systemAppMode,
            rules = rulesMap
        )
    }
}

sealed class UiState(open val isDirty: Boolean) {
    object Loading : UiState(isDirty = false)
    object Failed : UiState(isDirty = false)

    data class Loaded(
        val profile: ProfileDraft,
        override val isDirty: Boolean,
    ) : UiState(isDirty)
}

sealed interface UiEvent {
    object ProfileSaved : UiEvent
    object ProfileDeleted : UiEvent
}

@HiltViewModel(assistedFactory = ProfileEditViewModel.Factory::class)
class ProfileEditViewModel @AssistedInject constructor(
    @ApplicationContext private val context: Context,
    private val repository: LockRepository,
    @Assisted private val profileId: Long
) : ViewModel() {

    val uiState: StateFlow<UiState>
        field = MutableStateFlow<UiState>(UiState.Loading)

    val schedules = repository.observeSchedulesForProfile(profileId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val eventFlow: SharedFlow<UiEvent>
        field = MutableSharedFlow<UiEvent>()

    init {
        viewModelScope.launch {
            val config = repository.getCompleteProfile(profileId)
            val pm = context.packageManager

            val rules = config.rules.values.map { rule ->
                val (label, isSystemApp) = getAppInfo(pm, rule)
                RuleUiItem(
                    packageName = rule.packageName,
                    label = label,
                    isSystemApp = isSystemApp,
                    appliedAdapterId = rule.appliedAdapterId,
                    adapterConfig = rule.adapterConfig
                )
            }
            uiState.value = UiState.Loaded(
                profile = ProfileDraft(
                    id = config.profileId,
                    name = ValidatedField(config.name),
                    userAppMode = config.userAppMode,
                    systemAppMode = config.systemAppMode,
                    appRules = rules
                ),
                isDirty = false
            )
        }
    }

    private fun getAppInfo(
        pm: PackageManager?,
        rule: AppRuleConfig
    ): Pair<String, Boolean> {
        if (pm == null) {
            return context.getString(
                R.string.unknown_app_fmt,
                rule.packageName
            ) to false
        }
        return try {
            val appInfo = pm.getApplicationInfo(rule.packageName, 0)
            pm.getApplicationLabel(appInfo).toString() to
                    ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
        } catch (_: Exception) {
            context.getString(
                R.string.unknown_app_fmt,
                rule.packageName
            ) to false
        }
    }

    fun updateName(name: String) = uiState.update { state ->
        if (state is UiState.Loaded) {
            state.copy(
                profile = state.profile.copy(
                    name = ValidatedField(name)
                ).toValidated(),
                isDirty = true
            )
        } else {
            state
        }
    }

    fun setUserAppMode(mode: AppRuleMode) = uiState.update {
        if (it is UiState.Loaded) {
            it.copy(profile = it.profile.copy(userAppMode = mode).toValidated(), isDirty = true)
        } else {
            it
        }
    }

    fun setSystemAppMode(mode: AppRuleMode) = uiState.update {
        if (it is UiState.Loaded) {
            it.copy(profile = it.profile.copy(systemAppMode = mode).toValidated(), isDirty = true)
        } else {
            it
        }
    }

    fun addRules(packageNames: Set<String>) = uiState.update { state ->
        if (state !is UiState.Loaded) return@update state
        val existing = state.profile.appRules.associateBy { it.packageName }
        val pm = context.packageManager
        val newItems = packageNames.filterNot { it in existing }.map { pkg ->
            val (label, isSystemApp) = getAppInfo(pm, AppRuleConfig(pkg))
            RuleUiItem(
                packageName = pkg,
                label = label,
                isSystemApp = isSystemApp,
                appliedAdapterId = null,
                adapterConfig = emptyMap()
            )
        }
        val newRules = (state.profile.appRules + newItems).sortTextBy { it.label }
        state.copy(
            profile = state.profile.copy(appRules = newRules).toValidated(),
            isDirty = true
        )
    }

    fun removeRule(packageName: String) = uiState.update { state ->
        if (state !is UiState.Loaded) return@update state
        state.copy(
            profile = state.profile.copy(
                appRules = state.profile.appRules.filterNot { it.packageName == packageName }
            ).toValidated(),
            isDirty = true
        )
    }

    fun handleAdapterConfigSubmitted(result: AdapterConfigResult) =
        uiState.update { state ->
            if (state !is UiState.Loaded) return@update state
            state.copy(
                profile = state.profile.copy(
                    appRules = state.profile.appRules.map { rule ->
                        if (rule.packageName == result.packageName) {
                            rule.copy(
                                appliedAdapterId = result.config?.adapterId,
                                adapterConfig = result.config?.config ?: emptyMap()
                            )
                        } else {
                            rule
                        }
                    }
                ).toValidated(),
                isDirty = true
            )
        }


    fun save() {
        val state = uiState.value
        if (state !is UiState.Loaded) return
        viewModelScope.launch {
            repository.saveCompleteProfile(
                state.profile.toProfileConfig()
            )
            eventFlow.emit(UiEvent.ProfileSaved)
            uiState.value = state.copy(isDirty = false)
        }
    }

    fun delete() {
        val state = uiState.value
        if (state !is UiState.Loaded) return
        if (state.profile.id == GLOBAL_PROFILE_ID) return
        viewModelScope.launch {
            repository.deleteProfileById(state.profile.id)
            eventFlow.emit(UiEvent.ProfileDeleted)
            uiState.value = UiState.Failed
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(profileId: Long): ProfileEditViewModel
    }
}

