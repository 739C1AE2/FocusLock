package com.github739c1ae2.focuslock.database

import androidx.room3.withWriteTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 导入课程时，课程与名单配置的绑定方式。
 */
enum class CourseImportBinding {
    /** 全部绑定到指定的配置（默认全局配置） */
    PROFILE,

    /** 以指定配置为基准，为每门课程复制一个副本 */
    COPY_PER_COURSE
}

@Singleton
class CourseRepository @Inject constructor(
    private val courseDao: CourseDao,
    private val database: AppDatabase
) {

    fun observeCourseTables(courseTableId: Long = COURSE_TABLE_ID): Flow<CourseTableEntity> =
        courseDao.observeCourseTable(courseTableId).filterNotNull()

    fun observePeriodScheduleEntities(courseTableId: Long = COURSE_TABLE_ID): Flow<List<CoursePeriodScheduleEntity>> =
        courseDao.observePeriodSchedules(courseTableId)

    fun observeCourseEntityList(courseTableId: Long = COURSE_TABLE_ID): Flow<List<CourseListItem>> =
        courseDao.observeCourseList(courseTableId)

    suspend fun getCourseTable(): CourseTableEntity =
        requireNotNull(courseDao.getCourseTable(COURSE_TABLE_ID))

    suspend fun updateCourseTable(table: CourseTableEntity) = courseDao.updateCourseTable(table)

    suspend fun getActiveCourses(courseTableId: Long = COURSE_TABLE_ID): List<Course> =
        courseDao.getActiveCoursesWithSessions(courseTableId).map { it.toCourse() }

    suspend fun getBasePeriodSchedule(courseTableId: Long = COURSE_TABLE_ID): PeriodSchedule =
        requireNotNull(courseDao.getBasePeriodSchedule(courseTableId)).toPeriodSchedule()

    suspend fun getPeriodSchedule(id: Long): PeriodSchedule? {
        return courseDao.getPeriodSchedule(id)?.toPeriodSchedule()
    }

    suspend fun createPeriodSchedule(name: String, startEpochDay: Long, endEpochDay: Long, courseTableId: Long = COURSE_TABLE_ID): Long = database.withWriteTransaction {
        val newPeriodSchedule = getBasePeriodSchedule().copy(
            id = 0L,
            name = name,
            isBase = false,
            startEpochDay = startEpochDay,
            endEpochDay = endEpochDay
        )
        upsertPeriodSchedule(newPeriodSchedule, courseTableId)
    }

    suspend fun upsertPeriodSchedule(periodSchedule: PeriodSchedule, courseTableId: Long = COURSE_TABLE_ID): Long = database.withWriteTransaction {
        val periodScheduleEntity = CoursePeriodScheduleEntity(
            id = periodSchedule.id,
            courseTableId = courseTableId,
            name = periodSchedule.name,
            isBase = periodSchedule.isBase,
            startEpochDay = periodSchedule.startEpochDay,
            endEpochDay = periodSchedule.endEpochDay
        )
        val periodScheduleId = courseDao.upsertPeriodSchedule(periodScheduleEntity)
            .let { if (it == -1L) periodScheduleEntity.id else it }
        val slots = periodSchedule.slots.map { slot ->
            CourseTimeSlotEntity(
                courseTableId = courseTableId,
                periodScheduleId = periodSchedule.id,
                number = slot.number,
                startMinute = slot.startMinute,
                endMinute = slot.endMinute,
                alias = slot.alias
            )
        }
        courseDao.replaceTimeSlots(
            periodScheduleId = periodScheduleId,
            slots = slots
        )
        periodScheduleId
    }

    suspend fun deletePeriodSchedule(id: Long) {
        val periodSchedule = courseDao.getPeriodSchedule(id) ?: return
        require(!periodSchedule.isBase) { "Cannot delete the base period schedule" }
        courseDao.deletePeriodSchedule(id)
    }

    suspend fun getEffectiveTimeSlots(
        todayEpochDay: Long,
        courseTableId: Long = COURSE_TABLE_ID
    ): List<CourseTimeSlotEntity> =
        courseDao.getEffectiveTimeSlots(courseTableId, todayEpochDay)

    fun observeEffectiveTimeSlots(
        todayEpochDay: Long,
        courseTableId: Long = COURSE_TABLE_ID
    ): Flow<List<CourseTimeSlotEntity>> =
        courseDao.observeEffectiveTimeSlots(courseTableId, todayEpochDay)

    suspend fun getCourse(courseId: Long): Course? =
        courseDao.getCourseWithSessions(courseId)?.toCourse()

    private fun CourseWithSessions.toCourse(): Course = Course(
        id = course.id,
        name = course.name,
        profileId = course.profileId,
        sessions = sessions.map { it.toSession() }
    )

    suspend fun createCourse(name: String, courseTableId: Long = COURSE_TABLE_ID): Long = database.withWriteTransaction {
        courseDao.insertCourse(
            CourseEntity(
                courseTableId = courseTableId,
                name = name
            )
        )
    }

    suspend fun upsertCourse(course: Course, courseTableId: Long = COURSE_TABLE_ID): Long = database.withWriteTransaction {
        val courseId = if (course.id == 0L) {
            courseDao.insertCourse(
                CourseEntity(
                    courseTableId = courseTableId,
                    name = course.name,
                    profileId = course.profileId,
                    isActive = false
                )
            )
        } else {
            courseDao.updateCourseFields(
                id = course.id,
                courseTableId = courseTableId,
                name = course.name,
                profileId = course.profileId
            )
            course.id
        }
        val sessions = course.sessions.map { it.toEntity(courseId) }
        courseDao.replaceCourseSessions(course.id, sessions)
        courseId
    }

    suspend fun deleteCourse(id: Long) = courseDao.deleteCourseById(id)

    suspend fun deleteCourses(courseTableId: Long) = courseDao.deleteCourses(courseTableId)

    suspend fun deleteNonBasePeriodSchedules(courseTableId: Long) = courseDao.deleteNonBasePeriodSchedules(courseTableId)

    suspend fun updateCourseActiveStatus(courseId: Long, isActive: Boolean) =
        courseDao.updateCourseActiveStatus(courseId, isActive)


    private suspend fun CoursePeriodScheduleEntity.toPeriodSchedule(): PeriodSchedule = PeriodSchedule(
        id = id,
        name = name,
        isBase = isBase,
        startEpochDay = startEpochDay,
        endEpochDay = endEpochDay,
        slots = courseDao.getSlotsByPeriodSchedule(id).map { slot ->
            TimeSlot(
                number = slot.number,
                startMinute = slot.startMinute,
                endMinute = slot.endMinute,
                alias = slot.alias
            )
        }
    )

    private fun CourseSession.toEntity(courseId: Long): CourseSessionEntity =
        CourseSessionEntity(
            courseId = courseId,
            teacher = teacher,
            position = position,
            dayOfWeek = dayOfWeek,
            isCustomTime = time is CourseSessionTime.Custom,
            startSection = (time as? CourseSessionTime.Sections)?.startSection,
            endSection = (time as? CourseSessionTime.Sections)?.endSection,
            customStartMinute = (time as? CourseSessionTime.Custom)?.startMinute,
            customEndMinute = (time as? CourseSessionTime.Custom)?.endMinute,
            weeks = weeks
        )

    private fun CourseSessionEntity.toSession(): CourseSession = CourseSession(
        teacher = teacher,
        position = position,
        dayOfWeek = dayOfWeek,
        time = if (isCustomTime) {
            CourseSessionTime.Custom(
                startMinute = requireNotNull(customStartMinute),
                endMinute = requireNotNull(customEndMinute)
            )
        } else {
            CourseSessionTime.Sections(
                startSection = requireNotNull(startSection),
                endSection = requireNotNull(endSection)
            )
        },
        weeks = weeks
    )

}
