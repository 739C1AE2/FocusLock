package com.github739c1ae2.focuslock.course

import com.github739c1ae2.focuslock.database.CourseSessionTime
import com.github739c1ae2.focuslock.database.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** 使用独立构造的 Sleepy 文本检查记录解析、转义、分组与校验，不依赖 Android 文件接口。 */
class SleepyNativeCourseParserTest {
    private val semesterStart = LocalDate.of(2026, 9, 7).toEpochDay()

    /** 完整样例同时覆盖头部、两张作息、同一课程的单双周时段和自定义时间。 */
    @Test
    fun parsesCompleteTable() {
        val result = parse("""
            #sleepy-v1
            T秋季课表|2026/9/9|18|2|n=2
            N1|08:00|08:45
            N2|08:55|09:40
            P冬季|7|1
            Pn1|09:00|09:45
            C数学|一|1-2|1-5单|张老师|A101||||math
            C数学|5|1|2-4双|李老师|B202|||14:00-15:30|math
        """.trimIndent())

        assertEquals(CourseTableImport(
            semesterStart, 18, DayOfWeek.MONDAY,
            TimeTableImport(DEFAULT_BASE_TIME_TABLE_NAME, slots = listOf(TimeSlot(1, 480, 525), TimeSlot(2, 535, 580))),
            listOf(TimeTableImport("冬季", semesterStart - 1, semesterStart - 1, listOf(TimeSlot(1, 540, 585)))),
            listOf(CourseImport("数学", listOf(
                SessionImport("张老师", "A101", DayOfWeek.MONDAY, CourseSessionTime.Sections(1, 2), setOf(1, 3, 5)),
                SessionImport("李老师", "B202", DayOfWeek.FRIDAY, CourseSessionTime.Custom(840, 930), setOf(2, 4))
            )))
        ), result.data)
        assertTrue(result.issues.isEmpty())
    }

    /** 缺少标识行或版本号不可解析时，返回明确的格式错误。 */
    @Test
    fun missingOrMalformedMagicReturnsError() {
        for (text in listOf("", "普通文本", "T课表|2026-09-07|20|12", "#sleepy-vx", "#sleepy-v999999999999999999999")) {
            assertEquals(text, CourseTableParseResult.Error(listOf(CourseImportIssue.InvalidSleepyFormat)), SleepyNativeCourseParser.onParseText(text))
        }
    }

    /** 仅在前 32 个非空行中寻找标识，空行不计入限制。 */
    @Test
    fun magicSearchUsesNonBlankLineLimit() {
        val prefix = List(31) { "说明 $it\n\n" }.joinToString("")
        assertTrue(parse(prefix + "#sleepy-v1").issues.isEmpty())
        assertEquals(
            CourseTableParseResult.Error(listOf(CourseImportIssue.InvalidSleepyFormat)),
            SleepyNativeCourseParser.onParseText(prefix + "额外说明\n#sleepy-v1")
        )
    }

    /** 标识支持引用前缀、全角井号和大小写；未知版本仍尝试按 v1 读取并告警。 */
    @Test
    fun acceptsDecoratedMagicAndReportsUnknownVersion() {
        val result = parse("  > ＃＃ SLEEPY_V42\n")

        assertEquals(listOf(CourseImportIssue.UnknownSleepyVersion(42)), result.issues)
        assertEquals(20, result.data.totalWeeks)
    }

    /** 无头部和作息的最小文件也可解析，日期缺失、周数默认 20。 */
    @Test
    fun minimalFileUsesDefaults() {
        val result = parse("#sleepy-v1")

        assertEquals(CourseTableImport(
            null, 20, DayOfWeek.MONDAY,
            TimeTableImport(DEFAULT_BASE_TIME_TABLE_NAME, slots = emptyList()), emptyList(), emptyList()
        ), result.data)
        assertTrue(result.issues.isEmpty())
    }

    /** 头部支持多种日期写法，开学日期统一对齐到所在周的周一。 */
    @Test
    fun headerDatesSupportDifferentSeparatorsAndAlignToMonday() {
        for (date in listOf("2026-9-9", "2026/09/09", "2026.9.9", "20260909")) {
            val result = parse("#sleepy-v1\nT课表|$date|18|")
            assertEquals(date, semesterStart, result.data.semesterStartEpochDay)
            assertEquals(18, result.data.totalWeeks)
            assertTrue(date, result.issues.isEmpty())
        }
    }

