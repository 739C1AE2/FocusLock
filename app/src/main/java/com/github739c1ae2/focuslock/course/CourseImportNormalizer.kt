package com.github739c1ae2.focuslock.course

import com.github739c1ae2.focuslock.database.CourseSessionTime
import com.github739c1ae2.focuslock.database.TimeSlot
import java.time.LocalDate
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.iterator


data class NormalizeConfig(
    val mergeCoursesByName: Boolean = true
)

object CourseImportNormalizer {

    fun normalize(
        input: CourseTableImport,
        config: NormalizeConfig,
    ): CourseTableParseResult.Success {
        val issues = mutableListOf<CourseImportIssue.Normalization>()
        val courses = normalizeCourses(input.courses, issues)

        val totalWeeks = normalizeTotalWeeks(input.totalWeeks, courses, issues)

        val minEpochDay = LocalDate.MIN.toEpochDay()
        val maxEpochDay = LocalDate.MAX.toEpochDay()

        val semesterStartEpochDay = input.semesterStartEpochDay
            ?.takeIf { it in minEpochDay..maxEpochDay }
            .also {
                val original = input.semesterStartEpochDay
                if (original != null && it == null) {
                    issues += CourseImportIssue.InvalidSemesterStartEpochDayRemoved
                }
            }

        val todayEpochDay = LocalDate.now().toEpochDay()
        val fallbackEpochDay = (
                (semesterStartEpochDay ?: todayEpochDay) - 1L
                ).coerceAtLeast(minEpochDay)

        val baseSlots = normalizeSlots(
            input.basePeriodSchedule.slots,
            input.basePeriodSchedule.name,
            issues
        ).toMutableList()

        val seasons = input.seasonalTables.map { table ->
            val start = table.startEpochDay
            val end = table.endEpochDay

            val valid = start in minEpochDay..maxEpochDay &&
                    end in minEpochDay..maxEpochDay &&
                    start <= end

            val normalized = if (valid) {
                table
            } else {
                issues += CourseImportIssue.InvalidSeasonDateRangeFallback(
                    periodScheduleName = table.name,
                    adjustedStartEpochDay = fallbackEpochDay,
                    adjustedEndEpochDay = fallbackEpochDay
                )

                table.copy(
                    startEpochDay = fallbackEpochDay,
                    endEpochDay = fallbackEpochDay
                )
            }

            normalized.copy(
                slots = normalizeSlots(normalized.slots, normalized.name, issues)
            )
        }.toMutableList()

        // 只按原始编号对齐。遇到内部缺口时清空对应列表。
        reconcileSectionRows(
            basePeriodScheduleName = input.basePeriodSchedule.name,
            baseSlots = baseSlots,
            seasons = seasons,
            courses = courses,
            issues = issues
        )

        // 对齐完成后再移除空的季节作息。
        val validSeasons = seasons.filter {
            val empty = it.slots.isEmpty()
            if (empty) {
                issues += CourseImportIssue.EmptySeasonalPeriodScheduleRemoved(
                    periodScheduleName = it.name
                )
            }
            !empty
        }

        val normalizedBasePeriodSchedule: PeriodScheduleImport
        val normalizedSeasons: List<PeriodScheduleImport>
        val normalizedCourses: List<CourseImport>

        if (baseSlots.isEmpty() && validSeasons.isEmpty()) {
            val shifted = courses.shiftWithoutPeriodSchedules()
            normalizedBasePeriodSchedule = input.basePeriodSchedule.copy(slots = emptyList())
            normalizedSeasons = emptyList()
            normalizedCourses = shifted
            issues += CourseImportIssue.NoValidPeriodSchedule
        } else {
            // 统一重编号。
            val mapping = buildSet {
                addAll(baseSlots.map { it.number })
                validSeasons.forEach { table ->
                    addAll(table.slots.map { it.number })
                }
                addAll(referencedNumbers(courses))
            }
                .sorted()
                .mapIndexed { index, number -> number to index + 1 }
                .toMap()

            normalizedBasePeriodSchedule = input.basePeriodSchedule.copy(
                slots = baseSlots.remapNumbers(mapping)
            )

            normalizedSeasons = validSeasons.map { table ->
                table.copy(slots = table.slots.remapNumbers(mapping))
            }

            normalizedCourses = remapCourses(courses, mapping)

            val defined = buildSet {
                addAll(normalizedBasePeriodSchedule.slots.map { it.number })
                normalizedSeasons.forEach { table ->
                    addAll(table.slots.map { it.number })
                }
            }

            val unknown = referencedNumbers(normalizedCourses)
                .filterNot { it in defined }
                .sorted()

            if (unknown.isNotEmpty()) {
                issues += CourseImportIssue.UnknownSectionReferences(unknown)
            }
        }
        var data = input.copy(
            semesterStartEpochDay = semesterStartEpochDay,
            totalWeeks = totalWeeks,
            basePeriodSchedule = normalizedBasePeriodSchedule,
            seasonalTables = normalizedSeasons,
            courses = normalizedCourses
        )
        if (config.mergeCoursesByName) {
            data = data.mergeCoursesByName()
        }
        return CourseTableParseResult.Success(
            data = data,
            issues = issues
        )
    }

