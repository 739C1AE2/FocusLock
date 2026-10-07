package com.github739c1ae2.focuslock.util

import java.text.Collator
import java.util.Locale

fun <T> Iterable<T>.sortTextBy(selector: (T) -> String): List<T> {
    val collator = Collator.getInstance(Locale.getDefault())
    return sortedWith(compareBy(collator, selector))
}

sealed interface WeeksSummary {
    data object All : WeeksSummary
    data class Single(val week: Int) : WeeksSummary
    data class Range(val start: Int, val end: Int) : WeeksSummary
    data class Odd(val start: Int, val end: Int) : WeeksSummary
    data class Even(val start: Int, val end: Int) : WeeksSummary
    data class Weeks(val weeks: List<Int>) : WeeksSummary

    companion object {
        fun from(weeks: Set<Int>, totalWeeks: Int): WeeksSummary {
            if (weeks.isEmpty()) return All
            val sorted = weeks.sorted()
            if (sorted.size == totalWeeks) return All
            if (sorted.size == 1) return Single(sorted.first())
            if (sorted == (sorted.first()..sorted.last()).toList()) {
                return Range(sorted.first(), sorted.last())
            }
            val stepTwo = (sorted.first()..sorted.last() step 2).toList()
            if (stepTwo == sorted) {
                return if (sorted.first() % 2 == 1) {
                    Odd(sorted.first(), sorted.last())
                } else {
                    Even(sorted.first(), sorted.last())
                }
            }
            return Weeks(sorted)
        }
    }
}