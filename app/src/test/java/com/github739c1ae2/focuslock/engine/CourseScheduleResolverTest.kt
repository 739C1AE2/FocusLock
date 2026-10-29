package com.github739c1ae2.focuslock.engine

import com.github739c1ae2.focuslock.database.Course
import com.github739c1ae2.focuslock.database.CourseSession
import com.github739c1ae2.focuslock.database.CourseSessionTime
import com.github739c1ae2.focuslock.database.CourseTimeSlotEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * 通过公开接口验证课程周次、当前命中时段和下次开课时间。
 * 使用固定日期和时区，避免测试结果依赖运行当天或开发机的时区设置。
 */
class CourseScheduleResolverTest {
    // 统一以周一作为默认开学日，便于构造跨日、跨周和学期边界。
    private val monday = LocalDate.of(2026, 9, 7)
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setUp() {
        originalTimeZone = TimeZone.getDefault()
        // 解析器读取系统默认时区，因此每个用例都显式设置基准时区。
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
    }

    @After
    fun tearDown() {
        // 包括切换到纽约时区的用例，执行结束后都恢复环境，避免影响其他测试。
        TimeZone.setDefault(originalTimeZone)
    }

    /** 周三开学时，所在的整周仍记为第 1 周；更早的周可得到 0 或负数。 */
    @Test
    fun weekIndexAt_countsSemesterStartWeekAsOneWhenStartIsMidweek() {
        val start = monday.plusDays(2).toEpochDay()

        assertEquals(1, CourseScheduleResolver.weekIndexAt(monday, start, DayOfWeek.MONDAY))
        assertEquals(1, CourseScheduleResolver.weekIndexAt(monday.plusDays(6), start, DayOfWeek.MONDAY))
        assertEquals(2, CourseScheduleResolver.weekIndexAt(monday.plusWeeks(1), start, DayOfWeek.MONDAY))
        assertEquals(0, CourseScheduleResolver.weekIndexAt(monday.minusDays(1), start, DayOfWeek.MONDAY))
        assertEquals(-1, CourseScheduleResolver.weekIndexAt(monday.minusDays(8), start, DayOfWeek.MONDAY))
    }

    /** 每周首日可设置为任意星期，周次应仅在对应的周首日递增。 */
    @Test
    fun weekIndexAt_supportsEveryFirstDayOfWeek() {
        for (offset in 0L..6L) {
            val weekStart = monday.plusDays(offset)
            val start = weekStart.plusDays(2).toEpochDay()
            val firstDay = weekStart.dayOfWeek

            assertEquals(firstDay.name, 0, CourseScheduleResolver.weekIndexAt(weekStart.minusDays(1), start, firstDay))
            assertEquals(firstDay.name, 1, CourseScheduleResolver.weekIndexAt(weekStart, start, firstDay))
            assertEquals(firstDay.name, 1, CourseScheduleResolver.weekIndexAt(weekStart.plusDays(6), start, firstDay))
            assertEquals(firstDay.name, 2, CourseScheduleResolver.weekIndexAt(weekStart.plusWeeks(1), start, firstDay))
        }
    }

    /** 周次按连续自然周计算，跨年不会重新从第 1 周开始。 */
    @Test
    fun weekIndexAt_handlesYearBoundary() {
        val start = LocalDate.of(2026, 12, 30).toEpochDay()

        assertEquals(1, CourseScheduleResolver.weekIndexAt(LocalDate.of(2027, 1, 3), start, DayOfWeek.MONDAY))
        assertEquals(2, CourseScheduleResolver.weekIndexAt(LocalDate.of(2027, 1, 4), start, DayOfWeek.MONDAY))
    }

    /** 未设置开学日期时，既不能命中课程，也不能计算下次开课时间。 */
    @Test
    fun missingSemesterStart_returnsNoOccurrence() {
        assertNoOccurrence(schedule().copy(semesterStartEpochDay = null), at(8, 30))
    }

    /** 空课表不会产生当前课程或未来课程。 */
    @Test
    fun emptyCourses_returnsNoOccurrence() {
        assertNoOccurrence(schedule(courses = emptyList()), at(8, 30))
    }

    /** 课程存在但没有具体时段时，也不应产生排课结果。 */
    @Test
    fun courseWithoutSessions_returnsNoOccurrence() {
        assertNoOccurrence(schedule(courses = listOf(course(sessions = emptyList()))), at(8, 30))
    }

