package com.kit.video.vod

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kit.video.ui.PremiumBufferSlider
import com.kit.video.ui.formatPlaybackTime

/**
 * VOD 沉浸式控制层：顶栏 / 中部播放 / 底栏进度。
 * 业务扩展位：
 * - [topTrailingContent]：顶栏右侧额外按钮（如调试入口）
 * - [bannerContent]：顶栏下方提示条（如硬→软降级通知）
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
    modifier: Modifier = Modifier,
    topTrailingContent: @Composable RowScope.() -> Unit = {},
    bannerContent: @Composable (() -> Unit)? = null
) {
    AnimatedVisibility(
        visible = state.isControlsVisible || state.isBuffering,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Color.Black.copy(alpha = if (state.isControlsVisible) 0.32f else 0.12f)
                )
        ) {
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
                    topTrailingContent = topTrailingContent
                )
            }

            if (bannerContent != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .then(
                            if (state.isLandscapeLayout) Modifier.padding(top = 56.dp)
                            else Modifier
                                .statusBarsPadding()
                                .padding(top = 56.dp)
                        )
                        .padding(horizontal = 16.dp)
                ) {
                    bannerContent()
                }
            }

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
                    IconButton(
                        onClick = onPlayPauseClick,
                        modifier = Modifier
                            .size(64.dp)
                            .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) {
                                Icons.Default.Pause
                            } else {
                                Icons.Default.PlayArrow
                            },
                            contentDescription = "Play/Pause",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = state.isControlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                VodBottomBar(
                    state = state,
                    onSeekBy = onSeekBy,
                    onSeekTo = onSeekTo,
                    onDraggingStateChanged = onDraggingStateChanged,
                    onToggleFullscreen = onToggleFullscreen
                )
            }
        }
    }
}

@Composable
private fun VodTopBar(
    state: VodOverlayState,
    onBackClick: () -> Unit,
    onSpeedClick: () -> Unit,
    topTrailingContent: @Composable RowScope.() -> Unit
) {
    val horizontalPadding = if (state.isLandscapeLayout) 20.dp else 12.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                )
            )
            .then(if (state.isLandscapeLayout) Modifier else Modifier.statusBarsPadding())
            .padding(
                start = horizontalPadding,
                end = horizontalPadding,
                top = if (state.isLandscapeLayout) 10.dp else 4.dp,
                bottom = 12.dp
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
            ) {
                Text(
                    text = state.title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = if (state.isLandscapeLayout) 13.sp else 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (state.showDecoderBadge || state.isBuffering) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        if (state.showDecoderBadge) {
                            val badgeBg =
                                if (state.isHardwareDecoder) Color(0xFF4CAF50) else Color(0xFFFF9800)
                            val badgeText = if (state.isHardwareDecoder) "硬解" else "软解"
                            Text(
                                text = badgeText,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 9.sp,
                                modifier = Modifier
                                    .background(badgeBg.copy(alpha = 0.9f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                        if (state.isBuffering) {
                            Text(
                                text = "缓冲中",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }

            Text(
                text = "${state.speed}x",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(16.dp))
                    .clickable(onClick = onSpeedClick)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )

            Spacer(modifier = Modifier.width(8.dp))
            topTrailingContent()
        }
    }
}

@Composable
private fun VodBottomBar(
    state: VodOverlayState,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onDraggingStateChanged: (Boolean) -> Unit,
    onToggleFullscreen: () -> Unit
) {
    val horizontalPadding = if (state.isLandscapeLayout) 24.dp else 16.dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                )
            )
            .navigationBarsPadding()
            .padding(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 8.dp,
                bottom = if (state.isLandscapeLayout) 12.dp else 16.dp
            )
    ) {
        PremiumBufferSlider(
            currentPositionMs = state.currentPositionMs,
            bufferedPositionMs = state.bufferedPositionMs,
            durationMs = state.durationMs,
            onSeekTo = onSeekTo,
            onDraggingStateChanged = onDraggingStateChanged,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { onSeekBy(-10_000L) },
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.White.copy(alpha = 0.08f), CircleShape)
            ) {
                Icon(Icons.Default.FastRewind, contentDescription = "-10s", tint = Color.White)
            }
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(
                onClick = { onSeekBy(10_000L) },
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.White.copy(alpha = 0.08f), CircleShape)
            ) {
                Icon(Icons.Default.FastForward, contentDescription = "+10s", tint = Color.White)
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp)
            ) {
                Text(
                    text = formatPlaybackTime(state.currentPositionMs),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Text(
                    text = " / ",
                    color = Color.White.copy(alpha = 0.35f),
                    fontSize = 12.sp
                )
                Text(
                    text = formatPlaybackTime(state.durationMs),
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            IconButton(
                onClick = onToggleFullscreen,
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.White.copy(alpha = 0.10f), CircleShape)
            ) {
                Icon(
                    imageVector = if (state.isFullscreen) {
                        Icons.Default.FullscreenExit
                    } else {
                        Icons.Default.Fullscreen
                    },
                    contentDescription = "Fullscreen",
                    tint = Color.White
                )
            }
        }
    }
}
