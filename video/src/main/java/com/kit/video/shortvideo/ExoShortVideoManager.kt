package com.kit.video.shortvideo

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.RawRes
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.ui.PlayerView

/**
 * 抖音工业级短视频管理器 (带 Preload Queue 后台队列预加载与生命周期超时防护)：
 * 1. 响应 ON_PAUSE/ON_STOP 时仅暂停播放 (pause)，防止频发 release 触发 ExoTimeoutException。
 * 2. 仅在 ON_DESTROY 时释放资源，并加上 try-catch 防护，保障模拟器与低端机绝不崩溃。
 * 3. 支持静默队列预加载下一个视频 2MB 数据，沉降后即刻 0ms 闪电起播。
 */
@OptIn(UnstableApi::class)
class ExoShortVideoManager(
    lifecycleOwner: LifecycleOwner,
    private val context: Context,
    private val loop: Boolean = true,
    private val listener: Player.Listener? = null
) {
    private var currentCoverView: ExoCoverPlayerView? = null
    private var videoView: PlayerView? = null
    private var player: ExoPlayer? = null
    private var lastMediaSource: MediaSource? = null

    private val loadControl: LoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
            DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
            0,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS
        )
        .setAllocator(DefaultAllocator(true, 16))
        .build()

    init {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                    if (player == null) {
                        initializePlayer()
                    } else {
                        player?.playWhenReady = true
                    }
                    videoView?.onResume()
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    videoView?.onPause()
                    player?.pause()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    releasePlayer()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
    }

    private fun initializePlayer() {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        val newPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                repeatMode = if (loop) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                playWhenReady = true
            }

        lastMediaSource?.let {
            newPlayer.setMediaSource(it)
            newPlayer.prepare()
        }

        newPlayer.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                Log.d(TAG, "onRenderedFirstFrame: 首帧已渲染，隐去 Center Loading 和 Cover")
                currentCoverView?.onFirstFrameRendered()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        Log.d(TAG, "STATE_BUFFERING: 触发缓冲状态处理")
                        currentCoverView?.onBufferingState()
                    }
                    Player.STATE_READY -> {
                        Log.d(TAG, "STATE_READY: 恢复就绪播放，隐去缓冲动效")
                        currentCoverView?.onReadyState()
                    }
                    else -> {}
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "短视频播放异常: ${error.errorCodeName}", error)
            }
        })

        listener?.let { newPlayer.addListener(it) }
        videoView?.player = newPlayer
        player = newPlayer
    }

    fun releasePlayer() {
        player?.let { p ->
            try {
                videoView?.player = null
                p.stop()
                p.release()
            } catch (e: Throwable) {
                Log.e(TAG, "安全释放 ExoPlayer 发生捕获可忽略异常: ${e.message}", e)
            } finally {
                player = null
            }
        }
    }

    fun playVideoFromCoverView(coverView: ExoCoverPlayerView, videoUrl: String) {
        currentCoverView = coverView
        coverView.resetPlayerState()
        playVideoFromUrl(coverView.playerView, videoUrl)
    }

    fun playVideoFromCoverViewRaw(coverView: ExoCoverPlayerView, @RawRes rawRes: Int) {
        currentCoverView = coverView
        coverView.resetPlayerState()
        playVideoFromRaw(coverView.playerView, rawRes)
    }

    fun playVideoFromRaw(playerView: PlayerView, @RawRes videoRaw: Int) {
        val mediaSource = ShortVideoHelper.createRawMediaSource(playerView.context, videoRaw)
        playVideoFromMediaSource(playerView, mediaSource)
    }

    fun playVideoFromUrl(playerView: PlayerView, videoUrl: String) {
        val mediaSource = ShortVideoHelper.createMediaSource(playerView.context, Uri.parse(videoUrl))
        playVideoFromMediaSource(playerView, mediaSource)
    }

    fun playVideoFromMediaSource(newPlayerView: PlayerView, newMediaSource: MediaSource) {
        if (player == null) {
            initializePlayer()
        }
        player?.let {
            it.setMediaSource(newMediaSource)
            it.prepare()
            PlayerView.switchTargetView(it, videoView, newPlayerView)
        }
        lastMediaSource = newMediaSource
        videoView = newPlayerView
    }

    /**
     * 静默后台队列预加载 (Preload Queue)：
     * 提前把下一个视频的前 2MB 缓存在磁盘中
     */
    fun preloadNextVideo(nextVideoUrl: String?) {
        ShortVideoHelper.preloadNextVideo(context, nextVideoUrl)
    }

    companion object {
        private const val TAG = "ExoShortVideoManager"
    }
}
