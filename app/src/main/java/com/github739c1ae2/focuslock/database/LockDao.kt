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
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek

@Dao
interface LockDao {

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun getProfileById(id: Long): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE isGlobal = 1 LIMIT 1")
    suspend fun getGlobalProfile(): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfileRaw(profile: ProfileEntity): Long

    @Transaction
    suspend fun initializeOrUpdateGlobalProfile(name: String) {
        val existingGlobal = getGlobalProfile()
        if (existingGlobal == null) {
            insertProfileRaw(ProfileEntity(id = GLOBAL_PROFILE_ID, name = name, isGlobal = true))
        }
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun createCustomProfile(profile: ProfileEntity): Long

    @Update
    suspend fun updateProfile(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteProfileById(id: Long)

    @Query("SELECT * FROM profiles")
    fun observeAllProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    fun observeProfileById(id: Long): Flow<ProfileEntity?>


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: ScheduleEntity): Long

    @Query("UPDATE schedules SET isActive = :isActive WHERE id = :scheduleId")
    suspend fun updateScheduleActiveStatus(scheduleId: Long, isActive: Boolean)

    @Update(entity = ScheduleEntity::class)
    suspend fun updateScheduleEditDto(schedule: ScheduleEditDto)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun deleteScheduleById(id: Long)

    @Query("SELECT * FROM schedules WHERE id = :id LIMIT 1")
    suspend fun getScheduleById(id: Long): ScheduleEntity?

    @Query("UPDATE schedules SET isActive = 0 WHERE id = :scheduleId")
    suspend fun deactivateSchedule(scheduleId: Long)

    @Query("""
        SELECT schedules.*, profiles.name AS profileName 
        FROM schedules 
        INNER JOIN profiles ON schedules.profileId = profiles.id
        ORDER BY schedules.sortOrder ASC, schedules.id ASC
    """)
    fun observeAllSchedulesWithProfile(): Flow<List<ScheduleWithProfileName>>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM schedules")
    suspend fun getMaxScheduleSortOrder(): Int

    @Query("UPDATE schedules SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateScheduleSortOrder(id: Long, sortOrder: Int)

    @Transaction
    suspend fun updateAllScheduleSortOrders(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id ->
            updateScheduleSortOrder(id, index)
        }
    }

    @Query("SELECT * FROM schedules WHERE schedules.profileId = :profileId")
    fun observeSchedulesByProfileId(profileId: Long): Flow<List<ScheduleEntity>>


    @Query("""
        SELECT * FROM schedules 
        WHERE isActive = 1 
        AND (
            -- 常规不过夜任务 (比如 08:00 到 12:00)
            (startMinute <= endMinute 
             AND (daysOfWeek = '' OR daysOfWeek LIKE '%' || :todayStr || '%')
             AND :currentMinute >= startMinute 
             AND :currentMinute < endMinute)
             
            OR 
            
            -- 跨天任务的前一天 (比如 22:00 到 23:59)
            -- 此时处于配置的第一天
            (startMinute > endMinute 
             AND (daysOfWeek = '' OR daysOfWeek LIKE '%' || :todayStr || '%')
             AND :currentMinute >= startMinute)
             
            OR 
            
            -- 情况 3：跨天任务的后一天 (比如 00:00 到 06:00)
            -- 此时已经是第二天了，查昨天有没有配置这个任务
            (startMinute > endMinute 
             AND (daysOfWeek = '' OR daysOfWeek LIKE '%' || :yesterdayStr || '%')
             AND :currentMinute < endMinute)
        )
        ORDER BY sortOrder ASC, id ASC
    """)
    suspend fun getActiveSchedules(
        todayStr: String,
        yesterdayStr: String,
        currentMinute: Int
    ): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE isActive = 1")
    suspend fun getAllActiveSchedulesBasic(): List<ScheduleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun startQuickLock(quickLock: QuickLockEntity)

    @Query("DELETE FROM quick_lock WHERE id = 1")
    suspend fun stopQuickLock()

    @Query("""
        SELECT * FROM quick_lock 
        WHERE id = 1 
        AND startTimestamp <= :currentTimestampMillis 
        AND endTimestamp > :currentTimestampMillis 
        LIMIT 1
    """)
    suspend fun getActiveQuickLock(currentTimestampMillis: Long): QuickLockEntity?

    @Transaction
    suspend fun replaceProfileRules(profileId: Long, newRules: List<ProfileAppRuleEntity>) {
        deleteRulesByProfileId(profileId)
        insertAppRules(newRules)
    }

    @Query("DELETE FROM profile_app_rules WHERE profileId = :profileId")
    suspend fun deleteRulesByProfileId(profileId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppRules(rules: List<ProfileAppRuleEntity>)
    
    @Query("SELECT * FROM profile_app_rules WHERE profileId = :profileId")
    suspend fun getRulesByProfileId(profileId: Long): List<ProfileAppRuleEntity>

    // ---------------- 课程表 ----------------

    @Query("SELECT * FROM course_tables WHERE id = :id LIMIT 1")
    suspend fun getCourseTable(id: Long = COURSE_TABLE_ID): CourseTableEntity?

    @Query("SELECT * FROM course_tables WHERE id = :id LIMIT 1")
    fun observeCourseTable(id: Long = COURSE_TABLE_ID): Flow<CourseTableEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCourseTable(courseTable: CourseTableEntity)

    @Transaction
    suspend fun initializeCourseTable(courseTableName: String, baseTimeTableName: String) {
        if (getCourseTable() == null) {
            insertCourseTable(CourseTableEntity(id = COURSE_TABLE_ID, name = courseTableName))
        }
        if (getBaseTimeTable() == null) {
            insertTimeTable(
                CourseTimeTableEntity(
                    id = BASE_TIME_TABLE_ID,
                    courseTableId = COURSE_TABLE_ID,
                    name = baseTimeTableName,
                    isBase = true
                )
            )
        }
    }

    @Query(
        """
        UPDATE course_tables SET
            name = :name,
            semesterStartEpochDay = :semesterStartEpochDay,
            semesterTotalWeeks = :semesterTotalWeeks,
            firstDayOfWeek = :firstDayOfWeek,
            defaultClassDuration = :defaultClassDuration,
            defaultBreakDuration = :defaultBreakDuration
        WHERE id = :id
        """
    )
    suspend fun updateCourseTable(
        id: Long,
        name: String,
        semesterStartEpochDay: Long?,
        semesterTotalWeeks: Int,
        firstDayOfWeek: DayOfWeek,
        defaultClassDuration: Int,
        defaultBreakDuration: Int
    ): Int

    @Query("DELETE FROM course_tables WHERE id = :id")
    suspend fun deleteCourseTable(id: Long = COURSE_TABLE_ID)

    // ---------------- 作息表 ----------------

    @Query("SELECT * FROM course_time_tables WHERE courseTableId = :courseTableId ORDER BY isBase DESC, id ASC")
    suspend fun getTimeTables(courseTableId: Long = COURSE_TABLE_ID): List<CourseTimeTableEntity>

    @Query("SELECT * FROM course_time_tables WHERE courseTableId = :courseTableId ORDER BY isBase DESC, id ASC")
    fun observeTimeTables(courseTableId: Long = COURSE_TABLE_ID): Flow<List<CourseTimeTableEntity>>

    @Query("SELECT * FROM course_time_tables WHERE id = :id LIMIT 1")
    suspend fun getTimeTable(id: Long): CourseTimeTableEntity?

    @Query("SELECT * FROM course_time_tables WHERE courseTableId = :courseTableId AND isBase = 1 LIMIT 1")
    suspend fun getBaseTimeTable(courseTableId: Long = COURSE_TABLE_ID): CourseTimeTableEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimeTable(timeTable: CourseTimeTableEntity): Long

    @Query("DELETE FROM course_time_tables WHERE id = :id")
    suspend fun deleteTimeTable(id: Long)

    @Query("DELETE FROM course_time_tables WHERE courseTableId = :courseTableId")
    suspend fun deleteTimeTables(courseTableId: Long = COURSE_TABLE_ID)

    @Query("SELECT * FROM course_time_slots WHERE timeTableId = :timeTableId ORDER BY number ASC")
    suspend fun getSlotsByTimeTable(timeTableId: Long): List<CourseTimeSlotEntity>

    @Query("SELECT * FROM course_time_slots WHERE courseTableId = :courseTableId")
    suspend fun getAllTimeSlots(courseTableId: Long = COURSE_TABLE_ID): List<CourseTimeSlotEntity>

    @Query("SELECT * FROM course_time_slots WHERE courseTableId = :courseTableId")
    fun observeAllTimeSlots(courseTableId: Long = COURSE_TABLE_ID): Flow<List<CourseTimeSlotEntity>>

    @Query("DELETE FROM course_time_slots WHERE timeTableId = :timeTableId")
    suspend fun deleteSlotsByTimeTable(timeTableId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTimeSlots(slots: List<CourseTimeSlotEntity>)

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
    fun observeCourseList(courseTableId: Long = COURSE_TABLE_ID): Flow<List<CourseListItem>>

    @Transaction
    @Query("SELECT * FROM courses WHERE courseTableId = :courseTableId ORDER BY id ASC")
    suspend fun getCoursesWithSessions(courseTableId: Long = COURSE_TABLE_ID): List<CourseWithSessions>

    @Transaction
    @Query("SELECT * FROM courses WHERE isActive = 1 AND courseTableId = :courseTableId")
    suspend fun getActiveCoursesWithSessions(courseTableId: Long = COURSE_TABLE_ID): List<CourseWithSessions>

    @Transaction
    @Query("SELECT * FROM courses WHERE id = :id LIMIT 1")
    suspend fun getCourseWithSessions(id: Long): CourseWithSessions?

    @Query("SELECT * FROM courses WHERE id = :id LIMIT 1")
    suspend fun getCourseById(id: Long): CourseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourse(course: CourseEntity): Long

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteCourseById(id: Long)

    @Query("DELETE FROM courses WHERE courseTableId = :courseTableId")
    suspend fun deleteCourses(courseTableId: Long = COURSE_TABLE_ID)

    @Query("UPDATE courses SET isActive = :isActive WHERE id = :courseId")
    suspend fun updateCourseActiveStatus(courseId: Long, isActive: Boolean)

    @Query("SELECT * FROM course_sessions WHERE courseId = :courseId ORDER BY id ASC")
    suspend fun getSessionsByCourseId(courseId: Long): List<CourseSessionEntity>

    @Query("DELETE FROM course_sessions WHERE courseId = :courseId")
    suspend fun deleteSessionsByCourseId(courseId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(sessions: List<CourseSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSkip(skip: CourseSessionSkipEntity)

    @Query("SELECT sessionId FROM course_session_skips WHERE skipEpochDay = :epochDay")
    suspend fun getSkippedSessionIdsForDay(epochDay: Long): List<Long>

    @Query("SELECT sessionId FROM course_session_skips WHERE skipEpochDay = :epochDay")
    fun observeSkippedSessionIdsForDay(epochDay: Long): Flow<List<Long>>

    @Query("DELETE FROM course_session_skips WHERE skipEpochDay < :epochDay")
    suspend fun deleteSkipsBefore(epochDay: Long)

    @Query("DELETE FROM course_session_skips WHERE sessionId = :sessionId")
    suspend fun deleteSkipsBySession(sessionId: Long)

    @Transaction
    suspend fun replaceCourseSessions(courseId: Long, sessions: List<CourseSessionEntity>) {
        deleteSessionsByCourseId(courseId)
        insertSessions(sessions)
    }

}


data class ScheduleWithProfileName(
    @Embedded val schedule: ScheduleEntity,
    @ColumnInfo(name = "profileName") val profileName: String
)

data class CourseWithSessions(
    @Embedded val course: CourseEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["courseId"]) val sessions: List<CourseSessionEntity>
)

data class CourseListItem(
    @Embedded val course: CourseEntity,
    @ColumnInfo(name = "profileName") val profileName: String,
    @ColumnInfo(name = "sessionCount") val sessionCount: Int
)