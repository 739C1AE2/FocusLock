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

    fun observeTimeTableEntities(courseTableId: Long = COURSE_TABLE_ID): Flow<List<CourseTimeTableEntity>> =
        courseDao.observeTimeTables(courseTableId)

    fun observeCourseEntityList(courseTableId: Long = COURSE_TABLE_ID): Flow<List<CourseListItem>> =
        courseDao.observeCourseList(courseTableId)

    suspend fun getCourseTable(): CourseTableEntity =
        requireNotNull(courseDao.getCourseTable(COURSE_TABLE_ID))

    suspend fun updateCourseTable(table: CourseTableEntity) = courseDao.updateCourseTable(table)

    suspend fun getActiveCourses(courseTableId: Long = COURSE_TABLE_ID): List<Course> =
        courseDao.getActiveCoursesWithSessions(courseTableId).map { it.toCourse() }

    suspend fun getBaseTimeTable(courseTableId: Long = COURSE_TABLE_ID): TimeTable =
        requireNotNull(courseDao.getBaseTimeTable(courseTableId)).toTimeTable()

    suspend fun getTimeTable(id: Long): TimeTable? {
        return courseDao.getTimeTable(id)?.toTimeTable()
    }

    suspend fun createTimeTable(name: String, startEpochDay: Long, endEpochDay: Long, courseTableId: Long = COURSE_TABLE_ID): Long = database.withWriteTransaction {
        val newTimeTable = getBaseTimeTable().copy(
            id = 0L,
            name = name,
            isBase = false,
            startEpochDay = startEpochDay,
            endEpochDay = endEpochDay
        )
        upsertTimeTable(newTimeTable, courseTableId)
    }

    suspend fun upsertTimeTable(timeTable: TimeTable, courseTableId: Long = COURSE_TABLE_ID): Long = database.withWriteTransaction {
        val timeTableEntity = CourseTimeTableEntity(
            id = timeTable.id,
            courseTableId = courseTableId,
            name = timeTable.name,
            isBase = timeTable.isBase,
            startEpochDay = timeTable.startEpochDay,
            endEpochDay = timeTable.endEpochDay
        )
        val timeTableId = courseDao.upsertTimeTable(timeTableEntity)
            .let { if (it == -1L) timeTableEntity.id else it }
        val slots = timeTable.slots.map { slot ->
            CourseTimeSlotEntity(
                courseTableId = courseTableId,
                timeTableId = timeTable.id,
                number = slot.number,
                startMinute = slot.startMinute,
                endMinute = slot.endMinute,
                alias = slot.alias
            )
        }
        courseDao.replaceTimeSlots(
            timeTableId = timeTableId,
            slots = slots
        )
        timeTableId
    }

    suspend fun deleteTimeTable(id: Long) {
        val timeTable = courseDao.getTimeTable(id) ?: return
        require(!timeTable.isBase) { "Cannot delete the base time table" }
        courseDao.deleteTimeTable(id)
    }

    suspend fun getEffectiveTimeSlots(
        todayEpochDay: Long,
        courseTableId: Long = COURSE_TABLE_ID
    ): List<CourseTimeSlotEntity> =
        courseDao.getEffectiveTimeSlots(courseTableId, todayEpochDay)

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

    suspend fun deleteNonBaseTimeTables(courseTableId: Long) = courseDao.deleteNonBaseTimeTables(courseTableId)

    suspend fun updateCourseActiveStatus(courseId: Long, isActive: Boolean) =
        courseDao.updateCourseActiveStatus(courseId, isActive)


    private suspend fun CourseTimeTableEntity.toTimeTable(): TimeTable = TimeTable(
        id = id,
        name = name,
        isBase = isBase,
        startEpochDay = startEpochDay,
        endEpochDay = endEpochDay,
        slots = courseDao.getSlotsByTimeTable(id).map { slot ->
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
