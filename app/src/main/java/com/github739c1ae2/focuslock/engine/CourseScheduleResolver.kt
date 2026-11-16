package com.github739c1ae2.focuslock.engine

import com.github739c1ae2.focuslock.database.Course
import com.github739c1ae2.focuslock.database.CourseSession
import com.github739c1ae2.focuslock.database.CourseSessionTime
import com.github739c1ae2.focuslock.database.CourseTimeSlotEntity
import com.github739c1ae2.focuslock.database.PeriodSchedule
import com.github739c1ae2.focuslock.database.TimeSlot
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 一次具体课程窗口，起止时间为绝对毫秒时间戳。 */
data class CourseOccurrence(
    val startMillis: Long,
    val endMillis: Long,
    val occurrenceEpochDay: Long,
)

data class CourseScheduleData(
    val totalWeeks: Int,
    val firstDayOfWeek: DayOfWeek,
    val semesterStartEpochDay: Long?,
    val effectiveTimeSlots: List<CourseTimeSlotEntity> = emptyList(),
    val courses: List<Course>,
    val periodSchedules: List<PeriodSchedule> = emptyList(),
)

/** 根据学期、周次和作息配置解析课程窗口。 */
object CourseScheduleResolver {

    /**
     * 查找指定课程时段在 nowMillis 之后的最早开始点。
     *
     * @return 严格晚于 nowMillis 的 Unix 毫秒时间戳；没有后续窗口时返回 null。
     */
    fun nextStart(
        data: CourseScheduleData,
        session: CourseSession,
        nowMillis: Long,
        zone: ZoneId,
    ): Long? {
        val semesterStart = data.semesterStartEpochDay ?: return null
        // 开学日不一定是周首，先对齐周首，避免将同周的上课日期错算到下一周。
        val firstWeek = previousOrSame(LocalDate.ofEpochDay(semesterStart), data.firstDayOfWeek)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val dayOffset = (session.dayOfWeek.value - data.firstDayOfWeek.value + 7) % 7
        // 直接访问配置中的周次，避免逐天扫描整个学期。
        for (week in session.weeks.sorted()) {
            if (week !in 1..data.totalWeeks) continue
            val date = firstWeek.plusWeeks((week - 1).toLong()).plusDays(dayOffset.toLong())
            if (date < today || date.toEpochDay() < semesterStart) continue
            for (window in occurrencesOn(data, session, date, zone)) {
                if (window.startMillis <= nowMillis) continue
                return window.startMillis
            }
        }
        return null
    }

    /**
     * 解析指定本地日期上的课程窗口。
     *
     * @return 按开始时间排序、保留课间间隔的窗口列表；日期不符合学期、星期或周次时为空。
     */
    fun occurrencesOn(
        data: CourseScheduleData,
        session: CourseSession,
        date: LocalDate,
        zone: ZoneId,
    ): List<CourseOccurrence> {
        val semesterStart = data.semesterStartEpochDay ?: return emptyList()
        if (date.toEpochDay() < semesterStart || session.dayOfWeek != date.dayOfWeek) return emptyList()
        val week = weekIndexAt(date, semesterStart, data.firstDayOfWeek)
        if (week !in 1..data.totalWeeks || week !in session.weeks) return emptyList()
        return minuteRanges(session.time, data, date).mapNotNull { (startMinute, endMinute) ->
            val start = date.atTime(startMinute / 60, startMinute % 60).atZone(zone).toInstant().toEpochMilli()
            val end = date.atTime(endMinute / 60, endMinute % 60).atZone(zone).toInstant().toEpochMilli()
            // 夏令时可能将不同的本地分钟映射到同一时刻，转换后非正的窗口不能参与调度。
            if (end <= start) return@mapNotNull null
            CourseOccurrence(start, end, date.toEpochDay())
        }
    }

    /** 计算日期所属的教学周，开学日所在周编号为 1。 */
    fun weekIndexAt(date: LocalDate, semesterStartEpochDay: Long, firstDayOfWeek: DayOfWeek): Int {
        val start = previousOrSame(LocalDate.ofEpochDay(semesterStartEpochDay), firstDayOfWeek)
        return (ChronoUnit.DAYS.between(start, previousOrSame(date, firstDayOfWeek)) / 7).toInt() + 1
    }

    private fun previousOrSame(date: LocalDate, target: DayOfWeek): LocalDate =
        date.minusDays(((date.dayOfWeek.value - target.value + 7) % 7).toLong())

    /** 按课程发生日选择季节作息；重叠时优先开始日期较晚的季节作息，基础作息兜底。 */
    private fun slotsOn(data: CourseScheduleData, date: LocalDate): List<TimeSlot> {
        if (data.periodSchedules.isEmpty()) {
            return data.effectiveTimeSlots.map { TimeSlot(it.number, it.startMinute, it.endMinute, it.alias) }
        }
        val day = date.toEpochDay()
        return data.periodSchedules.asSequence()
            .filter { it.isBase || day in it.startEpochDay..it.endEpochDay }
            .minWithOrNull(compareBy<PeriodSchedule> { it.isBase }
                .thenByDescending { it.startEpochDay }.thenBy { it.id })
            ?.slots.orEmpty()
    }

    /** 将自定义时间或节次范围转换为升序分钟区间，并合并相接或重叠的区间。 */
    private fun minuteRanges(
        time: CourseSessionTime,
        data: CourseScheduleData,
        date: LocalDate,
    ): List<Pair<Int, Int>> {
        if (!time.isValid()) return emptyList()
        if (time is CourseSessionTime.Custom) return listOf(time.startMinute to time.endMinute)
        time as CourseSessionTime.Sections
        val ranges = slotsOn(data, date).asSequence()
            .filter { it.number in time.startSection..time.endSection && it.isValid() }
            .map { it.startMinute to it.endMinute }
            .sortedBy { it.first }
        // 节次编号连续不代表时间连续，合并时必须保留实际课间，否则课间也会被课程锁定。
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
}
