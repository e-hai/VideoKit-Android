package com.kit.video.vod

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kit.video.ui.PremiumBufferSlider
import com.kit.video.ui.formatPlaybackTime


/**
 * 工业级沉浸式点播控制层：
 * 1. 顶栏 / 底栏 / 居中播放器
 * 2. 屏幕锁与防误触解锁交互
 * 3. 长按 2X 极速快进发光 HUD
 * 4. 进度条拖动浮动时间预览
 * 5. Canvas 60fps 高性能防重叠弹幕层
 * 6. 影视级双语外挂字幕渲染
 * 7. 弹幕、字幕、画质、倍速快捷开关
 */
@Composable
fun VodOverlay(
    state: VodOverlayState,
    onBackClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onDraggingStateChanged: (Boolean) -> Unit,
    onToggleFullscreen: () -> Unit,
    onSpeedClick: () -> Unit,
    onToggleLock: () -> Unit = {},
    onToggleDanmaku: () -> Unit = {},
    onToggleSubtitle: () -> Unit = {},
    onQualityClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    topTrailingContent: @Composable RowScope.() -> Unit = {},
    bannerContent: @Composable (() -> Unit)? = null
) {
    Box(modifier = modifier.fillMaxSize()) {

        // ================= 1. 60fps Canvas 弹幕滚动图层 =================
        if (state.danmakuEnabled && state.activeDanmakus.isNotEmpty()) {
            DanmakuCanvasLayer(
                danmakus = state.activeDanmakus,
                currentPlaybackTimeMs = state.currentPositionMs,
                modifier = Modifier.fillMaxSize()
            )
        }

        // ================= 2. 影视级外挂双语字幕渲染图层 =================
        if (state.subtitleEnabled && state.activeSubtitle != null) {
            SubtitleLayer(
                subtitle = state.activeSubtitle,
                isLandscape = state.isLandscapeLayout,
                isControlsVisible = state.isControlsVisible && !state.isLocked,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // ================= 3. 长按 2X 极速快进发光 HUD =================
        if (state.isLongPressing2x && !state.isLocked) {
            LongPress2xHud(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .then(
                        if (state.isLandscapeLayout) Modifier.padding(top = 24.dp)
                        else Modifier.statusBarsPadding().padding(top = 16.dp)
                    )
            )
        }

        // ================= 4. 屏幕锁独立按钮 (防误触交互) =================
        AnimatedVisibility(
            visible = state.isControlsVisible || state.isLocked,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .then(
                    if (state.isLandscapeLayout) Modifier.padding(start = 28.dp)
                    else Modifier.padding(start = 16.dp)
                )
        ) {
            IconButton(
                onClick = onToggleLock,
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        if (state.isLocked) Color(0xFFFF9800).copy(alpha = 0.85f)
                        else Color.Black.copy(alpha = 0.55f),
                        CircleShape
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            ) {
                Icon(
                    imageVector = if (state.isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = if (state.isLocked) "点击解锁屏幕" else "点击锁定屏幕",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // ================= 5. 主控制层 (锁定状态下全部隐藏) =================
        if (!state.isLocked) {
            AnimatedVisibility(
                visible = state.isControlsVisible || state.isBuffering,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Color.Black.copy(alpha = if (state.isControlsVisible) 0.32f else 0.12f)
                        )
                ) {
                    // 顶栏
                    AnimatedVisibility(
                        visible = state.isControlsVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) {
                        VodTopBar(
                            state = state,
                            onBackClick = onBackClick,
                            onSpeedClick = onSpeedClick,
                            onQualityClick = onQualityClick,
                            onToggleDanmaku = onToggleDanmaku,
                            onToggleSubtitle = onToggleSubtitle,
                            topTrailingContent = topTrailingContent
                        )
                    }

                    // 提示条 Banner (如降级提示)
                    if (bannerContent != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .then(
                                    if (state.isLandscapeLayout) Modifier.padding(top = 56.dp)
                                    else Modifier.statusBarsPadding().padding(top = 56.dp)
                                )
                                .padding(horizontal = 16.dp)
                        ) {
                            bannerContent()
                        }
                    }

                    // 居中播放/暂停/缓冲中
                    Box(modifier = Modifier.align(Alignment.Center)) {
                        if (state.isBuffering) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .background(
                                        Color.Black.copy(alpha = 0.65f),
                                        RoundedCornerShape(16.dp)
                                    )
                                    .padding(horizontal = 24.dp, vertical = 16.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text(
                                    text = "缓冲中…",
                                    color = Color.LightGray,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        } else if (state.isControlsVisible) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(36.dp)
                            ) {
                                IconButton(
                                    onClick = { onSeekBy(-10000) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                                ) {
                                    Icon(
                                        Icons.Default.FastRewind,
                                        contentDescription = "Rewind 10s",
                                        tint = Color.White,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }

                                IconButton(
                                    onClick = onPlayPauseClick,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = Color.White,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }

                                IconButton(
                                    onClick = { onSeekBy(10000) },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                                ) {
                                    Icon(
                                        Icons.Default.FastForward,
                                        contentDescription = "Forward 10s",
                                        tint = Color.White,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 底栏进度与全屏控制
                    AnimatedVisibility(
                        visible = state.isControlsVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        VodBottomBar(
                            state = state,
                            onSeekTo = onSeekTo,
                            onDraggingStateChanged = onDraggingStateChanged,
                            onToggleFullscreen = onToggleFullscreen
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// 子组件：长按 2X 极速快进发光 HUD
// ============================================================================
@Composable
private fun LongPress2xHud(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "hud_pulse")
    val alphaAnim by infiniteTransition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hud_alpha"
    )

    Row(
        modifier = modifier
            .background(
                Brush.horizontalGradient(
                    listOf(
                        Color(0xFFE65100).copy(alpha = alphaAnim),
                        Color(0xFFFF9800).copy(alpha = alphaAnim)
                    )
                ),
                RoundedCornerShape(20.dp)
            )
            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.FastForward,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = " 正在 2X 极速快进中...",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ============================================================================
// 子组件：影视级双语字幕渲染图层
// ============================================================================
@Composable
private fun SubtitleLayer(
    subtitle: SubtitleItem,
    isLandscape: Boolean,
    isControlsVisible: Boolean,
    modifier: Modifier = Modifier
) {
    val bottomPad = if (isControlsVisible) 76.dp else if (isLandscape) 24.dp else 16.dp
    Column(
        modifier = modifier
            .padding(bottom = bottomPad)
            .padding(horizontal = 24.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = subtitle.primaryText,
            color = Color(0xFFFFF9C4), // 电影级柔和亮黄
            fontSize = if (isLandscape) 16.sp else 14.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black,
                    offset = Offset(2f, 2f),
                    blurRadius = 4f
                )
            )
        )
        if (!subtitle.secondaryText.isNullOrBlank()) {
            Text(
                text = subtitle.secondaryText,
                color = Color.White.copy(alpha = 0.9f),
                fontSize = if (isLandscape) 12.sp else 11.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

// ============================================================================
// 子组件：60fps Canvas 高性能弹幕图层
// ============================================================================
@Composable
private fun DanmakuCanvasLayer(
    danmakus: List<ActiveDanmaku>,
    currentPlaybackTimeMs: Long,
    modifier: Modifier = Modifier
) {
    val textPaint = rememberAndroidTextPaint()

    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val trackHeight = 36.dp.toPx()
        val maxTracks = (canvasHeight / trackHeight).toInt().coerceAtLeast(3)

        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas
            danmakus.forEach { active ->
                val elapsed = (currentPlaybackTimeMs - active.startPlaybackTimeMs).coerceAtLeast(0L)
                val progress = elapsed.toFloat() / active.durationMs.toFloat()

                // 只有在飞行时间内的弹幕才予以绘制
                if (progress in 0f..1.0f) {
                    val track = (active.trackIndex % maxTracks)
                    val y = (track + 1) * trackHeight - 8.dp.toPx()
                    val x = canvasWidth - (canvasWidth + 400.dp.toPx()) * progress

                    textPaint.color = if (active.item.isSelf) android.graphics.Color.YELLOW else android.graphics.Color.WHITE
                    textPaint.textSize = if (active.item.isSelf) 40f else 34f
                    textPaint.isFakeBoldText = true

                    // 绘制黑色投影提升弹幕对比度
                    textPaint.setShadowLayer(5f, 2f, 2f, android.graphics.Color.BLACK)
                    nativeCanvas.drawText(active.item.text, x, y, textPaint)
                }
            }
        }
    }
}

@Composable
private fun rememberAndroidTextPaint(): android.graphics.Paint {
    return androidx.compose.runtime.remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
        }
    }
}

// ============================================================================
// 子组件：顶栏 (含画质、弹幕、字幕、倍速、返回)
// ============================================================================
@Composable
private fun VodTopBar(
    state: VodOverlayState,
    onBackClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onQualityClick: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onToggleSubtitle: () -> Unit,
    topTrailingContent: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                )
            )
            .then(
                if (state.isLandscapeLayout) Modifier.padding(top = 10.dp)
                else Modifier.statusBarsPadding()
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBackClick) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White
            )
        }

        Text(
            text = state.title,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // 弹幕开关
        IconButton(
            onClick = onToggleDanmaku,
            modifier = Modifier.size(36.dp)
        ) {
            Text(
                text = "弹",
                color = if (state.danmakuEnabled) Color(0xFF64B5F6) else Color.Gray,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(
                        if (state.danmakuEnabled) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f),
                        CircleShape
                    )
                    .padding(5.dp)
            )
        }

        // 字幕 CC 开关
        IconButton(
            onClick = onToggleSubtitle,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = if (state.subtitleEnabled) Icons.Default.ClosedCaption else Icons.Default.ClosedCaptionDisabled,
                contentDescription = "字幕开关",
                tint = if (state.subtitleEnabled) Color(0xFFFFF176) else Color.Gray,
                modifier = Modifier.size(20.dp)
            )
        }

        // 画质切换
        Text(
            text = state.currentQualityName,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                .clickable { onQualityClick() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )

        Spacer(modifier = Modifier.width(6.dp))

        // 倍速
        Text(
            text = "${state.speed}x",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                .clickable { onSpeedClick() }
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )

        topTrailingContent()
    }
}

// ============================================================================
// 子组件：底栏 (含拖动时间与帧预览卡片)
// ============================================================================
@Composable
private fun VodBottomBar(
    state: VodOverlayState,
    onSeekTo: (Long) -> Unit,
    onDraggingStateChanged: (Boolean) -> Unit,
    onToggleFullscreen: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                )
            )
            .then(
                if (state.isLandscapeLayout) Modifier.padding(bottom = 8.dp)
                else Modifier.navigationBarsPadding()
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = formatPlaybackTime(state.currentPositionMs),
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
            ) {
                PremiumBufferSlider(
                    currentPositionMs = state.currentPositionMs,
                    bufferedPositionMs = state.bufferedPositionMs,
                    durationMs = state.durationMs,
                    onSeekTo = onSeekTo,
                    onDraggingStateChanged = onDraggingStateChanged,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(
                text = formatPlaybackTime(state.durationMs),
                color = Color.LightGray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(
                onClick = onToggleFullscreen,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (state.isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = "Fullscreen",
                    tint = Color.White
                )
            }
        }
    }
}
