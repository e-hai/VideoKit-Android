package com.kit.video.longvideo

import android.net.Uri

/**
 * 长视频播放控制状态模型
 */
data class LongVideoControlState(
    val videoUri: Uri,
    val title: String,
    val isFullscreen: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = true,
    val speed: Float = 1.0f,
    val currentQuality: String = "1080P"
)
