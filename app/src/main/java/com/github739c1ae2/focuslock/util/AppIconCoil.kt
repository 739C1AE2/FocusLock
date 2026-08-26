package com.github739c1ae2.focuslock.util

import android.content.Context
import android.graphics.drawable.Drawable
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options

data class AppIconRequest(val packageName: String)

class AppIconFetcher(
    private val request: AppIconRequest,
    private val context: Context
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        return try {
            val pm = context.packageManager
            val drawable: Drawable = pm.getApplicationIcon(request.packageName)
            ImageFetchResult(
                image = drawable.asImage(),
                isSampled = false,
                dataSource = DataSource.DISK
            )
        } catch (_: Exception) {
            null
        }
    }

    class Factory(private val context: Context) : Fetcher.Factory<AppIconRequest> {
        override fun create(data: AppIconRequest, options: Options, imageLoader: ImageLoader): Fetcher {
            return AppIconFetcher(data, context)
        }
    }
}