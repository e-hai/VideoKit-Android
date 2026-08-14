package com.kit.video.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 抖音 / TikTok 级底部微型拖拽进度条 (Feed Micro Scrubbing Bar)：
 * 1. 默认状态：屏幕最底部 2dp 极细白色半透明进度线，纯净零视觉遮挡；
 * 2. 交互状态：手指触碰底部热区时，进度条平滑放大变粗至 6dp，浮现白色发光拖拽圆点 (Thumb)；
 * 3. 气泡跟随：手指上方实时浮现磨砂黑圆角时间胶囊气泡 (如 00:15 / 00:45)；
 * 4. 手指释放：平滑 Seek 到目标位置并瞬间恢复 2dp 极简线。
 */
@Composable
fun FeedMicroProgressBar(
    currentPositionMs: Long,
    bufferedPositionMs: Long,
    durationMs: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (durationMs <= 0L) return

    var isDragging by remember { mutableStateOf(false) }
    var dragProgressRatio by remember { mutableFloatStateOf(0f) }
    var barWidthPx by remember { mutableFloatStateOf(1f) }
    var touchXPos by remember { mutableFloatStateOf(0f) }

    val normalProgress = (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    val bufferProgress = (bufferedPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    val activeProgress = if (isDragging) dragProgressRatio else normalProgress

    val barHeight by animateDpAsState(
        targetValue = if (isDragging) 6.dp else 2.dp,
        animationSpec = tween(durationMillis = 180),
        label = "barHeight"
    )

    val thumbRadius by animateDpAsState(
        targetValue = if (isDragging) 6.dp else 0.dp,
        animationSpec = tween(durationMillis = 180),
        label = "thumbRadius"
    )

    val displayedTimeMs = if (isDragging) (dragProgressRatio * durationMs).toLong() else currentPositionMs

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp) // 扩大底部透明触控热区
            .onSizeChanged { barWidthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(durationMs) {
                detectTapGestures(
                    onPress = { offset ->
                        touchXPos = offset.x
                        dragProgressRatio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        isDragging = true
                        tryAwaitRelease()
                        onSeekTo((dragProgressRatio * durationMs).toLong())
                        isDragging = false
                    }
                )
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        touchXPos = offset.x
                        dragProgressRatio = (offset.x / barWidthPx).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        onSeekTo((dragProgressRatio * durationMs).toLong())
                        isDragging = false
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        touchXPos = change.position.x
                        dragProgressRatio = (change.position.x / barWidthPx).coerceIn(0f, 1f)
                    }
                )
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        // 1. 跟随手指移动的高保真时间气泡
        AnimatedVisibility(
            visible = isDragging,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset {
                    val bubbleWidthEst = 120.dp.toPx()
                    val targetX = (touchXPos - bubbleWidthEst / 2f).coerceIn(16f, barWidthPx - bubbleWidthEst - 16f)
                    IntOffset(targetX.roundToInt(), -40.dp.roundToPx())
                }
        ) {
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "${formatFeedTime(displayedTimeMs)} / ${formatFeedTime(durationMs)}",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // 2. 进度条 Canvas
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
        ) {
            val width = size.width
            val height = size.height
            val radius = height / 2f

            // 背景底槽 (半透明黑白)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.22f),
                size = Size(width, height),
                cornerRadius = CornerRadius(radius, radius)
            )

            // 二级网络缓冲条
            if (bufferProgress > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.42f),
                    size = Size(width * bufferProgress, height),
                    cornerRadius = CornerRadius(radius, radius)
                )
            }

            // 当前播放/拖拽进度条 (高亮纯白)
            val progressWidth = (width * activeProgress).coerceAtLeast(0f)
            if (progressWidth > 0f) {
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.95f),
                    size = Size(progressWidth, height),
                    cornerRadius = CornerRadius(radius, radius)
                )
            }

            // 拖拽高亮白圆点 Thumb
            if (thumbRadius.toPx() > 0f) {
                val thumbX = progressWidth.coerceIn(thumbRadius.toPx(), width - thumbRadius.toPx())
                // 外圈微光
                drawCircle(
                    color = Color.White.copy(alpha = 0.35f),
                    radius = thumbRadius.toPx() + 3.dp.toPx(),
                    center = Offset(thumbX, height / 2f)
                )
                // 实体内芯
                drawCircle(
                    color = Color.White,
                    radius = thumbRadius.toPx(),
                    center = Offset(thumbX, height / 2f)
                )
            }
        }
    }
}

private fun formatFeedTime(timeMs: Long): String {
    val totalSeconds = (timeMs / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