    /** 无效日期报告原值，无法读取的周数回退到默认值。 */
    @Test
    fun invalidHeaderDateIsReportedAndMissingWeeksUseDefault() {
        val result = parse("#sleepy-v1\nT课表|2026-02-30|bad|")

        assertNull(result.data.semesterStartEpochDay)
        assertEquals(20, result.data.totalWeeks)
        assertEquals(listOf(CourseImportIssue.InvalidSemesterStartDateSkipped("2026-02-30")), result.issues)
    }

    /** 课程记录的星期、节次和周次为空时，使用周一、第 1 节和第 1 至 16 周。 */
    @Test
    fun blankCourseFieldsUseDefaults() {
        val result = parse(document(row(day = "", span = "", weeks = "")))

        assertEquals(SessionImport(
            "张老师", "A101", DayOfWeek.MONDAY, CourseSessionTime.Sections(1, 1), (1..16).toSet()
        ), result.data.courses.single().sessions.single())
        assertTrue(result.issues.isEmpty())
    }

    /** 数字、中文星期及常见前缀均可识别，数字越界时限制到一周范围。 */
    @Test
    fun acceptsDayAliasesAndClampsNumericDays() {
        val cases = listOf(
            "1" to DayOfWeek.MONDAY, "星期一" to DayOfWeek.MONDAY, "周二" to DayOfWeek.TUESDAY,
            "礼拜三" to DayOfWeek.WEDNESDAY, "四" to DayOfWeek.THURSDAY, "周五" to DayOfWeek.FRIDAY,
            "星期六" to DayOfWeek.SATURDAY, "周天" to DayOfWeek.SUNDAY,
            "0" to DayOfWeek.MONDAY, "9" to DayOfWeek.SUNDAY
        )
        for ((day, expected) in cases) {
            val result = parse(document(row(day = day)))
            assertEquals(day, expected, result.data.courses.single().sessions.single().dayOfWeek)
            assertTrue(day, result.issues.isEmpty())
        }
    }

    /** 支持节次单值，以及带空白和短横线的起止节次。 */
    @Test
    fun acceptsSingleAndRangedSections() {
        for ((span, expected) in listOf(
            "3" to CourseSessionTime.Sections(3, 3),
            "2 - 4" to CourseSessionTime.Sections(2, 4),
            "2 – 4" to CourseSessionTime.Sections(2, 4)
        )) {
            val result = parse(document(row(span = span)))
            assertEquals(span, expected, result.data.courses.single().sessions.single().time)
            assertTrue(result.issues.isEmpty())
        }
    }

    /** 周次范围支持单双周、定周、中英文后缀、倒置范围和不同连接符。 */
    @Test
    fun parsesWeekRangesAndParityAliases() {
        val cases = listOf(
            "1-6单" to setOf(1, 3, 5), "1~6odd" to setOf(1, 3, 5),
            "1至6奇" to setOf(1, 3, 5), "1–6o" to setOf(1, 3, 5),
            "1-6双" to setOf(2, 4, 6), "1—6even" to setOf(2, 4, 6),
            "1〜6偶" to setOf(2, 4, 6), "1-6e" to setOf(2, 4, 6),
            "6-2" to setOf(2, 3, 4, 5, 6), "3定" to setOf(3), "2-4散" to setOf(2, 3, 4)
        )
        for ((weeks, expected) in cases) {
            val result = parse(document(row(weeks = weeks)))
            assertEquals(weeks, expected, result.data.courses.single().sessions.single().weeks)
            assertTrue(weeks, result.issues.isEmpty())
        }
    }

    /** 文本解析最多展开到第 300 周；应用允许的 60 周上限由后续规范化处理。 */
    @Test
    fun weekExpansionIsBoundedBeforeNormalization() {
        val result = parse(document(row(weeks = "0-999")))
        assertEquals((1..300).toSet(), result.data.courses.single().sessions.single().weeks)
    }

