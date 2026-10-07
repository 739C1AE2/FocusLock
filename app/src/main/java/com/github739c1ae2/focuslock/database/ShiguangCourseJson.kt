package com.github739c1ae2.focuslock.database

import kotlinx.serialization.Serializable

/**
 * 与 https://github.com/ShiGuangSchedule/shiguangschedule 导出的课程表 JSON 对应的模型。
 * 使用 ignoreUnknownKeys + coerceInputValues，尽量兼容缺失/多余字段。
 */
@Serializable
data class ShiguangCourseTable(
    val courses: List<ShiguangCourse> = emptyList(),
    val timeSlots: List<ShiguangTimeSlot>? = emptyList(),
    val config: ShiguangConfig? = null,
    val comboSchedule: ShiguangComboSchedule? = null
)

@Serializable
data class ShiguangCourse(
    val name: String,
    val teacher: String = "",
    val position: String = "",
    val day: Int,
    val startSection: Int? = null,
    val endSection: Int? = null,
    val weeks: List<Int> = emptyList(),
    val isCustomTime: Boolean = false,
    val customStartTime: String? = null,
    val customEndTime: String? = null,
    val color: Int? = null,
    val remark: String? = null,
    val credit: Float? = null
)

@Serializable
data class ShiguangTimeSlot(
    val number: Int,
    val startTime: String,
    val endTime: String,
    val alias: String? = null
)

@Serializable
data class ShiguangConfig(
    val semesterStartDate: String? = null,
    val semesterTotalWeeks: Int = 20,
    val defaultClassDuration: Int = 45,
    val defaultBreakDuration: Int = 10,
    val firstDayOfWeek: Int = 1
)

@Serializable
data class ShiguangComboSchedule(
    val name: String? = null,
    val publicSchedules: List<ShiguangPublicSchedule> = emptyList()
)

@Serializable
data class ShiguangPublicSchedule(
    val name: String,
    val startDate: String,
    val endDate: String,
    val defaultClassDuration: Int = 45,
    val defaultBreakDuration: Int = 10,
    val timeSlots: List<ShiguangTimeSlot> = emptyList()
)
