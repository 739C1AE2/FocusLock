package com.github739c1ae2.focuslock.course

import com.github739c1ae2.focuslock.database.CourseSessionTime
import com.github739c1ae2.focuslock.database.TimeSlot


enum class CourseImportIssueSeverity {
    INFO,
    WARNING,
    ERROR
}

sealed class CourseImportIssue(
    val severity: CourseImportIssueSeverity
) {
    sealed class Parsing(severity: CourseImportIssueSeverity) : CourseImportIssue(severity)

    data class ReadFileFailed(
        val exception: Exception
    ) : Parsing(CourseImportIssueSeverity.ERROR)

    data class InvalidJsonFormat(
        val exception: Exception
    ) : Parsing(CourseImportIssueSeverity.ERROR)

    data class InvalidSemesterStartDateSkipped(
        val startDate: String
    ) : Parsing(CourseImportIssueSeverity.WARNING)

    data class InvalidSlotSkipped(
        val periodScheduleName: String,
        val slot: String
    ) : Parsing(CourseImportIssueSeverity.WARNING)

    data class InvalidSeasonStartDateFallback(
        val periodScheduleName: String,
        val startDate: String,
        val defaultEpochDay: Long
    ) : Parsing(CourseImportIssueSeverity.WARNING)

    data class InvalidSeasonEndDateFallback(
        val periodScheduleName: String,
        val endDate: String,
        val defaultEpochDay: Long
    ) : Parsing(CourseImportIssueSeverity.WARNING)

    data class InvalidCourseDayOfWeekSkipped(
        val courseName: String,
        val courseSession: String
    ) : Parsing(CourseImportIssueSeverity.WARNING)

    data class InvalidCourseTimeSkipped(
        val courseName: String,
        val courseSession: String
    ) : Parsing(CourseImportIssueSeverity.WARNING)

    data class InvalidCourseWeeksSkipped(
        val courseName: String,
        val courseSession: String
    ) : Parsing(CourseImportIssueSeverity.WARNING)

    data class InvalidCustomTimeFallbackToSections(
        val courseName: String,
        val courseSession: String
    ) : Parsing(CourseImportIssueSeverity.WARNING)

    object InvalidSleepyFormat : Parsing(CourseImportIssueSeverity.ERROR)

    data class UnknownSleepyVersion(
        val version: Int
    ) : Parsing(CourseImportIssueSeverity.WARNING)

    data class InvalidSleepyLineSkipped(
        val lineNumber: Int,
        val line: String
    ) : Parsing(CourseImportIssueSeverity.WARNING)

    data class ConflictingSleepyLineSkipped(
        val lineNumber: Int,
        val line: String
    ) : Parsing(CourseImportIssueSeverity.WARNING)

    data class SleepyDeclaredCourseCountMismatch(
        val declaredCount: Int,
        val actualCount: Int
    ) : Parsing(CourseImportIssueSeverity.WARNING)

    data class SleepyDeclaredTimeSlotsCountMismatch(
        val periodScheduleName: String,
        val declaredCount: Int,
        val actualCount: Int
    ) : Parsing(CourseImportIssueSeverity.WARNING)

    data class SleepyChecksumAlgorithmUnsupported(
        val algorithm: String
    ) : Parsing(CourseImportIssueSeverity.WARNING)

    object SleepyChecksumMismatch : Parsing(CourseImportIssueSeverity.WARNING)

    sealed class Normalization(severity: CourseImportIssueSeverity) : CourseImportIssue(severity)

    object InvalidSemesterStartEpochDayRemoved : Normalization(CourseImportIssueSeverity.WARNING)

    data class InvalidSeasonDateRangeFallback(
        val periodScheduleName: String,
        val adjustedStartEpochDay: Long,
        val adjustedEndEpochDay: Long
    ) : Normalization(CourseImportIssueSeverity.WARNING)

    data class InvalidSlotRemoved(
        val periodScheduleName: String,
        val slot: TimeSlot
    ) : Normalization(CourseImportIssueSeverity.WARNING)

    data class ConflictingSlotsNumbersRemoved(
        val periodScheduleName: String,
        val numbers: List<Int>
    ) : Normalization(CourseImportIssueSeverity.WARNING)

    data class OverlappingSlotsRemoved(
        val periodScheduleName: String,
        val numbers: List<Int>
    ) : Normalization(CourseImportIssueSeverity.WARNING)

    data class NonChronologicalSlotsRemoved(
        val periodScheduleName: String,
        val numbers: List<Int>
    ) : Normalization(CourseImportIssueSeverity.WARNING)

    data class EmptySeasonalPeriodScheduleRemoved(
        val periodScheduleName: String,
    ) : Normalization(CourseImportIssueSeverity.WARNING)

    data class PeriodScheduleClearedForSectionGap(
        val periodScheduleName: String,
        val section: Int
    ) : Normalization(CourseImportIssueSeverity.WARNING)

    data class EmptySessionCourse(
        val courseName: String
    ) : Normalization(CourseImportIssueSeverity.INFO)

    data class InvalidSessionWeeksRemoved(
        val courseName: String,
        val removedWeeks: List<Int>
    ) : Normalization(CourseImportIssueSeverity.WARNING)

    data class TotalWeeksOutOfRangeAdjusted(
        val originalTotalWeeks: Int,
        val adjustedTotalWeeks: Int
    ) : Normalization(CourseImportIssueSeverity.WARNING)

    data class TotalWeeksExpandedForCourseWeeks(
        val originalTotalWeeks: Int,
        val adjustedTotalWeeks: Int
    ) : Normalization(CourseImportIssueSeverity.INFO)

    data class InvalidSessionsRemoved(
        val courseName: String,
        val sessionTime: CourseSessionTime,
    ) : Normalization(CourseImportIssueSeverity.WARNING)

    object NoValidPeriodSchedule : Normalization(CourseImportIssueSeverity.WARNING)

    data class UnknownSectionReferences(
        val numbers: List<Int>
    ) : Normalization(CourseImportIssueSeverity.WARNING)

}