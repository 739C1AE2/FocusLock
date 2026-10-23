package com.github739c1ae2.focuslock.ui.screen.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.database.Course
import com.github739c1ae2.focuslock.database.CourseRepository
import com.github739c1ae2.focuslock.database.CourseSession
import com.github739c1ae2.focuslock.database.CourseSessionTime
import com.github739c1ae2.focuslock.database.CourseTimeSlotEntity
import com.github739c1ae2.focuslock.database.LockRepository
import com.github739c1ae2.focuslock.database.ProfileEntity
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

sealed interface SessionTimeDraft {
    data class Sections(val startSection: Int? = null, val endSection: Int? = null) : SessionTimeDraft
    data class Custom(val startMinute: Int = 8 * 60, val endMinute: Int = 9 * 60) : SessionTimeDraft
}

// TODO: 添加引用未知节次的警告
// TODO: 添加引用未知周次的警告
data class SessionDraft(
    val key: Long,
    val teacher: String = "",
    val position: String = "",
    val dayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    val time: SessionTimeDraft = SessionTimeDraft.Sections(),
    val weeks: Set<Int> = emptySet(),
    val errorMessage: UiText? = null
) : FormDraft<SessionDraft> {
    override val isValid: Boolean
        get() = errorMessage == null

    override fun toValidated(): SessionDraft {
        val error = when (val time = time) {
            is SessionTimeDraft.Sections ->
                if (time.startSection == null || time.endSection == null) {
                    UiText.StringResource(R.string.session_section_required_error)
                } else {
                    null
                }

            is SessionTimeDraft.Custom ->
                if (time.startMinute >= time.endMinute) {
                    UiText.StringResource(R.string.session_time_range_error)
                } else {
                    null
                }
        }
        return copy(errorMessage = error)
    }
}

data class CourseDraft(
    val id: Long,
    val name: ValidatedField<String>,
    val profileId: Long,
    val sessions: List<SessionDraft>
) : FormDraft<CourseDraft> {
    override val isValid: Boolean
        get() = name.isValid && sessions.all { it.isValid }

    override fun toValidated(): CourseDraft {
        val nameError = if (name.value.isBlank()) {
            UiText.StringResource(R.string.course_name_empty_error)
        } else {
            null
        }
        return copy(
            name = name.copy(errorMessage = nameError),
            sessions = sessions.map { it.toValidated() }
        )
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

    val profiles: StateFlow<List<ProfileEntity>> = lockRepository.observeAllProfileEntities()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val timeSlots: StateFlow<List<CourseTimeSlotEntity>>
        field = MutableStateFlow(emptyList())

    val totalWeeks: StateFlow<Int> = courseRepository.observeCourseTables()
        .map { it.semesterTotalWeeks }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 20)

    private var nextKey = 1L

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val epochDay = LocalDate.now().toEpochDay()
            timeSlots.value = courseRepository.getEffectiveTimeSlots(epochDay)
            val course = courseRepository.getCourse(courseId)
            if (course == null) {
                state.value = CourseEditState.Failed
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

                        is CourseSessionTime.Custom ->
                            SessionTimeDraft.Custom(
                                time.startMinute.coerceIn(0, 1438),
                                time.endMinute.coerceIn(1, 1439)
                            )
                    },
                    weeks = session.weeks
                )
            }.ifEmpty { listOf(newSession()) }

            val draft = CourseDraft(
                id = course.id,
                name = ValidatedField(course.name),
                profileId = course.profileId,
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
            val course = Course(
                id = draft.id,
                name = draft.name.value.trim(),
                profileId = draft.profileId,
                sessions = draft.sessions.map { session ->
                    val time = when (val time = session.time) {
                        is SessionTimeDraft.Sections -> CourseSessionTime.Sections(
                            startSection = requireNotNull(time.startSection),
                            endSection = requireNotNull(time.endSection)
                        )

                        is SessionTimeDraft.Custom -> CourseSessionTime.Custom(
                            startMinute = time.startMinute,
                            endMinute = time.endMinute
                        )
                    }
                    require(time.isValid()) { "Invalid session time" }
                    CourseSession(
                        teacher = session.teacher,
                        position = session.position,
                        dayOfWeek = session.dayOfWeek,
                        time = time,
                        weeks = session.weeks
                    )
                }
            )
            courseRepository.upsertCourse(course)
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
