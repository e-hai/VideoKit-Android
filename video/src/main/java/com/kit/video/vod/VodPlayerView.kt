package com.kit.video.vod

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.ui.PlayerView

/**
 * 沉浸式点播（VOD）全功能播放器 View (重力感应无缝全屏版)：
 * 1. 物理重力感应监听 (OrientationEventListener)，手机横过来自动切横屏全屏，竖过来自动恢复。
 * 2. 支持手动一键全屏/退出全屏切换。
 * 3. 沉浸式手势调亮调音与 HUD 浮层。
 * 4. 实时监听当前使用的真正解码器名称 (系统 MediaCodec 硬解 vs FFmpeg 软解)。
 */
@OptIn(UnstableApi::class)
class VodPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    val playerView: PlayerView = PlayerView(context, attrs, defStyleAttr)
    var exoPlayer: ExoPlayer? = null
        private set

    var isLocked: Boolean = false

    // 单击、横竖屏变化与解码器实时状态回调
    var onSingleTapListener: (() -> Unit)? = null
    var onOrientationChangedListener: ((isLandscape: Boolean) -> Unit)? = null
    var onDecoderNameChangedListener: ((decoderName: String, isHardware: Boolean) -> Unit)? = null
    var onPlayerErrorListener: ((PlaybackException) -> Unit)? = null

    var isLandscape: Boolean = false
        private set

    private var orientationEventListener: OrientationEventListener? = null

    // 手势 HUD 居中提示框
    private val hudTextView: TextView = TextView(context).apply {
        setTextColor(android.graphics.Color.WHITE)
        textSize = 15f
        setPadding(40, 20, 40, 20)
        background = ContextCompat.getDrawable(context, android.R.drawable.toast_frame)
        visibility = View.GONE
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var currentSpeed: Float = 1.0f

    // 手势识别
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onSingleTapListener?.invoke()
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (isLocked || e1 == null) return false
            val width = width
            val height = height
            if (width <= 0 || height <= 0) return false

            val deltaY = (e1.y - e2.y) / height

            if (e1.x < width / 2) {
                adjustBrightness(deltaY)
            } else {
                adjustVolume(deltaY)
            }
            return true
        }
    })

    init {
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        playerView.useController = false
        // 隐去 PlayerView 的黑色 Shutter 遮罩，防止遮挡自定义 FFmpeg 软件渲染 Surface
        playerView.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)

        val hudParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        addView(hudTextView, hudParams)

        setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        setupOrientationListener()
    }

    private fun setupOrientationListener() {
        orientationEventListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                val newIsLandscape = when (orientation) {
                    in 60..120, in 240..300 -> true
                    in 0..30, in 330..360 -> false
                    else -> isLandscape
                }

                if (newIsLandscape != isLandscape) {
                    isLandscape = newIsLandscape
                    onOrientationChangedListener?.invoke(isLandscape)
                }
            }
        }
    }

    fun bindLifecycle(lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> orientationEventListener?.enable()
                Lifecycle.Event.ON_PAUSE -> {
                    orientationEventListener?.disable()
                    val activity = context as? Activity
                    val isPip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        activity?.isInPictureInPictureMode == true
                    } else false
                    if (!isPip) {
                        pause()
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    val activity = context as? Activity
                    val isPip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        activity?.isInPictureInPictureMode == true
                    } else false
                    if (!isPip) {
                        pause()
                    }
                }
                Lifecycle.Event.ON_DESTROY -> release()
                else -> {}
            }
        })
    }

    fun toggleFullscreen() {
        isLandscape = !isLandscape
        onOrientationChangedListener?.invoke(isLandscape)
    }

    fun playUrl(url: String, customRenderersFactory: RenderersFactory? = null) {
        if (exoPlayer == null) {
            val rf = customRenderersFactory
                ?: DefaultRenderersFactory(context).setEnableDecoderFallback(true)
            exoPlayer = buildPlayer(rf)
            playerView.player = exoPlayer
        } else if (customRenderersFactory != null) {
            // 更换 RenderersFactory（软硬解切换 / 自动降级）时重建 Player
            exoPlayer?.release()
            exoPlayer = buildPlayer(customRenderersFactory)
            playerView.player = exoPlayer
        }

        val mediaItem = MediaItem.fromUri(url.toUri())
        exoPlayer?.apply {
            setMediaItem(mediaItem)
            prepare()
            setSpeed(currentSpeed)
            playWhenReady = true
        }
    }

    private fun buildPlayer(renderersFactory: RenderersFactory): ExoPlayer {
        return ExoPlayer.Builder(context, renderersFactory).build().apply {
            addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializationDurationMs: Long
                ) {
                    val isHardware = !decoderName.contains("Ffmpeg", ignoreCase = true) &&
                        !decoderName.contains("ffmpeg", ignoreCase = true) &&
                        !decoderName.contains("google", ignoreCase = true) &&
                        !decoderName.contains("c2.android", ignoreCase = true)
                    Log.d(
                        "VodPlayerView",
                        "解码器就绪: $decoderName (IsHardware: $isHardware)"
                    )
                    onDecoderNameChangedListener?.invoke(decoderName, isHardware)
                }
            })
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e(
                        "VodPlayerView",
                        "播放错误 code=${error.errorCodeName}",
                        error
                    )
                    onPlayerErrorListener?.invoke(error)
                }
            })
        }
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        exoPlayer?.setPlaybackSpeed(speed)
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    fun seekByOffset(offsetMs: Long) {
        exoPlayer?.let {
            val target = (it.currentPosition + offsetMs).coerceIn(0, it.duration.coerceAtLeast(0))
            it.seekTo(target)
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun play() {
        exoPlayer?.play()
    }

    fun release() {
        try {
            exoPlayer?.release()
            exoPlayer = null
        } catch (e: Exception) {
            Log.e("VodPlayerView", "Release player exception ignored", e)
        }
    }

    private fun adjustBrightness(deltaY: Float) {
        val activity = context as? Activity ?: return
        val lp = activity.window.attributes
        var brightness = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
        brightness = (brightness + deltaY * 0.5f).coerceIn(0.01f, 1.0f)
        lp.screenBrightness = brightness
        activity.window.attributes = lp
        showHud("☀️ 亮度: ${(brightness * 100).toInt()}%")
    }

    private fun adjustVolume(deltaY: Float) {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val deltaVol = (deltaY * maxVol * 0.5f).toInt()
        val newVol = (currentVol + deltaVol).coerceIn(0, maxVol)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
        showHud("🔊 音量: ${(newVol * 100 / maxVol)}%")
    }

    private fun showHud(text: String) {
        hudTextView.text = text
        hudTextView.visibility = View.VISIBLE
        removeCallbacks(hideHudRunnable)
        postDelayed(hideHudRunnable, 1500)
    }

    private val hideHudRunnable = Runnable {
        hudTextView.visibility = View.GONE
    }
}
