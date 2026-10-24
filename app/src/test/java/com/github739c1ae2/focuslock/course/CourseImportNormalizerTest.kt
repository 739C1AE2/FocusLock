package com.github739c1ae2.focuslock.course

import com.github739c1ae2.focuslock.database.CourseSessionTime
import com.github739c1ae2.focuslock.database.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** 检查规范化结果及诊断信息，尤其是多张作息表与课程引用之间的编号一致性。 */
class CourseImportNormalizerTest {
    private val semesterStart = LocalDate.of(2026, 9, 7).toEpochDay()
    private val baseSlots = listOf(TimeSlot(1, 480, 525), TimeSlot(2, 535, 580))

    /** 已经合法且连续编号的输入应原样保留，不产生修复提示。 */
    @Test
    fun validInputRemainsUnchangedWithoutIssues() {
        val input = table()
        val result = normalize(input)

        assertEquals(input, result.data)
        assertTrue(result.issues.isEmpty())
    }

    /** 总周数先限制在 1 至 60；空课程避免额外触发按课程扩展周数。 */
    @Test
    fun boundsTotalWeeksAtBothEnds() {
        for ((original, expected) in listOf(-1 to 1, 0 to 1, 61 to 60, Int.MAX_VALUE to 60)) {
            val result = normalize(table(courses = emptyList()).copy(totalWeeks = original))
            assertEquals(expected, result.data.totalWeeks)
            assertEquals(listOf(CourseImportIssue.TotalWeeksOutOfRangeAdjusted(original, expected)), result.issues)
        }
    }

    /** 总周数按清理后的有效时段扩展，无效周次或被删除的时段不参与最大周次计算。 */
    @Test
    fun expandsSemesterUsingOnlyValidSessionWeeks() {
        val invalidTime = CourseSessionTime.Custom(600, 540)
        val result = normalize(table(courses = listOf(course(sessions = listOf(
            session(weeks = setOf(-1, 0, 1, 25, 61)),
            session(time = invalidTime, weeks = setOf(50))
        )))))

        assertEquals(25, result.data.totalWeeks)
        assertEquals(setOf(1, 25), result.data.courses.single().sessions.single().weeks)
        assertEquals(listOf(
            CourseImportIssue.InvalidSessionWeeksRemoved("数学", listOf(-1, 0, 61)),
            CourseImportIssue.InvalidSessionsRemoved("数学", invalidTime),
            CourseImportIssue.TotalWeeksExpandedForCourseWeeks(20, 25)
        ), result.issues)
    }

    /** 下限修正后仍可按课程扩展，上限不能被非法的第 61 周突破。 */
    @Test
    fun boundsWeeksBeforeExpandingToHighestValidWeek() {
        val result = normalize(table(courses = listOf(course(sessions = listOf(
            session(weeks = setOf(1, 60, 61))
        )))).copy(totalWeeks = 0))

        assertEquals(60, result.data.totalWeeks)
        assertEquals(listOf(
            CourseImportIssue.InvalidSessionWeeksRemoved("数学", listOf(61)),
            CourseImportIssue.TotalWeeksOutOfRangeAdjusted(0, 1),
            CourseImportIssue.TotalWeeksExpandedForCourseWeeks(1, 60)
        ), result.issues)
    }

    /** 无法由 LocalDate 表示的开学日被清空，合法的最小和最大日期仍可保留。 */
    @Test
    fun validatesSemesterEpochDayBounds() {
        for (epochDay in listOf(Long.MIN_VALUE, Long.MAX_VALUE)) {
            val result = normalize(table().copy(semesterStartEpochDay = epochDay))
            assertNull(result.data.semesterStartEpochDay)
            assertEquals(listOf(CourseImportIssue.InvalidSemesterStartEpochDayRemoved), result.issues)
        }
        for (epochDay in listOf(LocalDate.MIN.toEpochDay(), LocalDate.MAX.toEpochDay())) {
            val result = normalize(table().copy(semesterStartEpochDay = epochDay))
            assertEquals(epochDay, result.data.semesterStartEpochDay)
            assertTrue(result.issues.isEmpty())
        }
        assertNull(normalize(table().copy(semesterStartEpochDay = null)).data.semesterStartEpochDay)
    }

