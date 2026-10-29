package com.github739c1ae2.focuslock.ui.screen.schedule

import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.util.UiText
import com.github739c1ae2.focuslock.util.ValidationIssue
import com.github739c1ae2.focuslock.util.ValidationResult

enum class ScheduleField { Name, StartTime, EndTime }

fun validateSchedule(draft: ScheduleDraft): ValidationResult<ScheduleField> {
    val issues = mutableListOf<ValidationIssue<ScheduleField>>()
    if (draft.name.isBlank()) {
        issues += ValidationIssue(setOf(ScheduleField.Name), UiText.StringResource(R.string.schedule_name_empty_error))
    }
    if (draft.startMinute == draft.endMinute) {
        issues += ValidationIssue(
            setOf(ScheduleField.StartTime, ScheduleField.EndTime),
            UiText.StringResource(R.string.schedule_time_equal_error)
        )
    }
    return ValidationResult(issues)
}

