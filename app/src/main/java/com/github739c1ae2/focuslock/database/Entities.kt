package com.github739c1ae2.focuslock.database

import androidx.annotation.StringRes
import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.github739c1ae2.focuslock.R
import com.github739c1ae2.focuslock.adapter.ConfigValue
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

const val GLOBAL_PROFILE_ID = 1L

val WEEKDAY_SET = setOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY
)

@Entity(
    tableName = "schedules",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.SET_DEFAULT
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
    @ColumnInfo(defaultValue = "$GLOBAL_PROFILE_ID")
    val profileId: Long = GLOBAL_PROFILE_ID,
    val isActive: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val sortOrder: Int = 0
) {
    fun getStartAndEndTimeMillis(nowMillis: Long): Pair<Long, Long> {
        val zoneId = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
        val currentMinute = now.hour * 60 + now.minute

        var startDate = now.toLocalDate()
        var endDate = now.toLocalDate()

        if (startMinute > endMinute) {
            // 这是一个跨天任务
            if (currentMinute >= startMinute) {
                // 当前时间大于开始时间，说明现在是前一天
                // 那么它的结束时间应该在第二天
                endDate = endDate.plusDays(1)
            } else {
                // 如果 currentMinute < endMinute，说明现在是第二天了
                // 那么它的开始时间应该在前一天
                startDate = startDate.minusDays(1)
            }
        }

        val startTime = startDate.atTime(startMinute / 60, startMinute % 60)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        val endTime = endDate.atTime(endMinute / 60, endMinute % 60)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
        return Pair(startTime, endTime)
    }

    fun toEditDto(): ScheduleEditDto {
        return ScheduleEditDto(
            id = this.id,
            name = this.name,
            daysOfWeek = this.daysOfWeek,
            startMinute = this.startMinute,
            endMinute = this.endMinute,
            profileId = this.profileId
        )
    }
}

data class ScheduleEditDto(
    val id: Long = 0,
    val name: String,
    val daysOfWeek: Set<DayOfWeek>,
    val startMinute: Int,
    val endMinute: Int,
    val profileId: Long
)

@Entity(
    tableName = "quick_lock",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.SET_DEFAULT
        )
    ],
    indices = [Index("profileId")]
)
data class QuickLockEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(defaultValue = "$GLOBAL_PROFILE_ID")
    val profileId: Long = GLOBAL_PROFILE_ID,
    val startTimestamp: Long,
    val endTimestamp: Long
)

enum class AppRuleMode(val value: Int) {
    WHITELIST(0),
    BLACKLIST(1);

    companion object {
        fun fromInt(value: Int) = entries.firstOrNull { it.value == value }
    }

    @StringRes
    fun toStringRes(): Int {
        return when (this) {
            WHITELIST -> R.string.app_rule_mode_whitelist
            BLACKLIST -> R.string.app_rule_mode_blacklist
        }
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

    val userAppMode: AppRuleMode = AppRuleMode.WHITELIST,   // 默认普通应用全锁
    val systemAppMode: AppRuleMode = AppRuleMode.BLACKLIST  // 默认系统应用全放
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

const val COURSE_TABLE_ID = 1L

/**
 * 课程表（目前仅支持一张）。
 * 记录开学日期、周数、每周首日等信息，用于把「周次 + 星期」映射到实际日期。
 */
@Entity(tableName = "course_tables")
data class CourseTableEntity(
    @PrimaryKey val id: Long = COURSE_TABLE_ID,
    val name: String,
    val semesterStartEpochDay: Long? = null,
    val semesterTotalWeeks: Int = 20,
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
    val defaultClassDuration: Int = 45,
    val defaultBreakDuration: Int = 10
)

const val BASE_TIME_TABLE_ID = 1L

/**
 * 作息时间表。基础作息 [isBase]=true、id 固定为 [BASE_TIME_TABLE_ID]、长期生效；
 * 季节作息 [isBase]=false，按 [startEpochDay]..[endEpochDay] 生效。
 */
@Entity(
    tableName = "course_time_tables",
    foreignKeys = [
        ForeignKey(
            entity = CourseTableEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseTableId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("courseTableId")]
)
data class CourseTimeTableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseTableId: Long = COURSE_TABLE_ID,
    val name: String,
    @ColumnInfo(defaultValue = "0")
    val isBase: Boolean = false,
    val startEpochDay: Long? = null,
    val endEpochDay: Long? = null
)

/**
 * 作息表中的一个节次时间（number -> 起止时间）。
 */
@Entity(
    tableName = "course_time_slots",
    foreignKeys = [
        ForeignKey(
            entity = CourseTableEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseTableId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = CourseTimeTableEntity::class,
            parentColumns = ["id"],
            childColumns = ["timeTableId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("courseTableId"), Index("timeTableId")]
)
data class CourseTimeSlotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseTableId: Long = COURSE_TABLE_ID,
    val timeTableId: Long,
    val number: Int,
    val startMinute: Int,
    val endMinute: Int,
    val alias: String? = null
)

/**
 * 课程。同一门课可能对应多个 [CourseSessionEntity]。
 */
@Entity(
    tableName = "courses",
    foreignKeys = [
        ForeignKey(
            entity = CourseTableEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseTableId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.SET_DEFAULT
        )
    ],
    indices = [Index("courseTableId"), Index("profileId")]
)
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseTableId: Long = COURSE_TABLE_ID,
    val name: String,
    val teacher: String = "",
    val position: String = "",
    val colorInt: Int? = null,
    val remark: String? = null,
    @ColumnInfo(defaultValue = "$GLOBAL_PROFILE_ID")
    val profileId: Long = GLOBAL_PROFILE_ID,
    val isActive: Boolean = true
)

/**
 * 课程的具体时段。一个课程可以有多个时段。
 * - [isCustomTime] 为 true 时使用自定义起止分钟；
 * - 否则使用 [startSection]/[endSection] 跟随作息时间表。
 * - [weeks] 为上课周次（空集合视为每周）。
 */
@Entity(
    tableName = "course_sessions",
    foreignKeys = [
        ForeignKey(
            entity = CourseEntity::class,
            parentColumns = ["id"],
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("courseId")]
)
data class CourseSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val dayOfWeek: DayOfWeek,
    val isCustomTime: Boolean = false,
    val startSection: Int? = null,
    val endSection: Int? = null,
    val customStartMinute: Int? = null,
    val customEndMinute: Int? = null,
    val weeks: Set<Int> = emptySet()
)

/**
 * 强制解锁时记录「跳过本次」的标记，仅对该课程时段当天生效。
 */
@Entity(
    tableName = "course_session_skips",
    primaryKeys = ["sessionId", "skipEpochDay"],
    foreignKeys = [
        ForeignKey(
            entity = CourseSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("skipEpochDay")]
)
data class CourseSessionSkipEntity(
    val sessionId: Long,
    val skipEpochDay: Long
)