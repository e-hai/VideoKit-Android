package com.kit.video.vod

/**
 * 工业级 VOD Overlay 控制层状态
 */
data class VodOverlayState(
    val title: String = "",
    val isControlsVisible: Boolean = true,
    val isPlaying: Boolean = true,
    val isBuffering: Boolean = false,
    val currentPositionMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    /** 当前是否为横屏布局（影响 padding / 字号） */
    val isLandscapeLayout: Boolean = false,
    /** 播放器内部记录的全屏态（影响全屏图标） */
    val isFullscreen: Boolean = false,
    val isHardwareDecoder: Boolean = true,
    val showDecoderBadge: Boolean = true,
    val isLocked: Boolean = false,
    val isLongPressing2x: Boolean = false,
    val isEnded: Boolean = false,
    val danmakuEnabled: Boolean = true,
    val subtitleEnabled: Boolean = true,
    val currentQualityName: String = "1080P",
    val activeSubtitle: SubtitleItem? = null,
    val activeDanmakus: List<ActiveDanmaku> = emptyList()
)
