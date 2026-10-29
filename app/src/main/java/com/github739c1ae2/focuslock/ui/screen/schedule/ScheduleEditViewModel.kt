package com.github739c1ae2.focuslock.ui.screen.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.database.LockRepository
import com.github739c1ae2.focuslock.database.ProfileEntity
import com.github739c1ae2.focuslock.database.Schedule
import com.github739c1ae2.focuslock.util.EditorState
import com.github739c1ae2.focuslock.util.ValidationResult
import com.github739c1ae2.focuslock.util.edit
import com.github739c1ae2.focuslock.util.markSaved
import com.github739c1ae2.focuslock.util.validatedSnapshot
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek

data class ScheduleDraft(
    val id: Long,
    val name: String,
    val daysOfWeek: Set<DayOfWeek>,
    val startMinute: Int,
    val endMinute: Int,
    val profileId: Long
) {
    fun toSchedule(): Schedule = Schedule(id, name, daysOfWeek, startMinute, endMinute, profileId)

    companion object {
        fun fromSchedule(schedule: Schedule): ScheduleDraft = ScheduleDraft(
            schedule.id, schedule.name, schedule.daysOfWeek, schedule.startMinute,
            schedule.endMinute, schedule.profileId
        )
    }
}

sealed class ScheduleState(open val isDirty: Boolean) {
    data object Loading : ScheduleState(false)
    data object Failed : ScheduleState(false)
    data class Loaded(
        val schedule: ScheduleDraft,
        override val isDirty: Boolean,
        val validation: ValidationResult<ScheduleField>
    ) : ScheduleState(isDirty)
}

sealed class UiEvent {
    data object ScheduleSaved : UiEvent()
    data object ScheduleDeleted : UiEvent()
    data class ProfileCreated(val profileId: Long) : UiEvent()
}

@HiltViewModel(assistedFactory = ScheduleEditViewModel.Factory::class)
class ScheduleEditViewModel @AssistedInject constructor(
    private val repository: LockRepository,
    @Assisted private val scheduleId: Long
) : ViewModel() {
    private val drafts = MutableStateFlow<EditorState<ScheduleDraft>>(EditorState.Loading)

    val scheduleState: StateFlow<ScheduleState> = drafts.map { raw ->
        when (raw) {
            EditorState.Loading -> ScheduleState.Loading
            EditorState.Failed -> ScheduleState.Failed
            is EditorState.Loaded -> ScheduleState.Loaded(raw.draft, raw.isDirty, validateSchedule(raw.draft))
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ScheduleState.Loading)

    val eventFlow: SharedFlow<UiEvent>
        field = MutableSharedFlow<UiEvent>()

    val profiles: StateFlow<List<ProfileEntity>> = repository.observeAllProfileEntities()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var loadJob: Job? = null

    init { load() }

    fun load() {
        loadJob?.cancel()
        drafts.value = EditorState.Loading
        loadJob = viewModelScope.launch {
            val schedule = repository.getSchedule(scheduleId)
            drafts.value = if (schedule == null) EditorState.Failed
            else EditorState.Loaded(ScheduleDraft.fromSchedule(schedule))
        }
    }

    fun updateSchedule(schedule: ScheduleDraft) = drafts.edit { schedule }

    fun createProfile(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = repository.createProfile(name)
            drafts.edit { it.copy(profileId = id) }
            eventFlow.emit(UiEvent.ProfileCreated(id))
        }
    }

    fun save() {
        val snapshot = drafts.validatedSnapshot(::validateSchedule) ?: return
        viewModelScope.launch {
            repository.updateSchedule(snapshot.draft.toSchedule())
            if (drafts.markSaved(snapshot)) {
                eventFlow.emit(UiEvent.ScheduleSaved)
            }
        }
    }

    fun delete() {
        val snapshot = drafts.value as? EditorState.Loaded ?: return
        viewModelScope.launch {
            repository.deleteScheduleById(snapshot.draft.id)
            drafts.value = EditorState.Failed
            eventFlow.emit(UiEvent.ScheduleDeleted)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(scheduleId: Long): ScheduleEditViewModel
    }
}
