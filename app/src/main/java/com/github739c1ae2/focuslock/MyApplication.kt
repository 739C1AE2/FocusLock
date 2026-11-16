package com.github739c1ae2.focuslock

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.github739c1ae2.focuslock.datastore.AppSettingsManager
import com.github739c1ae2.focuslock.guard.AccessibilityGuard
import com.github739c1ae2.focuslock.util.AppIconFetcher
import dagger.hilt.android.HiltAndroidApp
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class MyApplication : Application(), SingletonImageLoader.Factory {

    @Inject
    lateinit var settingsManager: AppSettingsManager

    override fun onCreate() {
        super.onCreate()
        warmUpChineseWeekdayCache()
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

    /**
     * 为 DatePicker 预热星期缓存，预热后中文 locale 可以命中缓存，而不会走有问题的引用比较
     *
     * TODO: 等待 desugar_jdk_libs 包含该修复的版本（> 2.1.5）发布后删除此方法
     *  https://github.com/google/desugar_jdk_libs/commit/fd0b59ead1385c95d89f0cf119c123fb619de104
     *  https://issuetracker.google.com/issues/300128109
     */
    private fun warmUpChineseWeekdayCache() {
        // 使用常量，保证 DesugarDateTimeTextProviderHelper.java 中的引用比较返回 true
        val locales = listOf(
            Locale.SIMPLIFIED_CHINESE,
            Locale.TRADITIONAL_CHINESE,
        )

        locales.forEach { locale ->
            DayOfWeek.MONDAY.getDisplayName(TextStyle.NARROW_STANDALONE, locale)
        }
    }

}