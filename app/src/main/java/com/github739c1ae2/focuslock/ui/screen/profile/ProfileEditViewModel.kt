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
import com.github739c1ae2.focuslock.util.EditorState
import com.github739c1ae2.focuslock.util.ValidationResult
import com.github739c1ae2.focuslock.util.markSaved
import com.github739c1ae2.focuslock.util.validatedSnapshot
import com.github739c1ae2.focuslock.util.sortTextBy
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
    val name: String,
    val userAppMode: AppRuleMode,
    val systemAppMode: AppRuleMode,
    val appRules: List<RuleUiItem>,
) {
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
            name = name,
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
        val validation: ValidationResult<ProfileField>,
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

    private val drafts = MutableStateFlow<EditorState<ProfileDraft>>(EditorState.Loading)

    val uiState: StateFlow<UiState> = drafts.map { raw ->
        when (raw) {
            EditorState.Loading -> UiState.Loading
            EditorState.Failed -> UiState.Failed
            is EditorState.Loaded -> UiState.Loaded(raw.draft, raw.isDirty, validateProfile(raw.draft))
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState.Loading)

    val schedules = repository.observeScheduleEntitiesForProfile(profileId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val eventFlow: SharedFlow<UiEvent>
        field = MutableSharedFlow<UiEvent>()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        drafts.value = EditorState.Loading
        loadJob = viewModelScope.launch {
            val config = repository.getProfile(profileId)
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
            drafts.value = EditorState.Loaded(
                draft = ProfileDraft(
                    id = config.profileId,
                    name = config.name,
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

    fun updateName(name: String) = drafts.update { state ->
        if (state is EditorState.Loaded) {
            state.copy(
                draft = state.draft.copy(
                    name = name
                ),
                isDirty = true
            )
        } else {
            state
        }
    }

    fun setUserAppMode(mode: AppRuleMode) = drafts.update {
        if (it is EditorState.Loaded) {
            it.copy(draft = it.draft.copy(userAppMode = mode), isDirty = true)
        } else {
            it
        }
    }

    fun setSystemAppMode(mode: AppRuleMode) = drafts.update {
        if (it is EditorState.Loaded) {
            it.copy(draft = it.draft.copy(systemAppMode = mode), isDirty = true)
        } else {
            it
        }
    }

    fun addRules(packageNames: Set<String>) = drafts.update { state ->
        if (state !is EditorState.Loaded) return@update state
        val existing = state.draft.appRules.associateBy { it.packageName }
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
        val newRules = (state.draft.appRules + newItems).sortTextBy { it.label }
        state.copy(
            draft = state.draft.copy(appRules = newRules),
            isDirty = true
        )
    }

    fun removeRule(packageName: String) = drafts.update { state ->
        if (state !is EditorState.Loaded) return@update state
        state.copy(
            draft = state.draft.copy(
                appRules = state.draft.appRules.filterNot { it.packageName == packageName }
            ),
            isDirty = true
        )
    }

    fun handleAdapterConfigSubmitted(result: AdapterConfigResult) =
        drafts.update { state ->
            if (state !is EditorState.Loaded) return@update state
            state.copy(
                draft = state.draft.copy(
                    appRules = state.draft.appRules.map { rule ->
                        if (rule.packageName == result.packageName) {
                            rule.copy(
                                appliedAdapterId = result.config?.adapterId,
                                adapterConfig = result.config?.config ?: emptyMap()
                            )
                        } else {
                            rule
                        }
                    }
                ),
                isDirty = true
            )
        }


    fun save() {
        val state = drafts.validatedSnapshot(::validateProfile) ?: return
        viewModelScope.launch {
            repository.upsertProfile(
                state.draft.toProfileConfig()
            )
            if (drafts.markSaved(state)) {
                eventFlow.emit(UiEvent.ProfileSaved)
            }
        }
    }

    fun delete() {
        val state = drafts.value
        if (state !is EditorState.Loaded) return
        if (state.draft.id == GLOBAL_PROFILE_ID) return
        viewModelScope.launch {
            repository.deleteProfileById(state.draft.id)
            eventFlow.emit(UiEvent.ProfileDeleted)
            drafts.value = EditorState.Failed
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(profileId: Long): ProfileEditViewModel
    }
}

