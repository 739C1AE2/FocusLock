package com.github739c1ae2.focuslock.ui.screen.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.database.CourseRepository
import com.github739c1ae2.focuslock.database.CourseTimeSlotEntity
import com.github739c1ae2.focuslock.database.CourseTimeTableEntity
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TimeSlotDraft(
    val key: Long,
    val startMinute: Int,
    val endMinute: Int,
    val alias: String
)

data class TimeTableDraft(
    val isBase: Boolean,
    val name: ValidatedField<String>,
    val startEpochDay: Long?,
    val endEpochDay: Long?,
    val slots: List<TimeSlotDraft>,
    val dateErrorMessage: UiText? = null
) : FormDraft<TimeTableDraft> {
    override val isValid: Boolean
        get() = name.isValid && dateErrorMessage == null

    override fun toValidated(): TimeTableDraft {
        val nameError = if (name.value.isBlank()) {
            UiText.StringResource(R.string.time_table_name_empty_error)
        } else {
            null
        }
        val dateError = if (isBase) {
            null
        } else when {
            startEpochDay == null || endEpochDay == null ->
                UiText.StringResource(R.string.time_table_date_required_error)

            startEpochDay > endEpochDay ->
                UiText.StringResource(R.string.time_table_date_range_error)

            else -> null
        }
        return copy(
            name = name.copy(errorMessage = nameError),
            dateErrorMessage = dateError
        )
    }
}

sealed class TimeTableEditorState(open val isDirty: Boolean) {
    object Loading : TimeTableEditorState(false)
    object Failed : TimeTableEditorState(false)
    data class Loaded(val draft: TimeTableDraft, override val isDirty: Boolean) : TimeTableEditorState(isDirty)
}

sealed interface TimeTableEditorEvent {
    object Saved : TimeTableEditorEvent
    object Deleted : TimeTableEditorEvent
}

@HiltViewModel(assistedFactory = TimeTableEditorViewModel.Factory::class)
class TimeTableEditorViewModel @AssistedInject constructor(
    private val repository: CourseRepository,
    @Assisted private val timeTableId: Long
) : ViewModel() {

    val state: StateFlow<TimeTableEditorState>
        field = MutableStateFlow<TimeTableEditorState>(TimeTableEditorState.Loading)

    val eventFlow: SharedFlow<TimeTableEditorEvent>
        field = MutableSharedFlow<TimeTableEditorEvent>()

    private var nextKey = 1L

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val data = repository.getTimeTableWithSlots(timeTableId)
            if (data == null) {
                state.value = TimeTableEditorState.Failed
                return@launch
            }
            val (timeTable, slots) = data
            val draft = TimeTableDraft(
                isBase = timeTable.isBase,
                name = ValidatedField(timeTable.name),
                startEpochDay = timeTable.startEpochDay,
                endEpochDay = timeTable.endEpochDay,
                slots = slots.map { slot ->
                    TimeSlotDraft(
                        key = nextKey++,
                        startMinute = slot.startMinute,
                        endMinute = slot.endMinute,
                        alias = slot.alias.orEmpty()
                    )
                }
            ).toValidated()
            state.value = TimeTableEditorState.Loaded(draft, isDirty = false)
        }
    }

    fun updateDraft(transform: (TimeTableDraft) -> TimeTableDraft) {
        state.update { current ->
            if (current is TimeTableEditorState.Loaded) {
                current.copy(draft = transform(current.draft).toValidated(), isDirty = true)
            } else {
                current
            }
        }
    }

    fun addSlot() {
        updateDraft { draft ->
            val last = draft.slots.lastOrNull()
            val start = last?.endMinute?.plus(10) ?: (8 * 60)
            draft.copy(
                slots = draft.slots + TimeSlotDraft(
                    key = nextKey++,
                    startMinute = start,
                    endMinute = start + 45,
                    alias = ""
                )
            )
        }
    }

    fun removeSlot(key: Long) {
        updateDraft { draft -> draft.copy(slots = draft.slots.filterNot { it.key == key }) }
    }

    fun save() {
        val loaded = state.value
        if (loaded !is TimeTableEditorState.Loaded) return
        if (!loaded.draft.isValid) return
        viewModelScope.launch {
            val draft = loaded.draft
            repository.saveTimeTable(
                CourseTimeTableEntity(
                    id = timeTableId,
                    name = draft.name.value.trim(),
                    isBase = draft.isBase,
                    startEpochDay = if (draft.isBase) null else draft.startEpochDay,
                    endEpochDay = if (draft.isBase) null else draft.endEpochDay
                ),
                draft.slots.mapIndexed { index, slot ->
                    CourseTimeSlotEntity(
                        timeTableId = timeTableId,
                        number = index + 1,
                        startMinute = slot.startMinute,
                        endMinute = slot.endMinute,
                        alias = slot.alias.trim().ifBlank { null }
                    )
                }
            )
            eventFlow.emit(TimeTableEditorEvent.Saved)
            state.value = loaded.copy(isDirty = false)
        }
    }

    fun delete() {
        val loaded = state.value
        if (loaded !is TimeTableEditorState.Loaded) return
        if (loaded.draft.isBase) return
        viewModelScope.launch {
            repository.deleteTimeTable(timeTableId)
            eventFlow.emit(TimeTableEditorEvent.Deleted)
            state.value = TimeTableEditorState.Failed
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(timeTableId: Long): TimeTableEditorViewModel
    }
}
