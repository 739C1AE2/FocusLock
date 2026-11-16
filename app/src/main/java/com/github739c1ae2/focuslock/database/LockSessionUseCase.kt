package com.github739c1ae2.focuslock.database

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.github739c1ae2.focuslock.engine.CourseScheduleData
import com.github739c1ae2.focuslock.engine.CourseScheduleResolver
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * 一次调度求值的结果。
 *
 * @property activeSession 当前锁定任务；没有命中任务时为 null。
 * @property nextWakeupMillis 下次重新求值的 Unix 毫秒时间戳；没有后续边界时为 null。
 */
data class SessionPlan(val activeSession: ActiveLockSession?, val nextWakeupMillis: Long?)

/** 读取锁定计划，并处理任务的完成和取消。 */
class LockSessionUseCase @Inject constructor(
    private val courseRepository: CourseRepository,
    private val database: AppDatabase,
) {
    /** 观察影响锁定计划或应用规则的数据表变化，订阅时立即发送一次通知。 */
    fun observeChanges(): Flow<Set<String>> = database.invalidationTracker.createFlow(
        "schedules", "quick_lock", "course_tables", "course_period_schedules",
        "course_time_slots", "courses", "course_sessions",
        // 应用规则修改不一定改变任务窗口，但仍需要引擎重新加载适配器。
        "profiles", "profile_app_rules",
        emitInitialState = true
    )

    /** 读取已启用的任务，返回当前锁定任务及下次唤醒时间。 */
    suspend fun getSessionPlan(): SessionPlan = database.withReadTransaction {
        val quickLock = database.lockDao().getQuickLock()
        val schedules = database.lockDao().getAllActiveSchedulesBasic()
        val courses = readCourseScheduleData()
        calculateSessionPlan(
            quickLock, courses, schedules, System.currentTimeMillis(), ZoneId.systemDefault()
        )
    }

    /**
     * 清理自然到期的手动锁定，或停用已执行完的一次性计划。
     *
     * @param session 待完成的已执行窗口。
     * @return 仅在一次性计划实际被停用时返回 true，其他情况返回 false。
     */
    suspend fun completeSession(session: ActiveLockSession): Boolean {
        // 任务可能因更高优先级任务开始而失去执行权，未到结束时间时不能当作完成。
        if (session.endTimeMillis > System.currentTimeMillis()) return false
        return database.withWriteTransaction {
            val dao = database.lockDao()
            val source = session.source
            if (source == LockSource.QuickLock) {
                dao.deleteQuickLockWindow(session.startTimeMillis, session.endTimeMillis)
                return@withWriteTransaction false
            }
            if (source !is LockSource.Schedule) return@withWriteTransaction false
            val schedule = dao.getScheduleById(source.scheduleId) ?: return@withWriteTransaction false
            if (!schedule.isActive || schedule.daysOfWeek.isNotEmpty()) return@withWriteTransaction false
            // 时间规则可能在执行期间被编辑，旧窗口的完成不能停用新窗口。
            val window = currentOrNextScheduleWindow(schedule, session.startTimeMillis, ZoneId.systemDefault())
            if (window == null || window.startMillis != session.startTimeMillis ||
                window.endMillis != session.endTimeMillis) return@withWriteTransaction false
            dao.updateScheduleActiveStatus(source.scheduleId, false)
            true
        }
    }

    /**
     * 取消当前时刻生效的全部任务，包括被高优先级任务遮住的任务。
     *
     * 手动锁定删除对应窗口，普通计划停用，课程停用整门课。
     */
    suspend fun cancelActiveSessions() = database.withWriteTransaction {
        val dao = database.lockDao()
        val quickLock = dao.getQuickLock()
        val schedules = dao.getAllActiveSchedulesBasic()
        val courses = readCourseScheduleData()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        if (quickLock != null && now >= quickLock.startTimestamp && now < quickLock.endTimestamp) {
            dao.deleteQuickLockWindow(quickLock.startTimestamp, quickLock.endTimestamp)
        }
        for (schedule in schedules) {
            val window = currentOrNextScheduleWindow(schedule, now, zone) ?: continue
            if (window.startMillis <= now) dao.updateScheduleActiveStatus(schedule.id, false)
        }
        for (course in courses.courses) {
            val isOngoing = course.sessions.any { session ->
                CourseScheduleResolver.occurrencesOn(courses, session, today, zone)
                    .any { now >= it.startMillis && now < it.endMillis }
            }
            if (isOngoing) courseRepository.updateCourseActiveStatus(course.id, false)
        }
    }

    /** 读取课程调度所需的学期、课程和作息配置。 */
    private suspend fun readCourseScheduleData(): CourseScheduleData {
        val table = courseRepository.getCourseTable()
        return CourseScheduleData(
            totalWeeks = table.semesterTotalWeeks,
            firstDayOfWeek = table.firstDayOfWeek,
            semesterStartEpochDay = table.semesterStartEpochDay,
            courses = courseRepository.getActiveCourses(),
            periodSchedules = courseRepository.getPeriodSchedules(table.id)
        )
    }
}

