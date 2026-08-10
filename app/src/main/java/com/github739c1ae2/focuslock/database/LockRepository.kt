package com.github739c1ae2.focuslock.database

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
    val isGlobal: Boolean = false,
    val userAppMode: FilterMode = FilterMode.WHITELIST,
    val systemAppMode: FilterMode = FilterMode.BLACKLIST,
    val rules: Map<String, AppRuleConfig> = emptyMap()
)

@Singleton
class LockRepository @Inject constructor(private val dao: LockDao) {

    /**
     * 获取一个完整的、封装好的配置对象。UI 层拿到后直接塞给 ViewModel。
     */
    suspend fun getCompleteProfile(profileId: Long): ProfileConfig = withContext(Dispatchers.IO) {
        val profile = requireNotNull(dao.getProfileById(profileId))
        val rulesEntity = dao.getRulesByProfileId(profileId)

        val rulesMap = rulesEntity.associate { entity ->
            entity.packageName to AppRuleConfig(
                packageName = entity.packageName,
                appliedAdapterId = entity.appliedAdapterId,
                adapterConfig = entity.adapterConfig
            )
        }

        return@withContext ProfileConfig(
            profileId = profile.id,
            name = profile.name,
            isGlobal = profile.isGlobal,
            userAppMode = profile.userAppMode,
            systemAppMode = profile.systemAppMode,
            rules = rulesMap
        )
    }

    suspend fun saveCompleteProfile(config: ProfileConfig) = withContext(Dispatchers.IO) {
        val profileEntity = ProfileEntity(
            id = config.profileId,
            name = config.name,
            isGlobal = config.isGlobal,
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

    fun observeAllProfiles(): Flow<List<ProfileEntity>> = dao.observeAllProfiles()

    suspend fun saveSchedule(schedule: ScheduleEntity) = withContext(Dispatchers.IO) {
        if (schedule.id == 0L) {
            dao.insertSchedule(schedule)
        } else {
            dao.updateSchedule(schedule)
        }
    }

    suspend fun deleteSchedule(schedule: ScheduleEntity) = withContext(Dispatchers.IO) {
        dao.deleteSchedule(schedule)
    }

    suspend fun getScheduleById(id: Long): ScheduleEntity? = withContext(Dispatchers.IO) {
        dao.getScheduleById(id)
    }

    fun observeAllSchedules(): Flow<List<ScheduleWithProfileName>> {
        return dao.observeAllSchedulesWithProfile()
    }

    fun observeSchedulesForProfile(profileId: Long): Flow<List<ScheduleWithProfileName>> {
        return dao.observeSchedulesByProfileId(profileId)
    }

    suspend fun getActiveSchedules(now: LocalDateTime = LocalDateTime.now()): List<ScheduleEntity> = withContext(Dispatchers.IO) {
        val currentMinute = now.hour * 60 + now.minute
        val today = now.dayOfWeek
        val yesterday = today.minus(1)

        return@withContext dao.getActiveSchedules(
            todayStr = today.value.toString(),
            yesterdayStr = yesterday.value.toString(),
            currentMinute = currentMinute
        )
    }

    suspend fun getNextScheduleStartTimeMillis(nowMillis: Long): Long? = withContext(Dispatchers.IO) {
        val allActive = dao.getAllActiveSchedulesBasic()
        if (allActive.isEmpty()) return@withContext null

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

        return@withContext closestStartMillis
    }

    suspend fun getAppRule(profileId: Long, packageName: String): ProfileAppRuleEntity? {
        return dao.getAppRule(profileId, packageName)
    }
}