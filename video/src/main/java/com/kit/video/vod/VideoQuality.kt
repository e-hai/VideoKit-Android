package com.kit.video.vod

/**
 * 视频画质/清晰度定义
 */
data class VideoQuality(
    val id: String,
    val name: String, // 如 "1080P 超清", "720P 高清", "480P 标清", "自动"
    val url: String,
    val isAuto: Boolean = false
) {
    companion object {
        fun defaultQualities(baseUrl: String): List<VideoQuality> {
            return listOf(
                VideoQuality("auto", "自动 (Auto)", baseUrl, isAuto = true),
                VideoQuality("1080p", "1080P 超清", baseUrl),
                VideoQuality("720p", "720P 高清", baseUrl),
                VideoQuality("480p", "480P 标清", baseUrl)
            )
        }
    }
}
