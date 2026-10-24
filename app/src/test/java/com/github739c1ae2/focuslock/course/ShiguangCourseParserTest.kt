package com.github739c1ae2.focuslock.course

import com.github739c1ae2.focuslock.database.CourseSessionTime
import com.github739c1ae2.focuslock.database.TimeSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** 直接解析文本，分别检查时光 JSON 的字段映射、兼容规则和可恢复错误。 */
class ShiguangCourseParserTest {
    private val semesterStart = LocalDate.of(2026, 9, 7).toEpochDay()

    /** 完整样例包含学期配置、基础作息、季节作息以及两种课程时间。 */
    @Test
    fun parsesCompleteTable() {
        val result = parse("""
            {
              "config": {"semesterStartDate":"2026-09-07","semesterTotalWeeks":18,"firstDayOfWeek":7},
              "timeSlots": [{"number":1,"startTime":"08:00","endTime":"08:45","alias":"早课"}],
              "comboSchedule": {"publicSchedules":[{
                "name":"冬季作息","startDate":"2026-11-01","endDate":"2026-12-31",
                "timeSlots":[{"number":1,"startTime":"08:30","endTime":"09:15"}]
              }]},
              "courses": [
                {"name":" 数学 ","teacher":" 张老师 ","position":" A101 ","day":1,
                 "startSection":1,"endSection":2,"weeks":[1,3]},
                {"name":"英语","teacher":"李老师","position":"B202","day":5,
                 "isCustomTime":true,"customStartTime":"14:00","customEndTime":"15:30","weeks":[2,4]}
              ]
            }
        """.trimIndent())

        assertEquals(
            CourseTableImport(
                semesterStartEpochDay = semesterStart, totalWeeks = 18, firstDayOfWeek = DayOfWeek.SUNDAY,
                baseTimeTable = TimeTableImport(DEFAULT_BASE_TIME_TABLE_NAME, slots = listOf(TimeSlot(1, 480, 525, "早课"))),
                seasonalTables = listOf(TimeTableImport(
                    "冬季作息", LocalDate.of(2026, 11, 1).toEpochDay(), LocalDate.of(2026, 12, 31).toEpochDay(),
                    listOf(TimeSlot(1, 510, 555))
                )),
                courses = listOf(
                    CourseImport("数学", listOf(SessionImport("张老师", "A101", DayOfWeek.MONDAY, CourseSessionTime.Sections(1, 2), setOf(1, 3)))),
                    CourseImport("英语", listOf(SessionImport("李老师", "B202", DayOfWeek.FRIDAY, CourseSessionTime.Custom(840, 930), setOf(2, 4))))
                )
            ), result.data
        )
        assertTrue(result.issues.isEmpty())
    }

    /** 可选配置和作息缺失时使用默认值，空课程列表仍是合法文件。 */
    @Test
    fun missingOptionalFieldsUsesDefaults() {
        val result = parse("""{"courses":[],"timeSlots":null}""")

        assertEquals(CourseTableImport(
            null, 20, DayOfWeek.MONDAY,
            TimeTableImport(DEFAULT_BASE_TIME_TABLE_NAME, slots = emptyList()), emptyList(), emptyList()
        ), result.data)
        assertTrue(result.issues.isEmpty())
    }

    /** 新增的未知字段可以忽略；有默认值的非空字段收到 null 时回退到默认值。 */
    @Test
    fun toleratesUnknownFieldsAndCoercesNullDefaults() {
        val result = parse("""
            {"futureField":{"value":1},"config":{"semesterTotalWeeks":null},"courses":[
              {"name":"数学","teacher":"老师","position":"教室","day":1,"startSection":1,
               "isCustomTime":null,"futureCourseField":true}
            ]}
        """.trimIndent())

        assertEquals(20, result.data.totalWeeks)
        assertEquals(CourseSessionTime.Sections(1, 1), result.data.courses.single().sessions.single().time)
        assertTrue(result.issues.isEmpty())
    }

    /** JSON 损坏、根节点类型错误或缺少必填字段时，应返回结构化错误而非抛异常。 */
    @Test
    fun invalidJsonOrRequiredFieldsReturnsError() {
        for (text in listOf("not-json", "[]", "{}", """{"courses":[{"name":"数学"}]}""", """{"courses":12}""")) {
            val result = ShiguangCourseParser.onParseText(text)
            assertTrue(text, result is CourseTableParseResult.Error)
            val issue = (result as CourseTableParseResult.Error).issues.single()
            assertTrue(text, issue is CourseImportIssue.InvalidJsonFormat)
            assertNotNull((issue as CourseImportIssue.InvalidJsonFormat).exception)
        }
    }

