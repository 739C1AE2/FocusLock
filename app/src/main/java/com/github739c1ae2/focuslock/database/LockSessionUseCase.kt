package com.github739c1ae2.focuslock.database

import androidx.room3.withWriteTransaction
import com.github739c1ae2.focuslock.engine.CourseOccurrence
import com.github739c1ae2.focuslock.engine.CourseScheduleData
import com.github739c1ae2.focuslock.engine.CourseScheduleResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

class LockSessionUseCase @Inject constructor(
    private val lockRepository: LockRepository,
    private val courseRepository: CourseRepository,
    private val database: AppDatabase
) {


    suspend fun getActiveSession(): ActiveLockSession? = database.withWriteTransaction {
        val now = System.currentTimeMillis()
        val quickLock = lockRepository.getActiveQuickLock(now)
        if (quickLock != null) {
            return@withWriteTransaction ActiveLockSession(
                profileId = quickLock.profileId,
                startTimeMillis = quickLock.startTimestamp,
                endTimeMillis = quickLock.endTimestamp,
                source = LockSource.QuickLock
            )
        }

        // 课程时段优先于普通时间段
        val courseOccurrence = getActiveCourseOccurrence(now)
        if (courseOccurrence != null) {
            return@withWriteTransaction ActiveLockSession(
                profileId = courseOccurrence.profileId,
                startTimeMillis = courseOccurrence.startMillis,
                endTimeMillis = courseOccurrence.endMillis,
                source = LockSource.CourseSession(
                    courseId = courseOccurrence.courseId
                )
            )
        }

        val currentDateTime = LocalDateTime.now()
        val currentMinute = currentDateTime.hour * 60 + currentDateTime.minute
        val today = currentDateTime.dayOfWeek
        val yesterday = today.minus(1)

        val activeSchedules = lockRepository.getActiveScheduleEntities(
            todayStr = today.value.toString(),
            yesterdayStr = yesterday.value.toString(),
            currentMinute = currentMinute
        )
        return@withWriteTransaction activeSchedules.firstOrNull()?.let { schedule ->
            val (startTime, endTime) = schedule.getStartAndEndTimeMillis(now)
            ActiveLockSession(
                profileId = schedule.profileId,
                startTimeMillis = startTime,
                endTimeMillis = endTime,
                source = LockSource.Schedule(scheduleId = schedule.id)
            )
        }
    }


    private suspend fun getActiveCourseOccurrence(nowMillis: Long): CourseOccurrence? {
        return CourseScheduleResolver.findActive(loadCourseScheduleData(nowMillis), nowMillis)
    }

    private suspend fun loadCourseScheduleData(nowMillis: Long): CourseScheduleData {
        val table = courseRepository.getCourseTable()
        val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val todayEpochDay = today.toEpochDay()
        return CourseScheduleData(
            totalWeeks = table.semesterTotalWeeks,
            firstDayOfWeek = table.firstDayOfWeek,
            semesterStartEpochDay = table.semesterStartEpochDay,
            effectiveTimeSlots = courseRepository.getEffectiveTimeSlots(
                todayEpochDay = todayEpochDay,
                courseTableId = table.id
            ),
            courses = courseRepository.getActiveCourses()
        )
    }

    suspend fun getNextStartTimeMillis(nowMillis: Long): Long? =
        withContext(Dispatchers.IO) {
            database.withWriteTransaction {
                val closestStartMillis = lockRepository.getNextScheduleStartTimeMillis(nowMillis)
                val courseNext = CourseScheduleResolver.findNextStart(
                    loadCourseScheduleData(nowMillis),
                    nowMillis
                )
                return@withWriteTransaction listOfNotNull(
                    closestStartMillis,
                    courseNext
                ).minOrNull()
            }
        }


    suspend fun cancelSession(session: ActiveLockSession) = database.withWriteTransaction {
        when (val source = session.source) {
            is LockSource.QuickLock -> {
                lockRepository.stopQuickLock()
            }

            is LockSource.Schedule -> {
                lockRepository.updateScheduleActiveStatus(source.scheduleId, false)
            }

            is LockSource.CourseSession -> {
                courseRepository.updateCourseActiveStatus(source.courseId, false)
            }
        }
    }


    suspend fun completeSession(session: ActiveLockSession) = database.withWriteTransaction {
        when (val source = session.source) {
            is LockSource.QuickLock -> {
                lockRepository.stopQuickLock()
            }

            is LockSource.Schedule -> {
                val schedule = lockRepository.getSchedule(source.scheduleId)
                if (schedule?.daysOfWeek?.isEmpty() == true) {
                    // 一次性任务，完成后直接停用
                    lockRepository.updateScheduleActiveStatus(source.scheduleId, false)
                }
            }

            is LockSource.CourseSession -> {
                // 周期性课程，无需处理
            }
        }
    }


}