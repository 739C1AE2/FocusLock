package com.github739c1ae2.focuslock.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github739c1ae2.focuslock.adapter.ConfigValue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "migration-${UUID.randomUUID()}"
    private var database: AppDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun migrate2To3_preservesDataAndSupportsCourses() = runBlocking {
        createLegacyDatabase(version = 2)
        val migrated = openCurrentDatabase()
        assertLegacyData(migrated, expectedSortOrder = 7)

        val courseDao = migrated.courseDao()
        assertNull(courseDao.getCourseTable(COURSE_TABLE_ID))
        courseDao.initializeCourseTable("课程表", "基础作息")
        val table = requireNotNull(courseDao.getCourseTable(COURSE_TABLE_ID))
        assertEquals(CourseTableEntity(name = "课程表"), table)
        val base = requireNotNull(courseDao.getBasePeriodSchedule(table.id))
        assertTrue(base.isBase)
        assertEquals(LocalDate.MIN.toEpochDay(), base.startEpochDay)
        assertEquals(LocalDate.MAX.toEpochDay(), base.endEpochDay)

        // 初始化可重复执行，不覆盖已有设置或额外创建基础作息。
        courseDao.initializeCourseTable("新名称", "新作息")
        assertEquals(table, courseDao.getCourseTable(table.id))
        assertEquals(base, courseDao.getBasePeriodSchedule(table.id))
        val slot = CourseTimeSlotEntity(
            periodScheduleId = base.id, number = 1, startMinute = 480, endMinute = 525
        )
        courseDao.insertTimeSlots(listOf(slot))
        assertEquals(slot.copy(id = 1), courseDao.getSlotsByPeriodSchedule(base.id).single())

        val courseId = courseDao.insertCourse(CourseEntity(name = "数学", profileId = 2))
        val sectionSession = CourseSessionEntity(
            courseId = courseId, dayOfWeek = DayOfWeek.MONDAY,
            startSection = 1, endSection = 2, weeks = setOf(1, 3, 5)
        )
        val customSession = CourseSessionEntity(
            courseId = courseId, dayOfWeek = DayOfWeek.FRIDAY,
            teacher = "老师", position = "教室", isCustomTime = true,
            customStartMinute = 600, customEndMinute = 660, weeks = setOf(2, 4)
        )
        courseDao.insertSessions(listOf(sectionSession, customSession))
        val course = requireNotNull(courseDao.getCourseWithSessions(courseId))
        assertEquals(CourseEntity(id = courseId, name = "数学", profileId = 2), course.course)
        assertEquals(
            listOf(sectionSession.copy(id = 1), customSession.copy(id = 2)),
            course.sessions.sortedBy { it.id }
        )

        // 删除绑定配置后，课程应回退到全局配置，保留其时段。
        migrated.lockDao().deleteProfileById(2)
        val reassigned = requireNotNull(courseDao.getCourseWithSessions(courseId))
        assertEquals(GLOBAL_PROFILE_ID, reassigned.course.profileId)
        assertEquals(course.sessions.sortedBy { it.id }, reassigned.sessions.sortedBy { it.id })

        courseDao.deletePeriodSchedule(base.id)
        assertTrue(courseDao.getSlotsByPeriodSchedule(base.id).isEmpty())
        courseDao.deleteCourseById(courseId)
        assertNull(courseDao.getCourseWithSessions(courseId))
    }

    @Test
    fun migrate1To3_appliesBothMigrationsAndPreservesData() = runBlocking {
        createLegacyDatabase(version = 1)
        val migrated = openCurrentDatabase()
        assertLegacyData(migrated, expectedSortOrder = 480)
        migrated.courseDao().initializeCourseTable("课程表", "基础作息")
        assertNotNull(migrated.courseDao().getBasePeriodSchedule(COURSE_TABLE_ID))
    }

    private fun openCurrentDatabase(): AppDatabase = Room.databaseBuilder(
        context, AppDatabase::class.java, databaseName
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { database = it }

    private suspend fun assertLegacyData(database: AppDatabase, expectedSortOrder: Int) {
        val dao = database.lockDao()
        assertEquals(
            ProfileEntity(id = 1, name = "全局配置", isGlobal = true),
            dao.getProfileById(1)
        )
        assertEquals(
            ProfileEntity(
                id = 2, name = "自定义配置", userAppMode = AppRuleMode.BLACKLIST,
                systemAppMode = AppRuleMode.WHITELIST
            ),
            dao.getProfileById(2)
        )
        assertEquals(
            ScheduleEntity(
                id = 9, name = "旧计划", daysOfWeek = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                startMinute = 480, endMinute = 600, profileId = 2, isActive = false,
                sortOrder = expectedSortOrder
            ),
            dao.getScheduleById(9)
        )
        assertEquals(
            QuickLockEntity(profileId = 2, startTimestamp = 1000, endTimestamp = 2000),
            dao.getActiveQuickLock(1500)
        )
        assertEquals(
            ProfileAppRuleEntity(
                id = 4, profileId = 2, packageName = "com.example.app", appliedAdapterId = "test",
                adapterConfig = mapOf("enabled" to ConfigValue.BooleanValue(true))
            ),
            dao.getRulesByProfileId(2).single()
        )
    }

    // 固定历史结构，避免测试夹具随当前实体变化，掩盖迁移缺漏。
    // 对应 8dc7d10 的版本 2；版本 1 仅缺少 schedules.sortOrder。
    private fun createLegacyDatabase(version: Int) {
        val path = context.getDatabasePath(databaseName)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            legacy.execSQL("""
                CREATE TABLE profiles (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL,
                    isGlobal INTEGER NOT NULL, userAppMode INTEGER NOT NULL,
                    systemAppMode INTEGER NOT NULL
                )
            """.trimIndent())
            legacy.execSQL("CREATE INDEX index_profiles_isGlobal ON profiles (isGlobal)")
            legacy.execSQL("""
                CREATE TABLE quick_lock (
                    id INTEGER NOT NULL, profileId INTEGER NOT NULL DEFAULT 1,
                    startTimestamp INTEGER NOT NULL, endTimestamp INTEGER NOT NULL,
                    PRIMARY KEY(id), FOREIGN KEY(profileId) REFERENCES profiles(id)
                        ON UPDATE NO ACTION ON DELETE SET DEFAULT
                )
            """.trimIndent())
            legacy.execSQL("CREATE INDEX index_quick_lock_profileId ON quick_lock (profileId)")
            legacy.execSQL("""
                CREATE TABLE profile_app_rules (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, profileId INTEGER NOT NULL,
                    packageName TEXT NOT NULL, appliedAdapterId TEXT, adapterConfig TEXT NOT NULL,
                    FOREIGN KEY(profileId) REFERENCES profiles(id)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            legacy.execSQL("CREATE INDEX index_profile_app_rules_profileId ON profile_app_rules (profileId)")
            legacy.execSQL("CREATE INDEX index_profile_app_rules_packageName ON profile_app_rules (packageName)")
            val sortOrderColumn = if (version == 2) "sortOrder INTEGER NOT NULL DEFAULT 0," else ""
            legacy.execSQL("""
                CREATE TABLE schedules (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL,
                    daysOfWeek TEXT NOT NULL, startMinute INTEGER NOT NULL, endMinute INTEGER NOT NULL,
                    profileId INTEGER NOT NULL DEFAULT 1, isActive INTEGER NOT NULL,
                    $sortOrderColumn
                    FOREIGN KEY(profileId) REFERENCES profiles(id)
                        ON UPDATE NO ACTION ON DELETE SET DEFAULT
                )
            """.trimIndent())
            legacy.execSQL("CREATE INDEX index_schedules_profileId ON schedules (profileId)")
            legacy.execSQL("INSERT INTO profiles VALUES (1, '全局配置', 1, 0, 1)")
            legacy.execSQL("INSERT INTO profiles VALUES (2, '自定义配置', 0, 1, 0)")
            legacy.execSQL("""
                INSERT INTO schedules (id, name, daysOfWeek, startMinute, endMinute, profileId, isActive)
                VALUES (9, '旧计划', '1,5', 480, 600, 2, 0)
            """.trimIndent())
            if (version == 2) {
                legacy.execSQL("UPDATE schedules SET sortOrder = 7 WHERE id = 9")
            }
            legacy.execSQL("INSERT INTO quick_lock VALUES (1, 2, 1000, 2000)")
            legacy.execSQL("""
                INSERT INTO profile_app_rules VALUES (
                    4, 2, 'com.example.app', 'test', '{"enabled":{"type":"boolean","value":true}}'
                )
            """.trimIndent())
            legacy.version = version
        }
    }
}