    /** 命中自定义时段后，应返回课程 ID、配置 ID、完整起止时间和课程所属日期。 */
    @Test
    fun findActive_returnsCourseIdentityAndFullCustomTimeWindow() {
        assertEquals(
            occurrence(),
            CourseScheduleResolver.findActive(schedule(), at(8, 30))
        )
    }

    /** 当前课程采用左闭右开区间 [开始, 结束)，边界精确到毫秒。 */
    @Test
    fun findActive_includesStartAndExcludesEndAtMillisecondPrecision() {
        val data = schedule()

        assertNull(CourseScheduleResolver.findActive(data, at(8) - 1))
        assertEquals(occurrence(), CourseScheduleResolver.findActive(data, at(8)))
        assertEquals(occurrence(), CourseScheduleResolver.findActive(data, at(9) - 1))
        assertNull(CourseScheduleResolver.findActive(data, at(9)))
    }

    /** 星期和周次必须同时匹配，不能仅凭当天时间落在时段内就命中。 */
    @Test
    fun findActive_requiresMatchingWeekdayAndExplicitWeek() {
        val sessions = listOf(
            session(dayOfWeek = DayOfWeek.TUESDAY),
            session(weeks = setOf(2, 4)),
            session(weeks = emptySet())
        )
        for (candidate in sessions) {
            val data = schedule(courses = listOf(course(sessions = listOf(candidate))))
            assertNull(candidate.toString(), CourseScheduleResolver.findActive(data, at(8, 30)))
        }

        val data = schedule(courses = listOf(course(sessions = listOf(session(weeks = setOf(2, 4))))))
        val secondMonday = monday.plusWeeks(1)
        assertEquals(
            occurrence(date = secondMonday),
            CourseScheduleResolver.findActive(data, at(8, 30, secondMonday))
        )
    }

    /** 空周次集合表示没有上课周，不能解释为每周都上课。 */
    @Test
    fun emptySessionWeeks_doesNotMeanEveryWeek() {
        assertNoOccurrence(
            schedule(courses = listOf(course(sessions = listOf(session(weeks = emptySet()))))),
            at(8, 30)
        )
    }

    /** 学期共 4 周；即使时段误填第 0、5 周，学期范围外也不能命中。 */
    @Test
    fun findActive_rejectsDatesOutsideSemesterEvenIfSessionListsTheirWeeks() {
        val data = schedule(courses = listOf(course(sessions = listOf(session(weeks = setOf(0, 1, 5))))))

        assertNull(CourseScheduleResolver.findActive(data, at(8, 30, monday.minusWeeks(1))))
        assertNull(CourseScheduleResolver.findActive(data, at(8, 30, monday.plusWeeks(4))))
    }

    /** 学期周数为 0 时不存在有效排课日期。 */
    @Test
    fun zeroLengthSemester_returnsNoOccurrence() {
        assertNoOccurrence(schedule().copy(totalWeeks = 0), at(7))
    }

    /** 多个时段重叠时，按课程和时段的输入顺序返回第一个命中项。 */
    @Test
    fun findActive_usesFirstMatchingCourseAndSessionInInputOrder() {
        val firstCourse = course(
            id = 42, profileId = 99,
            sessions = listOf(
                session(dayOfWeek = DayOfWeek.TUESDAY),
                session(time = CourseSessionTime.Custom(8 * 60 + 15, 10 * 60)),
                session()
            )
        )
        val data = schedule(courses = listOf(firstCourse, course()))

        assertEquals(
            occurrence(start = at(8, 15), end = at(10)).copy(courseId = 42, profileId = 99),
            CourseScheduleResolver.findActive(data, at(8, 30))
        )
    }

    /** 起止节次相同时，使用该节次的完整作息时间。 */
    @Test
    fun sectionTime_usesSingleSectionWindow() {
        val data = schedule(
            courses = listOf(course(sessions = listOf(session(time = CourseSessionTime.Sections(2, 2))))),
            slots = listOf(slot(2, 8 * 60, 9 * 60))
        )

        assertEquals(occurrence(), CourseScheduleResolver.findActive(data, at(8, 30)))
        assertEquals(at(8), CourseScheduleResolver.findNextStart(data, at(7)))
    }

