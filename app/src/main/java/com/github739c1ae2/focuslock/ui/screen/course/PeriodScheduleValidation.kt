package com.github739c1ae2.focuslock.ui.screen.course

import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.database.CoursePeriodScheduleEntity
import com.github739c1ae2.focuslock.util.EditorState
import com.github739c1ae2.focuslock.util.UiText
import com.github739c1ae2.focuslock.util.ValidationIssue
import com.github739c1ae2.focuslock.util.ValidationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

data class PeriodScheduleValidationContext(val periodScheduleId: Long, val periodSchedules: List<CoursePeriodScheduleEntity>)

sealed interface PeriodScheduleField {
    data object Name : PeriodScheduleField
    data object StartDate : PeriodScheduleField
    data object EndDate : PeriodScheduleField
    data class Slot(val key: Long) : PeriodScheduleField
}

private fun validateSlots(slots: List<TimeSlotDraft>): List<ValidationIssue<PeriodScheduleField>> {
    val issues = mutableListOf<ValidationIssue<PeriodScheduleField>>()
    val validSlots = slots.filter { slot ->
        val valid = slot.startMinute in 0..1438 && slot.endMinute in 1..1439 &&
            slot.startMinute < slot.endMinute
        if (!valid) {
            issues += ValidationIssue(
                setOf(PeriodScheduleField.Slot(slot.key)), UiText.StringResource(R.string.time_slot_time_range_error)
            )
        }
        valid
    }
    val overlapping = mutableSetOf<PeriodScheduleField>()
    for (i in validSlots.indices) {
        val a = validSlots[i]
        for (j in i + 1 until validSlots.size) {
            val b = validSlots[j]
            if (a.startMinute < b.endMinute && b.startMinute < a.endMinute) {
                overlapping += PeriodScheduleField.Slot(a.key)
                overlapping += PeriodScheduleField.Slot(b.key)
            }
        }
    }
    if (overlapping.isNotEmpty()) {
        issues += ValidationIssue(overlapping, UiText.StringResource(R.string.time_slot_overlap_error))
    }
    return issues
}

fun validatePeriodSchedule(
    draft: PeriodScheduleDraft,
    context: PeriodScheduleValidationContext
): ValidationResult<PeriodScheduleField> {
    val issues = mutableListOf<ValidationIssue<PeriodScheduleField>>()
    if (draft.name.isBlank()) {
        issues += ValidationIssue(setOf(PeriodScheduleField.Name), UiText.StringResource(R.string.period_schedule_name_empty_error))
    }
    if (!draft.isBase) {
        val dates = setOf(PeriodScheduleField.StartDate, PeriodScheduleField.EndDate)
        if (draft.startEpochDay > draft.endEpochDay) {
            issues += ValidationIssue(dates, UiText.StringResource(R.string.period_schedule_date_range_error))
        } else if (context.periodSchedules.any { other ->
            !other.isBase && other.id != context.periodScheduleId &&
                draft.startEpochDay <= other.endEpochDay && other.startEpochDay <= draft.endEpochDay
        }) {
            issues += ValidationIssue(dates, UiText.StringResource(R.string.period_schedule_date_overlap_error))
        }
    }
    issues += validateSlots(draft.slots)
    return ValidationResult(issues)
}

/** Preserve editing order while a slot is invalid; validation itself never changes inputs. */
fun normalizePeriodSchedule(draft: PeriodScheduleDraft): PeriodScheduleDraft =
    if (validateSlots(draft.slots).isEmpty()) {
        draft.copy(slots = draft.slots.sortedBy { it.startMinute })
    } else draft

internal fun periodScheduleEditorStates(
    drafts: Flow<EditorState<PeriodScheduleDraft>>,
    contexts: Flow<PeriodScheduleValidationContext?>
): Flow<PeriodScheduleEditorState> = combine(drafts, contexts) { raw, context ->
    when (raw) {
        EditorState.Loading -> PeriodScheduleEditorState.Loading
        EditorState.Failed -> PeriodScheduleEditorState.Failed
        is EditorState.Loaded -> if (context == null) {
            PeriodScheduleEditorState.Loading
        } else {
            PeriodScheduleEditorState.Loaded(raw.draft, raw.isDirty, validatePeriodSchedule(raw.draft, context))
        }
    }
}

