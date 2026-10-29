package com.github739c1ae2.focuslock.database

import android.content.Context
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.github739c1ae2.focuslock.R
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import javax.inject.Singleton

@Database(
    entities = [
        ProfileEntity::class,
        QuickLockEntity::class,
        ProfileAppRuleEntity::class,
        ScheduleEntity::class,
        CourseTableEntity::class,
        CoursePeriodScheduleEntity::class,
        CourseTimeSlotEntity::class,
        CourseEntity::class,
        CourseSessionEntity::class
    ],
    version = 3,
    exportSchema = false
)
@ColumnTypeConverters(LockTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lockDao(): LockDao
    abstract fun courseDao(): CourseDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.prepare(
            "ALTER TABLE schedules ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0"
        ).use { it.step() }
        connection.prepare(
            "UPDATE schedules SET sortOrder = startMinute"
        ).use { it.step() }
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `course_tables` (
                `id` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `semesterStartEpochDay` INTEGER,
                `semesterTotalWeeks` INTEGER NOT NULL,
                `firstDayOfWeek` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `course_period_schedules` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `courseTableId` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `isBase` INTEGER NOT NULL DEFAULT 0,
                `startEpochDay` INTEGER NOT NULL,
                `endEpochDay` INTEGER NOT NULL,
                FOREIGN KEY(`courseTableId`) REFERENCES `course_tables`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_course_period_schedules_courseTableId` ON `course_period_schedules` (`courseTableId`)")
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `course_time_slots` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `courseTableId` INTEGER NOT NULL,
                `periodScheduleId` INTEGER NOT NULL,
                `number` INTEGER NOT NULL,
                `startMinute` INTEGER NOT NULL,
                `endMinute` INTEGER NOT NULL,
                `alias` TEXT NOT NULL,
                FOREIGN KEY(`courseTableId`) REFERENCES `course_tables`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`periodScheduleId`) REFERENCES `course_period_schedules`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_course_time_slots_courseTableId` ON `course_time_slots` (`courseTableId`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_course_time_slots_periodScheduleId` ON `course_time_slots` (`periodScheduleId`)")
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `courses` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `courseTableId` INTEGER NOT NULL,
                `name` TEXT NOT NULL,
                `profileId` INTEGER NOT NULL DEFAULT 1,
                `isActive` INTEGER NOT NULL,
                FOREIGN KEY(`courseTableId`) REFERENCES `course_tables`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE,
                FOREIGN KEY(`profileId`) REFERENCES `profiles`(`id`)
                    ON UPDATE NO ACTION ON DELETE SET DEFAULT
            )
        """.trimIndent())
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_courses_courseTableId` ON `courses` (`courseTableId`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_courses_profileId` ON `courses` (`profileId`)")
        connection.execSQL("""
            CREATE TABLE IF NOT EXISTS `course_sessions` (
                `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                `courseId` INTEGER NOT NULL,
                `dayOfWeek` INTEGER NOT NULL,
                `teacher` TEXT NOT NULL,
                `position` TEXT NOT NULL,
                `isCustomTime` INTEGER NOT NULL,
                `startSection` INTEGER,
                `endSection` INTEGER,
                `customStartMinute` INTEGER,
                `customEndMinute` INTEGER,
                `weeks` TEXT NOT NULL,
                FOREIGN KEY(`courseId`) REFERENCES `courses`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """.trimIndent())
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_course_sessions_courseId` ON `course_sessions` (`courseId`)")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "app_database"
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    }

    @Provides
    @Singleton
    fun provideLockDao(@ApplicationContext context: Context, database: AppDatabase): LockDao {
        runBlocking {
            database.lockDao().initializeOrUpdateGlobalProfile(context.getString(R.string.global_profile_name))
        }
        return database.lockDao()
    }

    @Provides
    @Singleton
    fun provideCourseDao(@ApplicationContext context: Context, database: AppDatabase): CourseDao {
        runBlocking {
            database.courseDao().initializeCourseTable(
                courseTableName = context.getString(R.string.course_table_title),
                basePeriodScheduleName = context.getString(R.string.base_period_schedule_name)
            )
        }
        return database.courseDao()
    }
}
