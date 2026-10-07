package com.github739c1ae2.focuslock.engine

import com.github739c1ae2.focuslock.database.CourseSessionEntity
import com.github739c1ae2.focuslock.database.CourseTimeSlotEntity
import com.github739c1ae2.focuslock.database.CourseTimeTableEntity
import com.github739c1ae2.focuslock.database.CourseWithSessions
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 命中的课程时段。时间均为绝对毫秒时间戳。
 */
data class CourseOccurrence(
    val courseId: Long,
    val sessionId: Long,
    val profileId: Long,
    val startMillis: Long,
    val endMillis: Long,
    /** 该节课程所属的日期（开学后第几周中的那一天） */
    val occurrenceEpochDay: Long
)

/**
 * 解析课程表所需的全部数据快照。
 */
data class CourseScheduleData(
    val totalWeeks: Int,
    val firstDayOfWeek: DayOfWeek,
    val semesterStartEpochDay: Long?,
    val baseTimeTableId: Long,
    val timeTables: List<CourseTimeTableEntity>,
    val slots: List<CourseTimeSlotEntity>,
    val courses: List<CourseWithSessions>,
    /** 需要判定跳过标记的日期 -> 当天被跳过的 sessionId 集合 */
    val skippedByDay: Map<Long, Set<Long>>
)

object CourseScheduleResolver {

    /**
     * 计算给定时刻命中的课程时段。多个命中时返回第一个。
     */
    fun findActive(data: CourseScheduleData, nowMillis: Long): CourseOccurrence? {
        val semesterStart = data.semesterStartEpochDay ?: return null
        if (data.courses.isEmpty()) return null

        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val today = now.toLocalDate()
        val yesterday = today.minusDays(1)
        val todayDow = today.dayOfWeek
        val yesterdayDow = yesterday.dayOfWeek

        for (courseWithSessions in data.courses) {
            for (session in courseWithSessions.sessions) {
                val candidateDates = buildList {
                    if (session.dayOfWeek == todayDow) add(today)
                    if (session.dayOfWeek == yesterdayDow) add(yesterday)
                }

                for (date in candidateDates) {
                    val week = weekIndexAt(date, semesterStart, data.firstDayOfWeek) ?: continue
                    if (!isWeekActive(session, week, data.totalWeeks)) continue
                    if (session.id in (data.skippedByDay[date.toEpochDay()] ?: emptySet())) continue

                    val window = occurrenceWindow(date, session, data, zone) ?: continue
                    if (nowMillis < window.first || nowMillis >= window.second) continue

                    return CourseOccurrence(
                        courseId = courseWithSessions.course.id,
                        sessionId = session.id,
                        profileId = courseWithSessions.course.profileId,
                        startMillis = window.first,
                        endMillis = window.second,
                        occurrenceEpochDay = date.toEpochDay()
                    )
                }
            }
        }
        return null
    }

    /**
     * 计算下一个课程开始时间（含未来日期）。无课程表或学期未设置时返回 null。
     */
    fun findNextStart(data: CourseScheduleData, nowMillis: Long): Long? {
        val semesterStart = data.semesterStartEpochDay ?: return null
        if (data.courses.isEmpty()) return null

        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val today = now.toLocalDate()
        val semesterStartDate = LocalDate.ofEpochDay(semesterStart)
        val lastDate = semesterStartDate.plusWeeks(data.totalWeeks.toLong()).minusDays(1)

        var date = if (today < semesterStartDate) semesterStartDate else today
        var best: Long? = null

        while (date <= lastDate) {
            val week = weekIndexAt(date, semesterStart, data.firstDayOfWeek) ?: break
            if (week in 1..data.totalWeeks) {
                val dow = date.dayOfWeek
                val skipped = data.skippedByDay[date.toEpochDay()] ?: emptySet()
                for (courseWithSessions in data.courses) {
                    for (session in courseWithSessions.sessions) {
                        if (session.dayOfWeek != dow) continue
                        if (!isWeekActive(session, week, data.totalWeeks)) continue
                        if (session.id in skipped) continue
                        val window = occurrenceWindow(date, session, data, zone) ?: continue
                        if (window.first > nowMillis && (best == null || window.first < best)) {
                            best = window.first
                        }
                    }
                }
            }
            date = date.plusDays(1)
        }
        return best
    }

