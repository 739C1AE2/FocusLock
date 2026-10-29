/*
 * This file contains code derived from Sleepy:
 * https://github.com/lingion/sleepy
 *
 * Relevant upstream files:
 * - https://github.com/lingion/sleepy/blob/ab4722e8868e5891315434b55e70d228e8917c58/app/src/main/java/com/lingion/sleepy/data/parser/SleepyNativeParser.kt
 * - https://github.com/lingion/sleepy/blob/ab4722e8868e5891315434b55e70d228e8917c58/app/src/main/java/com/lingion/sleepy/data/parser/SleepyNativeFormat.kt
 *
 * Original project license: GNU General Public License v3.0
 *  https://github.com/lingion/sleepy/blob/ab4722e8868e5891315434b55e70d228e8917c58/LICENSE
 *
 * Changes made on 2026-10-21:
 * - Adapted for this project's course import adapter.
 */

package com.github739c1ae2.focuslock.course

import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.database.CourseSessionTime
import com.github739c1ae2.focuslock.database.TimeSlot
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import java.util.zip.CRC32

object SleepyNativeCourseParser : CourseImportFileParser(
    mimeTypes = listOf("text/plain")
) {
    override val nameRes: Int = R.string.course_import_format_sleepy_native
    override val descRes: Int = R.string.course_import_format_sleepy_native_desc
    override val normalizeConfig = NormalizeConfig(mergeCoursesByName = false)

    override fun onParseText(text: String): CourseTableParseResult {
        val lines = text.split('\n')
        val magic = findMagic(lines)
            ?: return CourseTableParseResult.Error(
                listOf(CourseImportIssue.InvalidSleepyFormat)
            )

        val issues = mutableListOf<CourseImportIssue.Parsing>()
        if (magic.version != 1) {
            issues += CourseImportIssue.UnknownSleepyVersion(version = magic.version)
        }

        return parseV1(lines = lines, magicLineIndex = magic.lineIndex, issues = issues)
    }

    private data class Magic(val lineIndex: Int, val version: Int)
    private data class Header(
        val lineIndex: Int,
        val tableName: String,
        val semesterStartEpochDay: Long?,
        val totalWeeks: Int?,
        val nodesPerDay: Int?,
        val declaredCourseCount: Int?,
    )

    private data class PeriodHeader(val name: String, val id: Long, val nodesPerDay: Int)
    private data class ParsedCourseRow(
        val name: String,
        val token: String,
        val session: SessionImport,
    )

    private val magicRegex = Regex(
        """^[>\s]{0,4}[#＃]{1,2}\s*sleepy[\s\-_－]*v(\d+)""",
        RegexOption.IGNORE_CASE,
    )
    private val checksumRegex = Regex(
        """chk=([a-z0-9]+):([0-9a-fA-F]{8})""",
        RegexOption.IGNORE_CASE,
    )
    private val weekRegex = Regex(
        """^(\d+)(?:([-~–—〜至])(\d+))?(单|双|定|散|奇|偶|o|odd|e|even)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val nodeSpanRegex = Regex("""^(\d+)(?:\s*[-–]\s*(\d+))?$""")
    private val dateRegex = Regex("""^(\d{4})[-/.]?(\d{1,2})[-/.]?(\d{1,2})$""")
    private val clockRegex = Regex("""^(\d{1,2}):(\d{2})$""")

    private fun findMagic(lines: List<String>): Magic? {
        var nonBlankLines = 0
        for (index in lines.indices) {
            val line = lines[index].trim().trimEnd('\r')
            if (line.isEmpty()) continue
            nonBlankLines++
            if (nonBlankLines > 32) return null
            val match = magicRegex.find(line) ?: continue
            val version = match.groupValues[1].toIntOrNull() ?: return null
            return Magic(index, version)
        }
        return null
    }

    private fun parseV1(
        lines: List<String>,
        magicLineIndex: Int,
        issues: MutableList<CourseImportIssue.Parsing>
    ): CourseTableParseResult {
        val entries = (magicLineIndex + 1 until lines.size).map { index -> index to lines[index] }
        val headerEntry = entries.firstOrNull { (_, raw) ->
            val line = raw.trim()
            line.length > 1 && line[0].equals('T', ignoreCase = true)
        }
        val header = headerEntry?.let { (index, raw) ->
            parseHeader(
                lineIndex = index,
                line = raw.trim(),
                issues = issues
            )
        }


        val baseSlotTimes = mutableListOf<TimeSlot>()
        var baseDefaultSeen = false
        val periodSlotTimes = mutableListOf<TimeSlot>()
        var periodDefaultSeen = false
        var periodHeader: PeriodHeader? = null
        var seenCourseLineCount = 0
        val parsedCourseRows = mutableListOf<ParsedCourseRow>()
        val seenExactLines = mutableSetOf<String>()
        var checksumSeen = false

        for ((lineIndex, rawLine) in entries) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#")) continue // 注释或额外 magic 行

            when (line[0].uppercaseChar()) {
                'T' -> {
                    if (lineIndex != header?.lineIndex) {
                        issues += CourseImportIssue.ConflictingSleepyLineSkipped(
                            lineNumber = lineIndex + 1,
                            line = line.take(120),
                        )
                    }
                }

                'N' -> {
                    if (line.equals("Nd", ignoreCase = true)) {
                        baseDefaultSeen = true
                    } else {
                        parseSlotLine(
                            body = line.substring(1),
                            rawLine = line,
                            destination = baseSlotTimes,
                            periodScheduleName = DEFAULT_BASE_PERIOD_SCHEDULE_NAME,
                            issues = issues,
                        )
                    }
                }

                'P' -> {
                    when {
                        line.equals("Pd", ignoreCase = true) -> periodDefaultSeen = true
                        Regex("""(?i)^Pn\d+\s*\|""").containsMatchIn(line) -> {
                            parseSlotLine(
                                body = line.substring(2),
                                rawLine = line,
                                destination = periodSlotTimes,
                                periodScheduleName = periodHeader?.name ?: "Sleepy 独立作息",
                                issues = issues,
                            )
                        }

                        else -> {
                            val parsed = parsePeriodHeader(line)
                            if (parsed == null) {
                                issues += CourseImportIssue.InvalidSleepyLineSkipped(
                                    lineNumber = lineIndex + 1,
                                    line = line.take(120)
                                )
                            } else if (periodHeader != null) {
                                issues += CourseImportIssue.ConflictingSleepyLineSkipped(
                                    lineNumber = lineIndex + 1,
                                    line = line.take(120)
                                )
                            } else {
                                periodHeader = parsed
                            }
                        }
                    }
                }

                'C' -> {
                    seenCourseLineCount++
                    if (!seenExactLines.add(line)) {
                        issues += CourseImportIssue.ConflictingSleepyLineSkipped(
                            lineNumber = lineIndex + 1,
                            line = line.take(120),
                        )
                        continue
                    }
                    val row = parseCourseLine(line, issues)
                    if (row != null) parsedCourseRows += row
                }

                'Z' -> {
                    if (checksumSeen) continue
                    checksumSeen = true
                    val match = checksumRegex.find(line)
                    if (match == null) {
                        issues += CourseImportIssue.InvalidSleepyLineSkipped(
                            lineNumber = lineIndex + 1,
                            line = line.take(120)
                        )
                    } else {
                        val algorithm = match.groupValues[1].lowercase(Locale.ROOT)
                        val expected = match.groupValues[2].lowercase(Locale.ROOT)
                        if (algorithm == "crc32") {
                            // Sleepy 文件导出以 z 行之前的原始行（含 magic，不含尾部换行）计算 CRC32。
                            val body = lines.subList(magicLineIndex, lineIndex).joinToString("\n")
                            val actual = crc32(body)
                            if (actual != expected) {
                                issues += CourseImportIssue.SleepyChecksumMismatch
                            }
                        } else {
                            issues += CourseImportIssue.SleepyChecksumAlgorithmUnsupported(algorithm)
                        }
                    }
                }

                else -> {
                    issues += CourseImportIssue.InvalidSleepyLineSkipped(
                        lineNumber = lineIndex + 1,
                        line = line.take(120),
                    )
                }
            }
        }

        val finalBaseSlots = if (baseDefaultSeen) {
            expandPresetSlots(baseSlotTimes)
        } else {
            baseSlotTimes.toList()
        }
        val finalPeriodSlots = if (periodDefaultSeen && periodHeader != null) {
            expandPresetSlots(periodSlotTimes)
        } else {
            periodSlotTimes.toList()
        }

        if (header?.declaredCourseCount != null && header.declaredCourseCount != parsedCourseRows.size) {
            issues += CourseImportIssue.SleepyDeclaredCourseCountMismatch(
                declaredCount = header.declaredCourseCount,
                actualCount = parsedCourseRows.size,
            )
        }

        if (header?.nodesPerDay != null && header.nodesPerDay != finalBaseSlots.size) {
            issues += CourseImportIssue.SleepyDeclaredTimeSlotsCountMismatch(
                periodScheduleName = DEFAULT_BASE_PERIOD_SCHEDULE_NAME,
                declaredCount = header.nodesPerDay,
                actualCount = finalBaseSlots.size,
            )
        }

        val seasonalTables = buildList {
            if (periodHeader != null) {
                val start = (header?.semesterStartEpochDay?: LocalDate.now().toEpochDay()) - 1
                if (periodHeader.nodesPerDay != finalPeriodSlots.size) {
                    issues += CourseImportIssue.SleepyDeclaredTimeSlotsCountMismatch(
                        periodScheduleName = periodHeader.name,
                        declaredCount = periodHeader.nodesPerDay,
                        actualCount = finalPeriodSlots.size,
                    )
                }
                add(
                    PeriodScheduleImport(
                        name = periodHeader.name,
                        startEpochDay = start,
                        endEpochDay = start,
                        slots = finalPeriodSlots,
                    )
                )
            }
        }

        val courses = groupCourseRows(parsedCourseRows)
        return CourseTableParseResult.Success(
            data = CourseTableImport(
                semesterStartEpochDay = header?.semesterStartEpochDay,
                totalWeeks = header?.totalWeeks ?: 20,
                firstDayOfWeek = DayOfWeek.MONDAY,
                basePeriodSchedule = PeriodScheduleImport(
                    name = DEFAULT_BASE_PERIOD_SCHEDULE_NAME,
                    slots = finalBaseSlots,
                ),
                seasonalTables = seasonalTables,
                courses = courses,
            ),
            issues = issues,
        )
    }

    private fun expandPresetSlots(
        explicitSlots: List<TimeSlot>,
    ): List<TimeSlot> {
        val explicitByNumber = explicitSlots.groupBy(TimeSlot::number)
        val allNumbers = (1..DEFAULT_SLOTS.size).toSet()
        return allNumbers.sorted().flatMap { number ->
            explicitByNumber[number] ?: DEFAULT_SLOTS.getOrNull(number - 1)?.let { slot ->
                listOf(TimeSlot(number, slot.startMinute, slot.endMinute, alias = ""))
            }.orEmpty()
        }
    }

    private fun parseHeader(
        lineIndex: Int,
        line: String,
        issues: MutableList<CourseImportIssue.Parsing>
    ): Header {
        // T课表名称|开学日期|总周数|每天节数|n=课程记录数
        val cols = splitEscaped(line.substring(1))
        fun raw(index: Int): String = cols.getOrNull(index)?.trim().orEmpty()
        val extras = cols.drop(4)
        val startDate = raw(1).let {
            if (it.isBlank()) {
                return@let null
            }
            val epochDay = parseDateAsMonday(it)?.toEpochDay()
            if (epochDay == null) {
                issues += CourseImportIssue.InvalidSemesterStartDateSkipped(
                    startDate = it
                )
            }
            epochDay
        }

        val totalWeeks = raw(2).toIntOrNull()

        val nodesPerDay = raw(3).toIntOrNull()

        val declaredCount = extras.firstNotNullOfOrNull { value ->
            val trimmed = value.trim()
            if (trimmed.startsWith("n=", ignoreCase = true)) trimmed.substringAfter('=')
                .toIntOrNull() else null
        }

        return Header(
            lineIndex = lineIndex,
            tableName = unescape(raw(0)),
            semesterStartEpochDay = startDate,
            totalWeeks = totalWeeks,
            nodesPerDay = nodesPerDay,
            declaredCourseCount = declaredCount,
        )
    }

    private fun parsePeriodHeader(line: String): PeriodHeader? {
        val cols = splitEscaped(line.substring(1))
        val name = unescape(cols.getOrNull(0)?.trim().orEmpty())
        val id = cols.getOrNull(1)?.trim()?.toLongOrNull()
        val nodesPerDay = cols.getOrNull(2)?.trim()?.toIntOrNull() ?: 12
        if (name.isBlank() || id == null || id < 1) return null
        return PeriodHeader(name, id, nodesPerDay.coerceIn(1, 30))
    }

    private fun parseSlotLine(
        body: String,
        rawLine: String,
        destination: MutableList<TimeSlot>,
        periodScheduleName: String,
        issues: MutableList<CourseImportIssue.Parsing>,
    ) {
        val cols = splitEscaped(body)
        val number = cols.getOrNull(0)?.trim()?.toIntOrNull()
        val start = parseClockMinutes(cols.getOrNull(1).orEmpty())
        val end = parseClockMinutes(cols.getOrNull(2).orEmpty())
        if (number == null || start == null || end == null) {
            issues += CourseImportIssue.InvalidSlotSkipped(periodScheduleName, rawLine.take(120))
            return
        }
        destination += TimeSlot(number, start, end, alias = "")
    }

    private fun parseCourseLine(
        line: String,
        issues: MutableList<CourseImportIssue.Parsing>,
    ): ParsedCourseRow? {
        var cols = splitEscaped(line.substring(1))
        // 容忍错误地使用全角竖线分隔的行；正常导出仍使用半角 |。
        if (cols.size < 10 && line.contains('｜')) {
            val alternative = splitEscaped(line.substring(1).replace('｜', '|'))
            if (alternative.size == 10 || alternative.size == 11) cols = alternative
        }

        fun raw(index: Int): String = cols.getOrNull(index)?.trim().orEmpty()
        fun text(index: Int): String = unescape(raw(index))

        val name = text(0)

        val dayRaw = raw(1)
        val day = (if (dayRaw.isBlank()) 1 else parseDay(dayRaw))?.coerceIn(1, 7)
            ?: run {
                issues += CourseImportIssue.InvalidCourseDayOfWeekSkipped(
                    courseName = name,
                    courseSession = line
                )
                return null
            }

        val spanRaw = raw(2)
        val (startSection, endSection) = if (spanRaw.isBlank()) {
            1 to 1
        } else {
            parseNodeSpan(spanRaw) ?: run {
                issues += CourseImportIssue.InvalidCourseTimeSkipped(
                    courseName = name,
                    courseSession = line
                )
                return null
            }
        }

        val weekRaw = raw(3)
        val weekSpec = if (weekRaw.isBlank()) WeekSpec(1, 16, 3) else parseWeekSpec(weekRaw)
            ?: run {
                issues += CourseImportIssue.InvalidCourseWeeksSkipped(
                    courseName = name,
                    courseSession = line
                )
                return null
            }

        val weeks = (weekSpec.start..weekSpec.end).filter { week ->
            when (weekSpec.type) {
                1 -> week % 2 == 1
                2 -> week % 2 == 0
                else -> true // 0=每周；3=定周，Sleepy 以起止范围限定实际上课周
            }
        }.toSet()
        val customTimeRaw = raw(8)
        val time = if (customTimeRaw.isBlank()) {
            CourseSessionTime.Sections(startSection, endSection)
        } else {
            val custom = parseTimeRange(customTimeRaw)
            if (custom == null) {
                issues += CourseImportIssue.InvalidCustomTimeFallbackToSections(name, line)
                CourseSessionTime.Sections(startSection, endSection)
            } else {
                CourseSessionTime.Custom(custom.first, custom.second)
            }
        }

        val teacher = text(4)
        val room = text(5)
        return ParsedCourseRow(
            name = name,
            token = text(9),
            session = SessionImport(
                teacher = teacher,
                position = room,
                dayOfWeek = DayOfWeek.of(day),
                time = time,
                weeks = weeks,
            ),
        )
    }

    private data class WeekSpec(val start: Int, val end: Int, val type: Int)

    private fun parseWeekSpec(raw: String): WeekSpec? {
        val match = weekRegex.matchEntire(raw.trim()) ?: return null
        var start = match.groupValues[1].toIntOrNull() ?: return null
        val hasRange = match.groups[3] != null
        var end = if (hasRange) match.groupValues[3].toIntOrNull() ?: return null else start
        val suffix = match.groupValues[4].lowercase(Locale.ROOT)
        val type = when (suffix) {
            "" -> if (hasRange) 0 else 3
            "单", "奇", "o", "odd" -> 1
            "双", "偶", "e", "even" -> 2
            "定", "散" -> 3
            else -> return null
        }
        if (end < start) {
            val tmp = start
            start = end
            end = tmp
        }
        return WeekSpec(start = start.coerceAtLeast(1), end = end.coerceAtMost(300), type = type)
    }

    private fun parseNodeSpan(raw: String): Pair<Int, Int>? {
        val match = nodeSpanRegex.matchEntire(raw.trim()) ?: return null
        val start = match.groupValues[1].toIntOrNull() ?: return null
        val end = match.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: start
        return start to end
    }

    private fun parseDay(raw: String): Int? {
        val value = raw.trim()
        if (value.length == 1 && value[0].isDigit()) return value[0] - '0'
        return when (val stripped =
            value.removePrefix("礼拜").removePrefix("星期").removePrefix("周")) {
            "一" -> 1
            "二" -> 2
            "三" -> 3
            "四" -> 4
            "五" -> 5
            "六" -> 6
            "日", "天" -> 7
            else -> if (stripped.length == 1 && stripped[0].isDigit()) stripped[0] - '0' else null
        }
    }

    private fun parseTimeRange(raw: String): Pair<Int, Int>? {
        val parts = raw.trim().split(Regex("[-~～]"))
        if (parts.size != 2) return null
        val start = parseClockMinutes(parts[0]) ?: return null
        val end = parseClockMinutes(parts[1]) ?: return null
        return start to end
    }

    private fun parseClockMinutes(raw: String): Int? {
        val normalized = raw.trim().replace('：', ':')
        val match = clockRegex.matchEntire(normalized) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun parseDateAsMonday(raw: String): LocalDate? {
        val match = dateRegex.matchEntire(raw.trim()) ?: return null
        val date = runCatching {
            LocalDate.of(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )
        }.getOrNull() ?: return null
        return date.with(DayOfWeek.MONDAY)
    }

    /** 按反斜杠感知切分：\| 是字段内容，不是分隔符。 */
    private fun splitEscaped(body: String): List<String> {
        val columns = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < body.length) {
            when (val char = body[index]) {
                '\\' if index + 1 < body.length -> {
                    current.append(char).append(body[index + 1])
                    index += 2
                }

                '|' -> {
                    columns += current.toString()
                    current.clear()
                    index++
                }

                else -> {
                    current.append(char)
                    index++
                }
            }
        }
        columns += current.toString()
        return columns
    }

    /** Sleepy 转义：\n / \t 还原为换行和制表符，其他 \x 还原为 x。 */
    private fun unescape(value: String): String {
        if ('\\' !in value) return value
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] == '\\' && index + 1 < value.length) {
                result.append(
                    when (value[index + 1]) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> value[index + 1]
                    }
                )
                index += 2
            } else {
                result.append(value[index])
                index++
            }
        }
        return result.toString()
    }

    private fun groupCourseRows(rows: List<ParsedCourseRow>): List<CourseImport> {
        val names = linkedMapOf<String, String>()
        val sessions = linkedMapOf<String, MutableList<SessionImport>>()
        for (row in rows) {
            val normalizedName = row.name.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
            // 非空 token 是 Sleepy 的分组依据；空 token 时按归一化课名合并。
            val key = if (row.token.isNotBlank()) {
                "token:${row.token}|$normalizedName"
            } else {
                "name:$normalizedName"
            }
            names.putIfAbsent(key, row.name)
            sessions.getOrPut(key) { mutableListOf() }.add(row.session)
        }
        return sessions.map { (key, value) ->
            CourseImport(name = names.getValue(key), sessions = value.toList())
        }
    }

    private fun crc32(value: String): String {
        val crc = CRC32()
        crc.update(value.toByteArray(Charsets.UTF_8))
        return String.format(Locale.ROOT, "%08x", crc.value)
    }


    // 与 SleepyNativeFormat.ND_PRESET 一致；Nd/Pd 表示使用此预设，并允许显式 N/Pn 行覆盖单个节次。
    private val DEFAULT_SLOTS = listOf(
        TimeSlot(number = 1, startMinute = 8 * 60, endMinute = 8 * 60 + 45),
        TimeSlot(number = 2, startMinute = 8 * 60 + 55, endMinute = 9 * 60 + 40),
        TimeSlot(number = 3, startMinute = 10 * 60, endMinute = 10 * 60 + 45),
        TimeSlot(number = 4, startMinute = 10 * 60 + 55, endMinute = 11 * 60 + 40),
        TimeSlot(number = 5, startMinute = 14 * 60, endMinute = 14 * 60 + 45),
        TimeSlot(number = 6, startMinute = 14 * 60 + 55, endMinute = 15 * 60 + 40),
        TimeSlot(number = 7, startMinute = 16 * 60, endMinute = 16 * 60 + 45),
        TimeSlot(number = 8, startMinute = 16 * 60 + 55, endMinute = 17 * 60 + 40),
        TimeSlot(number = 9, startMinute = 19 * 60, endMinute = 19 * 60 + 45),
        TimeSlot(number = 10, startMinute = 19 * 60 + 55, endMinute = 20 * 60 + 40),
        TimeSlot(number = 11, startMinute = 20 * 60 + 50, endMinute = 21 * 60 + 35),
        TimeSlot(number = 12, startMinute = 21 * 60 + 45, endMinute = 22 * 60 + 30),
    )

}
