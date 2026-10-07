package com.github739c1ae2.focuslock.database

import androidx.room3.withWriteTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 导入课程时，课程与白名单的绑定方式。
 */
enum class CourseImportBinding {
    /** 全部绑定到全局配置 */
    GLOBAL,

    /** 全部绑定到指定的现有配置 */
    EXISTING_PROFILE,

    /** 以指定配置为基准，为每门课程复制一个副本 */
    COPY_PER_COURSE
}

data class CourseImportRequest(
    val json: String,
    val binding: CourseImportBinding,
    /** EXISTING_PROFILE / COPY_PER_COURSE 时的基准配置 ID */
    val baseProfileId: Long = GLOBAL_PROFILE_ID,
    val tableName: String
)

data class CourseImportResult(
    val tableName: String,
    val courseCount: Int,
    val sessionCount: Int,
    val createdProfileCount: Int
)

@Singleton
class CourseRepository @Inject constructor(
    private val dao: LockDao,
    private val database: AppDatabase,
    private val lockRepository: LockRepository
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    fun observeCourseTable(): Flow<CourseTableEntity> = dao.observeCourseTable().filterNotNull()

    fun observeAllTimeSlots(): Flow<List<CourseTimeSlotEntity>> = dao.observeAllTimeSlots()

    fun observeTimeTables(): Flow<List<CourseTimeTableEntity>> = dao.observeTimeTables()

    fun observeCourseList(): Flow<List<CourseListItem>> = dao.observeCourseList()

    suspend fun getCourseTable(): CourseTableEntity = withContext(Dispatchers.IO) {
        requireNotNull(dao.getCourseTable())
    }

    suspend fun saveCourseTable(table: CourseTableEntity) = withContext(Dispatchers.IO) {
        database.withWriteTransaction {
            val updated = dao.updateCourseTable(
                id = table.id,
                name = table.name,
                semesterStartEpochDay = table.semesterStartEpochDay,
                semesterTotalWeeks = table.semesterTotalWeeks,
                firstDayOfWeek = table.firstDayOfWeek,
                defaultClassDuration = table.defaultClassDuration,
                defaultBreakDuration = table.defaultBreakDuration
            )
            if (updated == 0) {
                dao.insertCourseTable(table)
            }
        }
    }

    suspend fun getTimeTableWithSlots(id: Long): Pair<CourseTimeTableEntity, List<CourseTimeSlotEntity>>? =
        withContext(Dispatchers.IO) {
            val timeTable = dao.getTimeTable(id) ?: return@withContext null
            timeTable to dao.getSlotsByTimeTable(id)
        }

    suspend fun saveTimeTable(
        timeTable: CourseTimeTableEntity,
        slots: List<CourseTimeSlotEntity>
    ): Long = withContext(Dispatchers.IO) {
        database.withWriteTransaction {
            val id = dao.insertTimeTable(timeTable.copy(courseTableId = COURSE_TABLE_ID))
            dao.deleteSlotsByTimeTable(id)
            if (slots.isNotEmpty()) {
                dao.insertTimeSlots(slots.map { it.copy(id = 0, courseTableId = COURSE_TABLE_ID, timeTableId = id) })
            }
            id
        }
    }

    suspend fun deleteTimeTable(id: Long) = withContext(Dispatchers.IO) {
        val timeTable = dao.getTimeTable(id) ?: return@withContext
        // 基础作息不可删除
        if (!timeTable.isBase) {
            dao.deleteTimeTable(id)
        }
    }

    suspend fun getCourseWithSessions(courseId: Long): CourseWithSessions? =
        withContext(Dispatchers.IO) {
            dao.getCourseWithSessions(courseId)
        }

    suspend fun createCourse(name: String): Long = withContext(Dispatchers.IO) {
        database.withWriteTransaction {
            dao.insertCourse(
                CourseEntity(
                    courseTableId = COURSE_TABLE_ID,
                    name = name
                )
            )
        }
    }

    suspend fun saveCourse(course: CourseEntity, sessions: List<CourseSessionEntity>): Long =
        withContext(Dispatchers.IO) {
            database.withWriteTransaction {
                val existing = if (course.id != 0L) dao.getCourseById(course.id) else null
                val courseId = if (existing == null) {
                    dao.insertCourse(course.copy(courseTableId = COURSE_TABLE_ID))
                } else {
                    dao.insertCourse(
                        course.copy(
                            courseTableId = COURSE_TABLE_ID,
                            colorInt = existing.colorInt,
                            isActive = course.isActive
                        )
                    )
                    course.id
                }
                val toSave = sessions.mapIndexed { index, session ->
                    session.copy(id = 0, courseId = courseId)
                }
                dao.replaceCourseSessions(courseId, toSave)
                courseId
            }
        }

    suspend fun deleteCourse(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteCourseById(id)
    }

    suspend fun updateCourseActive(id: Long, active: Boolean) = withContext(Dispatchers.IO) {
        dao.updateCourseActiveStatus(id, active)
    }

    suspend fun clearSkipsBeforeToday() = withContext(Dispatchers.IO) {
        val today = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        dao.deleteSkipsBefore(today)
    }

    suspend fun importShiguangCourses(request: CourseImportRequest): CourseImportResult =
        withContext(Dispatchers.IO) {
            val parsed = json.decodeFromString<ShiguangCourseTable>(request.json)

            val grouped = parsed.courses.groupBy {
                CourseMergeKey(
                    name = it.name.trim(),
                    teacher = it.teacher.trim(),
                    position = it.position.trim()
                )
            }

            // 事务外预先创建每门课程的白名单副本，避免嵌套事务
            var createdProfiles = 0
            val profileIdByKey: Map<CourseMergeKey, Long> = when (request.binding) {
                CourseImportBinding.GLOBAL -> grouped.keys.associateWith { GLOBAL_PROFILE_ID }
                CourseImportBinding.EXISTING_PROFILE -> grouped.keys.associateWith { request.baseProfileId }
                CourseImportBinding.COPY_PER_COURSE -> {
                    val base = lockRepository.getCompleteProfile(request.baseProfileId)
                    grouped.keys.associateWith { key ->
                        val newId = lockRepository.saveCompleteProfile(
                            base.copy(profileId = 0L, name = key.name.ifBlank { "课程配置" })
                        )
                        createdProfiles++
                        newId
                    }
                }
            }

            database.withWriteTransaction {
                val config = parsed.config
                val tableName = request.tableName.ifBlank { "课程表" }

                // 删除旧课表（会级联删除课程、作息等），再插入新表
                dao.deleteCourseTable()
                dao.insertCourseTable(
                    CourseTableEntity(
                        id = COURSE_TABLE_ID,
                        name = tableName,
                        semesterStartEpochDay = config?.semesterStartDate?.let(::parseDateToEpochDay),
                        semesterTotalWeeks = (config?.semesterTotalWeeks ?: 20).coerceAtLeast(1),
                        firstDayOfWeek = DayOfWeek.of((config?.firstDayOfWeek ?: 1).coerceIn(1, 7)),
                        defaultClassDuration = config?.defaultClassDuration ?: 45,
                        defaultBreakDuration = config?.defaultBreakDuration ?: 10
                    )
                )
                // 基础作息随课表一起重建
                dao.insertTimeTable(
                    CourseTimeTableEntity(
                        id = BASE_TIME_TABLE_ID,
                        courseTableId = COURSE_TABLE_ID,
                        name = "基础作息",
                        isBase = true
                    )
                )

                val baseSlots = (parsed.timeSlots ?: emptyList()).map { slot ->
                    CourseTimeSlotEntity(
                        courseTableId = COURSE_TABLE_ID,
                        timeTableId = BASE_TIME_TABLE_ID,
                        number = slot.number,
                        startMinute = parseTimeToMinute(slot.startTime) ?: 0,
                        endMinute = parseTimeToMinute(slot.endTime) ?: 0,
                        alias = slot.alias
                    )
                }
                if (baseSlots.isNotEmpty()) dao.insertTimeSlots(baseSlots)

                parsed.comboSchedule?.publicSchedules?.forEach { season ->
                    val seasonalStart = parseDateToEpochDay(season.startDate)
                    val seasonalEnd = parseDateToEpochDay(season.endDate)
                    if (seasonalStart == null || seasonalEnd == null) return@forEach
                    val timeTableId = dao.insertTimeTable(
                        CourseTimeTableEntity(
                            courseTableId = COURSE_TABLE_ID,
                            name = season.name,
                            isBase = false,
                            startEpochDay = seasonalStart,
                            endEpochDay = seasonalEnd
                        )
                    )
                    val slots = season.timeSlots.map { slot ->
                        CourseTimeSlotEntity(
                            courseTableId = COURSE_TABLE_ID,
                            timeTableId = timeTableId,
                            number = slot.number,
                            startMinute = parseTimeToMinute(slot.startTime) ?: 0,
                            endMinute = parseTimeToMinute(slot.endTime) ?: 0,
                            alias = slot.alias
                        )
                    }
                    if (slots.isNotEmpty()) dao.insertTimeSlots(slots)
                }

                grouped.entries.forEach { (key, entries) ->
                    val courseId = dao.insertCourse(
                        CourseEntity(
                            courseTableId = COURSE_TABLE_ID,
                            name = key.name,
                            teacher = key.teacher,
                            position = key.position,
                            colorInt = entries.firstNotNullOfOrNull { it.color },
                            remark = entries.firstNotNullOfOrNull { it.remark },
                            profileId = profileIdByKey[key] ?: GLOBAL_PROFILE_ID,
                            isActive = true
                        )
                    )

                    val sessions = entries.map { entry ->
                        CourseSessionEntity(
                            courseId = courseId,
                            dayOfWeek = DayOfWeek.of(entry.day.coerceIn(1, 7)),
                            isCustomTime = entry.isCustomTime,
                            startSection = if (entry.isCustomTime) null else entry.startSection,
                            endSection = if (entry.isCustomTime) null else entry.endSection,
                            customStartMinute = if (entry.isCustomTime) parseTimeToMinute(entry.customStartTime) else null,
                            customEndMinute = if (entry.isCustomTime) parseTimeToMinute(entry.customEndTime) else null,
                            weeks = entry.weeks.filter { it > 0 }.toSet()
                        )
                    }
                    dao.insertSessions(sessions)
                }

                CourseImportResult(
                    tableName = tableName,
                    courseCount = grouped.size,
                    sessionCount = parsed.courses.size,
                    createdProfileCount = createdProfiles
                )
            }
        }

    private data class CourseMergeKey(
        val name: String,
        val teacher: String,
        val position: String
    )

    companion object {
        fun parseTimeToMinute(value: String?): Int? {
            if (value.isNullOrBlank()) return null
            val parts = value.trim().split(":")
            if (parts.size < 2) return null
            val hour = parts[0].toIntOrNull() ?: return null
            val minute = parts[1].toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return hour * 60 + minute
        }

        fun parseDateToEpochDay(value: String?): Long? {
            if (value.isNullOrBlank()) return null
            return try {
                LocalDate.parse(value.trim()).toEpochDay()
            } catch (_: Exception) {
                null
            }
        }
    }
}