    /** 日期前后空白可以忽略，无法解析的开学日期则清空并给出原始值。 */
    @Test
    fun handlesTrimmedAndInvalidSemesterDates() {
        assertEquals(semesterStart, parse(table(config = """{"semesterStartDate":" 2026-09-07 "}""")).data.semesterStartEpochDay)
        val result = parse(table(config = """{"semesterStartDate":"2026-02-30"}"""))

        assertNull(result.data.semesterStartEpochDay)
        assertEquals(listOf(CourseImportIssue.InvalidSemesterStartDateSkipped("2026-02-30")), result.issues)
    }

    /** 每周首日和课程星期越界时，均限制到星期一至星期日。 */
    @Test
    fun clampsDayOfWeekAtBothBounds() {
        for ((value, expected) in listOf(0 to DayOfWeek.MONDAY, 9 to DayOfWeek.SUNDAY)) {
            val result = parse(table(courses = "[${course(day = value)}]", config = """{"firstDayOfWeek":$value}"""))
            assertEquals(expected, result.data.firstDayOfWeek)
            assertEquals(expected, result.data.courses.single().sessions.single().dayOfWeek)
            assertTrue(result.issues.isEmpty())
        }
    }

    /** 解析阶段去除文本两端空白、非正周次和重复周次；周数上限交给规范化器处理。 */
    @Test
    fun trimsCourseTextAndKeepsDistinctPositiveWeeks() {
        val result = parse(table(courses = "[${course(weeks = "[-1,0,1,1,65]")}]"))

        assertEquals(CourseImport("数学", listOf(SessionImport(
            "张老师", "A101", DayOfWeek.MONDAY, CourseSessionTime.Sections(1, 1), setOf(1, 65)
        ))), result.data.courses.single())
        assertTrue(result.issues.isEmpty())
    }

