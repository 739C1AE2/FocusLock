package com.github739c1ae2.focuslock.ui.screen.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.database.COURSE_TABLE_ID
import com.github739c1ae2.focuslock.database.CourseRepository
import com.github739c1ae2.focuslock.database.CourseTableEntity
import com.github739c1ae2.focuslock.database.CourseTimeTableEntity
import com.github739c1ae2.focuslock.util.EditorState
import com.github739c1ae2.focuslock.util.ValidationResult
import com.github739c1ae2.focuslock.util.edit
import com.github739c1ae2.focuslock.util.markSaved
import com.github739c1ae2.focuslock.util.validatedSnapshot
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
import java.time.LocalDate
import javax.inject.Inject

data class CourseTableDraft(
    val name: String,
    val semesterStartEpochDay: Long?,
    val totalWeeks: Int,
    val firstDayOfWeek: DayOfWeek
)

sealed class TableSettingsState(open val isDirty: Boolean) {
    object Loading : TableSettingsState(false)
    data class Loaded(
        val draft: CourseTableDraft,
        override val isDirty: Boolean,
        val validation: ValidationResult<CourseTableField>
    ) : TableSettingsState(isDirty)
}

sealed interface TableSettingsEvent {
    object Saved : TableSettingsEvent
    data class OpenTimeTable(val timeTableId: Long) : TableSettingsEvent
}

@HiltViewModel
class CourseTableSettingsViewModel @Inject constructor(
    private val repository: CourseRepository
) : ViewModel() {

    private val drafts = MutableStateFlow<EditorState<CourseTableDraft>>(EditorState.Loading)

    val state: StateFlow<TableSettingsState> = drafts.map { raw ->
        when (raw) {
            EditorState.Loading, EditorState.Failed -> TableSettingsState.Loading
            is EditorState.Loaded -> TableSettingsState.Loaded(raw.draft, raw.isDirty, validateCourseTable(raw.draft))
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TableSettingsState.Loading)

    val timeTables: StateFlow<List<CourseTimeTableEntity>> = repository.observeTimeTableEntities()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val eventFlow: SharedFlow<TableSettingsEvent>
        field = MutableSharedFlow<TableSettingsEvent>()

    private var loadJob: Job? = null

    init {
        load()
    }

    fun load() {
        loadJob?.cancel()
        drafts.value = EditorState.Loading
        loadJob = viewModelScope.launch {
            val table = repository.getCourseTable()
            val draft = CourseTableDraft(
                name = table.name,
                semesterStartEpochDay = table.semesterStartEpochDay,
                totalWeeks = table.semesterTotalWeeks,
                firstDayOfWeek = table.firstDayOfWeek
            )
            drafts.value = EditorState.Loaded(draft, isDirty = false)
        }
    }

    fun updateDraft(transform: (CourseTableDraft) -> CourseTableDraft) = drafts.edit(transform)

    fun save() {
        val loaded = drafts.validatedSnapshot(::validateCourseTable) ?: return
        viewModelScope.launch {
            val draft = loaded.draft
            repository.updateCourseTable(
                CourseTableEntity(
                    id = COURSE_TABLE_ID,
                    name = draft.name.trim(),
                    semesterStartEpochDay = draft.semesterStartEpochDay,
                    semesterTotalWeeks = draft.totalWeeks,
                    firstDayOfWeek = draft.firstDayOfWeek
                )
            )
            if (drafts.markSaved(loaded)) {
                eventFlow.emit(TableSettingsEvent.Saved)
            }
        }
    }

    fun addTimeTable(name: String) {
        viewModelScope.launch {
            val today = LocalDate.now().toEpochDay()
            val id = repository.createTimeTable(
                name = name,
                startEpochDay = today,
                endEpochDay = today + 30
            )
            eventFlow.emit(TableSettingsEvent.OpenTimeTable(id))
        }
    }

}
