package com.github739c1ae2.focuslock.database

import android.content.Context
import androidx.room3.ColumnTypeConverters
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
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
        CourseTimeTableEntity::class,
        CourseTimeSlotEntity::class,
        CourseEntity::class,
        CourseSessionEntity::class,
        CourseSessionSkipEntity::class
    ],
    version = 2,
    exportSchema = false
)
@ColumnTypeConverters(LockTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lockDao(): LockDao
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
        ).addMigrations(MIGRATION_1_2)
            .build()
    }

    @Provides
    @Singleton
    fun provideLockDao(@ApplicationContext context: Context, database: AppDatabase): LockDao {
        runBlocking {
            database.lockDao().apply {
                initializeOrUpdateGlobalProfile(context.getString(R.string.global_profile_name))
                initializeCourseTable(
                    courseTableName = context.getString(R.string.course_table_title),
                    baseTimeTableName = context.getString(R.string.base_time_table_name)
                )
            }
        }
        return database.lockDao()
    }
}
