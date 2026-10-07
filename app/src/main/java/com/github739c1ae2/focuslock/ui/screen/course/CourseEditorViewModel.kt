package com.github739c1ae2.focuslock.ui.screen.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.database.CourseRepository
import com.github739c1ae2.focuslock.database.CourseEntity
import com.github739c1ae2.focuslock.database.CourseSessionEntity
import com.github739c1ae2.focuslock.database.CourseTimeSlotEntity
import com.github739c1ae2.focuslock.database.LockRepository
import com.github739c1ae2.focuslock.database.ProfileEntity
import com.github739c1ae2.focuslock.engine.CourseScheduleResolver
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

data class SessionDraft(
    val key: Long,
    val dayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    val isCustomTime: Boolean = false,
    val startSection: Int? = null,
    val endSection: Int? = null,
    val startMinute: Int = 8 * 60,
    val endMinute: Int = 9 * 60,
    val weeks: Set<Int> = emptySet()
) {
    val isValid: Boolean
        get() = if (isCustomTime) {
            startMinute != endMinute
        } else {
            startSection != null && endSection != null
        }
}

data class CourseDraft(
    val id: Long,
    val name: ValidatedField<String>,
    val teacher: String,
    val position: String,
    val remark: String,
    val profileId: Long,
    val isActive: Boolean,
    val sessions: List<SessionDraft>
) : FormDraft<CourseDraft> {
    override val isValid: Boolean
        get() = name.isValid && sessions.isNotEmpty() && sessions.all { it.isValid }

    override fun toValidated(): CourseDraft {
        val nameError = if (name.value.isBlank()) {
            UiText.StringResource(R.string.course_name_empty_error)
        } else null
        return copy(name = name.copy(errorMessage = nameError))
    }
}

sealed class CourseEditState(open val isDirty: Boolean) {
    object Loading : CourseEditState(false)
    object Failed : CourseEditState(false)
    data class Loaded(val draft: CourseDraft, override val isDirty: Boolean) : CourseEditState(isDirty)
}

sealed interface CourseEditEvent {
    object Saved : CourseEditEvent
    object Deleted : CourseEditEvent
    data class ProfileCreated(val profileId: Long) : CourseEditEvent
}

@HiltViewModel(assistedFactory = CourseEditorViewModel.Factory::class)
class CourseEditorViewModel @AssistedInject constructor(
    private val courseRepository: CourseRepository,
    private val lockRepository: LockRepository,
    @Assisted private val courseId: Long
) : ViewModel() {

    val state: StateFlow<CourseEditState>
        field = MutableStateFlow<CourseEditState>(CourseEditState.Loading)

    val eventFlow: SharedFlow<CourseEditEvent>
        field = MutableSharedFlow<CourseEditEvent>()

    val profiles: StateFlow<List<ProfileEntity>> = lockRepository.observeAllProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val timeSlots: StateFlow<List<CourseTimeSlotEntity>> = combine(
        courseRepository.observeTimeTables(),
        courseRepository.observeAllTimeSlots()
    ) { timeTables, slots ->
        val baseTimeTableId = timeTables.firstOrNull { it.isBase }?.id ?: return@combine emptyList()
        CourseScheduleResolver.effectiveTimeSlots(
            baseTimeTableId = baseTimeTableId,
            timeTables = timeTables,
            slots = slots,
            epochDay = LocalDate.now().toEpochDay()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalWeeks: StateFlow<Int> = courseRepository.observeCourseTable()
        .map { it.semesterTotalWeeks }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 20)

    private var nextKey = 1L

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val loaded = courseRepository.getCourseWithSessions(courseId)
            if (loaded == null) {
                state.value = CourseEditState.Failed
                return@launch
            }
            val course = loaded.course
            val sessions = loaded.sessions.map { session ->
                SessionDraft(
                    key = nextKey++,
                    dayOfWeek = session.dayOfWeek,
                    isCustomTime = session.isCustomTime,
                    startSection = session.startSection,
                    endSection = session.endSection,
                    startMinute = session.customStartMinute ?: 8 * 60,
                    endMinute = session.customEndMinute ?: 9 * 60,
                    weeks = session.weeks
                )
            }.ifEmpty { listOf(newSession()) }

            val draft = CourseDraft(
                id = course.id,
                name = ValidatedField(course.name),
                teacher = course.teacher,
                position = course.position,
                remark = course.remark.orEmpty(),
                profileId = course.profileId,
                isActive = course.isActive,
                sessions = sessions
            ).toValidated()
            state.value = CourseEditState.Loaded(draft, isDirty = false)
        }
    }

    private fun newSession(): SessionDraft = SessionDraft(key = nextKey++)

    fun updateDraft(transform: (CourseDraft) -> CourseDraft) {
        state.update { current ->
            if (current is CourseEditState.Loaded) {
                current.copy(draft = transform(current.draft).toValidated(), isDirty = true)
            } else current
        }
    }

    fun addSession() {
        updateDraft { it.copy(sessions = it.sessions + newSession()) }
    }

    fun removeSession(key: Long) {
        updateDraft { draft ->
            draft.copy(sessions = draft.sessions.filterNot { it.key == key })
        }
    }

    fun updateSession(key: Long, transform: (SessionDraft) -> SessionDraft) {
        updateDraft { draft ->
            draft.copy(sessions = draft.sessions.map { if (it.key == key) transform(it) else it })
        }
    }

    fun createProfile(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = lockRepository.createProfile(name)
            updateDraft { it.copy(profileId = id) }
            eventFlow.emit(CourseEditEvent.ProfileCreated(id))
        }
    }

    fun save() {
        val current = state.value
        if (current !is CourseEditState.Loaded) return
        val draft = current.draft
        if (!draft.isValid) return
        viewModelScope.launch {
            val course = CourseEntity(
                id = draft.id,
                name = draft.name.value.trim(),
                teacher = draft.teacher.trim(),
                position = draft.position.trim(),
                remark = draft.remark.trim().ifBlank { null },
                profileId = draft.profileId,
                isActive = draft.isActive
            )
            val sessions = draft.sessions.map { session ->
                CourseSessionEntity(
                    courseId = draft.id,
                    dayOfWeek = session.dayOfWeek,
                    isCustomTime = session.isCustomTime,
                    startSection = if (session.isCustomTime) null else session.startSection,
                    endSection = if (session.isCustomTime) null else session.endSection,
                    customStartMinute = if (session.isCustomTime) session.startMinute else null,
                    customEndMinute = if (session.isCustomTime) session.endMinute else null,
                    weeks = session.weeks
                )
            }
            courseRepository.saveCourse(course, sessions)
            state.value = current.copy(isDirty = false)
            eventFlow.emit(CourseEditEvent.Saved)
        }
    }

    fun delete() {
        val current = state.value
        if (current !is CourseEditState.Loaded) return
        viewModelScope.launch {
            courseRepository.deleteCourse(current.draft.id)
            state.value = CourseEditState.Failed
            eventFlow.emit(CourseEditEvent.Deleted)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(courseId: Long): CourseEditorViewModel
    }
}
