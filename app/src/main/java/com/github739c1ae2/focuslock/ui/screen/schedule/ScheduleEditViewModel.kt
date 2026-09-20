package com.github739c1ae2.focuslock.ui.screen.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.database.LockRepository
import com.github739c1ae2.focuslock.database.ProfileEntity
import com.github739c1ae2.focuslock.database.ScheduleEntity
import com.github739c1ae2.focuslock.util.FormDraft
import com.github739c1ae2.focuslock.util.UiText
import com.github739c1ae2.focuslock.util.ValidatedField
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek

data class ScheduleDraft(
    val id: Long,
    val name: ValidatedField<String>,
    val daysOfWeek: Set<DayOfWeek>,
    val startMinute: ValidatedField<Int>,
    val endMinute: ValidatedField<Int>,
    val profileId: Long
) : FormDraft<ScheduleDraft> {
    override val isValid: Boolean
        get() = name.isValid && startMinute.isValid && endMinute.isValid

    override fun toValidated(): ScheduleDraft {
        val nameError = if (name.value.isBlank()) {
            UiText.StringResource(R.string.schedule_name_empty_error)
        } else {
            null
        }
        val timeError = if (startMinute == endMinute) {
            UiText.StringResource(R.string.schedule_time_equal_error)
        } else {
            null
        }
        return copy(
            name = name.copy(errorMessage = nameError),
            startMinute = startMinute.copy(errorMessage = timeError),
            endMinute = endMinute.copy(errorMessage = timeError)
        )
    }

    fun toEntity(): ScheduleEntity {
        return ScheduleEntity(
            id = id,
            name = name.value,
            daysOfWeek = daysOfWeek,
            startMinute = startMinute.value,
            endMinute = endMinute.value,
            profileId = profileId
        )
    }

    companion object {
        fun fromEntity(entity: ScheduleEntity): ScheduleDraft {
            return ScheduleDraft(
                id = entity.id,
                name = ValidatedField(entity.name),
                daysOfWeek = entity.daysOfWeek,
                startMinute = ValidatedField(entity.startMinute),
                endMinute = ValidatedField(entity.endMinute),
                profileId = entity.profileId
            ).toValidated()
        }
    }
}

sealed class ScheduleState(open val isDirty: Boolean) {
    object Loading : ScheduleState(false)
    object Failed : ScheduleState(false)
    data class Loaded(val schedule: ScheduleDraft, override val isDirty: Boolean) :
        ScheduleState(isDirty)
}

sealed class UiEvent {
    object ScheduleSaved : UiEvent()
    object ScheduleDeleted : UiEvent()
    data class ProfileCreated(val profileId: Long) : UiEvent()
}

@HiltViewModel(assistedFactory = ScheduleEditViewModel.Factory::class)
class ScheduleEditViewModel @AssistedInject constructor(
    private val repository: LockRepository,
    @Assisted private val scheduleId: Long
) : ViewModel() {

    val scheduleState: StateFlow<ScheduleState>
        field = MutableStateFlow<ScheduleState>(ScheduleState.Loading)

    val eventFlow: SharedFlow<UiEvent>
        field = MutableSharedFlow<UiEvent>()


    val profiles: StateFlow<List<ProfileEntity>> = repository.observeAllProfiles()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val schedule = repository.getScheduleById(scheduleId)
            if (schedule != null) {
                val scheduleDraft = ScheduleDraft.fromEntity(schedule)
                scheduleState.value = ScheduleState.Loaded(scheduleDraft, isDirty = false)
            } else {
                scheduleState.value = ScheduleState.Failed
            }
        }
    }

    fun updateSchedule(schedule: ScheduleDraft) {
        scheduleState.value = ScheduleState.Loaded(schedule.toValidated(), isDirty = true)
    }

    fun createProfile(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = repository.createProfile(name)
            scheduleState.update {
                when (it) {
                    is ScheduleState.Loaded -> it.copy(
                        schedule = it.schedule.copy(profileId = id),
                        isDirty = true
                    )

                    else -> it
                }
            }
            eventFlow.emit(UiEvent.ProfileCreated(id))
        }
    }

    fun save() {
        val state = scheduleState.value
        if (state !is ScheduleState.Loaded) return
        viewModelScope.launch {
            val schedule = state.schedule.toEntity()
            val newSchedule = repository.saveScheduleWithoutIsActive(schedule)
            eventFlow.emit(UiEvent.ScheduleSaved)
            scheduleState.value =
                ScheduleState.Loaded(ScheduleDraft.fromEntity(newSchedule), isDirty = false)
        }
    }

    fun delete() {
        val state = scheduleState.value
        if (state !is ScheduleState.Loaded) return
        viewModelScope.launch {
            repository.deleteScheduleById(state.schedule.id)
            scheduleState.value = ScheduleState.Failed
            eventFlow.emit(UiEvent.ScheduleDeleted)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(scheduleId: Long): ScheduleEditViewModel
    }
}
