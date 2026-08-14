package com.kit.video.vod

/**
 * 外挂字幕节点数据模型
 */
data class SubtitleItem(
    val startMs: Long,
    val endMs: Long,
    val primaryText: String, // 主语言字幕 (如中文)
    val secondaryText: String? = null // 次语言字幕 (如英文)
)

/**
 * 轻量字幕解析器与查询辅助工具
 */
object VodSubtitleHelper {

    /**
     * 根据当前播放进度获取应该显示的字幕条目
     */
    fun findActiveSubtitle(subtitles: List<SubtitleItem>, currentPositionMs: Long): SubtitleItem? {
        return subtitles.firstOrNull { item ->
            currentPositionMs in item.startMs..item.endMs
        }
    }

    /**
     * 生成内置默认双语演示字幕数据
     */
    fun getSampleSubtitles(): List<SubtitleItem> {
        return listOf(
            SubtitleItem(500, 3500, "欢迎体验 VideoKit 工业级音视频解决方案", "Welcome to VideoKit Video Solution"),
            SubtitleItem(4000, 7500, "基于 Media3 + FFmpeg OpenGL ES 2.0 极速 GPU 渲染", "Powered by Media3 + FFmpeg OpenGL ES 2.0 Shader"),
            SubtitleItem(8000, 12000, "长按屏幕任意位置即可触发 2X 极速快进", "Long press anywhere to trigger 2X fast forward"),
            SubtitleItem(12500, 16000, "支持高并发防重叠 Canvas 弹幕与外挂字幕", "Supports high-performance Danmaku & Subtitles"),
            SubtitleItem(16500, 21000, "支持画质无缝切换与本地记忆续播", "Supports seamless quality switch & playback resume"),
            SubtitleItem(21500, 26000, "沉浸手势调光调音，带来极致视听体验", "Immersive gestures for volume & brightness control"),
            SubtitleItem(26500, 32000, "VideoKit: 让 Android 音视频开发更简单高效", "VideoKit: Making Android Video Dev Simpler & Better")
        )
    }
}
