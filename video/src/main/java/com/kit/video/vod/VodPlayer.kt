package com.kit.video.vod

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.RenderersFactory
import com.kit.video.ui.formatPlaybackTime
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * 工业级沉浸式点播（VOD）全功能 Compose 组件：
 * 1. 深度整合 Media3 + FFmpeg OpenGL ES 2.0 GPU 软解渲染管线
 * 2. ⚡ 手指长按 2X 极速加速，松开瞬间停止加速恢复原速
 * 3. 🔒 屏幕锁 / 防误触模式
 * 4. ⏱️ 记忆播放 / 历史进度断点自动续播
 * 5. 🎚️ 多画质 / 清晰度无缝切换
 * 6. 💬 影视级外挂双语字幕与开关
 * 7. 🚀 60fps Canvas 高性能弹幕滚动系统
 */
@Composable
fun VodPlayer(
    url: String,
    title: String,
    modifier: Modifier = Modifier,
    renderersFactory: RenderersFactory? = null,
    speed: Float = 1.0f,
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    isInPipMode: Boolean = false,
    showDecoderBadge: Boolean = true,
    autoHideControlsMs: Long = 3500L,
    externalIsPlaying: Boolean? = null,
    initialSubtitles: List<SubtitleItem> = remember { VodSubtitleHelper.getSampleSubtitles() },
    qualities: List<VideoQuality> = remember(url) { VideoQuality.defaultQualities(url) },
    selectedQualityId: String = "1080p",
    onBackClick: () -> Unit,
    onPlayingStateChanged: (Boolean) -> Unit = {},
    onDecoderChanged: (decoderName: String, isHardware: Boolean) -> Unit = { _, _ -> },
    onPlayerError: (PlaybackException) -> Unit = {},
    onPlayerViewReady: (VodPlayerView) -> Unit = {},
    onFullscreenChanged: (Boolean) -> Unit = {},
    onSpeedClick: () -> Unit = {},
    onQualityClick: () -> Unit = {},
    topTrailingContent: @Composable RowScope.() -> Unit = {},
    bannerContent: @Composable (() -> Unit)? = null
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        VodProgressStore.init(context)
    }

    val configuration = LocalConfiguration.current
    val isLandscapeLayout = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var playerView by remember { mutableStateOf<VodPlayerView?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }
    var isHardwareDecoder by remember { mutableStateOf(true) }

    var isControlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var bufferedPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isDraggingSlider by remember { mutableStateOf(false) }

    // 工业级状态
    var isLocked by remember { mutableStateOf(false) }
    var isLongPressing2x by remember { mutableStateOf(false) }
    var basePlaybackSpeed by remember { mutableFloatStateOf(speed) }
    var danmakuEnabled by remember { mutableStateOf(true) }
    var subtitleEnabled by remember { mutableStateOf(true) }

    // 历史续播提示
    var resumeNoticeText by remember { mutableStateOf<String?>(null) }
    var hasHandledResume by remember { mutableStateOf(false) }

    // 弹幕池与活动弹幕
    val activeDanmakus = remember { mutableStateListOf<ActiveDanmaku>() }
    val defaultDanmakuPool = remember {
        listOf(
            DanmakuItem(text = "前方高能！VideoKit 太强了！", timeMs = 1000),
            DanmakuItem(text = "FFmpeg GPU 渲染真的丝滑无卡顿！", timeMs = 2500, color = Color(0xFF64B5F6)),
            DanmakuItem(text = "长按 2x 倍速体验很爽 👍", timeMs = 4000),
            DanmakuItem(text = "60fps 弹幕好丝滑", timeMs = 6000),
            DanmakuItem(text = "工业级全套功能爱了爱了！", timeMs = 8000, color = Color(0xFFFFD54F)),
            DanmakuItem(text = "无缝切换画质和断点续播超赞", timeMs = 11000),
            DanmakuItem(text = "打卡！Android 音视频天花板", timeMs = 14000)
        )
    }

    val currentOnDecoderChanged by rememberUpdatedState(onDecoderChanged)
    val currentOnPlayerError by rememberUpdatedState(onPlayerError)
    val currentOnPlayingStateChanged by rememberUpdatedState(onPlayingStateChanged)
    val currentOnPlayerViewReady by rememberUpdatedState(onPlayerViewReady)
    val currentOnFullscreenChanged by rememberUpdatedState(onFullscreenChanged)
    val currentOnSpeedClick by rememberUpdatedState(onSpeedClick)
    val currentIsInPipMode by rememberUpdatedState(isInPipMode)

    // 监听起播并处理断点续播
    LaunchedEffect(url, renderersFactory, playerView) {
        val view = playerView ?: return@LaunchedEffect
        view.playUrl(url, renderersFactory)

        // 断点续播检测
        if (!hasHandledResume) {
            val savedProgress = VodProgressStore.getProgress(url)
            if (savedProgress > 3000L) {
                delay(200)
                view.seekTo(savedProgress)
                resumeNoticeText = "已为你自动续播至 ${formatPlaybackTime(savedProgress)}"
            }
            hasHandledResume = true
        }
    }

    // 自动清除续播提示
    LaunchedEffect(resumeNoticeText) {
        if (resumeNoticeText != null) {
            delay(4000)
            resumeNoticeText = null
        }
    }

    // 播放过程中定时持久化播放进度
    LaunchedEffect(playerView, currentPositionMs, durationMs) {
        if (currentPositionMs > 1000 && durationMs > 0) {
            VodProgressStore.saveProgress(url, currentPositionMs, durationMs)
        }
    }

    LaunchedEffect(speed, playerView) {
        basePlaybackSpeed = speed
        if (!isLongPressing2x) {
            playerView?.setSpeed(speed)
        }
    }

    LaunchedEffect(externalIsPlaying, playerView) {
        val playing = externalIsPlaying ?: return@LaunchedEffect
        playerView?.exoPlayer?.let { player ->
            if (playing && !player.isPlaying) {
                player.play()
            } else if (!playing && player.isPlaying) {
                player.pause()
            }
        }
    }

    // 自动隐藏 Controls 栏
    LaunchedEffect(
        isControlsVisible,
        isPlaying,
        isDraggingSlider,
        isBuffering,
        isInPipMode,
        isLocked
    ) {
        if (isControlsVisible &&
            isPlaying &&
            !isDraggingSlider &&
            !isBuffering &&
            !isInPipMode
        ) {
            delay(autoHideControlsMs)
            isControlsVisible = false
        }
    }

    // 播放进度与弹幕派发轮询
    LaunchedEffect(playerView, danmakuEnabled) {
        var nextTrack = 0
        while (true) {
            playerView?.exoPlayer?.let { player ->
                if (!isDraggingSlider) {
                    currentPositionMs = player.currentPosition.coerceAtLeast(0)
                    bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0)
                    durationMs = player.duration.coerceAtLeast(0)
                    if (isPlaying != player.isPlaying) {
                        isPlaying = player.isPlaying
                        currentOnPlayingStateChanged(isPlaying)
                    }
                    isBuffering = player.playbackState == Player.STATE_BUFFERING

                    // 弹幕派发引擎
                    if (danmakuEnabled && player.isPlaying) {
                        defaultDanmakuPool.forEach { item ->
                            val diff = currentPositionMs - item.timeMs
                            if (diff in 0..400) {
                                if (activeDanmakus.none { it.item.id == item.id }) {
                                    activeDanmakus.add(
                                        ActiveDanmaku(
                                            item = item,
                                            trackIndex = nextTrack++,
                                            startPlaybackTimeMs = currentPositionMs
                                        )
                                    )
                                }
                            }
                        }
                        // 移除已飞出屏幕的过期弹幕
                        activeDanmakus.removeAll { active ->
                            (currentPositionMs - active.startPlaybackTimeMs) > active.durationMs
                        }
                    }
                }
            }
            delay(100)
        }
    }

    // 检索当前帧应渲染的字幕
    val activeSubtitle = remember(currentPositionMs, subtitleEnabled) {
        if (subtitleEnabled) {
            VodSubtitleHelper.findActiveSubtitle(initialSubtitles, currentPositionMs)
        } else null
    }

    val currentQuality = qualities.firstOrNull { it.id == selectedQualityId } ?: qualities.firstOrNull()

    val overlayState = VodOverlayState(
        title = title,
        isControlsVisible = isControlsVisible,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        currentPositionMs = currentPositionMs,
        bufferedPositionMs = bufferedPositionMs,
        durationMs = durationMs,
        speed = if (isLongPressing2x) 2.0f else basePlaybackSpeed,
        isLandscapeLayout = isLandscapeLayout,
        isFullscreen = isFullscreen,
        isHardwareDecoder = isHardwareDecoder,
        showDecoderBadge = showDecoderBadge,
        isLocked = isLocked,
        isLongPressing2x = isLongPressing2x,
        danmakuEnabled = danmakuEnabled,
        subtitleEnabled = subtitleEnabled,
        currentQualityName = currentQuality?.name ?: "1080P",
        activeSubtitle = activeSubtitle,
        activeDanmakus = activeDanmakus
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            // 🚀 Compose 顶层精准长按 2X 快进手势捕获：按住 380ms 触发 2.0x，松手立即恢复原速
            .pointerInput(isLocked, basePlaybackSpeed) {
                if (isLocked) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    try {
                        withTimeout(380L) {
                            waitForUpOrCancellation()
                        }
                    } catch (e: TimeoutCancellationException) {
                        // 超过 380ms 确认为长按，立即进入 2.0X 极速快进
                        isLongPressing2x = true
                        playerView?.setSpeed(2.0f)

                        // 持续跟踪触摸状态，直到所有手指抬起或取消
                        while (true) {
                            val event = awaitPointerEvent()
                            val anyPressed = event.changes.any { it.pressed }
                            if (!anyPressed) {
                                break
                            }
                        }
                        // 手指松开，微秒级恢复原速并关闭 HUD
                        isLongPressing2x = false
                        playerView?.setSpeed(basePlaybackSpeed)
                    }
                }
            }
    ) {
        AndroidView(
            factory = { ctx ->
                VodPlayerView(ctx).apply {
                    bindLifecycle(lifecycleOwner)
                    onDecoderNameChangedListener = { name, isHw ->
                        isHardwareDecoder = isHw
                        currentOnDecoderChanged(name, isHw)
                    }
                    onPlayerErrorListener = { error ->
                        currentOnPlayerError(error)
                    }
                    onSingleTapListener = {
                        if (!currentIsInPipMode) {
                            isControlsVisible = !isControlsVisible
                        }
                    }
                    onLongPressChangedListener = { isPressing ->
                        if (!isLocked) {
                            isLongPressing2x = isPressing
                            if (isPressing) {
                                setSpeed(2.0f)
                            } else {
                                setSpeed(basePlaybackSpeed)
                            }
                        }
                    }
                    onOrientationChangedListener = { landscape ->
                        isFullscreen = landscape
                        currentOnFullscreenChanged(landscape)
                    }
                    playerView = this
                    currentOnPlayerViewReady(this)
                }
            },
            update = { view ->
                view.isLocked = isLocked
                view.onDecoderNameChangedListener = { name, isHw ->
                    isHardwareDecoder = isHw
                    currentOnDecoderChanged(name, isHw)
                }
                view.onPlayerErrorListener = { error ->
                    currentOnPlayerError(error)
                }
                view.onSingleTapListener = {
                    if (!currentIsInPipMode) {
                        isControlsVisible = !isControlsVisible
                    }
                }
                view.onLongPressChangedListener = { isPressing ->
                    if (!isLocked) {
                        isLongPressing2x = isPressing
                        if (isPressing) {
                            view.setSpeed(2.0f)
                        } else {
                            view.setSpeed(basePlaybackSpeed)
                        }
                    }
                }
                view.onOrientationChangedListener = { landscape ->
                    isFullscreen = landscape
                    currentOnFullscreenChanged(landscape)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 续播提示 Banner
        if (resumeNoticeText != null && !isInPipMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .then(
                        if (isLandscapeLayout) Modifier.padding(top = 56.dp)
                        else Modifier.statusBarsPadding().padding(top = 56.dp)
                    )
                    .background(Color(0xFF1E88E5).copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = resumeNoticeText.orEmpty(),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "重新开始",
                    color = Color(0xFFFFD54F),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        playerView?.seekTo(0L)
                        VodProgressStore.clearProgress(url)
                        resumeNoticeText = null
                    }
                )
            }
        }

        if (!isInPipMode) {
            VodOverlay(
                state = overlayState,
                onBackClick = onBackClick,
                onPlayPauseClick = {
                    playerView?.exoPlayer?.let { player ->
                        if (player.isPlaying) player.pause() else player.play()
                        isPlaying = player.isPlaying
                        currentOnPlayingStateChanged(isPlaying)
                        isControlsVisible = true
                    }
                },
                onSeekBy = { offset ->
                    playerView?.seekByOffset(offset)
                    isControlsVisible = true
                },
                onSeekTo = { target ->
                    playerView?.seekTo(target)
                },
                onDraggingStateChanged = { dragging ->
                    isDraggingSlider = dragging
                    if (dragging) isControlsVisible = true
                },
                onToggleFullscreen = {
                    playerView?.toggleFullscreen()
                    isControlsVisible = true
                },
                onSpeedClick = {
                    isControlsVisible = true
                    currentOnSpeedClick()
                },
                onToggleLock = {
                    isLocked = !isLocked
                    isControlsVisible = !isLocked
                },
                onToggleDanmaku = {
                    danmakuEnabled = !danmakuEnabled
                },
                onToggleSubtitle = {
                    subtitleEnabled = !subtitleEnabled
                },
                onQualityClick = {
                    isControlsVisible = true
                    onQualityClick()
                },
                topTrailingContent = topTrailingContent,
                bannerContent = bannerContent
            )
        }
    }
}