    /** 无法识别的星期、节次或周次只跳过对应记录，诊断保留课程和原始行。 */
    @Test
    fun malformedCourseFieldsAreSkippedIndependently() {
        val badDay = row(name = "坏星期", day = "Monday")
        val badSpan = row(name = "坏节次", span = "one")
        val badWeeks = row(name = "坏周次", weeks = "1,3,5")
        val result = parse(document(badDay, badSpan, badWeeks, row()))

        assertEquals(1, result.data.courses.size)
        assertEquals(listOf(
            CourseImportIssue.InvalidCourseDayOfWeekSkipped("坏星期", badDay),
            CourseImportIssue.InvalidCourseTimeSkipped("坏节次", badSpan),
            CourseImportIssue.InvalidCourseWeeksSkipped("坏周次", badWeeks)
        ), result.issues)
    }

    /** 自定义时间支持全角冒号和范围连接符，并优先于节次时间。 */
    @Test
    fun customTimeAcceptsFullWidthPunctuation() {
        for (customTime in listOf("8:05-9:10", "08：05～09：10", " 08:05 ~ 09:10 ")) {
            val result = parse(document(row(customTime = customTime)))
            assertEquals(CourseSessionTime.Custom(485, 550), result.data.courses.single().sessions.single().time)
            assertTrue(result.issues.isEmpty())
        }
    }

    /** 无效自定义时间不丢弃整门课，而是回退到已解析的节次并告警。 */
    @Test
    fun invalidCustomTimeFallsBackToSections() {
        for (time in listOf("24:00-25:00", "08:60-09:00", "bad")) {
            val line = row(customTime = time)
            val result = parse(document(line))
            assertEquals(CourseSessionTime.Sections(1, 2), result.data.courses.single().sessions.single().time)
            assertEquals(listOf(CourseImportIssue.InvalidCustomTimeFallbackToSections("数学", line)), result.issues)
        }
    }

    /** 转义竖线属于字段内容，换行、制表符、反斜杠和尾部反斜杠均正确还原。 */
    @Test
    fun unescapesFieldsWithoutSplittingEscapedPipes() {
        val result = parse(document(row(
            name = """算法\|设计\\基础""", teacher = """张\n李\t老师""", position = """A\|101\\"""
        )))

        assertEquals("算法|设计\\基础", result.data.courses.single().name)
        val session = result.data.courses.single().sessions.single()
        assertEquals("张\n李\t老师", session.teacher)
        assertEquals("A|101\\", session.position)
        assertTrue(result.issues.isEmpty())
    }

    /** 容忍使用全角竖线的完整课程行，结果应与半角分隔相同。 */
    @Test
    fun toleratesFullWidthCourseSeparators() {
        val normal = parse(document(row()))
        val fullWidth = parse(document(row().replace('|', '｜')))

        assertEquals(normal, fullWidth)
    }

    /** 有 token 时按 token 和归一化课名分组，不同 token 或不同课名保持独立。 */
    @Test
    fun groupsByTokenAndNormalizedName() {
        val result = parse(document(
            row(name = "Data   Science", token = "a"),
            row(name = "data science", token = "a", day = "2"),
            row(name = "Data Science", token = "b"),
            row(name = "Other", token = "a")
        ))

        assertEquals(listOf("Data   Science", "Data Science", "Other"), result.data.courses.map { it.name })
        assertEquals(listOf(2, 1, 1), result.data.courses.map { it.sessions.size })
        assertTrue(result.issues.isEmpty())
        val normalized = CourseImportNormalizer.normalize(result.data, SleepyNativeCourseParser.normalizeConfig)
        assertEquals(3, normalized.data.courses.size)
    }

