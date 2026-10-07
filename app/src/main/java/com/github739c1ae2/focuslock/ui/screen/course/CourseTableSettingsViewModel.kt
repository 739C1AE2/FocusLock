package com.github739c1ae2.focuslock.ui.screen.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.database.COURSE_TABLE_ID
import com.github739c1ae2.focuslock.database.CourseRepository
import com.github739c1ae2.focuslock.database.CourseTableEntity
import com.github739c1ae2.focuslock.database.CourseTimeTableEntity
import com.github739c1ae2.focuslock.util.FormDraft
import com.github739c1ae2.focuslock.util.UiText
import com.github739c1ae2.focuslock.util.ValidatedField
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
import java.time.LocalDate
import javax.inject.Inject

data class CourseTableDraft(
    val name: ValidatedField<String>,
    val semesterStartEpochDay: Long?,
    val totalWeeks: ValidatedField<Int>,
    val firstDayOfWeek: DayOfWeek
) : FormDraft<CourseTableDraft> {
    override val isValid: Boolean
        get() = name.isValid && totalWeeks.isValid

    override fun toValidated(): CourseTableDraft {
        val nameError = if (name.value.isBlank()) {
            UiText.StringResource(R.string.table_name_empty_error)
        } else {
            null
        }
        val weeksError = if (totalWeeks.value !in 1..60) {
            UiText.StringResource(R.string.semester_total_weeks_error)
        } else {
            null
        }
        return copy(
            name = name.copy(errorMessage = nameError),
            totalWeeks = totalWeeks.copy(errorMessage = weeksError)
        )
    }
}

sealed class TableSettingsState(open val isDirty: Boolean) {
    object Loading : TableSettingsState(false)
    data class Loaded(val draft: CourseTableDraft, override val isDirty: Boolean) : TableSettingsState(isDirty)
}

sealed interface TableSettingsEvent {
    object Saved : TableSettingsEvent
    data class OpenTimeTable(val timeTableId: Long) : TableSettingsEvent
}

@HiltViewModel
class CourseTableSettingsViewModel @Inject constructor(
    private val repository: CourseRepository
) : ViewModel() {

    val state: StateFlow<TableSettingsState>
        field = MutableStateFlow<TableSettingsState>(TableSettingsState.Loading)

    val timeTables: StateFlow<List<CourseTimeTableEntity>> = repository.observeTimeTables()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val eventFlow: SharedFlow<TableSettingsEvent>
        field = MutableSharedFlow<TableSettingsEvent>()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val table = repository.getCourseTable()
            val draft = CourseTableDraft(
                name = ValidatedField(table.name),
                semesterStartEpochDay = table.semesterStartEpochDay,
                totalWeeks = ValidatedField(table.semesterTotalWeeks),
                firstDayOfWeek = table.firstDayOfWeek
            ).toValidated()
            state.value = TableSettingsState.Loaded(draft, isDirty = false)
        }
    }

    fun updateDraft(transform: (CourseTableDraft) -> CourseTableDraft) {
        state.update { current ->
            if (current is TableSettingsState.Loaded) {
                current.copy(draft = transform(current.draft).toValidated(), isDirty = true)
            } else {
                current
            }
        }
    }

    fun save() {
        val loaded = state.value
        if (loaded !is TableSettingsState.Loaded) return
        if (!loaded.draft.isValid) return
        viewModelScope.launch {
            val draft = loaded.draft
            repository.saveCourseTable(
                CourseTableEntity(
                    id = COURSE_TABLE_ID,
                    name = draft.name.value.trim(),
                    semesterStartEpochDay = draft.semesterStartEpochDay,
                    semesterTotalWeeks = draft.totalWeeks.value,
                    firstDayOfWeek = draft.firstDayOfWeek
                )
            )
            eventFlow.emit(TableSettingsEvent.Saved)
            state.value = loaded.copy(isDirty = false)
        }
    }

    fun addSeasonal() {
        viewModelScope.launch {
            val today = LocalDate.now().toEpochDay()
            val id = repository.saveTimeTable(
                CourseTimeTableEntity(
                    courseTableId = COURSE_TABLE_ID,
                    name = "新作息",
                    isBase = false,
                    startEpochDay = today,
                    endEpochDay = today + 30
                ),
                emptyList()
            )
            eventFlow.emit(TableSettingsEvent.OpenTimeTable(id))
        }
    }

    fun deleteTimeTable(id: Long) {
        viewModelScope.launch {
            repository.deleteTimeTable(id)
        }
    }
}
