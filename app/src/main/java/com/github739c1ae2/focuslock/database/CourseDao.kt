package com.github739c1ae2.focuslock.database

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Relation
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface CourseDao {

    @Query("SELECT * FROM course_tables WHERE id = :id LIMIT 1")
    suspend fun getCourseTable(id: Long): CourseTableEntity?

    @Query("SELECT * FROM course_tables WHERE id = :id LIMIT 1")
    fun observeCourseTable(id: Long): Flow<CourseTableEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCourseTable(courseTable: CourseTableEntity)

    @Transaction
    suspend fun initializeCourseTable(courseTableName: String, baseTimeTableName: String) {
        if (getCourseTable(COURSE_TABLE_ID) == null) {
            insertCourseTable(CourseTableEntity(id = COURSE_TABLE_ID, name = courseTableName))
            upsertTimeTable(
                CourseTimeTableEntity(
                    courseTableId = COURSE_TABLE_ID,
                    name = baseTimeTableName,
                    isBase = true,
                    startEpochDay = LocalDate.MIN.toEpochDay(),
                    endEpochDay = LocalDate.MAX.toEpochDay()
                )
            )
        }
    }

    @Update
    suspend fun updateCourseTable(courseTable: CourseTableEntity)

    // ---------------- 作息表 ----------------

    @Query("SELECT * FROM course_time_tables WHERE courseTableId = :courseTableId ORDER BY isBase DESC, id ASC")
    fun observeTimeTables(courseTableId: Long): Flow<List<CourseTimeTableEntity>>

    @Query("SELECT * FROM course_time_tables WHERE id = :id LIMIT 1")
    suspend fun getTimeTable(id: Long): CourseTimeTableEntity?

    @Query("SELECT * FROM course_time_tables WHERE courseTableId = :courseTableId AND isBase = 1 LIMIT 1")
    suspend fun getBaseTimeTable(courseTableId: Long): CourseTimeTableEntity?

    @Upsert
    suspend fun upsertTimeTable(timeTable: CourseTimeTableEntity): Long

    @Query("DELETE FROM course_time_tables WHERE id = :id")
    suspend fun deleteTimeTable(id: Long)

    @Query("DELETE FROM course_time_tables WHERE courseTableId = :courseTableId AND isBase = 0")
    suspend fun deleteNonBaseTimeTables(courseTableId: Long)

    @Query("SELECT * FROM course_time_slots WHERE timeTableId = :timeTableId ORDER BY number ASC")
    suspend fun getSlotsByTimeTable(timeTableId: Long): List<CourseTimeSlotEntity>

    @Query("DELETE FROM course_time_slots WHERE timeTableId = :timeTableId")
    suspend fun deleteSlotsByTimeTable(timeTableId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimeSlots(slots: List<CourseTimeSlotEntity>)

    @Transaction
    suspend fun replaceTimeSlots(timeTableId: Long, slots: List<CourseTimeSlotEntity>) {
        deleteSlotsByTimeTable(timeTableId)
        if (slots.isNotEmpty()) {
            insertTimeSlots(slots.map { it.copy(id = 0, timeTableId = timeTableId) })
        }
    }

    @Query("""
        SELECT s.*
        FROM course_time_slots AS s
        WHERE s.courseTableId = :courseTableId
          AND s.timeTableId = (
              SELECT t.id
              FROM course_time_tables AS t
              WHERE t.courseTableId = :courseTableId
                AND (
                    t.isBase = 1
                    OR (
                        t.isBase = 0
                        AND :todayEpochDay BETWEEN t.startEpochDay
                                               AND t.endEpochDay
                    )
                )
              ORDER BY
                  CASE WHEN t.isBase = 0 THEN 0 ELSE 1 END,
                  t.startEpochDay DESC
              LIMIT 1
          )
        ORDER BY s.number ASC
    """)
    suspend fun getEffectiveTimeSlots(
        courseTableId: Long,
        todayEpochDay: Long
    ): List<CourseTimeSlotEntity>

    // ---------------- 课程 ----------------

    @Query(
        """
        SELECT courses.*, profiles.name AS profileName,
            (SELECT COUNT(*) FROM course_sessions WHERE courseId = courses.id) AS sessionCount
        FROM courses
        INNER JOIN profiles ON courses.profileId = profiles.id
        WHERE courses.courseTableId = :courseTableId
        ORDER BY courses.id ASC
        """
    )
    fun observeCourseList(courseTableId: Long): Flow<List<CourseListItem>>

    @Transaction
    @Query("SELECT * FROM courses WHERE isActive = 1 AND courseTableId = :courseTableId")
    suspend fun getActiveCoursesWithSessions(courseTableId: Long): List<CourseWithSessions>

    @Transaction
    @Query("SELECT * FROM courses WHERE id = :id LIMIT 1")
    suspend fun getCourseWithSessions(id: Long): CourseWithSessions?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourse(course: CourseEntity): Long

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteCourseById(id: Long)

    @Query("DELETE FROM courses WHERE courseTableId = :courseTableId")
    suspend fun deleteCourses(courseTableId: Long)

    @Query("UPDATE courses SET isActive = :isActive WHERE id = :courseId")
    suspend fun updateCourseActiveStatus(courseId: Long, isActive: Boolean)

    @Query("UPDATE courses SET courseTableId = :courseTableId, name = :name, profileId = :profileId WHERE id = :id")
    suspend fun updateCourseFields(id: Long, courseTableId: Long, name: String, profileId: Long)

    @Query("DELETE FROM course_sessions WHERE courseId = :courseId")
    suspend fun deleteSessionsByCourseId(courseId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<CourseSessionEntity>)

    @Transaction
    suspend fun replaceCourseSessions(courseId: Long, sessions: List<CourseSessionEntity>) {
        deleteSessionsByCourseId(courseId)
        insertSessions(sessions)
    }
}

data class CourseWithSessions(
    @Embedded val course: CourseEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["courseId"]) val sessions: List<CourseSessionEntity>
)

data class CourseListItem(
    @Embedded val course: CourseEntity,
    @ColumnInfo(name = "profileName") val profileName: String,
    @ColumnInfo(name = "sessionCount") val sessionCount: Int
)
