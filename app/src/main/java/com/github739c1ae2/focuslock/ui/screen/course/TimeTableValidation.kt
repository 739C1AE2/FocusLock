package com.github739c1ae2.focuslock.ui.screen.course

import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.database.CourseTimeTableEntity
import com.github739c1ae2.focuslock.util.EditorState
import com.github739c1ae2.focuslock.util.UiText
import com.github739c1ae2.focuslock.util.ValidationIssue
import com.github739c1ae2.focuslock.util.ValidationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class TimeTableValidationContext(val timeTableId: Long, val timeTables: List<CourseTimeTableEntity>)

sealed interface TimeTableField {
    data object Name : TimeTableField
    data object StartDate : TimeTableField
    data object EndDate : TimeTableField
    data class Slot(val key: Long) : TimeTableField
}

private fun validateSlots(slots: List<TimeSlotDraft>): List<ValidationIssue<TimeTableField>> {
    val issues = mutableListOf<ValidationIssue<TimeTableField>>()
    val validSlots = slots.filter { slot ->
        val valid = slot.startMinute in 0..1438 && slot.endMinute in 1..1439 &&
            slot.startMinute < slot.endMinute
        if (!valid) {
            issues += ValidationIssue(
                setOf(TimeTableField.Slot(slot.key)), UiText.StringResource(R.string.time_slot_time_range_error)
            )
        }
        valid
    }
    val overlapping = mutableSetOf<TimeTableField>()
    for (i in validSlots.indices) {
        val a = validSlots[i]
        for (j in i + 1 until validSlots.size) {
            val b = validSlots[j]
            if (a.startMinute < b.endMinute && b.startMinute < a.endMinute) {
                overlapping += TimeTableField.Slot(a.key)
                overlapping += TimeTableField.Slot(b.key)
            }
        }
    }
    if (overlapping.isNotEmpty()) {
        issues += ValidationIssue(overlapping, UiText.StringResource(R.string.time_slot_overlap_error))
    }
    return issues
}

fun validateTimeTable(
    draft: TimeTableDraft,
    context: TimeTableValidationContext
): ValidationResult<TimeTableField> {
    val issues = mutableListOf<ValidationIssue<TimeTableField>>()
    if (draft.name.isBlank()) {
        issues += ValidationIssue(setOf(TimeTableField.Name), UiText.StringResource(R.string.time_table_name_empty_error))
    }
    if (!draft.isBase) {
        val dates = setOf(TimeTableField.StartDate, TimeTableField.EndDate)
        if (draft.startEpochDay > draft.endEpochDay) {
            issues += ValidationIssue(dates, UiText.StringResource(R.string.time_table_date_range_error))
        } else if (context.timeTables.any { other ->
            !other.isBase && other.id != context.timeTableId &&
                draft.startEpochDay <= other.endEpochDay && other.startEpochDay <= draft.endEpochDay
        }) {
            issues += ValidationIssue(dates, UiText.StringResource(R.string.time_table_date_overlap_error))
        }
    }
    issues += validateSlots(draft.slots)
    return ValidationResult(issues)
}

/** Preserve editing order while a slot is invalid; validation itself never changes inputs. */
fun normalizeTimeTable(draft: TimeTableDraft): TimeTableDraft =
    if (validateSlots(draft.slots).isEmpty()) {
        draft.copy(slots = draft.slots.sortedBy { it.startMinute })
    } else draft

internal fun timeTableEditorStates(
    drafts: Flow<EditorState<TimeTableDraft>>,
    contexts: Flow<TimeTableValidationContext?>
): Flow<TimeTableEditorState> = combine(drafts, contexts) { raw, context ->
    when (raw) {
        EditorState.Loading -> TimeTableEditorState.Loading
        EditorState.Failed -> TimeTableEditorState.Failed
        is EditorState.Loaded -> if (context == null) {
            TimeTableEditorState.Loading
        } else {
            TimeTableEditorState.Loaded(raw.draft, raw.isDirty, validateTimeTable(raw.draft, context))
        }
    }
}

