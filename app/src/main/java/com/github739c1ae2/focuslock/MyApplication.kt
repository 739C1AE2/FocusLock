package com.github739c1ae2.focuslock

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.github739c1ae2.focuslock.util.AppIconFetcher
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MyApplication : Application(), SingletonImageLoader.Factory {

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(AppIconFetcher.Factory(this@MyApplication))
            }
            .build()
    }

}