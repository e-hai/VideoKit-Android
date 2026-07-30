package com.kit.video.shortvideo

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.RawRes
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.File
import java.util.concurrent.Executors

/**
 * 短视频模块专用数据源与预缓存/预加载队列 (Preload Queue) 工厂
 */
@OptIn(UnstableApi::class)
object ShortVideoHelper {

    private const val TAG = "ShortVideoHelper"
    private const val CACHE_SIZE_BYTES = 512L * 1024 * 1024 // 512MB 短视频缓存
    private const val PRELOAD_BYTES = 2L * 1024 * 1024 // 预加载前 2MB 数据 (约 3~5 秒)
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 8000

    private var cacheFactory: DataSource.Factory? = null
    private var simpleCacheInstance: SimpleCache? = null
    private val executor = Executors.newSingleThreadExecutor()

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
        val dataSourceFactory = getCacheFactory(context.applicationContext)
        val mediaItem = MediaItem.fromUri(uri)
        return ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
    }

    /**
     * 后台异步线程预加载队列 (Preload Queue)：
     * 提前把下一个视频的前 2MB 数据静默缓存在磁盘中，实现 0ms 极速切页秒播！
     */
    fun preloadNextVideo(context: Context, videoUrl: String?) {
        if (videoUrl.isNull_or_empty()) return

        executor.execute {
            try {
                val uri = Uri.parse(videoUrl)
                val cache = getSimpleCache(context.applicationContext)
                val dataSource = (getCacheFactory(context.applicationContext) as CacheDataSource.Factory).createDataSource()
                val dataSpec = DataSpec.Builder()
                    .setUri(uri)
                    .setLength(PRELOAD_BYTES)
                    .build()

                Log.d(TAG, "🚀 [Preload Queue] 开始后台预加载下一个视频前 2MB 数据: $videoUrl")
                val writer = CacheWriter(
                    dataSource,
                    dataSpec,
                    null,
                    null
                )
                writer.cache()
                Log.d(TAG, "✅ [Preload Queue] 预加载完成，随时就绪秒播: $videoUrl")
            } catch (e: Throwable) {
                Log.d(TAG, "预加载取消或完成: ${e.message}")
            }
        }
    }

    private fun String?.isNull_or_empty(): Boolean = this == null || this.trim().isEmpty()

    @Synchronized
    fun getCacheFactory(context: Context): DataSource.Factory {
        return cacheFactory ?: synchronized(this) {
            cacheFactory ?: createCacheFactory(context).also { cacheFactory = it }
        }
    }

    @Synchronized
    private fun getSimpleCache(context: Context): SimpleCache {
        return simpleCacheInstance ?: synchronized(this) {
            val downDirectory = File(context.cacheDir, "short_videos")
            SimpleCache(
                downDirectory,
                LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES),
                StandaloneDatabaseProvider(context)
            ).also { simpleCacheInstance = it }
        }
    }

    private fun createCacheFactory(context: Context): DataSource.Factory {
        val cache = getSimpleCache(context)

        val upstreamDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36 VideoKit")
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36 VideoKit"
                )
            )

        return CacheDataSource.Factory()
            .setCache(cache)
            .setCacheReadDataSourceFactory(DefaultDataSource.Factory(context, upstreamDataSourceFactory))
            .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setEventListener(object : CacheDataSource.EventListener {
                override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                    Log.d(TAG, "短视频磁盘缓存命中: $cacheSizeBytes 字节, 读取: $cachedBytesRead 字节")
                }

                override fun onCacheIgnored(reason: Int) {
                    Log.d(TAG, "缓存忽略: $reason")
                }
            })
    }
}