    /** 季节日期倒置或超出范围时，两端统一回退到开学前一天。 */
    @Test
    fun invalidSeasonRangesFallBackToDayBeforeSemester() {
        for ((start, end) in listOf(20L to 10L, Long.MIN_VALUE to 10L, 10L to Long.MAX_VALUE)) {
            val season = season().copy(startEpochDay = start, endEpochDay = end)
            val result = normalize(table(seasons = listOf(season)))

            assertEquals(season.copy(startEpochDay = semesterStart - 1, endEpochDay = semesterStart - 1), result.data.seasonalTables.single())
            assertEquals(listOf(CourseImportIssue.InvalidSeasonDateRangeFallback("冬季", semesterStart - 1, semesterStart - 1)), result.issues)
        }
    }

    /** 无开学日期时，非法季节日期回退到昨天，允许测试跨越午夜。 */
    @Test
    fun invalidSeasonWithoutSemesterFallsBackToYesterday() {
        val before = LocalDate.now().minusDays(1).toEpochDay()
        val result = normalize(table(seasons = listOf(season().copy(startEpochDay = 2, endEpochDay = 1)))
            .copy(semesterStartEpochDay = null))
        val after = LocalDate.now().minusDays(1).toEpochDay()
        val adjusted = result.data.seasonalTables.single()

        assertTrue(adjusted.startEpochDay in before..after)
        assertEquals(adjusted.startEpochDay, adjusted.endEpochDay)
        assertEquals(listOf(CourseImportIssue.InvalidSeasonDateRangeFallback("冬季", adjusted.startEpochDay, adjusted.endEpochDay)), result.issues)
    }

    /** 开学日已经是可表示的最小日期时，回退不能再减到日期范围之外。 */
    @Test
    fun fallbackAtMinimumDateDoesNotUnderflow() {
        val minimum = LocalDate.MIN.toEpochDay()
        val result = normalize(table(seasons = listOf(season().copy(startEpochDay = 2, endEpochDay = 1)))
            .copy(semesterStartEpochDay = minimum))

        assertEquals(minimum, result.data.seasonalTables.single().startEpochDay)
        assertEquals(minimum, result.data.seasonalTables.single().endEpochDay)
        assertEquals(listOf(CourseImportIssue.InvalidSeasonDateRangeFallback("冬季", minimum, minimum)), result.issues)
    }

    /** 合法季节范围和名称不会被改成开学前一天。 */
    @Test
    fun preservesValidSeasonDateRange() {
        val input = table(seasons = listOf(season()))
        val result = normalize(input)

        assertEquals(input, result.data)
        assertTrue(result.issues.isEmpty())
    }

    /** 越界、零长度、倒置和跨天作息都移除，诊断保留原始作息对象。 */
    @Test
    fun removesInvalidSlotsAndPreservesValidSlots() {
        val invalid = listOf(
            TimeSlot(2, -1, 60), TimeSlot(3, 600, 600), TimeSlot(4, 660, 600),
            TimeSlot(5, 1380, 60), TimeSlot(6, 600, 1440), TimeSlot(7, 1440, 1500)
        )
        val result = normalize(table(slots = listOf(baseSlots.first()) + invalid, courses = emptyList()))

        assertEquals(listOf(baseSlots.first()), result.data.baseTimeTable.slots)
        assertEquals(invalid.map { CourseImportIssue.InvalidSlotRemoved("基础", it) }, result.issues)
    }

    /** 同编号同时间只保留一条，并选择第一条非空别名。 */
    @Test
    fun collapsesDuplicateSlotsAndKeepsFirstNonBlankAlias() {
        val first = baseSlots.first()
        val result = normalize(table(slots = listOf(
            first, first.copy(alias = " "), first.copy(alias = "早课"), first.copy(alias = "后续别名"), baseSlots.last()
        )))

        assertEquals(listOf(first.copy(alias = "早课"), baseSlots.last()), result.data.baseTimeTable.slots)
        assertTrue(result.issues.isEmpty())
    }

