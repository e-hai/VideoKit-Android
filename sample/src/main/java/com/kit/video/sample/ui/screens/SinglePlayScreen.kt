package com.kit.video.sample.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import com.kit.video.longvideo.LongVideoPlayerView
import kotlinx.coroutines.delay

@SuppressLint("DefaultLocale")
@Composable
fun SinglePlayScreen(
    onBackClick: () -> Unit,
    isInPipMode: Boolean = false,
    externalIsPlaying: Boolean = true,
    onPlayingStateChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val onlineLongVideoUrl = "https://vjs.zencdn.net/v/oceans.mp4"
    var playerViewInstance by remember { mutableStateOf<LongVideoPlayerView?>(null) }
    var selectedSpeed by remember { mutableFloatStateOf(1.0f) }
    var isLandscape by remember { mutableStateOf(false) }

    // 沉浸式控制层状态
    var isControlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var bufferedPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isDraggingSlider by remember { mutableStateOf(false) }

    val speedOptions = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)

    // 响应画中画系统的 Play/Pause 远程广播动作
    LaunchedEffect(externalIsPlaying) {
        playerViewInstance?.exoPlayer?.let { player ->
            if (externalIsPlaying && !player.isPlaying) {
                player.play()
            } else if (!externalIsPlaying && player.isPlaying) {
                player.pause()
            }
        }
    }

    // 响应横竖屏旋转，配置 Activity RequestedOrientation 与状态栏沉浸
    LaunchedEffect(isLandscape) {
        if (!isInPipMode) {
            activity?.let { act ->
                val window = act.window
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                if (isLandscape) {
                    act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    controller.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    controller.show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
                }
            }
        }
    }

    // 退出屏幕时恢复竖屏
    DisposableEffect(Unit) {
        onDispose {
            activity?.let { act ->
                act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                val window = act.window
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            }
        }
    }

    // 自动隐去控制面板（3.5秒倒计时，缓冲中与拖拽中不隐去）
    LaunchedEffect(isControlsVisible, isPlaying, isDraggingSlider, isBuffering) {
        if (isControlsVisible && isPlaying && !isDraggingSlider && !isBuffering) {
            delay(3500)
            isControlsVisible = false
        }
    }

    // 轮询获取播放器实时状态（精确定位 Buffering 与 User Paused）
    LaunchedEffect(playerViewInstance) {
        while (true) {
            playerViewInstance?.exoPlayer?.let { player ->
                if (!isDraggingSlider) {
                    currentPositionMs = player.currentPosition.coerceAtLeast(0)
                    bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0)
                    durationMs = player.duration.coerceAtLeast(0)
                    if (isPlaying != player.isPlaying) {
                        isPlaying = player.isPlaying
                        onPlayingStateChanged(isPlaying)
                    }
                    isBuffering = (player.playbackState == Player.STATE_BUFFERING)
                }
            }
            delay(300)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 底层长视频 Player Surface
            AndroidView(
                factory = { ctx ->
                    LongVideoPlayerView(ctx).apply {
                        bindLifecycle(lifecycleOwner)
                        playUrl(onlineLongVideoUrl)
                        onSingleTapListener = {
                            if (!isInPipMode) {
                                isControlsVisible = !isControlsVisible
                            }
                        }
                        onOrientationChangedListener = { landscape ->
                            isLandscape = landscape
                        }
                        playerViewInstance = this
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 沉浸式控制层 (画中画 PiP 悬浮窗状态下自动隐藏全部控制 Layer)
            AnimatedVisibility(
                visible = (isControlsVisible || isBuffering) && !isInPipMode,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = if (isControlsVisible) 0.35f else 0.15f))
                ) {
                    // 1. 顶栏：返回 + 标题 + 倍速选择器
                    AnimatedVisibility(
                        visible = isControlsVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                                    )
                                )
                                .padding(top = 20.dp, start = 16.dp, end = 16.dp, bottom = 20.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = onBackClick,
                                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = Color.White
                                    )
                                }
                                Column(modifier = Modifier.padding(start = 12.dp)) {
                                    Text(
                                        text = "Oceans & Marine Wildlife",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    if (isBuffering) {
                                        Text(
                                            text = "⚡ Buffering network stream...",
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }

                            // 倍速 Chips
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(20.dp))
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                speedOptions.forEach { speed ->
                                    val isSelected = (speed == selectedSpeed)
                                    Text(
                                        text = "${speed}x",
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 11.sp,
                                        modifier = Modifier
                                            .background(
                                                if (isSelected) Color.White.copy(alpha = 0.25f) else Color.Transparent,
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            .clickable {
                                                selectedSpeed = speed
                                                playerViewInstance?.setSpeed(speed)
                                            }
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 2. 居中区域：网络缓冲中 vs 播放/暂停
                    Box(
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        if (isBuffering) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .background(Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(16.dp))
                                    .padding(horizontal = 24.dp, vertical = 16.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = "Loading Buffer...",
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        } else if (isControlsVisible) {
                            IconButton(
                                onClick = {
                                    playerViewInstance?.exoPlayer?.let { player ->
                                        if (player.isPlaying) player.pause() else player.play()
                                        isPlaying = player.isPlaying
                                        onPlayingStateChanged(isPlaying)
                                    }
                                },
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(Color.Black.copy(alpha = 0.65f), shape = CircleShape)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Play/Pause",
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    }

                    // 3. 底部极简控制栏
                    AnimatedVisibility(
                        visible = isControlsVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    brush = Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                                    )
                                )
                                .padding(horizontal = 20.dp, vertical = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = { playerViewInstance?.seekByOffset(-10000) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Default.FastRewind, contentDescription = "-10s", tint = Color.White)
                                    }
                                    IconButton(
                                        onClick = { playerViewInstance?.seekByOffset(10000) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Default.FastForward, contentDescription = "+10s", tint = Color.White)
                                    }
                                    Text(
                                        text = "${formatTime(currentPositionMs)} / ${formatTime(durationMs)}",
                                        color = Color.LightGray,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (isLandscape) "Rotate Vertical" else "Auto Rotate On",
                                        color = Color.Gray,
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                    IconButton(
                                        onClick = {
                                            playerViewInstance?.toggleFullscreen()
                                        },
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = if (isLandscape) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                            contentDescription = "Fullscreen Toggle",
                                            tint = Color.White
                                        )
                                    }
                                }
                            }

                            // 极简高颜值双色缓冲进度条
                            PremiumBufferSlider(
                                currentPositionMs = currentPositionMs,
                                bufferedPositionMs = bufferedPositionMs,
                                durationMs = durationMs,
                                onSeekTo = { targetMs ->
                                    playerViewInstance?.seekTo(targetMs)
                                },
                                onDraggingStateChanged = { dragging ->
                                    isDraggingSlider = dragging
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 工业级极致高颜值双色缓冲进度条
 */
@Composable
fun PremiumBufferSlider(
    currentPositionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
    onDraggingStateChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isDragged by interactionSource.collectIsDraggedAsState()
    var containerWidthPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(isDragged) {
        onDraggingStateChanged(isDragged)
    }

    val trackHeight by animateDpAsState(targetValue = if (isDragged) 6.dp else 2.5.dp, label = "TrackHeight")
    val playFraction = if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val bufferFraction = if (durationMs > 0) (bufferedPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .height(36.dp)
            .onSizeChanged { containerWidthPx = it.width },
        contentAlignment = Alignment.Center
    ) {
        if (isDragged && containerWidthPx > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .offset {
                        val bubbleWidthApprox = 60.dp.roundToPx()
                        val targetX = (playFraction * containerWidthPx - bubbleWidthApprox / 2).toInt()
                        val clampedX = targetX.coerceIn(0, (containerWidthPx - bubbleWidthApprox).coerceAtLeast(0))
                        IntOffset(x = clampedX, y = (-26).dp.roundToPx())
                    }
            ) {
                Text(
                    text = formatTime(currentPositionMs),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.85f), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
        ) {
            val width = size.width
            val height = size.height
            val radius = height / 2

            drawRoundRect(
                color = Color.Gray.copy(alpha = 0.35f),
                size = Size(width, height),
                cornerRadius = CornerRadius(radius, radius)
            )

            if (bufferFraction > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.45f),
                    size = Size(width * bufferFraction, height),
                    cornerRadius = CornerRadius(radius, radius)
                )
            }
        }

        Slider(
            value = playFraction,
            onValueChange = { fraction ->
                onSeekTo((fraction * durationMs).toLong())
            },
            interactionSource = interactionSource,
            colors = SliderDefaults.colors(
                thumbColor = if (isDragged) primaryColor else Color.Transparent,
                activeTrackColor = primaryColor,
                inactiveTrackColor = Color.Transparent
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
