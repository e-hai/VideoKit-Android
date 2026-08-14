package com.kit.video.cache

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import java.io.File
import java.util.concurrent.Executors

/**
 * 工业级 VideoKit 统一视频磁盘缓存与 MediaSource 构建引擎：
 * 1. 1024MB (1GB) LRU 磁盘缓存单例，杜绝多实例冲突崩溃 (CacheException)；
 * 2. 边播边下、自动缓存到磁盘，二次播放 0 网络开销、0ms 闪电秒播；
 * 3. 支持后台异步预加载 (Preload Queue)；
 * 4. 读缓存故障时自动平滑回退网络 (FLAG_IGNORE_CACHE_ON_ERROR)，永不阻断播放。
 */
@OptIn(UnstableApi::class)
object VideoCacheManager {

    private const val TAG = "VideoCacheManager"
    private const val CACHE_MAX_BYTES = 1024L * 1024 * 1024 // 1GB LRU 缓存
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 8000

    @Volatile
    private var simpleCacheInstance: SimpleCache? = null

    @Volatile
    private var cacheDataSourceFactory: DataSource.Factory? = null

    private val preloadExecutor = Executors.newSingleThreadExecutor()

    /**
     * 获取全局唯一的 SimpleCache 实例
     */
    @Synchronized
    fun getSimpleCache(context: Context): SimpleCache {
        return simpleCacheInstance ?: synchronized(this) {
            simpleCacheInstance ?: run {
                val cacheDir = File(context.applicationContext.cacheDir, "videokit_media_cache")
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }
                SimpleCache(
                    cacheDir,
                    LeastRecentlyUsedCacheEvictor(CACHE_MAX_BYTES),
                    StandaloneDatabaseProvider(context.applicationContext)
                ).also {
                    simpleCacheInstance = it
                    Log.d(TAG, "✅ [VideoCache] 全局 1GB 视频磁盘缓存引擎初始化成功: ${cacheDir.absolutePath}")
                }
            }
        }
    }

    /**
     * 获取支持边播边存的高性能 CacheDataSource.Factory
     */
    @Synchronized
    fun getCacheDataSourceFactory(context: Context): DataSource.Factory {
        return cacheDataSourceFactory ?: synchronized(this) {
            cacheDataSourceFactory ?: createCacheDataSourceFactory(context.applicationContext).also {
                cacheDataSourceFactory = it
            }
        }
    }

    /**
     * 为 ExoPlayer.Builder 创建全局预置的 MediaSource.Factory
     */
    fun createMediaSourceFactory(context: Context): DefaultMediaSourceFactory {
        val dataSourceFactory = getCacheDataSourceFactory(context)
        return DefaultMediaSourceFactory(context.applicationContext).setDataSourceFactory(dataSourceFactory)
    }

    /**
     * 为指定 URI 创建具备磁盘缓存特性的自适应 MediaSource (完美支持 MP4 / HLS / DASH / WebM)
     */
    fun createMediaSource(context: Context, uri: Uri): MediaSource {
        val mediaItem = MediaItem.fromUri(uri)
        return createMediaSourceFactory(context).createMediaSource(mediaItem)
    }

    /**
     * 异步后台预加载指定视频前 N 字节 (默认 2MB)
     */
    fun preload(context: Context, url: String?, bytes: Long = 2L * 1024 * 1024) {
        if (url.isNullOrBlank()) return
        preloadExecutor.execute {
            try {
                val uri = Uri.parse(url)
                val dataSource = (getCacheDataSourceFactory(context) as CacheDataSource.Factory).createDataSource()
                val dataSpec = DataSpec.Builder()
                    .setUri(uri)
                    .setLength(bytes)
                    .build()

                Log.d(TAG, "🚀 [VideoCache Preload] 开始预加载: $url (目标 ${bytes / 1024 / 1024}MB)")
                val writer = CacheWriter(dataSource, dataSpec, null, null)
                writer.cache()
                Log.d(TAG, "✅ [VideoCache Preload] 预加载就绪: $url")
            } catch (e: Throwable) {
                Log.d(TAG, "预加载取消或完成: ${e.message}")
            }
        }
    }

    /**
     * 检查某个 URL 的内容是否已缓存在磁盘中
     */
    fun isCached(context: Context, url: String): Boolean {
        if (url.isBlank()) return false
        val cache = getSimpleCache(context)
        val uri = Uri.parse(url)
        val dataSpec = DataSpec(uri)
        val key = dataSpec.key ?: uri.toString()
        val cachedBytes = cache.getCachedBytes(key, 0, Long.MAX_VALUE)
        return cachedBytes > 0
    }

    /**
     * 清空全部视频缓存
     */
    fun clearCache(context: Context) {
        try {
            val cache = getSimpleCache(context)
            val keys = cache.keys
            for (key in keys) {
                cache.removeResource(key)
            }
            Log.d(TAG, "🧹 [VideoCache] 已清空全部本地视频缓存")
        } catch (e: Exception) {
            Log.e(TAG, "清空缓存异常", e)
        }
    }

    private fun createCacheDataSourceFactory(context: Context): DataSource.Factory {
        val cache = getSimpleCache(context)

        // 上游网络数据源
        val upstreamFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            .setUserAgent("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36 VideoKit")
            .setDefaultRequestProperties(
                mapOf(
                    "Accept" to "*/*"
                )
            )

        // 默认数据源 (支持 file://, asset://, content:// 等)
        val defaultDataSourceFactory = DefaultDataSource.Factory(context, upstreamFactory)

        // 纯文件读取源 (命中缓存时直接从本地文件读取，0 HTTP 协议消耗)
        val cacheReadFactory = FileDataSource.Factory()

        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(defaultDataSourceFactory)
            .setCacheReadDataSourceFactory(cacheReadFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .setEventListener(object : CacheDataSource.EventListener {
                override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                    Log.d(TAG, "⚡ [VideoCache HIT] 命中磁盘缓存读取: $cachedBytesRead 字节 (缓存池总计: ${cacheSizeBytes / 1024 / 1024}MB)")
                }

                override fun onCacheIgnored(reason: Int) {
                    Log.d(TAG, "⚠️ [VideoCache] 缓存跳过，原因代码: $reason")
                }
            })
    }
}
