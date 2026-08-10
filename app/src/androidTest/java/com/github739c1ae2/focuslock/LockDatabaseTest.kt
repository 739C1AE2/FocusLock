package com.github739c1ae2.focuslock

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github739c1ae2.focuslock.adapter.ConfigValue
import com.github739c1ae2.focuslock.database.AppDatabase
import com.github739c1ae2.focuslock.database.GLOBAL_PROFILE_ID
import com.github739c1ae2.focuslock.database.LockDao
import com.github739c1ae2.focuslock.database.ProfileAppRuleEntity
import com.github739c1ae2.focuslock.database.ProfileEntity
import com.github739c1ae2.focuslock.database.ScheduleEntity
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.collections.emptyMap

@RunWith(AndroidJUnit4::class)
class LockDatabaseTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: LockDao

    @Before
    fun createDb() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries() // 单元测试允许主键直接查询
            .build()
        dao = db.lockDao()

        // 核心底线：每场测试前，必须初始化全局白名单
        dao.initializeOrUpdateGlobalProfile("全局白名单")
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun test_1_全局白名单的唯一性与查询() = runTest {
        // 1. 查询自动生成的全局白名单
        val globalProfile = dao.getGlobalProfile()
        assertNotNull(globalProfile)
        assertEquals(GLOBAL_PROFILE_ID, globalProfile!!.id)
        assertTrue(globalProfile.isGlobal)

        // 2. 再次尝试初始化，验证不会创建第二个全局 Profile，而是维持现状
        dao.initializeOrUpdateGlobalProfile("企图覆盖的名字")
        val checkProfile = dao.getGlobalProfile()
        assertEquals(GLOBAL_PROFILE_ID, checkProfile!!.id)
    }

    @Test
    fun test_2_时间段调度_CRUD_及常规与跨天查询() = runTest {
        // 1. 创建常规时间段（下午 14:00 到 16:00 -> 840到960分钟），绑定全局白名单
        val scheduleId = dao.insertSchedule(
            ScheduleEntity(
                name = "常规学习",
                daysOfWeek = "1,2,3",
                startMinute = 840,
                endMinute = 960,
                profileId = GLOBAL_PROFILE_ID
            )
        )
        assertNotEquals(0L, scheduleId)

        // 2. 修改时间段（改为绑定专属白名单前，先创建个专属 Profile）
        val customProfileId = dao.createCustomProfile(
            ProfileEntity(
                name = "深夜地狱模式",
                isGlobal = false
            )
        )
        val existingSchedule = dao.getScheduleById(scheduleId)!!
        val updatedSchedule = existingSchedule.copy(
            name = "地狱锁机",
            startMinute = 1320, // 22:00
            endMinute = 120,    // 次日 02:00 (跨天任务!)
            profileId = customProfileId
        )
        dao.updateSchedule(updatedSchedule)

        // 3. 验证修改结果
        val checkUpdated = dao.getScheduleById(scheduleId)!!
        assertEquals("地狱锁机", checkUpdated.name)
        assertEquals(customProfileId, checkUpdated.profileId)

        // 4. 核心：验证上一次修复的跨天查询算法！
        // 设想今天周二（"2"），时间是凌晨 01:00（60分钟）。
        // 按照跨天逻辑，这其实算作【周一的前半夜延续】，所以 yesterdayStr 传入 "1" (周一)
        val activeSchedules = dao.getActiveSchedules(
            todayStr = "2",
            yesterdayStr = "1",
            currentMinute = 60
        )
        assertEquals(1, activeSchedules.size)
        assertEquals(customProfileId, activeSchedules[0].profileId)

        // 5. 删除时间段
        dao.deleteSchedule(checkUpdated)
        val afterDelete = dao.getScheduleById(scheduleId)
        assertNull(afterDelete)
    }

    @Test
    fun test_3_白名单修改_添加_删除应用_更改适配器() = runTest {
        val targetApp = "com.tencent.mm"

        // 1. 添加应用到全局白名单
        val rule = ProfileAppRuleEntity(
            profileId = GLOBAL_PROFILE_ID,
            packageName = targetApp,
            appliedAdapterId = null, // 默认无特定适配器，受黑白名单模式控制
            adapterConfig = emptyMap()
        )
        val ruleId = dao.saveAppRule(rule)
        assertNotEquals(0L, ruleId)

        // 2. 查询全局白名单里的应用
        val globalRulesBefore = dao.getRulesByProfile(GLOBAL_PROFILE_ID)
        assertEquals(1, globalRulesBefore.size)
        assertEquals(targetApp, globalRulesBefore[0].packageName)
        assertNull(globalRulesBefore[0].appliedAdapterId)

        // 3. 修改白名单：为此应用挂载通用文本适配器，并注入由 UI 生成的通用配置 Map
        val mockUIConfig = mapOf(
            "blockKeywords" to ConfigValue.StringListValue(listOf("微信支付", "朋友圈")),
            "strictMode" to ConfigValue.BooleanValue(true)
        )
        val modifiedRule = globalRulesBefore[0].copy(
            appliedAdapterId = "generic_text",
            adapterConfig = mockUIConfig
        )
        dao.saveAppRule(modifiedRule)

        // 4. 再次验证修改结果
        val checkModified = dao.getAppRule(GLOBAL_PROFILE_ID, targetApp)!!
        assertEquals("generic_text", checkModified.appliedAdapterId)
        // 验证 TypeConverter 是否无损还原了 Map
        assertEquals(2, checkModified.adapterConfig.size)
        assertTrue((checkModified.adapterConfig["strictMode"] as ConfigValue.BooleanValue).value)

        // 5. 删除白名单中的应用
        dao.deleteAppRule(GLOBAL_PROFILE_ID, targetApp)
        val afterDeleteRule = dao.getAppRule(GLOBAL_PROFILE_ID, targetApp)
        assertNull(afterDeleteRule)
    }

    @Test
    fun test_4_专用白名单的创建_使用_以及删除后回归全局() = runTest {
        val studyApp = "com.netease.youdao"

        // 1. 创建一个专用白名单 Profile 组
        val dedicatedProfileId = dao.createCustomProfile(
            ProfileEntity(name = "专心背单词组", isGlobal = false, userAppMode = 0)
        )

        // 2. 在专用白名单里添加特定应用
        dao.saveAppRule(
            ProfileAppRuleEntity(profileId = dedicatedProfileId, packageName = studyApp)
        )

        // 3. 检查专用白名单内容
        val dedicatedRules = dao.getRulesByProfile(dedicatedProfileId)
        assertEquals(1, dedicatedRules.size)
        assertEquals(studyApp, dedicatedRules[0].packageName)

        // 4. 业务场景模拟：用户不想要这个独立名单了，删除专用名单下的全部规则
        dao.deleteAppRule(dedicatedProfileId, studyApp)

        // 5. 模拟时间调度器回滚：将曾经绑定在此专属 Profile 的时间段，重新挂载回全局白名单 ID
        val scheduleId = dao.insertSchedule(
            ScheduleEntity(name = "背单词时间", daysOfWeek = "1", startMinute = 0, endMinute = 60, profileId = dedicatedProfileId)
        )

        val runningSchedule = dao.getScheduleById(scheduleId)!!
        // UI 操作：改写绑定的 profileId 为 GLOBAL_PROFILE_ID
        val revertedSchedule = runningSchedule.copy(profileId = GLOBAL_PROFILE_ID)
        dao.updateSchedule(revertedSchedule)

        // 6. 最终验证：时间段已安全重新接入全局体系
        val finalCheckSchedule = dao.getScheduleById(scheduleId)!!
        assertEquals(GLOBAL_PROFILE_ID, finalCheckSchedule.profileId)
    }
}