    /** token 为空时按忽略大小写和连续空白后的课名分组。 */
    @Test
    fun blankTokensGroupByNormalizedName() {
        val result = parse(document(
            row(name = "Data   Science", token = ""), row(name = "data science", token = "", day = "2")
        ))

        assertEquals("Data   Science", result.data.courses.single().name)
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), result.data.courses.single().sessions.map { it.dayOfWeek })
        assertTrue(result.issues.isEmpty())
    }

    /** 完全相同的记录只导入一次，并报告文件中的实际行号。 */
    @Test
    fun duplicateCourseRowsAreSkippedWithLineNumber() {
        val line = row()
        val result = parse(document(line, line))

        assertEquals(1, result.data.courses.single().sessions.size)
        assertEquals(listOf(CourseImportIssue.ConflictingSleepyLineSkipped(4, line)), result.issues)
    }

    /** Nd 使用 12 节预设作息，显式 N 行只覆盖对应节次。 */
    @Test
    fun defaultSlotsAllowExplicitOverrides() {
        val result = parse(document("Nd", "N1|07:30|08:15"))
        val slots = result.data.baseTimeTable.slots

        assertEquals((1..12).toList(), slots.map { it.number })
        assertEquals(TimeSlot(1, 450, 495), slots.first())
        assertEquals(TimeSlot(2, 535, 580), slots[1])
        assertEquals(TimeSlot(12, 1305, 1350), slots.last())
        assertTrue(result.issues.isEmpty())
    }

    /** Pd 和 Pn 对独立作息执行同样的预设覆盖规则，并暂存到开学前一天。 */
    @Test
    fun periodDefaultSlotsAllowExplicitOverrides() {
        val result = parse(document("P冬季|7|12", "Pd", "Pn2|09:00|09:45"))
        val period = result.data.seasonalTables.single()

        assertEquals("冬季", period.name)
        assertEquals(semesterStart - 1, period.startEpochDay)
        assertEquals(period.startEpochDay, period.endEpochDay)
        assertEquals((1..12).toList(), period.slots.map { it.number })
        assertEquals(TimeSlot(2, 540, 585), period.slots[1])
        assertTrue(result.issues.isEmpty())
    }

    /** 损坏作息行单独告警并跳过，不影响基础和季节中的其他合法行。 */
    @Test
    fun malformedSlotsReportTheirTimeTable() {
        val result = parse(document("Nbad|08:00|08:45", "N1|08:00|08:45", "P冬季|7|1", "Pn2|25:00|09:45", "Pn1|09:00|09:45"))

        assertEquals(listOf(TimeSlot(1, 480, 525)), result.data.baseTimeTable.slots)
        assertEquals(listOf(TimeSlot(1, 540, 585)), result.data.seasonalTables.single().slots)
        assertEquals(listOf(
            CourseImportIssue.InvalidSlotSkipped(DEFAULT_BASE_TIME_TABLE_NAME, "Nbad|08:00|08:45"),
            CourseImportIssue.InvalidSlotSkipped("冬季", "Pn2|25:00|09:45")
        ), result.issues)
    }

    /** 重复头部和独立作息定义不覆盖首条定义，无效定义也报告准确行号。 */
    @Test
    fun conflictingHeadersAndPeriodsKeepFirstDefinition() {
        val result = parse(document("T另一个|2026-10-01|10|", "P冬季|7|1", "P夏季|8|1", "Pn1|09:00|09:45", "P无效|0|1"))

        assertEquals(semesterStart, result.data.semesterStartEpochDay)
        assertEquals(20, result.data.totalWeeks)
        assertEquals("冬季", result.data.seasonalTables.single().name)
        assertEquals(listOf(
            CourseImportIssue.ConflictingSleepyLineSkipped(3, "T另一个|2026-10-01|10|"),
            CourseImportIssue.ConflictingSleepyLineSkipped(5, "P夏季|8|1"),
            CourseImportIssue.InvalidSleepyLineSkipped(7, "P无效|0|1")
        ), result.issues)
    }

    /** 声明课程数按有效原始记录核对，不能把合并后的课程数当成记录数。 */
    @Test
    fun declaredCourseCountUsesRowsBeforeGrouping() {
        val result = parse(listOf(
            "#sleepy-v1", "T课表|2026-09-09|20|0|n=2", row(), row(day = "2")
        ).joinToString("\n"))

        assertEquals(1, result.data.courses.size)
        assertEquals(2, result.data.courses.single().sessions.size)
        assertTrue(result.issues.isEmpty())
    }

    /** 课程数和两类作息的节数不匹配时分别诊断，实际已读数据仍保留。 */
    @Test
    fun reportsDeclaredCountsWithoutDiscardingData() {
        val result = parse(listOf(
            "#sleepy-v1", "T课表|2026-09-09|20|2|n=3", row(),
            "N1|08:00|08:45", "P冬季|7|2", "Pn1|09:00|09:45"
        ).joinToString("\n"))

        assertEquals(1, result.data.courses.size)
        assertEquals(listOf(
            CourseImportIssue.SleepyDeclaredCourseCountMismatch(3, 1),
            CourseImportIssue.SleepyDeclaredTimeSlotsCountMismatch(DEFAULT_BASE_TIME_TABLE_NAME, 2, 1),
            CourseImportIssue.SleepyDeclaredTimeSlotsCountMismatch("冬季", 2, 1)
        ), result.issues)
    }

    /** 未知记录限制诊断片段长度，注释、空行和 CRLF 换行可正常处理。 */
    @Test
    fun skipsUnknownLinesAndIgnoresComments() {
        val line = "X" + "x".repeat(200)
        val result = parse(document("# 注释", "", line, row()).replace("\n", "\r\n"))

        assertEquals(1, result.data.courses.size)
        assertEquals(listOf(CourseImportIssue.InvalidSleepyLineSkipped(5, line.take(120))), result.issues)
    }

    /** 固定 CRC32 向量由独立工具预先计算，包含 magic、原始注释和空行，排除前导说明。 */
    @Test
    fun validatesKnownChecksumOverOriginalBody() {
        val result = parse("说明\n\n$checksumBody\nZchk=crc32:996DF0F2")

        assertEquals("Math", result.data.courses.single().name)
        assertTrue(result.issues.isEmpty())
    }

    /** 校验不匹配或算法不支持时保留数据，分别提示两种问题。 */
    @Test
    fun checksumProblemsAreWarnings() {
        assertEquals(listOf(CourseImportIssue.SleepyChecksumMismatch), parse("$checksumBody\nZchk=crc32:00000000").issues)
        assertEquals(listOf(CourseImportIssue.SleepyChecksumAlgorithmUnsupported("sha256")), parse("$checksumBody\nZchk=SHA256:12345678").issues)
    }

    /** 格式损坏的首条校验行告警；后续校验行不会覆盖首条结果。 */
    @Test
    fun onlyFirstChecksumRecordIsUsed() {
        assertEquals(listOf(CourseImportIssue.InvalidSleepyLineSkipped(6, "Zbad")), parse("$checksumBody\nZbad\nZchk=crc32:00000000").issues)
        assertTrue(parse("$checksumBody\nZchk=crc32:996df0f2\nZbad").issues.isEmpty())
    }

    /** Sleepy 的同名不同 token 课程经过规范化后仍分开，非法周次再由规范化器清理。 */
    @Test
    fun parserConfigPreservesTokenGroupsDuringNormalization() {
        val parsed = parse(document("Nd", row(token = "a", weeks = "1-65"), row(token = "b", day = "2")))
        val result = CourseImportNormalizer.normalize(parsed.data, SleepyNativeCourseParser.normalizeConfig)

        assertEquals(2, result.data.courses.size)
        assertEquals(listOf("数学", "数学"), result.data.courses.map { it.name })
        assertEquals((1..60).toSet(), result.data.courses.first().sessions.single().weeks)
        assertEquals(60, result.data.totalWeeks)
        assertEquals(listOf(
            CourseImportIssue.InvalidSessionWeeksRemoved("数学", (61..65).toList()),
            CourseImportIssue.TotalWeeksExpandedForCourseWeeks(20, 60)
        ), result.issues)
    }

    /** 留空头部节数可避免测试其他字段时引入无关的数量警告。 */
    private fun document(vararg lines: String): String =
        (listOf("#sleepy-v1", "T测试课表|2026-09-09|20|") + lines).joinToString("\n")

    /** 外部格式共 10 列；第 9 列是自定义时间，第 10 列是课程分组 token。 */
    private fun row(
        name: String = "数学", day: String = "1", span: String = "1-2", weeks: String = "1-4",
        teacher: String = "张老师", position: String = "A101", customTime: String = "", token: String = "g1"
    ) = "C" + listOf(name, day, span, weeks, teacher, position, "", "", customTime, token).joinToString("|")

    private fun parse(text: String): CourseTableParseResult.Success {
        val result = SleepyNativeCourseParser.onParseText(text)
        assertTrue("预期解析成功，实际为 $result", result is CourseTableParseResult.Success)
        return result as CourseTableParseResult.Success
    }

    // 预期 CRC32 为 996df0f2；测试代码不调用被测实现或重新生成这个预期值。
    private val checksumBody = """
        #sleepy-v1
        TTable|2026-09-09|20|
        # keep this comment

        CMath|1|1-2|1-4|Teacher|A101||||g1
    """.trimIndent()
}
