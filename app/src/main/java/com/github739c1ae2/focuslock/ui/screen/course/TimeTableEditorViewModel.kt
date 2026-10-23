package com.github739c1ae2.focuslock.ui.screen.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.database.CourseRepository
import com.github739c1ae2.focuslock.database.TimeSlot
import com.github739c1ae2.focuslock.database.TimeTable
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
    val startEpochDay: ValidatedField<Long>,
    val endEpochDay: ValidatedField<Long>,
    val slots: List<ValidatedField<TimeSlotDraft>>,
) : FormDraft<TimeTableDraft> {
    override val isValid: Boolean
        get() = name.isValid && startEpochDay.isValid && endEpochDay.isValid &&
                slots.all { it.isValid }

    override fun toValidated(): TimeTableDraft {
        // TODO: 校验是否与其他作息的日期范围重叠
        val nameError = if (name.value.isBlank()) {
            UiText.StringResource(R.string.time_table_name_empty_error)
        } else {
            null
        }
        val dateError = if (!isBase && startEpochDay.value > endEpochDay.value) {
            UiText.StringResource(R.string.time_table_date_range_error)
        } else {
            null
        }

        // 基础校验
        val basicValidated = slots.map { slot ->
            val slotError = if (slot.value.startMinute >= slot.value.endMinute) {
                UiText.StringResource(R.string.time_slot_time_range_error)
            } else {
                null
            }
            slot.copy(errorMessage = slotError)
        }
        // 重叠检测
        val overlapFlags = BooleanArray(basicValidated.size)
        for (i in basicValidated.indices) {
            if (basicValidated[i].errorMessage != null) continue
            val a = basicValidated[i].value
            for (j in i + 1 until basicValidated.size) {
                if (basicValidated[j].errorMessage != null) continue
                val b = basicValidated[j].value
                if (a.startMinute < b.endMinute && b.startMinute < a.endMinute) {
                    overlapFlags[i] = true
                    overlapFlags[j] = true
                }
            }
        }
        val overlapErrorText = UiText.StringResource(R.string.time_slot_overlap_error)
        val withErrors = basicValidated.mapIndexed { i, slot ->
            if (slot.errorMessage == null && overlapFlags[i]) {
                slot.copy(errorMessage = overlapErrorText)
            } else {
                slot
            }
        }

        // 有错误就保持原顺序；只有乱序（无其他错误）才静默排序
        val hasError = withErrors.any { it.errorMessage != null }
        val finalSlots = if (hasError) {
            withErrors
        } else {
            withErrors.sortedBy { it.value.startMinute }
        }
        return copy(
            name = name.copy(errorMessage = nameError),
            startEpochDay = startEpochDay.copy(errorMessage = dateError),
            endEpochDay = endEpochDay.copy(errorMessage = dateError),
            slots = finalSlots
        )
    }
}

sealed class TimeTableEditorState(open val isDirty: Boolean) {
    object Loading : TimeTableEditorState(false)
    object Failed : TimeTableEditorState(false)
    data class Loaded(val draft: TimeTableDraft, override val isDirty: Boolean) :
        TimeTableEditorState(isDirty)
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
            val timeTable = repository.getTimeTable(timeTableId)
            if (timeTable == null) {
                state.value = TimeTableEditorState.Failed
                return@launch
            }
            val draft = TimeTableDraft(
                isBase = timeTable.isBase,
                name = ValidatedField(timeTable.name),
                startEpochDay = ValidatedField(timeTable.startEpochDay),
                endEpochDay = ValidatedField(timeTable.endEpochDay),
                slots = timeTable.slots.map { slot ->
                    ValidatedField(
                        TimeSlotDraft(
                            key = nextKey++,
                            startMinute = slot.startMinute.coerceIn(0, 1438),
                            endMinute = slot.endMinute.coerceIn(1, 1439),
                            alias = slot.alias
                        )
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

    fun updateStartEpochDay(epochDay: Long) {
        updateDraft { draft -> draft.copy(startEpochDay = ValidatedField(epochDay)) }
    }

    fun updateEndEpochDay(epochDay: Long) {
        updateDraft { draft -> draft.copy(endEpochDay = ValidatedField(epochDay)) }
    }

    fun updateSlot(key: Long, transform: (TimeSlotDraft) -> TimeSlotDraft) {
        updateDraft { draft ->
            val updatedSlots = draft.slots.map { slot ->
                if (slot.value.key == key) {
                    slot.copy(value = transform(slot.value))
                } else {
                    slot
                }
            }
            draft.copy(slots = updatedSlots)
        }
    }

    fun addSlot() {
        updateDraft { draft ->
            val last = draft.slots.lastOrNull()?.value
            val start = last?.endMinute?.plus(10) ?: (8 * 60)
            draft.copy(
                slots = draft.slots + ValidatedField(
                    TimeSlotDraft(
                        key = nextKey++,
                        startMinute = start.coerceIn(0, 1438),
                        endMinute = (start + 45).coerceIn(1, 1439),
                        alias = ""
                    )
                )
            )
        }
    }

    fun removeSlot(key: Long) {
        updateDraft { draft -> draft.copy(slots = draft.slots.filterNot { it.value.key == key }) }
    }

    fun save() {
        val loaded = state.value
        if (loaded !is TimeTableEditorState.Loaded) return
        if (!loaded.draft.isValid) return
        viewModelScope.launch {
            val draft = loaded.draft
            repository.upsertTimeTable(
                TimeTable(
                    id = timeTableId,
                    name = draft.name.value.trim(),
                    isBase = draft.isBase,
                    startEpochDay = draft.startEpochDay.value,
                    endEpochDay = draft.endEpochDay.value,
                    slots = draft.slots.mapIndexed { index, slot ->
                        TimeSlot(
                            number = index + 1,
                            startMinute = slot.value.startMinute,
                            endMinute = slot.value.endMinute,
                            alias = slot.value.alias.trim()
                        ).also {
                            require(it.isValid()) { "Invalid time slot: $it" }
                        }
                    }
                )
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
