package com.github739c1ae2.focuslock.ui.screen.course.periods

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.database.CourseRepository
import com.github739c1ae2.focuslock.database.TimeSlot
import com.github739c1ae2.focuslock.database.PeriodSchedule
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

data class TimeSlotDraft(val key: Long, val startMinute: Int, val endMinute: Int, val alias: String)

data class PeriodScheduleDraft(
    val isBase: Boolean,
    val name: String,
    val startEpochDay: Long,
    val endEpochDay: Long,
    val slots: List<TimeSlotDraft>
)

sealed class PeriodScheduleEditorState(open val isDirty: Boolean) {
    data object Loading : PeriodScheduleEditorState(false)
    data object Failed : PeriodScheduleEditorState(false)
    data class Loaded(
        val draft: PeriodScheduleDraft,
        override val isDirty: Boolean,
        val validation: ValidationResult<PeriodScheduleField>
    ) : PeriodScheduleEditorState(isDirty)
}

sealed interface PeriodScheduleEditorEvent {
    data object Saved : PeriodScheduleEditorEvent
    data object Deleted : PeriodScheduleEditorEvent
}

@HiltViewModel(assistedFactory = PeriodScheduleEditorViewModel.Factory::class)
class PeriodScheduleEditorViewModel @AssistedInject constructor(
    private val repository: CourseRepository,
    @Assisted private val periodScheduleId: Long
) : ViewModel() {
    private val drafts = MutableStateFlow<EditorState<PeriodScheduleDraft>>(EditorState.Loading)
    private val context: StateFlow<PeriodScheduleValidationContext?> = repository.observePeriodScheduleEntities()
        .map { PeriodScheduleValidationContext(periodScheduleId, it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<PeriodScheduleEditorState> = periodScheduleEditorStates(drafts, context)
        .stateIn(viewModelScope, SharingStarted.Eagerly, PeriodScheduleEditorState.Loading)

    val eventFlow: SharedFlow<PeriodScheduleEditorEvent>
        field = MutableSharedFlow<PeriodScheduleEditorEvent>()

    private var nextKey = 1L
    private var loadJob: Job? = null

    init { load() }

    fun load() {
        loadJob?.cancel()
        drafts.value = EditorState.Loading
        loadJob = viewModelScope.launch {
            val periodSchedule = repository.getPeriodSchedule(periodScheduleId)
            if (periodSchedule == null) {
                drafts.value = EditorState.Failed
                return@launch
            }
            val draft = PeriodScheduleDraft(
                isBase = periodSchedule.isBase,
                name = periodSchedule.name,
                startEpochDay = periodSchedule.startEpochDay,
                endEpochDay = periodSchedule.endEpochDay,
                slots = periodSchedule.slots.map { slot ->
                    TimeSlotDraft(
                        key = nextKey++,
                        startMinute = slot.startMinute.coerceIn(0, 1438),
                        endMinute = slot.endMinute.coerceIn(1, 1439),
                        alias = slot.alias
                    )
                }
            )
            drafts.value = EditorState.Loaded(normalizePeriodSchedule(draft))
        }
    }

    fun updateDraft(transform: (PeriodScheduleDraft) -> PeriodScheduleDraft) =
        drafts.edit { normalizePeriodSchedule(transform(it)) }

    fun updateStartEpochDay(epochDay: Long) = updateDraft { it.copy(startEpochDay = epochDay) }
    fun updateEndEpochDay(epochDay: Long) = updateDraft { it.copy(endEpochDay = epochDay) }

    fun updateSlot(key: Long, transform: (TimeSlotDraft) -> TimeSlotDraft) = updateDraft { draft ->
        draft.copy(slots = draft.slots.map { if (it.key == key) transform(it) else it })
    }

    fun addSlot() {
        val key = nextKey++
        updateDraft { draft ->
            val start = draft.slots.lastOrNull()?.endMinute?.plus(10) ?: (8 * 60)
            draft.copy(slots = draft.slots + TimeSlotDraft(
                key, start.coerceIn(0, 1438), (start + 45).coerceIn(1, 1439), ""
            ))
        }
    }

    fun removeSlot(key: Long) = updateDraft { draft ->
        draft.copy(slots = draft.slots.filterNot { it.key == key })
    }

    fun save() {
        val currentContext = context.value ?: return
        val snapshot = drafts.validatedSnapshot { validatePeriodSchedule(it, currentContext) } ?: return
        val draft = snapshot.draft
        viewModelScope.launch {
            repository.upsertPeriodSchedule(PeriodSchedule(
                id = periodScheduleId,
                name = draft.name.trim(),
                isBase = draft.isBase,
                startEpochDay = draft.startEpochDay,
                endEpochDay = draft.endEpochDay,
                slots = draft.slots.mapIndexed { index, slot ->
                    TimeSlot(index + 1, slot.startMinute, slot.endMinute, slot.alias.trim())
                }
            ))
            if (drafts.markSaved(snapshot)) {
                eventFlow.emit(PeriodScheduleEditorEvent.Saved)
            }
        }
    }

    fun delete() {
        val snapshot = drafts.value as? EditorState.Loaded ?: return
        if (snapshot.draft.isBase) return
        viewModelScope.launch {
            repository.deletePeriodSchedule(periodScheduleId)
            drafts.value = EditorState.Failed
            eventFlow.emit(PeriodScheduleEditorEvent.Deleted)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(periodScheduleId: Long): PeriodScheduleEditorViewModel
    }
}
