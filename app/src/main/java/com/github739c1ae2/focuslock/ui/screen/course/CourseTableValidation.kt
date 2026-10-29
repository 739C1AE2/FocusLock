package com.github739c1ae2.focuslock.ui.screen.course

import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.util.UiText
import com.github739c1ae2.focuslock.util.ValidationIssue
import com.github739c1ae2.focuslock.util.ValidationResult

enum class CourseTableField { Name, TotalWeeks }

fun validateCourseTable(draft: CourseTableDraft): ValidationResult<CourseTableField> {
    val issues = mutableListOf<ValidationIssue<CourseTableField>>()
    if (draft.name.isBlank()) {
        issues += ValidationIssue(setOf(CourseTableField.Name), UiText.StringResource(R.string.table_name_empty_error))
    }
    if (draft.totalWeeks !in 1..60) {
        issues += ValidationIssue(setOf(CourseTableField.TotalWeeks), UiText.StringResource(R.string.semester_total_weeks_invalid_fmt, 1, 60))
    }
    return ValidationResult(issues)
}

