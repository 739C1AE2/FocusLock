package com.github739c1ae2.focuslock.engine

import com.github739c1ae2.focuslock.database.BASE_TIME_TABLE_ID
import com.github739c1ae2.focuslock.database.CourseEntity
import com.github739c1ae2.focuslock.database.CourseSessionEntity
import com.github739c1ae2.focuslock.database.CourseTimeSlotEntity
import com.github739c1ae2.focuslock.database.CourseTimeTableEntity
import com.github739c1ae2.focuslock.database.CourseWithSessions
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CourseScheduleResolverTest {

    private val zone = ZoneId.systemDefault()
    private val semesterStart = LocalDate.of(2026, 9, 7) // Monday

    private fun slot(
        number: Int,
        startMinute: Int,
        endMinute: Int,
        timeTableId: Long = BASE_TIME_TABLE_ID
    ) = CourseTimeSlotEntity(
        id = number.toLong(),
        timeTableId = timeTableId,
        number = number,
        startMinute = startMinute,
        endMinute = endMinute
    )

    private fun baseSlots() = listOf(
        slot(1, 8 * 60, 8 * 60 + 45),
        slot(2, 8 * 60 + 55, 9 * 60 + 40)
    )

    private fun session(
        id: Long = 100,
        dayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
        weeks: Set<Int> = (1..16).toSet(),
        startSection: Int? = 1,
        endSection: Int? = 2
    ) = CourseSessionEntity(
        id = id,
        courseId = 10,
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        endSection = endSection,
        weeks = weeks
    )

    private fun data(
        sessions: List<CourseSessionEntity>,
        skipped: Map<Long, Set<Long>> = emptyMap(),
        timeTables: List<CourseTimeTableEntity> = emptyList(),
        slots: List<CourseTimeSlotEntity> = emptyList()
    ) = CourseScheduleData(
        totalWeeks = 16,
        firstDayOfWeek = DayOfWeek.MONDAY,
        semesterStartEpochDay = semesterStart.toEpochDay(),
        baseTimeTableId = BASE_TIME_TABLE_ID,
        timeTables = timeTables,
        slots = baseSlots() + slots,
        courses = listOf(
            CourseWithSessions(
                course = CourseEntity(id = 10, name = "Math", profileId = 2),
                sessions = sessions
            )
        ),
        skippedByDay = skipped
    )

    private fun millis(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun weekIndexAlignedToFirstDay() {
        assertEquals(1, CourseScheduleResolver.weekIndexAt(semesterStart, semesterStart.toEpochDay(), DayOfWeek.MONDAY))
        assertEquals(2, CourseScheduleResolver.weekIndexAt(semesterStart.plusWeeks(1), semesterStart.toEpochDay(), DayOfWeek.MONDAY))
        assertEquals(0, CourseScheduleResolver.weekIndexAt(semesterStart.minusDays(1), semesterStart.toEpochDay(), DayOfWeek.MONDAY))
    }

    @Test
    fun activeSessionIsDetected() {
        val occurrence = CourseScheduleResolver.findActive(
            data(listOf(session())),
            millis(semesterStart, 8, 30)
        )
        assertNotNull(occurrence)
        assertEquals(100L, occurrence!!.sessionId)
        assertEquals(2L, occurrence.profileId)
    }

    @Test
    fun sessionNotActiveOutsideTime() {
        assertNull(
            CourseScheduleResolver.findActive(data(listOf(session())), millis(semesterStart, 10, 0))
        )
    }

    @Test
    fun skippedSessionIsNotActive() {
        val skipped = mapOf(semesterStart.toEpochDay() to setOf(100L))
        assertNull(
            CourseScheduleResolver.findActive(
                data(listOf(session()), skipped = skipped),
                millis(semesterStart, 8, 30)
            )
        )
    }

    @Test
    fun sessionNotActiveInWrongWeek() {
        assertNull(
            CourseScheduleResolver.findActive(
                data(listOf(session(weeks = setOf(2)))),
                millis(semesterStart, 8, 30)
            )
        )
    }

    @Test
    fun seasonalTimeTableOverridesBaseSlot() {
        val seasonal = CourseTimeTableEntity(
            id = 5,
            name = "Winter",
            isBase = false,
            startEpochDay = semesterStart.toEpochDay(),
            endEpochDay = semesterStart.plusDays(30).toEpochDay()
        )
        val overrideSlot1 = slot(
            number = 1,
            startMinute = 9 * 60,
            endMinute = 9 * 60 + 45,
            timeTableId = seasonal.id
        )
        val testData = data(
            sessions = listOf(session()),
            timeTables = listOf(seasonal),
            slots = listOf(overrideSlot1)
        )
        // 基础作息此时为课间，但季节作息把第1节推迟到 09:00
        assertNull(CourseScheduleResolver.findActive(testData, millis(semesterStart, 8, 30)))
        assertNotNull(CourseScheduleResolver.findActive(testData, millis(semesterStart, 9, 10)))
    }

    @Test
    fun nextStartFindsFutureOccurrence() {
        val next = CourseScheduleResolver.findNextStart(
            data(listOf(session())),
            millis(semesterStart.minusDays(3), 12, 0)
        )
        assertNotNull(next)
        assertEquals(millis(semesterStart, 8, 0), next)
    }

    @Test
    fun effectiveSlotsOverrideBaseByNumber() {
        val seasonal = CourseTimeTableEntity(
            id = 5,
            name = "Winter",
            isBase = false,
            startEpochDay = semesterStart.toEpochDay(),
            endEpochDay = semesterStart.plusDays(30).toEpochDay()
        )
        val override = slot(
            number = 1,
            startMinute = 9 * 60,
            endMinute = 9 * 60 + 45,
            timeTableId = seasonal.id
        )
        val result = CourseScheduleResolver.effectiveTimeSlots(
            baseTimeTableId = BASE_TIME_TABLE_ID,
            timeTables = listOf(seasonal),
            slots = baseSlots() + override,
            epochDay = semesterStart.toEpochDay()
        )
        assertEquals(2, result.size)
        assertEquals(9 * 60, result.first { it.number == 1 }.startMinute)
        assertEquals(8 * 60 + 55, result.first { it.number == 2 }.startMinute)
    }
}
