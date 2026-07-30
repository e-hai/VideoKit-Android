package com.kit.video.longvideo

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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * YouTube 对标长视频专用全功能播放器 View (重力感应无缝全屏版)：
 * 1. 物理重力感应监听 (OrientationEventListener)，手机横过来自动切横屏全屏，竖过来自动恢复。
 * 2. 支持手动一键全屏/退出全屏切换。
 * 3. 沉浸式手势调亮调音与 HUD 浮层。
 */
@OptIn(UnstableApi::class)
class LongVideoPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    val playerView: PlayerView = PlayerView(context, attrs, defStyleAttr)
    var exoPlayer: ExoPlayer? = null
        private set

    // 单击与横竖屏变化回调
    var onSingleTapListener: (() -> Unit)? = null
    var onOrientationChangedListener: ((isLandscape: Boolean) -> Unit)? = null

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
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTapListener?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val width = width
            if (width > 0) {
                if (e.x < width / 2) {
                    seekByOffset(-10000)
                    showHud("⏪ -10s")
                } else {
                    seekByOffset(10000)
                    showHud("⏩ +10s")
                }
            }
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (e1 == null) return false
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

    fun playUrl(url: String) {
        if (exoPlayer == null) {
            val renderersFactory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
            exoPlayer = ExoPlayer.Builder(context, renderersFactory).build()
            playerView.player = exoPlayer
        }

        val mediaItem = MediaItem.fromUri(url.toUri())
        exoPlayer?.apply {
            setMediaItem(mediaItem)
            setPlaybackSpeed(currentSpeed)
            prepare()
            playWhenReady = true
        }
    }

    fun setSpeed(speed: Float) {
        currentSpeed = speed
        exoPlayer?.setPlaybackSpeed(speed)
        showHud("🚀 倍速: ${speed}x")
    }

    fun seekByOffset(offsetMs: Long) {
        exoPlayer?.let { player ->
            val target = (player.currentPosition + offsetMs).coerceIn(0, player.duration.coerceAtLeast(0))
            player.seekTo(target)
        }
    }

    private fun adjustVolume(deltaY: Float) {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val change = (deltaY * maxVol).toInt()
        val newVol = (currentVol + change).coerceIn(0, maxVol)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
        val percent = (newVol * 100) / maxVol
        showHud("🔊 音量: $percent%")
    }

    private fun adjustBrightness(deltaY: Float) {
        val activity = context as? Activity ?: return
        val lp = activity.window.attributes
        var currentBrightness = lp.screenBrightness
        if (currentBrightness < 0) currentBrightness = 0.5f
        val newBrightness = (currentBrightness + deltaY * 0.5f).coerceIn(0.01f, 1.0f)
        lp.screenBrightness = newBrightness
        activity.window.attributes = lp
        val percent = (newBrightness * 100).toInt()
        showHud("☀️ 亮度: $percent%")
    }

    private val hideHudRunnable = Runnable {
        hudTextView.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction { hudTextView.visibility = View.GONE }
            .start()
    }

    private fun showHud(text: String) {
        removeCallbacks(hideHudRunnable)
        hudTextView.animate().cancel()
        hudTextView.text = text
        hudTextView.alpha = 1f
        hudTextView.visibility = View.VISIBLE
        postDelayed(hideHudRunnable, 1000)
    }

    fun play() {
        exoPlayer?.play()
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    fun release() {
        orientationEventListener?.disable()
        orientationEventListener = null
        exoPlayer?.let { p ->
            try {
                playerView.player = null
                p.stop()
                p.release()
            } catch (e: Throwable) {
                Log.e("LongVideoPlayerView", "安全释放 ExoPlayer 捕获可忽略异常", e)
            } finally {
                exoPlayer = null
            }
        }
    }
}