    private fun normalizeSlots(
        slots: List<TimeSlot>,
        name: String,
        issues: MutableList<CourseImportIssue.Normalization>
    ): List<TimeSlot> {
        val valid = slots.filter {
            val valid = it.isValid()
            if (!valid) {
                issues += CourseImportIssue.InvalidSlotRemoved(
                    periodScheduleName = name,
                    slot = it
                )
            }
            valid
        }

        // 同编号、同时间：合并重复项。
        // 同编号、不同时间：删除该编号的全部冲突项。
        val conflicting = sortedSetOf<Int>()
        val unique = mutableListOf<TimeSlot>()

        for ((number, group) in valid.groupBy { it.number }) {
            val intervals = group
                .map { it.startMinute to it.endMinute }
                .distinct()

            if (intervals.size > 1) {
                conflicting += number
            } else {
                unique += group.first().copy(
                    alias = group.firstOrNull { it.alias.isNotBlank() }
                        ?.alias
                        .orEmpty()
                )
            }
        }

        if (conflicting.isNotEmpty()) {
            issues += CourseImportIssue.ConflictingSlotsNumbersRemoved(
                periodScheduleName = name,
                numbers = conflicting.toList()
            )
        }

        // 删除所有时间重叠的节次。
        val overlapping = sortedSetOf<Int>()
        for (i in unique.indices) {
            for (j in i + 1 until unique.size) {
                val a = unique[i]
                val b = unique[j]

                if (
                    a.startMinute < b.endMinute &&
                    b.startMinute < a.endMinute
                ) {
                    overlapping += a.number
                    overlapping += b.number
                }
            }
        }
        if (overlapping.isNotEmpty()) {
            issues += CourseImportIssue.OverlappingSlotsRemoved(
                periodScheduleName = name,
                numbers = overlapping.toList()
            )
        }

        val remaining = unique
            .filterNot { it.number in overlapping }
            .sortedBy { it.number }

        // 编号顺序与时间顺序冲突时，删除所有涉及的节次。
        val outOfOrder = sortedSetOf<Int>()
        for (i in remaining.indices) {
            for (j in i + 1 until remaining.size) {
                if (
                    remaining[i].startMinute >=
                    remaining[j].startMinute
                ) {
                    outOfOrder += remaining[i].number
                    outOfOrder += remaining[j].number
                }
            }
        }
        if (outOfOrder.isNotEmpty()) {
            issues += CourseImportIssue.NonChronologicalSlotsRemoved(
                periodScheduleName = name,
                numbers = outOfOrder.toList()
            )
        }

        return remaining.filterNot { it.number in outOfOrder }
    }

    private fun reconcileSectionRows(
        basePeriodScheduleName: String,
        baseSlots: MutableList<TimeSlot>,
        seasons: MutableList<PeriodScheduleImport>,
        courses: List<CourseImport>,
        issues: MutableList<CourseImportIssue.Normalization>
    ) {
        val references = referencedNumbers(courses)

        val rows = buildSet {
            addAll(baseSlots.map { it.number })
            seasons.forEach { addAll(it.slots.map(TimeSlot::number)) }
            addAll(references)
        }.sortedDescending()

        for (number in rows) {
            if (baseSlots.hasGapAt(number)) {
                baseSlots.clear()

                issues += CourseImportIssue.PeriodScheduleClearedForSectionGap(
                    periodScheduleName = basePeriodScheduleName,
                    section = number
                )
            }

            for (index in seasons.indices) {
                val table = seasons[index]

                if (table.slots.hasGapAt(number)) {
                    seasons[index] = table.copy(slots = emptyList())

                    issues += CourseImportIssue.PeriodScheduleClearedForSectionGap(
                        periodScheduleName = table.name,
                        section = number
                    )
                }
            }
        }

    }

    /**
     * 当前编号缺失，并且列表中还存在更大的编号，
     * 就说明该列表在此处存在内部缺口。
     *
     * 缺少的编号如果位于尾部，则不视为缺口。
     */
    private fun List<TimeSlot>.hasGapAt(
        number: Int
    ): Boolean =
        isNotEmpty() &&
                none { it.number == number } &&
                any { it.number > number }


