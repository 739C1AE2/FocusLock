package com.github739c1ae2.focuslock.course

import com.github739c1ae2.focuslock.database.CourseSessionTime
import com.github739c1ae2.focuslock.database.TimeSlot
import java.time.DayOfWeek
import java.time.LocalDate

data class CourseTableImport(
    val semesterStartEpochDay: Long?,
    val totalWeeks: Int,
    val firstDayOfWeek: DayOfWeek,
    val basePeriodSchedule: PeriodScheduleImport,
    val seasonalTables: List<PeriodScheduleImport>,
    val courses: List<CourseImport>
)

data class PeriodScheduleImport(
    val name: String,
    val startEpochDay: Long = LocalDate.MIN.toEpochDay(),
    val endEpochDay: Long = LocalDate.MAX.toEpochDay(),
    val slots: List<TimeSlot>
)

data class CourseImport(
    val name: String,
    val sessions: List<SessionImport>
)

data class SessionImport(
    val teacher: String,
    val position: String,
    val dayOfWeek: DayOfWeek,
    val time: CourseSessionTime,
    val weeks: Set<Int>
)