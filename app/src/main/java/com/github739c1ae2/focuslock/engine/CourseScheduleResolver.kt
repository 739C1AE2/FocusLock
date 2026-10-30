package com.github739c1ae2.focuslock.engine

import com.github739c1ae2.focuslock.database.Course
import com.github739c1ae2.focuslock.database.CourseSession
import com.github739c1ae2.focuslock.database.CourseSessionTime
import com.github739c1ae2.focuslock.database.CourseTimeSlotEntity
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
    val profileId: Long,
    val startMillis: Long,
    val endMillis: Long,
    /** 该节课程所属的日期 */
    val occurrenceEpochDay: Long
)

/**
 * 解析课程表所需的全部数据快照。
 */
data class CourseScheduleData(
    val totalWeeks: Int,
    val firstDayOfWeek: DayOfWeek,
    val semesterStartEpochDay: Long?,
    val effectiveTimeSlots: List<CourseTimeSlotEntity>,
    val courses: List<Course>,
)

object CourseScheduleResolver {

    /**
     * 计算给定时刻命中的课程时段。
     *
     * @param data 课程表数据快照
     * @param nowMillis 当前时刻（毫秒时间戳）
     * @return 命中的第一个课程时段；未设置开学日期、无课程或未命中时返回 null
     */
    fun findActive(data: CourseScheduleData, nowMillis: Long): CourseOccurrence? {
        val semesterStart = data.semesterStartEpochDay ?: return null
        if (data.courses.isEmpty()) return null

        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val today = now.toLocalDate()
        val todayDow = today.dayOfWeek

        val week = weekIndexAt(today, semesterStart, data.firstDayOfWeek)
        if (week !in 1..data.totalWeeks) return null

        for (course in data.courses) {
            for (session in course.sessions) {
                if (session.dayOfWeek != todayDow) continue

                if (!isWeekActive(session, week)) continue

                val window = occurrenceWindows(today, session, data, zone)
                    .firstOrNull { nowMillis >= it.first && nowMillis < it.second } ?: continue

                return CourseOccurrence(
                    courseId = course.id,
                    profileId = course.profileId,
                    startMillis = window.first,
                    endMillis = window.second,
                    occurrenceEpochDay = today.toEpochDay()
                )

            }
        }
        return null
    }

    /**
     * 计算下一个课程开始时间，用于空闲时安排唤醒。
     *
     * 从今天（或开学日，若尚未开学）起逐天遍历到学期最后一天，取所有未跳过时段中
     * 最早的、且晚于 [nowMillis] 的开始时间。
     *
     * @param data 课程表数据快照
     * @param nowMillis 当前时刻（毫秒时间戳）
     * @return 下一个课程开始时间；未设置开学日期、无课程或学期内已无后续课程时返回 null
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
            val week = weekIndexAt(date, semesterStart, data.firstDayOfWeek)
            if (week in 1..data.totalWeeks) {
                val dow = date.dayOfWeek
                for (courseWithSessions in data.courses) {
                    for (session in courseWithSessions.sessions) {
                        if (session.dayOfWeek != dow) continue
                        if (!isWeekActive(session, week)) continue
                        for (window in occurrenceWindows(date, session, data, zone)) {
                            if (window.first > nowMillis && (best == null || window.first < best)) {
                                best = window.first
                            }
                        }
                    }
                }
            }
            date = date.plusDays(1)
        }
        return best
    }

    /**
     * 计算某日期是开学后的第几周。
     *
     * 周次按 [firstDayOfWeek] 对齐：先分别找到开学日期、目标日期所在周的周首日，再算相差周数。
     * 因此开学日所在的那一周记为第 1 周，开学日之前可能算出 0 或负数。
     *
     * @param date 目标日期
     * @param semesterStartEpochDay 开学日期（epoch day）
     * @param firstDayOfWeek 每周的首日（如周一）
     * @return 周次（从 1 开始）
     */
    fun weekIndexAt(date: LocalDate, semesterStartEpochDay: Long, firstDayOfWeek: DayOfWeek): Int {
        val alignedStart =
            previousOrSame(LocalDate.ofEpochDay(semesterStartEpochDay), firstDayOfWeek)
        val alignedDate = previousOrSame(date, firstDayOfWeek)
        val diffDays = ChronoUnit.DAYS.between(alignedStart, alignedDate)
        return (diffDays / 7).toInt() + 1
    }

    /**
     * 判断某个周次对该时段是否生效。
     */
    private fun isWeekActive(session: CourseSession, week: Int): Boolean {
        return week in session.weeks
    }

    /**
     * 返回不晚于 [date] 的、最近的一个 [target] 星期几（将日期对齐到所在周的周首日）。
     */
    private fun previousOrSame(date: LocalDate, target: DayOfWeek): LocalDate {
        val diff = (date.dayOfWeek.value - target.value + 7) % 7
        return date.minusDays(diff.toLong())
    }


    /**
     * 计算某次课程当天的锁定区间，单位为分钟。
     *
     * - 自定义时间时段：直接取自定义起止分钟；
     * - 跟随作息时段：仅锁定节次范围内实际存在且有效的上课时间，保留课间空隙；
     * - 相邻或重叠的上课时间合并，避免没有课间时切换锁定时段。
     *
     * @return 按开始时间排序的 (startMinute, endMinute) 列表；无有效时间时返回空列表
     */
    private fun resolveMinuteRanges(
        sessionTime: CourseSessionTime,
        data: CourseScheduleData
    ): List<Pair<Int, Int>> {
        if (!sessionTime.isValid()) return emptyList()
        if (sessionTime is CourseSessionTime.Custom) {
            return listOf(sessionTime.startMinute to sessionTime.endMinute)
        }

        sessionTime as CourseSessionTime.Sections
        val ranges = data.effectiveTimeSlots
            .filter { it.number in sessionTime.startSection..sessionTime.endSection }
            .filter { CourseSessionTime.Custom(it.startMinute, it.endMinute).isValid() }
            .map { it.startMinute to it.endMinute }
            .sortedBy { it.first }

        val merged = mutableListOf<Pair<Int, Int>>()
        for (range in ranges) {
            val previous = merged.lastOrNull()
            if (previous != null && range.first <= previous.second) {
                merged[merged.lastIndex] = previous.first to maxOf(previous.second, range.second)
            } else {
                merged += range
            }
        }
        return merged
    }

    /**
     * 把某次课程在 [date] 的各个锁定区间换算成绝对毫秒时间戳。
     *
     * @return (startMillis, endMillis) 列表；无有效时间时返回空列表
     */
    private fun occurrenceWindows(
        date: LocalDate,
        session: CourseSession,
        data: CourseScheduleData,
        zone: ZoneId
    ): List<Pair<Long, Long>> = resolveMinuteRanges(session.time, data).map { (startMinute, endMinute) ->
        val start = date.atTime(startMinute / 60, startMinute % 60)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        val end = date.atTime(endMinute / 60, endMinute % 60)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        start to end
    }
}