/**
 * 按手动锁定、课程、普通计划的优先级选择当前任务，并计算下次唤醒时间。
 *
 * @param courses 已启用的课程，课程及其时段均按优先级从高到低排列。
 * @param schedules 已启用的普通计划，按 sortOrder、id 升序排列。
 * @param nowMillis 求值时间的 Unix 毫秒时间戳。
 * @return 当前任务和下次唤醒时间；非空的唤醒时间严格晚于 nowMillis。
 */
internal fun calculateSessionPlan(
    quickLock: QuickLockEntity?,
    courses: CourseScheduleData,
    schedules: List<ScheduleEntity>,
    nowMillis: Long,
    zoneId: ZoneId,
): SessionPlan {
    var nextStartMillis: Long? = null
    fun includeStart(time: Long?) {
        val previous = nextStartMillis
        if (time != null && time > nowMillis && (previous == null || time < previous)) nextStartMillis = time
    }

    fun planFor(session: ActiveLockSession): SessionPlan = SessionPlan(
        activeSession = session,
        nextWakeupMillis = minOf(session.endTimeMillis, nextStartMillis ?: session.endTimeMillis),
    )

    // 按优先级遍历并在命中时返回，已收集的开始点才都来自可能抢占当前任务的规则。
    // 当前窗口结束后重新选择仍有效的任务即可，无需记录被抢占任务的恢复顺序。
    if (quickLock != null) {
        if (nowMillis >= quickLock.startTimestamp && nowMillis < quickLock.endTimestamp) {
            return planFor(ActiveLockSession(
                profileId = quickLock.profileId,
                startTimeMillis = quickLock.startTimestamp,
                endTimeMillis = quickLock.endTimestamp,
                source = LockSource.QuickLock,
            ))
        } else if (quickLock.endTimestamp > quickLock.startTimestamp) {
            includeStart(quickLock.startTimestamp)
        }
    }

    val today = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    for (course in courses.courses) {
        for (session in course.sessions) {
            val occurrence = CourseScheduleResolver.occurrencesOn(courses, session, today, zoneId)
                .firstOrNull { nowMillis >= it.startMillis && nowMillis < it.endMillis }
            if (occurrence != null) {
                return planFor(ActiveLockSession(
                    profileId = course.profileId,
                    startTimeMillis = occurrence.startMillis,
                    endTimeMillis = occurrence.endMillis,
                    source = LockSource.CourseSession(
                        courseId = course.id,
                        courseName = course.name,
                        teacher = session.teacher,
                        position = session.position
                    ),
                ))
            }
            includeStart(CourseScheduleResolver.nextStart(courses, session, nowMillis, zoneId))
        }
    }

    for (schedule in schedules) {
        val window = currentOrNextScheduleWindow(schedule, nowMillis, zoneId) ?: continue
        if (window.startMillis <= nowMillis) {
            return planFor(ActiveLockSession(
                profileId = schedule.profileId,
                startTimeMillis = window.startMillis,
                endTimeMillis = window.endMillis,
                source = LockSource.Schedule(schedule.id),
            ))
        }
        includeStart(window.startMillis)
    }
    return SessionPlan(activeSession = null, nextWakeupMillis = nextStartMillis)
}

/**
 * 查找普通计划当前或下一次的窗口；重复星期为空时不限制发生日期。
 *
 * @param nowMillis 查找起点的 Unix 毫秒时间戳。
 * @return 按开始时间排列的首个尚未结束的窗口；没有有效窗口时返回 null。
 */
private fun currentOrNextScheduleWindow(schedule: ScheduleEntity, nowMillis: Long, zone: ZoneId): ScheduleWindow? {
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    // 昨天覆盖仍在进行的跨午夜窗口，下周同一天覆盖本周唯一一次窗口已结束的情况。
    for (offset in -1L..7L) {
        val date = today.plusDays(offset)
        if (schedule.daysOfWeek.isNotEmpty() && date.dayOfWeek !in schedule.daysOfWeek) continue
        val window = scheduleWindowOnDate(schedule, date, zone) ?: continue
        if (window.endMillis > nowMillis) return window
    }
    return null
}

/**
 * 解析指定本地日期开始的普通计划窗口，结束分钟早于开始分钟时延续到次日。
 *
 * @return 绝对时间窗口；分钟无效、起止相同或转换后长度非正时返回 null。
 */
private fun scheduleWindowOnDate(schedule: ScheduleEntity, date: LocalDate, zone: ZoneId): ScheduleWindow? {
    val startMinute = schedule.startMinute
    val endMinute = schedule.endMinute
    if (startMinute !in 0 until 1440 || endMinute !in 0 until 1440 || startMinute == endMinute) return null
    val endDate = if (startMinute > endMinute) date.plusDays(1) else date
    // 夏令时切换日不一定有 24 小时，两端需要分别按本地日期和时区转换。
    val start = date.atTime(startMinute / 60, startMinute % 60).atZone(zone).toInstant().toEpochMilli()
    val end = endDate.atTime(endMinute / 60, endMinute % 60).atZone(zone).toInstant().toEpochMilli()
    return if (end > start) ScheduleWindow(start, end) else null
}

/** 使用 Unix 毫秒时间戳表示的左闭右开普通计划窗口。 */
private data class ScheduleWindow(val startMillis: Long, val endMillis: Long)
