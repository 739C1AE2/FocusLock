package com.github739c1ae2.focuslock.util

import org.junit.Assert.assertEquals
import org.junit.Test

class WeeksSummaryTest {

    @Test
    fun emptyMeansAll() {
        assertEquals(WeeksSummary.All, WeeksSummary.from(emptySet(), 16))
    }

    @Test
    fun fullSemesterMeansAll() {
        assertEquals(WeeksSummary.All, WeeksSummary.from((1..16).toSet(), 16))
    }

    @Test
    fun singleWeek() {
        assertEquals(WeeksSummary.Single(5), WeeksSummary.from(setOf(5), 16))
    }

    @Test
    fun contiguousRange() {
        assertEquals(WeeksSummary.Range(3, 6), WeeksSummary.from(setOf(3, 4, 5, 6), 16))
    }

    @Test
    fun partialOddWeeks() {
        assertEquals(
            WeeksSummary.Odd(3, 15),
            WeeksSummary.from(setOf(3, 5, 7, 9, 11, 13, 15), 16)
        )
    }

    @Test
    fun partialEvenWeeks() {
        assertEquals(
            WeeksSummary.Even(4, 10),
            WeeksSummary.from(setOf(4, 6, 8, 10), 16)
        )
    }

    @Test
    fun twoOddWeeks() {
        assertEquals(WeeksSummary.Odd(3, 5), WeeksSummary.from(setOf(3, 5), 16))
    }

    @Test
    fun irregularWeeksBecomeList() {
        assertEquals(
            WeeksSummary.Weeks(listOf(1, 3, 4)),
            WeeksSummary.from(setOf(1, 3, 4), 16)
        )
    }
}
