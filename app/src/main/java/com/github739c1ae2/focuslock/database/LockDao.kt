package com.github739c1ae2.focuslock.database

import androidx.room3.ColumnInfo
import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

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

    @Upsert
    suspend fun upsertProfile(profile: ProfileEntity): Long

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteProfileById(id: Long)

    @Query("SELECT * FROM profiles")
    fun observeAllProfiles(): Flow<List<ProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: ScheduleEntity): Long

    @Query("UPDATE schedules SET isActive = :isActive WHERE id = :scheduleId")
    suspend fun updateScheduleActiveStatus(scheduleId: Long, isActive: Boolean)

    @Update(entity = ScheduleEntity::class)
    suspend fun updateScheduleDto(schedule: Schedule)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun deleteScheduleById(id: Long)

    @Query("SELECT * FROM schedules WHERE id = :id LIMIT 1")
    suspend fun getScheduleById(id: Long): ScheduleEntity?

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


    @Query("SELECT * FROM schedules WHERE isActive = 1 ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllActiveSchedulesBasic(): List<ScheduleEntity>

    @Query("SELECT * FROM quick_lock WHERE id = 1")
    suspend fun getQuickLock(): QuickLockEntity?

    @Query("DELETE FROM quick_lock WHERE id = 1 AND startTimestamp = :startMillis AND endTimestamp = :endMillis")
    suspend fun deleteQuickLockWindow(startMillis: Long, endMillis: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun startQuickLock(quickLock: QuickLockEntity)

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
        if (newRules.isNotEmpty()) {
            insertAppRules(newRules.map { it.copy(id = 0, profileId = profileId) })
        }
    }

    @Query("DELETE FROM profile_app_rules WHERE profileId = :profileId")
    suspend fun deleteRulesByProfileId(profileId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAppRules(rules: List<ProfileAppRuleEntity>)

    @Query("SELECT * FROM profile_app_rules WHERE profileId = :profileId")
    suspend fun getRulesByProfileId(profileId: Long): List<ProfileAppRuleEntity>
}

data class ScheduleWithProfileName(
    @Embedded val schedule: ScheduleEntity,
    @ColumnInfo(name = "profileName") val profileName: String
)
