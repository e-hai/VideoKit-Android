package com.kit.video.shortvideo

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.media3.ui.PlayerView

/**
 * 抖音级极速起播短视频控件（防抖 Loading 与无感封面切换）：
 * 1. 切页/加载时默认静默，200ms 内首帧成功渲染时不弹出任何 Loading 控件，实现秒播体验。
 * 2. 加载时间超过 200ms 门槛后，才延迟唤起 Center Loading。
 * 3. 播放中途卡顿二次缓冲时，仅展示底部 GPU 加速呼吸灯，不遮挡中间画面。
 */
class ExoCoverPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val LOADING_DELAY_MS = 200L // 200ms 防抖门槛
    }

    val playerView: PlayerView = PlayerView(context, attrs, defStyleAttr).apply {
        useController = false
    }

    val coverImageView: ImageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    val centerLoadingView: ProgressBar = ProgressBar(context).apply {
        isIndeterminate = true
        visibility = View.GONE
        alpha = 0f
    }

    // 底部呼吸灯脉冲线条控件 (开启 GPU 硬件图层)
    val bottomBreathingView: View = View(context).apply {
        val gradientDrawable = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(
                Color.TRANSPARENT,
                Color.parseColor("#80BB86FC"),
                Color.parseColor("#FFBB86FC"),
                Color.parseColor("#80BB86FC"),
                Color.TRANSPARENT
            )
        )
        background = gradientDrawable
        visibility = View.GONE
        alpha = 0f
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private var breathingAnimator: ObjectAnimator? = null
    var hasRenderedFirstFrame: Boolean = false
        private set

    private val delayedLoadingRunnable = Runnable {
        if (!hasRenderedFirstFrame) {
            showCenterLoading()
        }
    }

    init {
        // 1. 底层视频 Surface
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 2. 静态封面
        coverImageView.setLayerType(LAYER_TYPE_HARDWARE, null)
        addView(coverImageView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // 3. 居中 Center Loading 控件
        val loadingParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
        }
        addView(centerLoadingView, loadingParams)
        // 4. 底部呼吸灯脉冲控件
        val breathingParams = LayoutParams(LayoutParams.MATCH_PARENT, (3 * resources.displayMetrics.density).toInt()).apply {
            gravity = Gravity.BOTTOM
        }
        addView(bottomBreathingView, breathingParams)
    }

    /**
     * 播放卡顿缓冲 (STATE_BUFFERING)
     */
    fun onBufferingState() {
        if (!hasRenderedFirstFrame) {
            scheduleDelayedCenterLoading()
            hideBreathingLoading()
        } else {
            cancelDelayedCenterLoading()
            hideCenterLoading()
            showBreathingLoading()
        }
    }

    /**
     * 恢复就绪播放 (STATE_READY)
     */
    fun onReadyState() {
        cancelDelayedCenterLoading()
        hideCenterLoading()
        hideBreathingLoading()
    }

    /**
     * 当 ExoPlayer 解码出第一帧 (onRenderedFirstFrame) 时调用
     */
    fun onFirstFrameRendered() {
        hasRenderedFirstFrame = true
        cancelDelayedCenterLoading()
        hideCenterLoading()
        hideCover()
        hideBreathingLoading()
    }

    /**
     * 重置播放状态（切页重用时重置，默认静默封面，不强弹 Loading）
     */
    fun resetPlayerState() {
        hasRenderedFirstFrame = false
        cancelDelayedCenterLoading()
        stopBreathingAnimation()
        showCover()
        // 不在切页瞬间强弹 Loading，而是启动 200ms 防抖门槛
        scheduleDelayedCenterLoading()
        hideBreathingLoading()
    }

    private fun scheduleDelayedCenterLoading() {
        cancelDelayedCenterLoading()
        postDelayed(delayedLoadingRunnable, LOADING_DELAY_MS)
    }

    private fun cancelDelayedCenterLoading() {
        removeCallbacks(delayedLoadingRunnable)
    }

    fun showCenterLoading() {
        if (!hasRenderedFirstFrame) {
            centerLoadingView.animate().cancel()
            centerLoadingView.visibility = View.VISIBLE
            centerLoadingView.animate()
                .alpha(1f)
                .setDuration(150)
                .start()
        }
    }

    fun hideCenterLoading() {
        cancelDelayedCenterLoading()
        if (centerLoadingView.visibility == View.VISIBLE) {
            centerLoadingView.animate().cancel()
            centerLoadingView.animate()
                .alpha(0f)
                .setDuration(100)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        centerLoadingView.visibility = View.GONE
                    }
                })
                .start()
        }
    }

    fun showBreathingLoading() {
        bottomBreathingView.animate().cancel()
        bottomBreathingView.visibility = View.VISIBLE
        if (breathingAnimator == null) {
            breathingAnimator = ObjectAnimator.ofFloat(bottomBreathingView, "alpha", 0.2f, 1.0f).apply {
                duration = 800
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
            }
        }
        if (breathingAnimator?.isStarted != true) {
            breathingAnimator?.start()
        }
    }

    fun hideBreathingLoading() {
        stopBreathingAnimation()
        if (bottomBreathingView.visibility == View.VISIBLE) {
            bottomBreathingView.animate().cancel()
            bottomBreathingView.animate()
                .alpha(0f)
                .setDuration(100)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        bottomBreathingView.visibility = View.GONE
                    }
                })
                .start()
        }
    }

    private fun stopBreathingAnimation() {
        breathingAnimator?.cancel()
        breathingAnimator = null
    }

    fun showCover() {
        coverImageView.animate().cancel()
        coverImageView.alpha = 1f
        coverImageView.visibility = View.VISIBLE
    }

    fun hideCover() {
        if (coverImageView.visibility == View.VISIBLE) {
            coverImageView.animate().cancel()
            coverImageView.animate()
                .alpha(0f)
                .setDuration(100)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        coverImageView.visibility = View.GONE
                        coverImageView.setLayerType(LAYER_TYPE_NONE, null)
                    }
                })
                .start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelDelayedCenterLoading()
        stopBreathingAnimation()
    }
}