    /** 同一编号对应不同时间时，全部冲突项都删除，不随输入顺序任选一项。 */
    @Test
    fun removesAllSlotsWithConflictingNumbers() {
        val result = normalize(table(slots = listOf(
            baseSlots.first(), TimeSlot(2, 540, 585), TimeSlot(2, 600, 645), TimeSlot(3, 660, 705)
        ), courses = emptyList()))

        assertEquals(listOf(baseSlots.first(), TimeSlot(2, 660, 705)), result.data.baseTimeTable.slots)
        assertEquals(listOf(CourseImportIssue.ConflictingSlotsNumbersRemoved("基础", listOf(2))), result.issues)
    }

    /** 连锁重叠涉及的每个节次都移除，不只删除相邻两个中的一个。 */
    @Test
    fun removesEverySlotParticipatingInOverlap() {
        val result = normalize(table(slots = listOf(
            TimeSlot(1, 480, 540), TimeSlot(2, 525, 585), TimeSlot(3, 570, 630), TimeSlot(4, 660, 720)
        ), courses = emptyList()))

        assertEquals(listOf(TimeSlot(1, 660, 720)), result.data.baseTimeTable.slots)
        assertEquals(listOf(CourseImportIssue.OverlappingSlotsRemoved("基础", listOf(1, 2, 3))), result.issues)
    }

    /** 前一节结束恰好等于后一节开始时不算重叠。 */
    @Test
    fun touchingSlotsAreNotOverlapping() {
        val slots = listOf(TimeSlot(1, 480, 540), TimeSlot(2, 540, 600))
        val result = normalize(table(slots = slots))

        assertEquals(slots, result.data.baseTimeTable.slots)
        assertTrue(result.issues.isEmpty())
    }

    /** 编号升序与时间先后顺序矛盾时，删除所有涉及该矛盾的节次。 */
    @Test
    fun removesNonChronologicalSlots() {
        val result = normalize(table(slots = listOf(
            TimeSlot(1, 600, 645), TimeSlot(2, 480, 525), TimeSlot(3, 720, 765)
        ), courses = emptyList()))

        assertEquals(listOf(TimeSlot(1, 720, 765)), result.data.baseTimeTable.slots)
        assertEquals(listOf(CourseImportIssue.NonChronologicalSlotsRemoved("基础", listOf(1, 2))), result.issues)
    }

    /** 与另一张作息对齐时发现缺少中间行，应清空缺行的基础作息。 */
    @Test
    fun clearsBaseTableWithInternalSectionGap() {
        val complete = baseSlots + TimeSlot(3, 600, 645)
        val result = normalize(table(
            slots = listOf(complete[0], complete[2]), seasons = listOf(season(slots = complete)),
            courses = listOf(course(sessions = listOf(session(time = CourseSessionTime.Sections(1, 3)))))
        ))

        assertTrue(result.data.baseTimeTable.slots.isEmpty())
        assertEquals(complete, result.data.seasonalTables.single().slots)
        assertEquals(listOf(CourseImportIssue.TimeTableClearedForSectionGap("基础", 2)), result.issues)
    }

    /** 季节作息缺少内部行时先清空，再移除空表，并分别记录两种诊断。 */
    @Test
    fun clearsAndRemovesSeasonWithInternalSectionGap() {
        val complete = baseSlots + TimeSlot(3, 600, 645)
        val result = normalize(table(slots = complete, seasons = listOf(season(slots = listOf(complete[0], complete[2])))))

        assertEquals(complete, result.data.baseTimeTable.slots)
        assertTrue(result.data.seasonalTables.isEmpty())
        assertEquals(listOf(
            CourseImportIssue.TimeTableClearedForSectionGap("冬季", 2),
            CourseImportIssue.EmptySeasonalTimeTableRemoved("冬季")
        ), result.issues)
    }

    /** 缺少开头的已知编号同样属于缺口；检查从大编号向小编号进行。 */
    @Test
    fun detectsLeadingGapAndReportsHighestGapFirst() {
        val complete = (1..5).map { TimeSlot(it, 420 + it * 60, 465 + it * 60) }
        for ((slots, expectedGap) in listOf(
            listOf(complete.first(), complete.last()) to 4,
            complete.drop(1) to 1
        )) {
            val result = normalize(table(slots = slots, seasons = listOf(season(slots = complete)), courses = emptyList()))
            assertTrue(result.data.baseTimeTable.slots.isEmpty())
            assertEquals(listOf(CourseImportIssue.TimeTableClearedForSectionGap("基础", expectedGap)), result.issues)
        }
    }

