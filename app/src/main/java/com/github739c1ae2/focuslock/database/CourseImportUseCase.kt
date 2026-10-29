package com.github739c1ae2.focuslock.database

import androidx.room3.withWriteTransaction
import com.github739c1ae2.focuslock.course.CourseTableImport
import com.github739c1ae2.focuslock.course.SessionImport
import java.time.LocalDate
import javax.inject.Inject

class CourseImportUseCase @Inject constructor(
    private val lockRepository: LockRepository,
    private val courseRepository: CourseRepository,
    private val database: AppDatabase
) {

    suspend fun importCourseTable(
        tableImport: CourseTableImport,
        binding: CourseImportBinding,
        baseProfileId: Long
    ) = database.withWriteTransaction {
        val courseTable = courseRepository.getCourseTable()
        val basePeriodSchedule = courseRepository.getBasePeriodSchedule(courseTable.id)

        // 清空旧内容，保留下课表与基础作息
        courseRepository.deleteCourses(courseTable.id)
        courseRepository.deleteNonBasePeriodSchedules(courseTable.id)

        courseRepository.updateCourseTable(
            courseTable.copy(
                semesterStartEpochDay = tableImport.semesterStartEpochDay,
                semesterTotalWeeks = tableImport.totalWeeks,
                firstDayOfWeek = tableImport.firstDayOfWeek,
            )
        )

        courseRepository.upsertPeriodSchedule(
            PeriodSchedule(
                id = basePeriodSchedule.id,
                name = tableImport.basePeriodSchedule.name,
                isBase = true,
                startEpochDay = LocalDate.MIN.toEpochDay(),
                endEpochDay = LocalDate.MAX.toEpochDay(),
                slots = tableImport.basePeriodSchedule.slots
            )
        )

        tableImport.seasonalTables.forEach { season ->
            courseRepository.upsertPeriodSchedule (
                PeriodSchedule(
                    id = 0L,
                    name = season.name,
                    isBase = false,
                    startEpochDay = season.startEpochDay,
                    endEpochDay = season.endEpochDay,
                    slots = season.slots
                )
            )
        }

        tableImport.courses.forEach { course ->
            val profileId = when (binding) {
                CourseImportBinding.PROFILE -> baseProfileId
                CourseImportBinding.COPY_PER_COURSE -> {
                    lockRepository.createProfile(
                        name = course.name,
                        baseProfileId = baseProfileId,
                    )
                }
            }
            val sessions = course.sessions.map { it.toSession() }
            courseRepository.upsertCourse(
                Course(
                    id = 0L,
                    name = course.name,
                    profileId = profileId,
                    sessions = sessions
                )
            )
        }
    }


    private fun SessionImport.toSession(): CourseSession = CourseSession(
        teacher = teacher,
        position = position,
        dayOfWeek = dayOfWeek,
        time = time,
        weeks = weeks
    )

}