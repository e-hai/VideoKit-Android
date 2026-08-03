package com.kit.video.ui

import android.annotation.SuppressLint
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * VOD 双色缓冲进度条：底轨 / 缓冲 / 渐变已播进度，拖拽加粗并弹出时间气泡。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val density = LocalDensity.current

    LaunchedEffect(isDragged) {
        onDraggingStateChanged(isDragged)
    }

    val trackHeight by animateDpAsState(
        targetValue = if (isDragged) 5.dp else 3.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "TrackHeight"
    )
    val thumbSize by animateDpAsState(
        targetValue = if (isDragged) 16.dp else 11.dp,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
        label = "ThumbSize"
    )
    val thumbHaloAlpha by animateFloatAsState(
        targetValue = if (isDragged) 0.35f else 0.16f,
        label = "ThumbHalo"
    )

    val playFraction =
        if (durationMs > 0) (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        else 0f
    val bufferFraction =
        if (durationMs > 0) (bufferedPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
        else 0f

    val accent = MaterialTheme.colorScheme.primary
    val accentSoft = accent.copy(alpha = 0.85f)
    val progressBrush = remember(accent) {
        Brush.horizontalGradient(
            colors = listOf(accentSoft, Color.White.copy(alpha = 0.95f))
        )
    }

    Box(
        modifier = modifier
            .height(40.dp)
            .onSizeChanged { containerWidthPx = it.width },
        contentAlignment = Alignment.Center
    ) {
        if (isDragged && containerWidthPx > 0) {
            val bubbleWidthPx = with(density) { 72.dp.roundToPx() }
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset {
                        val targetX = (playFraction * containerWidthPx - bubbleWidthPx / 2f).toInt()
                        val clampedX = targetX.coerceIn(
                            0,
                            (containerWidthPx - bubbleWidthPx).coerceAtLeast(0)
                        )
                        IntOffset(x = clampedX, y = (-30).dp.roundToPx())
                    }
                    .widthIn(min = 64.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatPlaybackTime(currentPositionMs),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        letterSpacing = 0.4.sp,
                        modifier = Modifier
                            .shadow(8.dp, RoundedCornerShape(10.dp))
                            .background(
                                brush = Brush.verticalGradient(
                                    listOf(
                                        Color(0xFF2A2D33),
                                        Color(0xFF16181C)
                                    )
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                    Canvas(modifier = Modifier.size(10.dp, 5.dp)) {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(size.width / 2f, size.height)
                            lineTo(0f, 0f)
                            lineTo(size.width, 0f)
                            close()
                        }
                        drawPath(path, color = Color(0xFF16181C))
                    }
                }
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .align(Alignment.Center)
        ) {
            val width = size.width
            val height = size.height
            val radius = CornerRadius(height / 2f, height / 2f)

            // 底轨
            drawRoundRect(
                color = Color.White.copy(alpha = 0.18f),
                size = Size(width, height),
                cornerRadius = radius
            )

            // 缓冲
            if (bufferFraction > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.38f),
                    size = Size(width * bufferFraction, height),
                    cornerRadius = radius
                )
            }

            // 已播放（渐变）
            if (playFraction > 0f) {
                drawRoundRect(
                    brush = progressBrush,
                    size = Size(width * playFraction, height),
                    cornerRadius = radius
                )
                // 末端微光，增强进度头部存在感
                val tipX = width * playFraction
                drawCircle(
                    color = Color.White.copy(alpha = if (isDragged) 0.55f else 0.28f),
                    radius = height * 0.95f,
                    center = Offset(tipX.coerceAtMost(width), height / 2f)
                )
            }
        }

        Slider(
            value = playFraction,
            onValueChange = { fraction ->
                if (durationMs > 0L) {
                    onSeekTo((fraction * durationMs).toLong().coerceIn(0L, durationMs))
                }
            },
            interactionSource = interactionSource,
            thumb = {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(thumbSize + 10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(thumbSize + 8.dp)
                            .background(
                                accent.copy(alpha = thumbHaloAlpha),
                                CircleShape
                            )
                    )
                    Box(
                        modifier = Modifier
                            .size(thumbSize)
                            .shadow(
                                elevation = if (isDragged) 6.dp else 2.dp,
                                shape = CircleShape,
                                clip = false
                            )
                            .background(Color.White, CircleShape)
                    )
                    if (isDragged) {
                        Canvas(modifier = Modifier.size(thumbSize * 0.42f)) {
                            drawCircle(
                                color = accent,
                                style = Stroke(width = size.minDimension * 0.35f, cap = StrokeCap.Round)
                            )
                        }
                    }
                }
            },
            track = {
                // 轨道已由 Canvas 绘制，这里保持可点按热区透明
                SliderDefaults.Track(
                    sliderState = it,
                    modifier = Modifier.height(trackHeight),
                    colors = SliderDefaults.colors(
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent,
                        disabledActiveTrackColor = Color.Transparent,
                        disabledInactiveTrackColor = Color.Transparent
                    ),
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 0.dp
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@SuppressLint("DefaultLocale")
fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt().coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
