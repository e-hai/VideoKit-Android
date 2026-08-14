package com.kit.video.feed

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.annotation.RawRes
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.kit.video.cache.VideoCacheManager

/**
 * Feed 模块专用数据源构建与预加载桥接类（统一委托底层 [VideoCacheManager]）
 */
@OptIn(UnstableApi::class)
object FeedMediaHelper {

    fun createRawMediaSource(context: Context, @RawRes rawRes: Int): MediaSource {
        val rawResourceDataSource = RawResourceDataSource(context)
        val videoUri = Uri.Builder()
            .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
            .authority(context.packageName)
            .path(rawRes.toString())
            .build()
        val dataSpec = DataSpec(videoUri)
        try {
            rawResourceDataSource.open(dataSpec)
        } catch (e: RawResourceDataSource.RawResourceDataSourceException) {
            throw RuntimeException("打开 raw 资源失败", e)
        }
        val uri = rawResourceDataSource.uri ?: throw RuntimeException("未找到 raw 资源 URI")
        return createProgressiveMediaSource(context, uri)
    }

    fun createMediaSource(context: Context, uri: Uri): MediaSource {
        return createProgressiveMediaSource(context, uri)
    }

    private fun createProgressiveMediaSource(context: Context, uri: Uri): MediaSource {
        return VideoCacheManager.createMediaSource(context, uri)
    }

    /**
     * 后台异步线程预加载队列 (Preload Queue)
     */
    fun preloadNextVideo(context: Context, videoUrl: String?) {
        VideoCacheManager.preload(context, videoUrl)
    }

    fun getCacheFactory(context: Context): DataSource.Factory {
        return VideoCacheManager.getCacheDataSourceFactory(context)
    }
}