    /** 某张表仅缺少尾部节次时，不清空其已有作息。 */
    @Test
    fun trailingMissingSectionsDoNotClearShorterTable() {
        val longer = baseSlots + TimeSlot(3, 600, 645)
        val result = normalize(table(seasons = listOf(season(slots = longer)), courses = listOf(
            course(sessions = listOf(session(time = CourseSessionTime.Sections(2, 4))))
        )))

        assertEquals(baseSlots, result.data.baseTimeTable.slots)
        assertEquals(longer, result.data.seasonalTables.single().slots)
        assertEquals(listOf(CourseImportIssue.UnknownSectionReferences(listOf(4))), result.issues)
    }

    /** 统一压缩原始编号时，基础、季节和课程引用同步映射，自定义时间保持不变。 */
    @Test
    fun renumbersAllTablesAndCourseReferencesTogether() {
        val slots = listOf(TimeSlot(0, 480, 525, "早课"), TimeSlot(2, 540, 585), TimeSlot(5, 600, 645))
        val custom = session(time = CourseSessionTime.Custom(720, 780))
        val input = table(slots = slots.reversed(), seasons = listOf(season(slots = slots)), courses = listOf(
            course(sessions = listOf(session(time = CourseSessionTime.Sections(0, 5)), custom))
        ))
        val result = normalize(input)
        val expectedSlots = slots.mapIndexed { index, slot -> slot.copy(number = index + 1) }

        assertEquals(expectedSlots, result.data.baseTimeTable.slots)
        assertEquals(expectedSlots, result.data.seasonalTables.single().slots)
        assertEquals(listOf(session(time = CourseSessionTime.Sections(1, 3)), custom), result.data.courses.single().sessions)
        assertTrue(result.issues.isEmpty())
    }

    /** 未定义的尾部引用仍保留，但诊断应使用映射后的新编号，便于用户定位。 */
    @Test
    fun reportsUnknownReferencesAfterRenumbering() {
        val result = normalize(table(courses = listOf(course(sessions = listOf(
            session(time = CourseSessionTime.Sections(2, 7))
        )))))

        assertEquals(CourseSessionTime.Sections(2, 3), result.data.courses.single().sessions.single().time)
        assertEquals(listOf(CourseImportIssue.UnknownSectionReferences(listOf(3))), result.issues)
    }

    /** 没有有效作息时只整体平移非正节次，保留课程跨度与引用间隔。 */
    @Test
    fun noTimeTablesShiftsSectionReferencesWithoutCompressingGaps() {
        val custom = session(time = CourseSessionTime.Custom(720, 780))
        val result = normalize(table(slots = emptyList(), courses = listOf(course(sessions = listOf(
            session(time = CourseSessionTime.Sections(-2, 0)),
            session(time = CourseSessionTime.Sections(4, 5)), custom
        )))))

        assertEquals(listOf(
            session(time = CourseSessionTime.Sections(1, 3)),
            session(time = CourseSessionTime.Sections(7, 8)), custom
        ), result.data.courses.single().sessions)
        assertTrue(result.data.baseTimeTable.slots.isEmpty())
        assertEquals(listOf(CourseImportIssue.NoValidTimeTable), result.issues)
    }

    /** 无作息时，已为正数的引用无需压缩；纯自定义课程也可以保留。 */
    @Test
    fun noTimeTablesKeepsPositiveReferencesAndCustomTimes() {
        for (time in listOf(CourseSessionTime.Sections(5, 7), CourseSessionTime.Custom(480, 540))) {
            val input = table(slots = emptyList(), courses = listOf(course(sessions = listOf(session(time = time)))))
            val result = normalize(input)
            assertEquals(input, result.data)
            assertEquals(listOf(CourseImportIssue.NoValidTimeTable), result.issues)
        }
    }

