package com.github739c1ae2.focuslock.database

import androidx.room3.InvalidationTracker
import androidx.room3.withWriteTransaction
import com.github739c1ae2.focuslock.adapter.ConfigValue
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

data class AppRuleConfig(
    val packageName: String,
    val appliedAdapterId: String? = null,
    val adapterConfig: Map<String, ConfigValue> = emptyMap()
)

data class ProfileConfig(
    val profileId: Long = 0L,
    val name: String,
    val userAppMode: AppRuleMode = AppRuleMode.WHITELIST,
    val systemAppMode: AppRuleMode = AppRuleMode.BLACKLIST,
    val rules: Map<String, AppRuleConfig> = emptyMap()
)

sealed class LockSource {
    object QuickLock : LockSource()
    data class Schedule(val scheduleId: Long) : LockSource()
}

data class ActiveLockSession(
    val profileId: Long,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val source: LockSource
)

@Singleton
class LockRepository @Inject constructor(
    private val dao: LockDao,
    private val database: AppDatabase
) {

    suspend fun getCompleteProfile(profileId: Long): ProfileConfig = withContext(Dispatchers.IO) {
        database.withWriteTransaction {
            val profile = requireNotNull(dao.getProfileById(profileId))
            val rulesEntity = dao.getRulesByProfileId(profileId)

            val rulesMap = rulesEntity.associate { entity ->
                entity.packageName to AppRuleConfig(
                    packageName = entity.packageName,
                    appliedAdapterId = entity.appliedAdapterId,
                    adapterConfig = entity.adapterConfig
                )
            }

            return@withWriteTransaction ProfileConfig(
                profileId = profile.id,
                name = profile.name,
                userAppMode = profile.userAppMode,
                systemAppMode = profile.systemAppMode,
                rules = rulesMap
            )
        }
    }

    suspend fun saveCompleteProfile(config: ProfileConfig) = withContext(Dispatchers.IO) {
        database.withWriteTransaction {
            val profileEntity = ProfileEntity(
                id = config.profileId,
                name = config.name,
                isGlobal = config.profileId == GLOBAL_PROFILE_ID,
                userAppMode = config.userAppMode,
                systemAppMode = config.systemAppMode
            )

            val actualProfileId = if (config.profileId == 0L) {
                dao.createCustomProfile(profileEntity) // 新建
            } else {
                dao.updateProfile(profileEntity) // 更新
                config.profileId
            }

            val rulesToSave = config.rules.values.map { ruleConfig ->
                ProfileAppRuleEntity(
                    profileId = actualProfileId,
                    packageName = ruleConfig.packageName,
                    appliedAdapterId = ruleConfig.appliedAdapterId,
                    adapterConfig = ruleConfig.adapterConfig
                )
            }

            dao.replaceProfileRules(actualProfileId, rulesToSave)
        }
    }

    fun observeAllProfiles(): Flow<List<ProfileEntity>> = dao.observeAllProfiles()

    suspend fun createProfile(name: String): Long = withContext(Dispatchers.IO) {
        database.withWriteTransaction {
            val global = dao.getGlobalProfile()!!
            val newProfile = global.copy(id = 0, name = name, isGlobal = false)
            dao.createCustomProfile(newProfile)
        }
    }

    suspend fun deleteProfileById(profileId: Long) = withContext(Dispatchers.IO) {
        require(profileId != GLOBAL_PROFILE_ID) { "Cannot delete the global profile" }
        dao.deleteProfileById(profileId)
    }

    suspend fun updateScheduleActiveStatus(scheduleId: Long, isActive: Boolean) =
        withContext(Dispatchers.IO) {
            dao.updateScheduleActiveStatus(scheduleId, isActive)
        }

    suspend fun createSchedule(name: String): Long = withContext(Dispatchers.IO) {
        database.withWriteTransaction {
            val newSchedule = ScheduleEntity(
                name = name,
                daysOfWeek = WEEKDAY_SET,
                startMinute = 8 * 60,
                endMinute = 9 * 60,
                isActive = false,
                sortOrder = dao.getMaxScheduleSortOrder() + 1
            )
            dao.insertSchedule(newSchedule)
        }
    }

    suspend fun updateSchedulesOrder(orderedIds: List<Long>) = withContext(Dispatchers.IO) {
        dao.updateAllScheduleSortOrders(orderedIds)
    }

    suspend fun deleteScheduleById(id: Long) = withContext(Dispatchers.IO) {
        dao.deleteScheduleById(id)
    }

    suspend fun getScheduleEditDtoById(id: Long): ScheduleEditDto? = withContext(Dispatchers.IO) {
        dao.getScheduleById(id)?.toEditDto()
    }

    suspend fun updateScheduleEditDto(schedule: ScheduleEditDto) = withContext(Dispatchers.IO) {
        dao.updateScheduleEditDto(schedule)
    }

    suspend fun startQuickLock(quickLock: QuickLockEntity) = withContext(Dispatchers.IO) {
        require(quickLock.id == 1) { "QuickLockEntity id must be 1" }
        dao.startQuickLock(quickLock)
    }

    fun observeAllSchedules(): Flow<List<ScheduleWithProfileName>> {
        return dao.observeAllSchedulesWithProfile()
    }

    fun observeSchedulesForProfile(profileId: Long): Flow<List<ScheduleEntity>> {
        return dao.observeSchedulesByProfileId(profileId)
    }

    suspend fun getActiveSession(): ActiveLockSession? = withContext(Dispatchers.IO) {
        database.withWriteTransaction {
            val now = System.currentTimeMillis()
            val currentDateTime = LocalDateTime.now()
            val quickLock = dao.getActiveQuickLock(now)
            if (quickLock != null) {
                return@withWriteTransaction ActiveLockSession(
                    profileId = quickLock.profileId,
                    startTimeMillis = quickLock.startTimestamp,
                    endTimeMillis = quickLock.endTimestamp,
                    source = LockSource.QuickLock
                )
            }

            val currentMinute = currentDateTime.hour * 60 + currentDateTime.minute
            val today = currentDateTime.dayOfWeek
            val yesterday = today.minus(1)

            val activeSchedules = dao.getActiveSchedules(
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
    }

    suspend fun cancelSession(session: ActiveLockSession) = withContext(Dispatchers.IO) {
        when (val source = session.source) {
            is LockSource.QuickLock -> {
                dao.stopQuickLock()
            }

            is LockSource.Schedule -> {
                dao.deactivateSchedule(source.scheduleId)
            }
        }
    }

    suspend fun completeSession(session: ActiveLockSession) = withContext(Dispatchers.IO) {
        database.withWriteTransaction {
            when (val source = session.source) {
                is LockSource.QuickLock -> {
                    dao.stopQuickLock()
                }

                is LockSource.Schedule -> {
                    val schedule = dao.getScheduleById(source.scheduleId)
                    if (schedule?.daysOfWeek?.isEmpty() == true) {
                        // 一次性任务，完成后直接停用
                        dao.deactivateSchedule(source.scheduleId)
                    }
                }
            }
        }
    }

    suspend fun getNextScheduleStartTimeMillis(nowMillis: Long): Long? =
        withContext(Dispatchers.IO) {
            database.withWriteTransaction {
                val allActive = dao.getAllActiveSchedulesBasic()
                if (allActive.isEmpty()) return@withWriteTransaction null

                val zoneId = ZoneId.systemDefault()
                val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
                val todayDate = now.toLocalDate()
                val currentMinute = now.hour * 60 + now.minute

                var closestStartMillis: Long? = null

                for (schedule in allActive) {
                    if (schedule.daysOfWeek.isEmpty()) continue

                    var daysToAdd: Long? = null

                    for (i in 0L..7L) {
                        val targetLocalDate = todayDate.plusDays(i)
                        val checkDayOfWeek = targetLocalDate.dayOfWeek

                        if (schedule.daysOfWeek.contains(checkDayOfWeek)) {
                            if (i == 0L) {
                                if (currentMinute < schedule.startMinute) {
                                    daysToAdd = 0L
                                    break
                                }
                            } else {
                                daysToAdd = i
                                break
                            }
                        }
                    }

                    if (daysToAdd != null) {
                        val targetDate = todayDate.plusDays(daysToAdd)
                        val startHour = schedule.startMinute / 60
                        val startMin = schedule.startMinute % 60

                        val startMillis = targetDate.atTime(startHour, startMin)
                            .atZone(zoneId)
                            .toInstant()
                            .toEpochMilli()

                        if (closestStartMillis == null || startMillis < closestStartMillis) {
                            closestStartMillis = startMillis
                        }
                    }
                }

                return@withWriteTransaction closestStartMillis
            }
        }

    val invalidationTracker: InvalidationTracker
        get() = database.invalidationTracker

}