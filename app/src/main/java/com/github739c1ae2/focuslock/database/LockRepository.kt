package com.github739c1ae2.focuslock.database

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.github739c1ae2.focuslock.adapter.ConfigValue
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import java.time.Instant
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
    data class CourseSession(val courseId: Long) : LockSource()
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

    suspend fun getProfile(profileId: Long): ProfileConfig = database.withReadTransaction {
        val profile = requireNotNull(dao.getProfileById(profileId))
        val rulesEntity = dao.getRulesByProfileId(profileId)

        val rulesMap = rulesEntity.associate { entity ->
            entity.packageName to AppRuleConfig(
                packageName = entity.packageName,
                appliedAdapterId = entity.appliedAdapterId,
                adapterConfig = entity.adapterConfig
            )
        }

        return@withReadTransaction ProfileConfig(
            profileId = profile.id,
            name = profile.name,
            userAppMode = profile.userAppMode,
            systemAppMode = profile.systemAppMode,
            rules = rulesMap
        )
    }


    suspend fun upsertProfile(config: ProfileConfig): Long = database.withWriteTransaction {
        val profileEntity = ProfileEntity(
            id = config.profileId,
            name = config.name,
            isGlobal = config.profileId == GLOBAL_PROFILE_ID,
            userAppMode = config.userAppMode,
            systemAppMode = config.systemAppMode
        )

        val actualProfileId = dao.upsertProfile(profileEntity)
            .let { if (it == -1L) profileEntity.id else it }

        val rulesToSave = config.rules.values.map { ruleConfig ->
            ProfileAppRuleEntity(
                profileId = actualProfileId,
                packageName = ruleConfig.packageName,
                appliedAdapterId = ruleConfig.appliedAdapterId,
                adapterConfig = ruleConfig.adapterConfig
            )
        }

        dao.replaceProfileRules(actualProfileId, rulesToSave)
        actualProfileId
    }


    fun observeAllProfileEntities(): Flow<List<ProfileEntity>> = dao.observeAllProfiles()

    suspend fun createProfile(name: String, baseProfileId: Long = GLOBAL_PROFILE_ID): Long = database.withWriteTransaction {
        val profile = getProfile(baseProfileId)
        val newProfileConfig = profile.copy(
            profileId = 0L,
            name = name
        )
        upsertProfile(newProfileConfig)
    }

    suspend fun deleteProfileById(profileId: Long) {
        require(profileId != GLOBAL_PROFILE_ID) { "Cannot delete the global profile" }
        dao.deleteProfileById(profileId)
    }

    suspend fun updateScheduleActiveStatus(scheduleId: Long, isActive: Boolean) {
        dao.updateScheduleActiveStatus(scheduleId, isActive)
    }

    suspend fun createSchedule(name: String): Long = database.withWriteTransaction {
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

    suspend fun updateSchedulesOrder(orderedIds: List<Long>) {
        dao.updateAllScheduleSortOrders(orderedIds)
    }

    suspend fun deleteScheduleById(id: Long) {
        dao.deleteScheduleById(id)
    }

    suspend fun getSchedule(id: Long): Schedule? {
        return dao.getScheduleById(id)?.toSchedule()
    }

    suspend fun updateSchedule(schedule: Schedule) =
        dao.updateScheduleDto(schedule)

    suspend fun startQuickLock(quickLock: QuickLockEntity) {
        require(quickLock.id == 1) { "QuickLockEntity id must be 1" }
        dao.startQuickLock(quickLock)
    }

    suspend fun getActiveQuickLock(currentTimeMillis: Long): QuickLockEntity? =
        dao.getActiveQuickLock(currentTimeMillis)

    suspend fun stopQuickLock() = dao.stopQuickLock()

    fun observeAllScheduleEntitiesWithProfileName(): Flow<List<ScheduleWithProfileName>> {
        return dao.observeAllSchedulesWithProfile()
    }

    fun observeScheduleEntitiesForProfile(profileId: Long): Flow<List<ScheduleEntity>> {
        return dao.observeSchedulesByProfileId(profileId)
    }

    suspend fun getActiveScheduleEntities(
        todayStr: String,
        yesterdayStr: String,
        currentMinute: Int
    ): List<ScheduleEntity> = dao.getActiveSchedules(todayStr, yesterdayStr, currentMinute)

    suspend fun getNextScheduleStartTimeMillis(nowMillis: Long): Long? {
        val allActive = dao.getAllActiveSchedulesBasic()
        if (allActive.isEmpty()) return null

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

        return closestStartMillis
    }

}