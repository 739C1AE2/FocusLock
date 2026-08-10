package com.github739c1ae2.focuslock.database

import androidx.room.TypeConverter
import com.github739c1ae2.focuslock.adapter.ConfigValue
import kotlinx.serialization.json.Json
import java.time.DayOfWeek

class LockTypeConverters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromConfigMap(config: Map<String, ConfigValue>?): String {
        if (config == null) return "{}"
        return json.encodeToString(config)
    }

    @TypeConverter
    fun toConfigMap(jsonString: String?): Map<String, ConfigValue> {
        if (jsonString.isNullOrBlank()) return emptyMap()
        return try {
            json.decodeFromString(jsonString)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    @TypeConverter
    fun fromFilterMode(mode: FilterMode): Int {
        return mode.value
    }

    @TypeConverter
    fun toFilterMode(value: Int): FilterMode {
        return FilterMode.fromInt(value) ?: FilterMode.WHITELIST
    }

    @TypeConverter
    fun fromDayOfWeekSet(days: Set<DayOfWeek>?): String {
        if (days.isNullOrEmpty()) return ""
        // 转成逗号分隔的字符串，例如 "1,3,5"
        return days.joinToString(",") { it.value.toString() }
    }

    @TypeConverter
    fun toDayOfWeekSet(data: String?): Set<DayOfWeek> {
        if (data.isNullOrBlank()) return emptySet()
        return data.split(",")
            .mapNotNull { it.toIntOrNull() }
            .map { DayOfWeek.of(it) }
            .toSet()
    }

}