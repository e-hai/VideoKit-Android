package com.kit.video.vod

import androidx.compose.ui.graphics.Color
import java.util.UUID

/**
 * 弹幕实体数据
 */
data class DanmakuItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val timeMs: Long, // 触发出现的视频时间点 (毫秒)
    val color: Color = Color.White,
    val isSelf: Boolean = false, // 是否自己发送
    val speed: Float = 1.0f // 滚动速度系数
)

/**
 * 屏幕上正在飞行的弹幕实例 (UI 内部追踪)
 */
data class ActiveDanmaku(
    val item: DanmakuItem,
    val trackIndex: Int,
    val startPlaybackTimeMs: Long, // 开始绘制时的视频播放进度
    val durationMs: Long = 6000L // 飞行总耗时 (6秒划过屏幕)
)