    /** 跨节课程按节次编号查找首尾作息，整个区间包含中间的课间休息。 */
    @Test
    fun sectionTime_usesSectionNumbersAndCoversBreaksBetweenSections() {
        val data = schedule(
            courses = listOf(course(sessions = listOf(session(time = CourseSessionTime.Sections(1, 3))))),
            slots = listOf(slot(3, 10 * 60, 11 * 60), slot(1, 8 * 60, 8 * 60 + 45))
        )

        // 作息列表可以无序；中间节次缺失也不拆分整个课程时段。
        assertEquals(occurrence(end = at(11)), CourseScheduleResolver.findActive(data, at(9)))
        assertEquals(at(8), CourseScheduleResolver.findNextStart(data, at(7)))
    }

    /** 起始节次缺失时无法确定开课时间，应跳过该时段。 */
    @Test
    fun sectionTime_missingStartSectionSkipsSession() {
        val data = schedule(
            courses = listOf(course(sessions = listOf(session(time = CourseSessionTime.Sections(1, 2))))),
            slots = listOf(slot(2, 9 * 60, 10 * 60))
        )

        assertNoOccurrence(data, at(7))
    }

    /** 记录当前回退规则：结束节次缺失时，使用起始节次的结束时间。 */
    @Test
    fun sectionTime_missingEndSectionFallsBackToStartSection() {
        val data = schedule(
            courses = listOf(course(sessions = listOf(session(time = CourseSessionTime.Sections(1, 2))))),
            slots = listOf(slot(1, 8 * 60, 9 * 60))
        )

        assertEquals(occurrence(), CourseScheduleResolver.findActive(data, at(8, 30)))
        assertNull(CourseScheduleResolver.findActive(data, at(9)))
        assertEquals(at(8), CourseScheduleResolver.findNextStart(data, at(7)))
    }

    /** 排除节次倒置，以及映射后起止时间相等或倒置的作息组合。 */
    @Test
    fun sectionTime_rejectsReversedSectionsAndNonIncreasingTimeWindows() {
        val cases = listOf(
            // 起始节次大于结束节次。
            CourseSessionTime.Sections(2, 1) to listOf(slot(1, 480, 525), slot(2, 540, 585)),
            // 节次顺序正确，但开始时间等于结束时间。
            CourseSessionTime.Sections(1, 2) to listOf(slot(1, 540, 585), slot(2, 480, 540)),
            // 节次顺序正确，但开始时间晚于结束时间。
            CourseSessionTime.Sections(1, 2) to listOf(slot(1, 540, 585), slot(2, 480, 525))
        )
        for ((time, slots) in cases) {
            assertNoOccurrence(
                schedule(courses = listOf(course(sessions = listOf(session(time = time)))), slots = slots),
                at(7), "$time with $slots"
            )
        }
    }

    /** 自定义时间必须位于当天允许的分钟范围内，且结束时间严格晚于开始时间。 */
    @Test
    fun customTime_rejectsOutOfRangeZeroLengthAndOvernightWindows() {
        val invalidTimes = listOf(
            CourseSessionTime.Custom(-1, 60),
            CourseSessionTime.Custom(1440, 1500),
            CourseSessionTime.Custom(0, 0),
            CourseSessionTime.Custom(480, 480),
            CourseSessionTime.Custom(480, 1440),
            CourseSessionTime.Custom(480, -1),
            CourseSessionTime.Custom(23 * 60, 60)
        )
        for (time in invalidTimes) {
            assertNoOccurrence(
                schedule(courses = listOf(course(sessions = listOf(session(time = time))))),
                at(8), time.toString()
            )
        }
    }

    /** 遇到缺失作息或无效时间后仍应继续查找，不能漏掉后续的有效时段。 */
    @Test
    fun invalidSession_doesNotHideLaterValidSession() {
        val data = schedule(courses = listOf(course(sessions = listOf(
            session(time = CourseSessionTime.Sections(1, 2)),
            session(time = CourseSessionTime.Custom(480, 480)),
            session()
        ))))

        assertEquals(occurrence(), CourseScheduleResolver.findActive(data, at(8, 30)))
        assertEquals(at(8), CourseScheduleResolver.findNextStart(data, at(7)))
    }

    /** 下次开课取所有课程中最早的未来时间，不依赖课程或时段是否已排序。 */
    @Test
    fun findNextStart_selectsEarliestFutureSessionAcrossUnsortedCourses() {
        val data = schedule(courses = listOf(
            course(id = 1, sessions = listOf(session(time = CourseSessionTime.Custom(12 * 60, 13 * 60)))),
            course(id = 2, sessions = listOf(
                session(time = CourseSessionTime.Custom(11 * 60, 12 * 60)),
                session(),
                session(time = CourseSessionTime.Custom(10 * 60, 11 * 60))
            ))
        ))

        assertEquals(at(10), CourseScheduleResolver.findNextStart(data, at(9)))
    }