    private fun normalizeCourses(
        courses: List<CourseImport>,
        issues: MutableList<CourseImportIssue.Normalization>
    ): List<CourseImport> {
        return courses.map { course ->
            val name = course.name.trim()

            val sessions = course.sessions
                .map { session ->
                    val invalidWeeks = session.weeks
                        .filter { it !in 1..60 }
                        .sorted()

                    if (invalidWeeks.isNotEmpty()) {
                        issues += CourseImportIssue.InvalidSessionWeeksRemoved(
                            courseName = name,
                            removedWeeks = invalidWeeks
                        )
                    }

                    session.copy(
                        weeks = session.weeks.filter { it in 1..60 }.toSet()
                    )
                }
                .filter { session ->
                    if (!session.time.isValid()) {
                        issues += CourseImportIssue.InvalidSessionsRemoved(
                            courseName = name,
                            sessionTime = session.time
                        )
                        false
                    } else {
                        true
                    }
                }

            if (sessions.isEmpty()) {
                issues += CourseImportIssue.EmptySessionCourse(
                    courseName = name
                )
            }

            course.copy(
                name = name,
                sessions = sessions
            )
        }
    }

    private fun normalizeTotalWeeks(
        totalWeeks: Int,
        courses: List<CourseImport>,
        issues: MutableList<CourseImportIssue.Normalization>
    ): Int {
        // 先将总周数限制在 1..60。
        val boundedTotalWeeks = totalWeeks.coerceIn(1, 60)

        if (boundedTotalWeeks != totalWeeks) {
            issues += CourseImportIssue.TotalWeeksOutOfRangeAdjusted(
                originalTotalWeeks = totalWeeks,
                adjustedTotalWeeks = boundedTotalWeeks
            )
        }

        // courses 已经清理过非法周次，因此这里的最大值必然在 1..60 内。
        val maxCourseWeek = courses
            .asSequence()
            .flatMap { it.sessions.asSequence() }
            .flatMap { it.weeks.asSequence() }
            .maxOrNull()
            ?: return boundedTotalWeeks

        if (maxCourseWeek > boundedTotalWeeks) {
            val adjustedTotalWeeks = minOf(60, maxCourseWeek)
            issues += CourseImportIssue.TotalWeeksExpandedForCourseWeeks(
                originalTotalWeeks = boundedTotalWeeks,
                adjustedTotalWeeks = adjustedTotalWeeks
            )

            return adjustedTotalWeeks
        }

        return boundedTotalWeeks
    }

    private fun referencedNumbers(
        courses: List<CourseImport>
    ): Set<Int> = buildSet {
        courses.forEach { course ->
            course.sessions.forEach { session ->
                val time = session.time as? CourseSessionTime.Sections
                    ?: return@forEach

                add(time.startSection)
                add(time.endSection)
            }
        }
    }

    private fun remapCourses(
        courses: List<CourseImport>,
        mapping: Map<Int, Int>
    ): List<CourseImport> =
        courses.map { course ->
            course.copy(
                sessions = course.sessions.map { session ->
                    val time = when (val old = session.time) {
                        is CourseSessionTime.Custom -> old

                        is CourseSessionTime.Sections ->
                            CourseSessionTime.Sections(
                                startSection = mapping.getValue(old.startSection),
                                endSection = mapping.getValue(old.endSection)
                            )
                    }

                    session.copy(time = time)
                }
            )
        }

    private fun List<TimeSlot>.remapNumbers(
        mapping: Map<Int, Int>
    ): List<TimeSlot> =
        map { slot ->
            slot.copy(number = mapping.getValue(slot.number))
        }

    // 没有有效作息表时，只统一平移课程引用
    private fun List<CourseImport>.shiftWithoutPeriodSchedules(): List<CourseImport> {
        val references = referencedNumbers(this)
        if (references.isEmpty()) return this

        val offset = maxOf(0, 1 - references.min())

        val shifted = this.map { course ->
            course.copy(
                sessions = course.sessions.map { session ->
                    when (val time = session.time) {
                        is CourseSessionTime.Custom -> session

                        is CourseSessionTime.Sections ->
                            session.copy(
                                time = CourseSessionTime.Sections(
                                    startSection = time.startSection + offset,
                                    endSection = time.endSection + offset
                                )
                            )
                    }
                }
            )
        }
        return shifted
    }

    private fun CourseTableImport.mergeCoursesByName(): CourseTableImport = copy(
        courses = courses
            .groupBy { it.name.trim() }
            .map { (name, duplicates) ->
                CourseImport(
                    name = name,
                    sessions = duplicates.flatMap { it.sessions }
                )
            }
    )
}