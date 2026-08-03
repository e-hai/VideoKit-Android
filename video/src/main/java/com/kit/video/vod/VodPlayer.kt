package com.kit.video.vod

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.RenderersFactory
import kotlinx.coroutines.delay

/**
 * 沉浸式点播（VOD）Compose 封装：底层 [VodPlayerView] + 默认 [VodOverlay]。
 *
 * 宿主提供 url / 标题 / 可选 [renderersFactory]；调试按钮、降级提示通过 slot 注入。
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
    onBackClick: () -> Unit,
    onPlayingStateChanged: (Boolean) -> Unit = {},
    onDecoderChanged: (decoderName: String, isHardware: Boolean) -> Unit = { _, _ -> },
    onPlayerError: (PlaybackException) -> Unit = {},
    onPlayerViewReady: (VodPlayerView) -> Unit = {},
    onFullscreenChanged: (Boolean) -> Unit = {},
    onSpeedClick: () -> Unit = {},
    topTrailingContent: @Composable RowScope.() -> Unit = {},
    bannerContent: @Composable (() -> Unit)? = null
) {
    val configuration = LocalConfiguration.current
    val isLandscapeLayout =
        configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

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

    val currentOnDecoderChanged by rememberUpdatedState(onDecoderChanged)
    val currentOnPlayerError by rememberUpdatedState(onPlayerError)
    val currentOnPlayingStateChanged by rememberUpdatedState(onPlayingStateChanged)
    val currentOnPlayerViewReady by rememberUpdatedState(onPlayerViewReady)
    val currentOnFullscreenChanged by rememberUpdatedState(onFullscreenChanged)
    val currentOnSpeedClick by rememberUpdatedState(onSpeedClick)
    val currentIsInPipMode by rememberUpdatedState(isInPipMode)

    LaunchedEffect(url, renderersFactory, playerView) {
        val view = playerView ?: return@LaunchedEffect
        view.playUrl(url, renderersFactory)
    }

    LaunchedEffect(speed, playerView) {
        playerView?.setSpeed(speed)
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

    LaunchedEffect(
        isControlsVisible,
        isPlaying,
        isDraggingSlider,
        isBuffering,
        isInPipMode
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

    LaunchedEffect(playerView) {
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
                }
            }
            delay(300)
        }
    }

    val overlayState = VodOverlayState(
        title = title,
        isControlsVisible = isControlsVisible,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        currentPositionMs = currentPositionMs,
        bufferedPositionMs = bufferedPositionMs,
        durationMs = durationMs,
        speed = speed,
        isLandscapeLayout = isLandscapeLayout,
        isFullscreen = isFullscreen,
        isHardwareDecoder = isHardwareDecoder,
        showDecoderBadge = showDecoderBadge
    )

    Box(modifier = modifier.fillMaxSize()) {
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
                    onOrientationChangedListener = { landscape ->
                        isFullscreen = landscape
                        currentOnFullscreenChanged(landscape)
                    }
                    playerView = this
                    currentOnPlayerViewReady(this)
                }
            },
            update = { view ->
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
                view.onOrientationChangedListener = { landscape ->
                    isFullscreen = landscape
                    currentOnFullscreenChanged(landscape)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

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
                topTrailingContent = topTrailingContent,
                bannerContent = bannerContent
            )
        }
    }
}