    /**
     * 计算某日期是开学后的第几周（对齐到每周首日），与时光课表算法一致。
     */
    fun weekIndexAt(date: LocalDate, semesterStartEpochDay: Long, firstDayOfWeek: DayOfWeek): Int? {
        val alignedStart = previousOrSame(LocalDate.ofEpochDay(semesterStartEpochDay), firstDayOfWeek)
        val alignedDate = previousOrSame(date, firstDayOfWeek)
        val diffDays = ChronoUnit.DAYS.between(alignedStart, alignedDate)
        return (diffDays / 7).toInt() + 1
    }

    private fun isWeekActive(session: CourseSessionEntity, week: Int, totalWeeks: Int): Boolean {
        return if (session.weeks.isEmpty()) {
            week in 1..totalWeeks.coerceAtLeast(0)
        } else {
            week in session.weeks
        }
    }

    private fun previousOrSame(date: LocalDate, target: DayOfWeek): LocalDate {
        val diff = (date.dayOfWeek.value - target.value + 7) % 7
        return date.minusDays(diff.toLong())
    }

    /**
     * 计算某个日期实际生效的节次列表：以基础作息为骨架，命中日期区间的季节作息按其 number 覆盖时间。
     */
    fun effectiveTimeSlots(
        baseTimeTableId: Long,
        timeTables: List<CourseTimeTableEntity>,
        slots: List<CourseTimeSlotEntity>,
        epochDay: Long
    ): List<CourseTimeSlotEntity> {
        val baseSlots = slots.filter { it.timeTableId == baseTimeTableId }
        val seasonal = timeTables.firstOrNull { timeTable ->
            !timeTable.isBase &&
                timeTable.startEpochDay != null &&
                timeTable.endEpochDay != null &&
                epochDay in timeTable.startEpochDay!!..timeTable.endEpochDay!!
        } ?: return baseSlots
        val overrides = slots.filter { it.timeTableId == seasonal.id }.associateBy { it.number }
        return baseSlots.map { baseSlot -> overrides[baseSlot.number] ?: baseSlot }
    }

    private fun resolveMinuteRange(
        date: LocalDate,
        session: CourseSessionEntity,
        data: CourseScheduleData
    ): Pair<Int, Int>? {
        if (session.isCustomTime) {
            val start = session.customStartMinute ?: return null
            val end = session.customEndMinute ?: return null
            if (start == end) return null
            return start to end
        }

        val slots = effectiveTimeSlots(
            baseTimeTableId = data.baseTimeTableId,
            timeTables = data.timeTables,
            slots = data.slots,
            epochDay = date.toEpochDay()
        ).associateBy { it.number }
        val startSection = session.startSection ?: return null
        val endSection = session.endSection ?: startSection
        val start = slots[startSection] ?: return null
        val end = slots[endSection] ?: start
        if (start.startMinute == end.endMinute) return null
        return start.startMinute to end.endMinute
    }

    private fun occurrenceWindow(
        date: LocalDate,
        session: CourseSessionEntity,
        data: CourseScheduleData,
        zone: ZoneId
    ): Pair<Long, Long>? {
        val (startMinute, endMinute) = resolveMinuteRange(date, session, data) ?: return null
        val crossMidnight = endMinute <= startMinute
        val startDate = date
        val endDate = if (crossMidnight) date.plusDays(1) else date

        val start = startDate.atTime(startMinute / 60, startMinute % 60)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val end = endDate.atTime(endMinute / 60, endMinute % 60)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        return start to end
    }
}