    /** 自定义时间可带秒和空白，但导入模型只保留整分钟。 */
    @Test
    fun customTimeTruncatesSeconds() {
        val result = parse(table(courses = "[${course(time = """
            "isCustomTime":true,"customStartTime":" 08:05:59 ","customEndTime":"09:10:30"
        """.trimIndent())}]"))

        assertEquals(CourseSessionTime.Custom(485, 550), result.data.courses.single().sessions.single().time)
        assertTrue(result.issues.isEmpty())
    }

    /** 未提供结束节次时，默认只占用起始节次。 */
    @Test
    fun missingEndSectionUsesStartSection() {
        val result = parse(table(courses = "[${course(time = """"startSection":3""")}]"))
        assertEquals(CourseSessionTime.Sections(3, 3), result.data.courses.single().sessions.single().time)
    }

    /** 跳过无法解析的时间，保留后续合法课程，并限制诊断片段长度。 */
    @Test
    fun invalidCourseTimesAreSkippedWithoutDroppingValidCourses() {
        val invalidTimes = listOf(
            """"isCustomTime":true,"customStartTime":"25:00","customEndTime":"09:00"""",
            """"isCustomTime":true,"customStartTime":"08:00"""",
            """"endSection":2"""
        )
        val result = parse(table(courses = (invalidTimes.map { course(time = it) } + course()).joinToString(",", "[", "]")))

        assertEquals(1, result.data.courses.size)
        val issues = result.issues.filterIsInstance<CourseImportIssue.InvalidCourseTimeSkipped>()
        assertEquals(3, issues.size)
        assertEquals(issues, result.issues)
        assertTrue(issues.all { it.courseName == " 数学 " && it.courseSession.length <= 120 })
    }

    /** 无法解析的作息行被跳过，合法行的编号和别名保持原样。 */
    @Test
    fun invalidSlotTimesAreSkippedWithTableName() {
        val result = parse(table(slots = """[
            {"number":1,"startTime":"bad","endTime":"08:45"},
            {"number":2,"startTime":"09:00","endTime":"24:00"},
            {"number":3,"startTime":"10:00","endTime":"10:45","alias":"第三节"}
        ]"""))

        assertEquals(listOf(TimeSlot(3, 600, 645, "第三节")), result.data.baseTimeTable.slots)
        val issues = result.issues.filterIsInstance<CourseImportIssue.InvalidSlotSkipped>()
        assertEquals(2, issues.size)
        assertEquals(issues, result.issues)
        assertTrue(issues.all { it.timeTableName == DEFAULT_BASE_TIME_TABLE_NAME && it.slot.length <= 120 })
    }

    /** 季节作息开始日期无效时，优先回退到开学前一天；无开学日期时使用合法结束日期。 */
    @Test
    fun invalidSeasonStartUsesSemesterOrEndDate() {
        val seasons = """[{"name":"冬季","startDate":"bad","endDate":"2026-12-31","timeSlots":[]}]"""
        for ((config, expected) in listOf(
            """{"semesterStartDate":"2026-09-07"}""" to semesterStart - 1,
            "null" to LocalDate.of(2026, 12, 31).toEpochDay()
        )) {
            val result = parse(table(config = config, seasons = seasons))
            assertEquals(expected, result.data.seasonalTables.single().startEpochDay)
            assertEquals(listOf(CourseImportIssue.InvalidSeasonStartDateFallback("冬季", "bad", expected)), result.issues)
        }
    }

    /** 季节作息结束日期无效时收缩到开始日期，保证回退信息可供界面展示。 */
    @Test
    fun invalidSeasonEndUsesStartDate() {
        val result = parse(table(seasons = """[
            {"name":"冬季","startDate":"2026-11-01","endDate":"bad","timeSlots":[]}
        ]"""))
        val expected = LocalDate.of(2026, 11, 1).toEpochDay()

        assertEquals(expected, result.data.seasonalTables.single().endEpochDay)
        assertEquals(listOf(CourseImportIssue.InvalidSeasonEndDateFallback("冬季", "bad", expected)), result.issues)
    }

    /** 两端日期都无效且没有开学日期时，使用昨天；跨午夜运行也不依赖固定的今天。 */
    @Test
    fun invalidSeasonDatesWithoutSemesterFallBackToYesterday() {
        val before = LocalDate.now().minusDays(1).toEpochDay()
        val result = parse(table(seasons = """[
            {"name":"冬季","startDate":"bad-start","endDate":"bad-end","timeSlots":[]}
        ]"""))
        val after = LocalDate.now().minusDays(1).toEpochDay()
        val season = result.data.seasonalTables.single()

        assertTrue(season.startEpochDay in before..after)
        assertEquals(season.startEpochDay, season.endEpochDay)
        assertEquals(listOf(
            CourseImportIssue.InvalidSeasonStartDateFallback("冬季", "bad-start", season.startEpochDay),
            CourseImportIssue.InvalidSeasonEndDateFallback("冬季", "bad-end", season.endEpochDay)
        ), result.issues)
    }

    /** 解析负责保留可读数据，规范化阶段再移除倒置时段、合并同名课程并清理周次。 */
    @Test
    fun parsedDataWorksWithParserNormalizationConfig() {
        val parsed = parse(table(
            courses = listOf(
                course(weeks = "[1,25,65]"), course(day = 2),
                course(time = """"isCustomTime":true,"customStartTime":"10:00","customEndTime":"09:00"""")
            ).joinToString(",", "[", "]"),
            slots = """[{"number":1,"startTime":"08:00","endTime":"08:45"}]"""
        ))
        assertEquals(3, parsed.data.courses.size)
        assertTrue(parsed.issues.isEmpty())

        val result = CourseImportNormalizer.normalize(parsed.data, ShiguangCourseParser.normalizeConfig)
        assertEquals(25, result.data.totalWeeks)
        assertEquals(2, result.data.courses.single().sessions.size)
        assertEquals(setOf(1, 25), result.data.courses.single().sessions.first().weeks)
        assertEquals("数学", result.data.courses.single().name)
        assertTrue(result.issues.any { it is CourseImportIssue.InvalidSessionsRemoved })
        assertTrue(result.issues.any { it is CourseImportIssue.InvalidSessionWeeksRemoved })
        assertTrue(result.issues.any { it is CourseImportIssue.TotalWeeksExpandedForCourseWeeks })
    }

    /** 构造外部 JSON 文本，不调用被测解析器的内部序列化模型。 */
    private fun table(
        courses: String = "[${course()}]", slots: String = "[]",
        config: String = "null", seasons: String = "[]"
    ) = """{"courses":$courses,"timeSlots":$slots,"config":$config,"comboSchedule":{"publicSchedules":$seasons}}"""

    private fun course(
        time: String = """"startSection":1""", day: Int = 1, weeks: String = "[1,3]"
    ) = """{"name":" 数学 ","teacher":" 张老师 ","position":" A101 ","day":$day,"weeks":$weeks,$time}"""

    private fun parse(text: String): CourseTableParseResult.Success {
        val result = ShiguangCourseParser.onParseText(text)
        assertTrue("预期解析成功，实际为 $result", result is CourseTableParseResult.Success)
        return result as CourseTableParseResult.Success
    }
}
