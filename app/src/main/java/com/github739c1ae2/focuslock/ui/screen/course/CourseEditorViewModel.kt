package com.github739c1ae2.focuslock.ui.screen.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.database.Course
import com.github739c1ae2.focuslock.database.CourseRepository
import com.github739c1ae2.focuslock.database.CourseSession
import com.github739c1ae2.focuslock.database.CourseSessionTime
import com.github739c1ae2.focuslock.database.LockRepository
import com.github739c1ae2.focuslock.database.ProfileEntity
import com.github739c1ae2.focuslock.util.EditorState
import com.github739c1ae2.focuslock.util.ValidationResult
import com.github739c1ae2.focuslock.util.edit
import com.github739c1ae2.focuslock.util.markSaved
import com.github739c1ae2.focuslock.util.validatedSnapshot
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

sealed interface SessionTimeDraft {
    data class Sections(val startSection: Int = 1, val endSection: Int = 1) : SessionTimeDraft
    data class Custom(val startMinute: Int = 8 * 60, val endMinute: Int = 9 * 60) : SessionTimeDraft
}

data class SessionDraft(
    val key: Long,
    val teacher: String = "",
    val position: String = "",
    val dayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    val time: SessionTimeDraft = SessionTimeDraft.Sections(),
    val weeks: Set<Int> = emptySet()
)

data class CourseDraft(
    val id: Long,
    val name: String,
    val profileId: Long,
    val sessions: List<SessionDraft>
)

sealed class CourseEditState(open val isDirty: Boolean) {
    data object Loading : CourseEditState(false)
    data object Failed : CourseEditState(false)
    data class Loaded(
        val draft: CourseDraft,
        override val isDirty: Boolean,
        val validation: ValidationResult<CourseField>,
        val context: CourseValidationContext
    ) : CourseEditState(isDirty)
}

sealed interface CourseEditEvent {
    data object Saved : CourseEditEvent
    data object Deleted : CourseEditEvent
    data class ProfileCreated(val profileId: Long) : CourseEditEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = CourseEditorViewModel.Factory::class)
class CourseEditorViewModel @AssistedInject constructor(
    private val courseRepository: CourseRepository,
    private val lockRepository: LockRepository,
    @Assisted private val courseId: Long
) : ViewModel() {
    private val drafts = MutableStateFlow<EditorState<CourseDraft>>(EditorState.Loading)
    private val referenceDate = MutableStateFlow(LocalDate.now().toEpochDay())
    private val context: StateFlow<CourseValidationContext?> = referenceDate.flatMapLatest { day ->
        combine(
            courseRepository.observeEffectiveTimeSlots(day),
            courseRepository.observeCourseTables()
        ) { slots, table -> CourseValidationContext(slots, table.semesterTotalWeeks, day) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<CourseEditState> = courseEditorStates(drafts, context, referenceDate)
        .stateIn(viewModelScope, SharingStarted.Eagerly, CourseEditState.Loading)

    val eventFlow: SharedFlow<CourseEditEvent>
        field = MutableSharedFlow<CourseEditEvent>()

    val profiles: StateFlow<List<ProfileEntity>> = lockRepository.observeAllProfileEntities()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var nextKey = 1L
    private var loadJob: Job? = null

    init { load() }

    fun load() {
        loadJob?.cancel()
        drafts.value = EditorState.Loading
        referenceDate.value = LocalDate.now().toEpochDay()
        loadJob = viewModelScope.launch {
            val course = courseRepository.getCourse(courseId)
            if (course == null) {
                drafts.value = EditorState.Failed
                return@launch
            }
            val sessions = course.sessions.map { session ->
                SessionDraft(
                    key = nextKey++,
                    teacher = session.teacher,
                    position = session.position,
                    dayOfWeek = session.dayOfWeek,
                    time = when (val time = session.time) {
                        is CourseSessionTime.Sections ->
                            SessionTimeDraft.Sections(time.startSection, time.endSection)
                        is CourseSessionTime.Custom -> SessionTimeDraft.Custom(
                            time.startMinute.coerceIn(0, 1438), time.endMinute.coerceIn(1, 1439)
                        )
                    },
                    weeks = session.weeks
                )
            }.ifEmpty { listOf(newSession()) }
            drafts.value = EditorState.Loaded(CourseDraft(course.id, course.name, course.profileId, sessions))
        }
    }

    private fun newSession(): SessionDraft = SessionDraft(key = nextKey++)

    fun updateDraft(transform: (CourseDraft) -> CourseDraft) = drafts.edit(transform)

    fun addSession() {
        val session = newSession()
        updateDraft { it.copy(sessions = it.sessions + session) }
    }

    fun removeSession(key: Long) = updateDraft { draft ->
        draft.copy(sessions = draft.sessions.filterNot { it.key == key })
    }

    fun updateSession(key: Long, transform: (SessionDraft) -> SessionDraft) = updateDraft { draft ->
        draft.copy(sessions = draft.sessions.map { if (it.key == key) transform(it) else it })
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
        val currentContext = context.value ?: return
        if (currentContext.epochDay != referenceDate.value) return
        val snapshot = drafts.validatedSnapshot { validateCourse(it, currentContext) } ?: return
        val draft = snapshot.draft
        viewModelScope.launch {
            val course = Course(
                id = draft.id,
                name = draft.name.trim(),
                profileId = draft.profileId,
                sessions = draft.sessions.map { session ->
                    val time = when (val time = session.time) {
                        is SessionTimeDraft.Sections -> CourseSessionTime.Sections(
                            time.startSection, time.endSection
                        )
                        is SessionTimeDraft.Custom -> CourseSessionTime.Custom(time.startMinute, time.endMinute)
                    }
                    CourseSession(session.teacher, session.position, session.dayOfWeek, time, session.weeks)
                }
            )
            courseRepository.upsertCourse(course)
            if (drafts.markSaved(snapshot)) {
                eventFlow.emit(CourseEditEvent.Saved)
            }
        }
    }

    fun delete() {
        val snapshot = drafts.value as? EditorState.Loaded ?: return
        viewModelScope.launch {
            courseRepository.deleteCourse(snapshot.draft.id)
            drafts.value = EditorState.Failed
            eventFlow.emit(CourseEditEvent.Deleted)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(courseId: Long): CourseEditorViewModel
    }
}
