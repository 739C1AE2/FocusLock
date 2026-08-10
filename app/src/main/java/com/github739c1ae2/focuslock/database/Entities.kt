package com.github739c1ae2.focuslock.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.github739c1ae2.focuslock.adapter.ConfigValue
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

const val GLOBAL_PROFILE_ID = 1L

@Entity(
    tableName = "schedules",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index("profileId")]
)
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val daysOfWeek: Set<DayOfWeek>,
    val startMinute: Int,
    val endMinute: Int,
    val profileId: Long,
    val isActive: Boolean = true
) {
    fun getEndTimeMillisForToday(nowMillis: Long): Long {
        val zoneId = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val currentMinute = now.hour * 60 + now.minute

        var targetDate = now.toLocalDate()

        if (startMinute > endMinute) {
            // 这是一个跨天任务
            if (currentMinute >= startMinute) {
                // 当前时间大于开始时间，说明现在是前一天
                // 那么它的结束时间应该在第二天
                targetDate = targetDate.plusDays(1)
            }
            // 如果 currentMinute <= endMinute，说明现在是第二天了，不用加天数
        }

        val endHour = endMinute / 60
        val endMin = endMinute % 60

        return targetDate.atTime(endHour, endMin)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }
}

enum class FilterMode(val value: Int) {
    WHITELIST(0),
    BLACKLIST(1);

    companion object {
        fun fromInt(value: Int) = entries.firstOrNull { it.value == value }
    }
}

@Entity(
    tableName = "profiles",
    indices = [Index(value = ["isGlobal"], unique = false)]
)
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isGlobal: Boolean = false,

    val userAppMode: FilterMode = FilterMode.WHITELIST,   // 默认普通应用全锁
    val systemAppMode: FilterMode = FilterMode.BLACKLIST  // 默认系统应用全放
)

@Entity(
    tableName = "profile_app_rules",
    foreignKeys = [ForeignKey(
        entity = ProfileEntity::class,
        parentColumns = ["id"],
        childColumns = ["profileId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("profileId"), Index("packageName")]
)
data class ProfileAppRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val packageName: String,

    /**
     * 适配器 ID
     * 如果为 null，表示只是基础的黑白名单控制；
     * 如果不为 null，表示由对应的适配器接管判定。
     * */
    val appliedAdapterId: String? = null,

    /**
     * 适配器配置
     * 由对应的适配器自行解析，仅在适配器 ID 不为 null 时有效。
     * */
    val adapterConfig: Map<String, ConfigValue> = emptyMap()
)