    /** 空季节作息移除后，若所有表都无效，仍须报告没有有效作息。 */
    @Test
    fun removesEmptySeasonsBeforeDetectingNoValidTimeTable() {
        val result = normalize(table(slots = emptyList(), seasons = listOf(season(slots = emptyList()))))

        assertTrue(result.data.seasonalTables.isEmpty())
        assertEquals(listOf(
            CourseImportIssue.EmptySeasonalTimeTableRemoved("冬季"), CourseImportIssue.NoValidTimeTable
        ), result.issues)
    }

    /** 清理无效时间后仍保留空课程，提示用户补充时段；空周次不会删除合法时段。 */
    @Test
    fun preservesCoursesAfterAllTheirInvalidSessionsAreRemoved() {
        val invalid = CourseSessionTime.Sections(3, 1)
        val result = normalize(table(courses = listOf(
            course(name = " 空课程 ", sessions = listOf(session(time = invalid))),
            course(name = "空周次", sessions = listOf(session(weeks = emptySet())))
        )))

        assertEquals(listOf(
            course(name = "空课程", sessions = emptyList()),
            course(name = "空周次", sessions = listOf(session(weeks = emptySet())))
        ), result.data.courses)
        assertEquals(listOf(
            CourseImportIssue.InvalidSessionsRemoved("空课程", invalid), CourseImportIssue.EmptySessionCourse("空课程")
        ), result.issues)
    }

    /** 同名合并只忽略课名两端空白，保留首次出现顺序和所有时段，不忽略大小写。 */
    @Test
    fun mergesTrimmedNamesAndPreservesSessionOrder() {
        val monday = session()
        val tuesday = session().copy(dayOfWeek = DayOfWeek.TUESDAY)
        val result = normalize(table(courses = listOf(
            course(name = " 数学 ", sessions = listOf(monday)), course(name = "Math"),
            course(name = "数学", sessions = listOf(tuesday)), course(name = "math")
        )))

        assertEquals(listOf(course(sessions = listOf(monday, tuesday)), course(name = "Math"), course(name = "math")), result.data.courses)
        assertTrue(result.issues.isEmpty())
    }

    /** 禁用按名称合并时，相同课名仍是独立课程，只清理课名两端空白。 */
    @Test
    fun disablingMergeKeepsSameNamedCoursesSeparate() {
        val result = normalize(table(courses = listOf(course(name = " 数学 "), course())), merge = false)

        assertEquals(listOf(course(), course()), result.data.courses)
        assertTrue(result.issues.isEmpty())
    }

    /** 规范化不改写输入；修复后的数据再次规范化应稳定且不重复产生已解决的问题。 */
    @Test
    fun normalizationIsIdempotentAndDoesNotMutateInput() {
        val invalid = TimeSlot(3, 600, 540)
        val input = table(slots = baseSlots + invalid, courses = listOf(course(name = " 数学 "), course()))
        val first = normalize(input)
        val second = normalize(first.data)

        assertEquals(table(slots = baseSlots + invalid, courses = listOf(course(name = " 数学 "), course())), input)
        assertEquals(first.data, second.data)
        assertEquals(listOf(CourseImportIssue.InvalidSlotRemoved("基础", invalid)), first.issues)
        assertTrue(second.issues.isEmpty())
    }

    /** 默认样例包含合法基础作息、两节连堂课和明确的开学日期。 */
    private fun table(
        slots: List<TimeSlot> = baseSlots,
        seasons: List<TimeTableImport> = emptyList(),
        courses: List<CourseImport> = listOf(course())
    ) = CourseTableImport(
        semesterStart, 20, DayOfWeek.MONDAY,
        TimeTableImport("基础", slots = slots), seasons, courses
    )

    private fun season(slots: List<TimeSlot> = baseSlots) =
        TimeTableImport("冬季", semesterStart + 30, semesterStart + 60, slots)

    private fun course(name: String = "数学", sessions: List<SessionImport> = listOf(session())) = CourseImport(name, sessions)

    private fun session(
        time: CourseSessionTime = CourseSessionTime.Sections(1, 2), weeks: Set<Int> = setOf(1, 2)
    ) = SessionImport("张老师", "A101", DayOfWeek.MONDAY, time, weeks)

    private fun normalize(input: CourseTableImport, merge: Boolean = true) =
        CourseImportNormalizer.normalize(input, NormalizeConfig(mergeCoursesByName = merge))
}