    /** 下一次开课必须严格晚于当前时刻；已开始或正在上的课程不再作为候选。 */
    @Test
    fun findNextStart_requiresStartToBeStrictlyAfterNow() {
        val data = schedule(courses = listOf(course(sessions = listOf(
            session(), session(time = CourseSessionTime.Custom(10 * 60, 11 * 60))
        ))))

        assertEquals(at(8), CourseScheduleResolver.findNextStart(data, at(8) - 1))
        assertEquals(at(10), CourseScheduleResolver.findNextStart(data, at(8)))
        assertEquals(at(10), CourseScheduleResolver.findNextStart(data, at(8, 30)))
    }

    /** 当天课程已结束后，应继续查找后续日期的课程。 */
    @Test
    fun findNextStart_advancesToAnotherDay() {
        val data = schedule(courses = listOf(course(sessions = listOf(
            session(), session(dayOfWeek = DayOfWeek.TUESDAY)
        ))))

        assertEquals(at(8, date = monday.plusDays(1)), CourseScheduleResolver.findNextStart(data, at(9)))
    }

    /** 仅第 3 周上课时，应跨过未排课的第 1、2 周。 */
    @Test
    fun findNextStart_skipsWeeksNotListedBySession() {
        val data = schedule(courses = listOf(course(sessions = listOf(session(weeks = setOf(3))))))

        assertEquals(at(8, date = monday.plusWeeks(2)), CourseScheduleResolver.findNextStart(data, at(7)))
    }

    /** 周三开学前查询时，不能把同属第 1 周、但早于开学日的周一作为候选。 */
    @Test
    fun findNextStart_beforeSemesterDoesNotScheduleEarlierDaysInFirstWeek() {
        val data = schedule(courses = listOf(course(sessions = listOf(
            session(), session(dayOfWeek = DayOfWeek.FRIDAY)
        )))).copy(semesterStartEpochDay = monday.plusDays(2).toEpochDay())

        assertEquals(at(8, date = monday.plusDays(4)), CourseScheduleResolver.findNextStart(data, at(7)))
    }

    /** 每周以周日开始时，周三开学后的首个周日已经属于第 2 周。 */
    @Test
    fun sundayFirstWeek_changesWeekUsedByBothQueries() {
        val sunday = monday.plusDays(6)
        val data = schedule(courses = listOf(course(sessions = listOf(
            session(dayOfWeek = DayOfWeek.SUNDAY, weeks = setOf(2))
        )))).copy(
            semesterStartEpochDay = monday.plusDays(2).toEpochDay(),
            firstDayOfWeek = DayOfWeek.SUNDAY
        )

        assertEquals(occurrence(date = sunday), CourseScheduleResolver.findActive(data, at(8, 30, sunday)))
        assertEquals(at(8, date = sunday), CourseScheduleResolver.findNextStart(data, at(7)))
    }

    /** 周一开学的 4 周学期包含第 4 周周日，但不能延伸到第 5 周。 */
    @Test
    fun lastSemesterDay_isIncludedButFollowingWeekIsExcluded() {
        val lastSunday = monday.plusWeeks(3).plusDays(6)
        val data = schedule(courses = listOf(course(sessions = listOf(
            session(dayOfWeek = DayOfWeek.SUNDAY, weeks = setOf(4, 5))
        ))))

        assertEquals(at(8, date = lastSunday), CourseScheduleResolver.findNextStart(data, at(7, date = lastSunday)))
        assertEquals(occurrence(date = lastSunday), CourseScheduleResolver.findActive(data, at(8, 30, lastSunday)))
        assertNull(CourseScheduleResolver.findNextStart(data, at(9, date = lastSunday)))
        assertNoOccurrence(data, at(8, 30, lastSunday.plusWeeks(1)))
    }

    /** 唯一排课已经结束，且剩余周次不再上课时，应返回 null。 */
    @Test
    fun findNextStart_returnsNullWhenRemainingSemesterHasNoMatchingSession() {
        assertNull(CourseScheduleResolver.findNextStart(schedule(), at(9)))
    }

