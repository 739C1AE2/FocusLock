package com.github739c1ae2.focuslock.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun createCustomProfile(profile: ProfileEntity): Long

    @Update
    suspend fun updateProfile(profile: ProfileEntity)

    @Delete
    suspend fun deleteProfile(profile: ProfileEntity)

    @Query("SELECT * FROM profiles")
    fun observeAllProfiles(): Flow<List<ProfileEntity>>


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: ScheduleEntity): Long

    @Update
    suspend fun updateSchedule(schedule: ScheduleEntity)

    @Delete
    suspend fun deleteSchedule(schedule: ScheduleEntity)

    @Query("SELECT * FROM schedules WHERE id = :id LIMIT 1")
    suspend fun getScheduleById(id: Long): ScheduleEntity?

    @Query("""
        SELECT schedules.*, profiles.name AS profileName 
        FROM schedules 
        INNER JOIN profiles ON schedules.profileId = profiles.id
        ORDER BY schedules.startMinute ASC
    """)
    fun observeAllSchedulesWithProfile(): Flow<List<ScheduleWithProfileName>>

    @Query("""
        SELECT schedules.*, profiles.name AS profileName 
        FROM schedules 
        INNER JOIN profiles ON schedules.profileId = profiles.id
        WHERE schedules.profileId = :profileId
        ORDER BY schedules.startMinute ASC
    """)
    fun observeSchedulesByProfileId(profileId: Long): Flow<List<ScheduleWithProfileName>>


    @Query("""
        SELECT * FROM schedules 
        WHERE isActive = 1 
        AND (
            -- 常规不过夜任务 (比如 08:00 到 12:00)
            (startMinute <= endMinute 
             AND daysOfWeek LIKE '%' || :todayStr || '%' 
             AND :currentMinute >= startMinute 
             AND :currentMinute <= endMinute)
             
            OR 
            
            -- 跨天任务的前一天 (比如 22:00 到 23:59)
            -- 此时处于配置的第一天
            (startMinute > endMinute 
             AND daysOfWeek LIKE '%' || :todayStr || '%' 
             AND :currentMinute >= startMinute)
             
            OR 
            
            -- 情况 3：跨天任务的后一天 (比如 00:00 到 06:00)
            -- 此时已经是第二天了，查昨天有没有配置这个任务
            (startMinute > endMinute 
             AND daysOfWeek LIKE '%' || :yesterdayStr || '%' 
             AND :currentMinute <= endMinute)
        )
    """)
    suspend fun getActiveSchedules(
        todayStr: String,
        yesterdayStr: String,
        currentMinute: Int
    ): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE isActive = 1")
    suspend fun getAllActiveSchedulesBasic(): List<ScheduleEntity>

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

    @Query("SELECT * FROM profile_app_rules WHERE profileId = :profileId AND packageName = :packageName LIMIT 1")
    suspend fun getAppRule(profileId: Long, packageName: String): ProfileAppRuleEntity?
}


data class ScheduleWithProfileName(
    @Embedded val schedule: ScheduleEntity,
    @ColumnInfo(name = "profileName") val profileName: String
)