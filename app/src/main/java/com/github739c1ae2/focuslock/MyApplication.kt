package com.github739c1ae2.focuslock

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.github739c1ae2.focuslock.datastore.AppSettingsManager
import com.github739c1ae2.focuslock.guard.AccessibilityGuard
import com.github739c1ae2.focuslock.util.AppIconFetcher
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MyApplication : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var settingsManager: AppSettingsManager

    override fun onCreate() {
        super.onCreate()
        AccessibilityGuard.start(
            this,
            settingsManager.autoEnableAccessibilityEnabled
        )
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(AppIconFetcher.Factory(this@MyApplication))
            }
            .build()
    }

}