    /** 上海时间已到周一凌晨、UTC 仍为周日时，按本地周一匹配课程及所属日期。 */
    @Test
    fun queries_useLocalCalendarDateWhenUtcDateIsPreviousDay() {
        val data = schedule(courses = listOf(course(sessions = listOf(
            session(time = CourseSessionTime.Custom(0, 60))
        ))))
        val start = Instant.parse("2026-09-06T16:00:00Z").toEpochMilli()
        val end = Instant.parse("2026-09-06T17:00:00Z").toEpochMilli()

        assertEquals(
            occurrence(start = start, end = end),
            CourseScheduleResolver.findActive(data, Instant.parse("2026-09-06T16:30:00Z").toEpochMilli())
        )
        assertEquals(start, CourseScheduleResolver.findNextStart(data, start - 1))
    }

    /** 跨越夏令时切换的课程，起止端点应分别使用各自的时区偏移。 */
    @Test
    fun queries_convertEachEndpointWithItsDaylightSavingOffset() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val sunday = LocalDate.of(2026, 3, 8)
        val data = schedule(courses = listOf(course(sessions = listOf(
            session(dayOfWeek = DayOfWeek.SUNDAY, time = CourseSessionTime.Custom(90, 210))
        )))).copy(semesterStartEpochDay = LocalDate.of(2026, 3, 2).toEpochDay(), totalWeeks = 1)
        // 当天 02:00 跳到 03:00，01:30 至 03:30 实际仅持续一小时。
        val start = Instant.parse("2026-03-08T06:30:00Z").toEpochMilli()
        val end = Instant.parse("2026-03-08T07:30:00Z").toEpochMilli()

        assertEquals(
            occurrence(date = sunday, start = start, end = end),
            CourseScheduleResolver.findActive(data, Instant.parse("2026-03-08T07:00:00Z").toEpochMilli())
        )
        assertNull(CourseScheduleResolver.findActive(data, end))
        assertEquals(start, CourseScheduleResolver.findNextStart(data, start - 1))
    }

    /** 默认课表从基准周一开始，共 4 周；具体用例只覆盖需要变化的数据。 */
    private fun schedule(
        courses: List<Course> = listOf(course()),
        slots: List<CourseTimeSlotEntity> = emptyList()
    ) = CourseScheduleData(
        totalWeeks = 4,
        firstDayOfWeek = DayOfWeek.MONDAY,
        semesterStartEpochDay = monday.toEpochDay(),
        effectiveTimeSlots = slots,
        courses = courses
    )

    /** 固定课程和配置 ID，便于验证命中结果是否携带了正确的身份信息。 */
    private fun course(
        id: Long = 11,
        profileId: Long = 22,
        sessions: List<CourseSession> = listOf(session())
    ) = Course(id = id, name = "测试课程", profileId = profileId, sessions = sessions)

    /** 默认时段为第 1 周周一 08:00 至 09:00，使用自定义时间。 */
    private fun session(
        dayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
        weeks: Set<Int> = setOf(1),
        time: CourseSessionTime = CourseSessionTime.Custom(8 * 60, 9 * 60)
    ) = CourseSession(teacher = "", position = "", dayOfWeek = dayOfWeek, time = time, weeks = weeks)

    /** 构造作息节次；起止时间均为从本地当天零点起算的分钟数。 */
    private fun slot(number: Int, startMinute: Int, endMinute: Int) = CourseTimeSlotEntity(
        periodScheduleId = 1, number = number, startMinute = startMinute, endMinute = endMinute
    )

    /** 按用例当前的系统时区，将本地日期和时刻转成绝对毫秒时间戳。 */
    private fun at(hour: Int, minute: Int = 0, date: LocalDate = monday): Long =
        date.atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 构造完整的预期命中结果，同时检查起止时间和课程所属的本地日期。 */
    private fun occurrence(
        date: LocalDate = monday,
        start: Long = at(8, date = date),
        end: Long = at(9, date = date)
    ) = CourseOccurrence(
        courseId = 11, profileId = 22, startMillis = start, endMillis = end,
        occurrenceEpochDay = date.toEpochDay()
    )

    /** 同时断言没有当前课程、也没有未来课程，适用于无效或空排课数据。 */
    private fun assertNoOccurrence(data: CourseScheduleData, now: Long, message: String = "") {
        assertNull(message, CourseScheduleResolver.findActive(data, now))
        assertNull(message, CourseScheduleResolver.findNextStart(data, now))
    }
}
