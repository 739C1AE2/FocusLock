package com.github739c1ae2.focuslock.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
        ProfileAppRuleEntity::class,
        ScheduleEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(LockTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lockDao(): LockDao
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
        ).build()
    }

    @Provides
    @Singleton
    fun provideLockDao(@ApplicationContext context: Context, database: AppDatabase): LockDao {
        runBlocking {
            database.lockDao().initializeOrUpdateGlobalProfile(context.getString(R.string.global_profile_name))
        }
        return database.lockDao()
    }
}