package com.github739c1ae2.focuslock.ui.screen.course.course

import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.database.CourseTimeSlotEntity
import com.github739c1ae2.focuslock.util.EditorState
import com.github739c1ae2.focuslock.util.UiText
import com.github739c1ae2.focuslock.util.ValidationIssue
import com.github739c1ae2.focuslock.util.ValidationResult
import com.github739c1ae2.focuslock.util.ValidationSeverity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class CourseValidationContext(
    val timeSlots: List<CourseTimeSlotEntity>,
    val totalWeeks: Int,
    val epochDay: Long
)

sealed interface CourseField {
    data object Name : CourseField
    data class SessionTime(val key: Long) : CourseField
    data class SessionWeeks(val key: Long) : CourseField
}

fun validateCourse(draft: CourseDraft, context: CourseValidationContext): ValidationResult<CourseField> {
    val issues = mutableListOf<ValidationIssue<CourseField>>()
    if (draft.name.isBlank()) {
        issues += ValidationIssue(setOf(CourseField.Name), UiText.StringResource(R.string.course_name_empty_error))
    }
    val knownSections = context.timeSlots.map { it.number }.toSet()
    for (session in draft.sessions) {
        val timeField = CourseField.SessionTime(session.key)
        when (val time = session.time) {
            is SessionTimeDraft.Sections -> {
                if (time.startSection > time.endSection) {
                    issues += ValidationIssue(setOf(timeField), UiText.StringResource(R.string.session_section_range_error))
                }
                val unknown = listOfNotNull(time.startSection, time.endSection).toSet() - knownSections
                if (unknown.isNotEmpty()) {
                    issues += ValidationIssue(
                        setOf(timeField),
                        UiText.StringResource(R.string.session_unknown_sections_warning, unknown.sorted().joinToString()),
                        ValidationSeverity.Warning
                    )
                }
            }
            is SessionTimeDraft.Custom -> {
                if (time.startMinute !in 0..1438 || time.endMinute !in 1..1439 ||
                    time.startMinute >= time.endMinute
                ) {
                    issues += ValidationIssue(setOf(timeField), UiText.StringResource(R.string.session_time_range_error))
                }
            }
        }
        val unknownWeeks = session.weeks.filter { it !in 1..context.totalWeeks }
        if (unknownWeeks.isNotEmpty()) {
            issues += ValidationIssue(
                setOf(CourseField.SessionWeeks(session.key)),
                UiText.StringResource(R.string.session_unknown_weeks_warning, unknownWeeks.sorted().joinToString()),
                ValidationSeverity.Warning
            )
        }
    }
    return ValidationResult(issues)
}

internal fun courseEditorStates(
    drafts: Flow<EditorState<CourseDraft>>,
    contexts: Flow<CourseValidationContext?>,
    referenceDates: Flow<Long>
): Flow<CourseEditState> = combine(drafts, contexts, referenceDates) { raw, context, referenceDate ->
    when (raw) {
        EditorState.Loading -> CourseEditState.Loading
        EditorState.Failed -> CourseEditState.Failed
        is EditorState.Loaded -> if (context == null || context.epochDay != referenceDate) {
            CourseEditState.Loading
        } else {
            CourseEditState.Loaded(raw.draft, raw.isDirty, validateCourse(raw.draft, context), context)
        }
    }
}

