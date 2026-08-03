package com.kit.video.vod

import android.net.Uri

/**
 * VOD 播放控制状态模型
 */
data class VodControlState(
    val videoUri: Uri,
    val title: String,
    val isFullscreen: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = true,
    val speed: Float = 1.0f,
    val currentQuality: String = "1080P"
)
