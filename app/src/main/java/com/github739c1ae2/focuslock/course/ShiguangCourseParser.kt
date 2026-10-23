/*
 * This file contains code derived from ShiGuangSchedule.
 * https://github.com/ShiGuangSchedule/shiguangschedule
 *
 * Relevant upstream files:
 * - https://github.com/ShiGuangSchedule/shiguangschedule/blob/feabb68f75c4058319b449287980b5f645eb8d61/shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/data/model/CourseImportExport.kt
 *
 * Original project license: Apache License 2.0
 * - https://github.com/ShiGuangSchedule/shiguangschedule/blob/feabb68f75c4058319b449287980b5f645eb8d61/LICENSE
 *
 * Modifications: Adapted for this project's course import adapter.
 */

package com.github739c1ae2.focuslock.course

import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.database.CourseSessionTime
import com.github739c1ae2.focuslock.database.TimeSlot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

object ShiguangCourseParser :
    CourseImportFileParser(listOf("application/json", "text/json")) {

    override val nameRes: Int = R.string.import_source_shiguang

    override val descRes: Int = R.string.import_source_shiguang_desc

    override val normalizeConfig: NormalizeConfig = NormalizeConfig(mergeCoursesByName = true)

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    override fun onParseText(text: String): CourseTableParseResult {
        val table = try {
            json.decodeFromString<CourseTableImportModel>(text)
        } catch (e: Exception) {
            return CourseTableParseResult.Error(
                issues = listOf(CourseImportIssue.InvalidJsonFormat(e))
            )
        }
        val config = table.config
        val issues = mutableListOf<CourseImportIssue.Parsing>()

        val semesterStartDate = config?.semesterStartDate?.let {
            val epochDay = parseDateToEpochDay(it)
            if (epochDay == null) {
                issues += CourseImportIssue.InvalidSemesterStartDateSkipped(it)
            }
            epochDay
        }
        val totalWeeks = config?.semesterTotalWeeks ?: 20
        val firstDayOfWeek = DayOfWeek.of(
            (config?.firstDayOfWeek ?: DayOfWeek.MONDAY.value).coerceIn(1, 7)
        )

        val baseSlots = table.timeSlots.orEmpty().toImport(DEFAULT_BASE_TIME_TABLE_NAME, issues)

        val seasonalTables = table.comboSchedule?.publicSchedules.orEmpty().map { season ->
            var start = parseDateToEpochDay(season.startDate)
            var end = parseDateToEpochDay(season.endDate)
            if (start == null) {
                start = semesterStartDate?.let{it - 1} ?: end ?: (LocalDate.now().toEpochDay() - 1)
                issues += CourseImportIssue.InvalidSeasonStartDateFallback(
                    timeTableName = season.name,
                    startDate = season.startDate,
                    defaultEpochDay = start
                )
            }
            if (end == null) {
                end = start
                issues += CourseImportIssue.InvalidSeasonEndDateFallback(
                    timeTableName = season.name,
                    endDate = season.endDate,
                    defaultEpochDay = end
                )
            }
            TimeTableImport(
                name = season.name,
                startEpochDay = start,
                endEpochDay = end,
                slots = season.timeSlots.toImport(season.name, issues)
            )
        }

        val courses = table.courses.mapNotNull { course ->
            val dayOfWeek = course.day
                .coerceIn(1, 7)
                .let { DayOfWeek.of(it) }
            val time = course.toTime()
            if (time == null) {
                issues += CourseImportIssue.InvalidCourseTimeSkipped(
                    courseName = course.name,
                    courseSession = json.encodeToString(course).take(120)
                )
                return@mapNotNull null
            }
            CourseImport(
                name = course.name.trim(),
                sessions = listOf(
                    SessionImport(
                        teacher = course.teacher.trim(),
                        position = course.position.trim(),
                        dayOfWeek = dayOfWeek,
                        time = time,
                        weeks = course.weeks.filter { it > 0 }.toSet()
                    )
                )
            )
        }

        val data = CourseTableImport(
            semesterStartEpochDay = semesterStartDate,
            totalWeeks = totalWeeks,
            firstDayOfWeek = firstDayOfWeek,
            baseTimeTable = TimeTableImport(
                name = DEFAULT_BASE_TIME_TABLE_NAME,
                slots = baseSlots
            ),
            seasonalTables = seasonalTables,
            courses = courses
        )
        return CourseTableParseResult.Success(
            data = data,
            issues = issues
        )
    }

    private fun List<TimeSlotJsonModel>.toImport(
        timeTableName: String,
        issues: MutableList<CourseImportIssue.Parsing>
    ): List<TimeSlot> {
        val slots = mapNotNull {
            val slot = it.toImport()
            if (slot == null) {
                issues += CourseImportIssue.InvalidSlotSkipped(
                    timeTableName = timeTableName,
                    slot = json.encodeToString(it).take(120)
                )
            }
            slot
        }
        return slots
    }

    private fun TimeSlotJsonModel.toImport(): TimeSlot? {
        val start = parseTimeToMinute(startTime) ?: return null
        val end = parseTimeToMinute(endTime) ?: return null
        return TimeSlot(
            number = number,
            startMinute = start,
            endMinute = end,
            alias = alias.orEmpty()
        )
    }

    private fun ImportCourseJsonModel.toTime(): CourseSessionTime? = if (isCustomTime) {
        val start = parseTimeToMinute(customStartTime) ?: return null
        val end = parseTimeToMinute(customEndTime) ?: return null
        CourseSessionTime.Custom(startMinute = start, endMinute = end)
    } else {
        val start = startSection ?: return null
        CourseSessionTime.Sections(startSection = start, endSection = endSection ?: start)
    }

    private fun parseTimeToMinute(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        return try {
            LocalTime.parse(value.trim()).toSecondOfDay() / 60
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private fun parseDateToEpochDay(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            LocalDate.parse(value.trim()).toEpochDay()
        } catch (_: Exception) {
            null
        }
    }

    @Serializable
    private data class CourseTableImportModel(
        val courses: List<ImportCourseJsonModel>,
        val timeSlots: List<TimeSlotJsonModel>? = emptyList(), // 跟随/基准作息
        val config: CourseConfigJsonModel? = null,
        val comboSchedule: ComboScheduleJsonModel? = null       // 组合作息扩展（可选）
    )

    @Serializable
    private data class ImportCourseJsonModel(
        val name: String,
        val teacher: String,
        val position: String,
        val day: Int,
        val startSection: Int? = null,
        val endSection: Int? = null,
        val weeks: List<Int> = emptyList(),
        val isCustomTime: Boolean = false,
        val customStartTime: String? = null,
        val customEndTime: String? = null,
        val color: Int? = null,
        val remark: String? = null,
        val credit: Float? = null
    )

    @Serializable
    private data class TimeSlotJsonModel(
        val number: Int,
        val startTime: String,
        val endTime: String,
        val alias: String? = null
    )

    @Serializable
    private data class CourseConfigJsonModel(
        val semesterStartDate: String? = null,
        val semesterTotalWeeks: Int = 20,
        val defaultClassDuration: Int = 45, // 基准作息的默认单节时长
        val defaultBreakDuration: Int = 10, // 基准作息的默认休息时长
        val firstDayOfWeek: Int = 1
    )

    @Serializable
    private data class ComboScheduleJsonModel(
        val name: String? = null, // 组合作息方案名称
        val publicSchedules: List<PublicScheduleTemplateJsonModel> = emptyList() // 公共作息模板规则列表
    )

    @Serializable
    private data class PublicScheduleTemplateJsonModel(
        val name: String,                   // 公共作息名称（如"夏季作息"）
        val startDate: String,              // 生效起始日期 "2026-05-01"
        val endDate: String,                // 生效结束日期 "2026-09-30"
        val defaultClassDuration: Int = 45, // 该公共作息下的单节时长
        val defaultBreakDuration: Int = 10, // 该公共作息下的休息时长
        val timeSlots: List<TimeSlotJsonModel> // 该公共作息对应的节次时间点
    )
}