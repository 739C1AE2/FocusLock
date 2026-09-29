package com.github739c1ae2.focuslock.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppSettingsDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UnlockUsageDataStore

private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    @AppSettingsDataStore
    fun provideAppSettingsDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> {
        return context.settingsDataStore
    }

    @Provides
    @Singleton
    @UnlockUsageDataStore
    fun provideUnlockUsageDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> {
        return context.settingsDataStore
    }

}