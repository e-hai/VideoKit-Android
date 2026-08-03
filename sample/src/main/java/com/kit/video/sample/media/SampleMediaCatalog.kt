package com.kit.video.sample.media

/**
 * Sample 统一测试片源目录。
 *
 * 规则：
 * - 每个条目至少 1 个已探测可用的 HTTPS 直链
 * - [urls] 同编码备用地址按优先级排列；播放失败时顺序切换
 * - 仅收录近期 Range 探测返回 206 且可识别为真实媒体的链接
 */
data class SampleVideo(
    val id: String,
    val name: String,
    val description: String,
    val codec: String,
    val urls: List<String>,
    val expectHardDecode: Boolean = true,
    val likelyNeedsSoftFallback: Boolean = false,
    val author: String = "@VideoKit"
) {
    init {
        require(urls.isNotEmpty()) { "SampleVideo($id) urls must not be empty" }
    }

    val primaryUrl: String get() = urls.first()
}

object SampleMediaCatalog {

    /** VOD 调试面板：覆盖 H.264 / HEVC / VP9 / AV1 */
    val vodSources: List<SampleVideo> = listOf(
        SampleVideo(
            id = "bbb_h264_10s",
            name = "BBB H.264 (10s)",
            description = "期望硬解成功 · 对照基线",
            codec = "H.264",
            urls = listOf(
                "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4",
                "https://lorem.video/480p_h264_10s.mp4",
                "https://test-videos.co.uk/vids/sintel/mp4/h264/360/Sintel_360_10s_1MB.mp4"
            )
        ),
        SampleVideo(
            id = "oceans_h264",
            name = "Oceans H.264",
            description = "期望硬解成功 · 常规长片",
            codec = "H.264",
            urls = listOf(
                "https://vjs.zencdn.net/v/oceans.mp4",
                "https://media.w3.org/2010/05/sintel/trailer.mp4"
            )
        ),
        SampleVideo(
            id = "sintel_h264",
            name = "Sintel Trailer",
            description = "期望硬解成功 · W3C 样片",
            codec = "H.264",
            urls = listOf(
                "https://media.w3.org/2010/05/sintel/trailer.mp4",
                "https://test-videos.co.uk/vids/sintel/mp4/h264/360/Sintel_360_10s_1MB.mp4",
                "https://vjs.zencdn.net/v/oceans.mp4"
            )
        ),
        SampleVideo(
            id = "exoplayer_screens",
            name = "ExoPlayer Screens",
            description = "期望硬解成功 · Media3 官方样片",
            codec = "H.264",
            urls = listOf(
                "https://storage.googleapis.com/exoplayer-test-media-1/mp4/android-screens-10s.mp4",
                "https://storage.googleapis.com/exoplayer-test-media-1/mp4/android-screens-25s.mp4",
                "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4"
            )
        ),
        SampleVideo(
            id = "bbb_hevc_10s",
            name = "BBB HEVC/H.265",
            description = "多数机硬解；老机可能失败→软解",
            codec = "H.265",
            expectHardDecode = true,
            likelyNeedsSoftFallback = true,
            urls = listOf(
                "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h265/360/Big_Buck_Bunny_360_10s_1MB.mp4",
                "https://lorem.video/bunny_480p_h265_10s.mp4"
            )
        ),
        SampleVideo(
            id = "bbb_vp9_10s",
            name = "BBB VP9 WebM",
            description = "部分机无 VP9 硬解→软解/选轨降级",
            codec = "VP9",
            expectHardDecode = false,
            likelyNeedsSoftFallback = true,
            urls = listOf(
                "https://test-videos.co.uk/vids/bigbuckbunny/webm/vp9/360/Big_Buck_Bunny_360_10s_1MB.webm"
            )
        ),
        SampleVideo(
            id = "bbb_av1_10s",
            name = "BBB AV1",
            description = "推荐验证：无 AV1 硬解时走 FFmpeg",
            codec = "AV1",
            expectHardDecode = false,
            likelyNeedsSoftFallback = true,
            urls = listOf(
                "https://test-videos.co.uk/vids/bigbuckbunny/mp4/av1/360/Big_Buck_Bunny_360_10s_1MB.mp4",
                "https://test-videos.co.uk/vids/sintel/mp4/av1/360/Sintel_360_10s_1MB.mp4",
                "https://lorem.video/480p_av1_10s.mp4"
            )
        )
    )

    /** Feed 竖滑：只用高可用 H.264，避免滑页卡在冷门编码 */
    val feedSources: List<SampleVideo> = listOf(
        SampleVideo(
            id = "feed_oceans",
            name = "Oceans & Marine Wildlife",
            author = "@VideoJS",
            description = "H.264 HD · VideoJS CDN",
            codec = "H.264",
            urls = listOf(
                "https://vjs.zencdn.net/v/oceans.mp4",
                "https://media.w3.org/2010/05/sintel/trailer.mp4"
            )
        ),
        SampleVideo(
            id = "feed_sintel",
            name = "Sintel Open Movie Trailer",
            author = "@W3C",
            description = "H.264 · W3C",
            codec = "H.264",
            urls = listOf(
                "https://media.w3.org/2010/05/sintel/trailer.mp4",
                "https://test-videos.co.uk/vids/sintel/mp4/h264/360/Sintel_360_10s_1MB.mp4"
            )
        ),
        SampleVideo(
            id = "feed_flower",
            name = "Blossom Flower Nature Shot",
            author = "@MDN",
            description = "H.264 · MDN CC0",
            codec = "H.264",
            urls = listOf(
                "https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4",
                "https://lorem.video/480p_h264_10s.mp4"
            )
        ),
        SampleVideo(
            id = "feed_bbb",
            name = "Big Buck Bunny (10s)",
            author = "@test-videos",
            description = "H.264 · 短片秒播",
            codec = "H.264",
            urls = listOf(
                "https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/360/Big_Buck_Bunny_360_10s_1MB.mp4",
                "https://lorem.video/480p_h264_10s.mp4",
                "https://www.w3schools.com/html/mov_bbb.mp4"
            )
        )
    )

    /** Clip 预览默认片源 */
    val clipPreview: SampleVideo = SampleVideo(
        id = "clip_sintel",
        name = "Sintel Trailer",
        description = "剪辑预览默认片源",
        codec = "H.264",
        urls = listOf(
            "https://media.w3.org/2010/05/sintel/trailer.mp4",
            "https://test-videos.co.uk/vids/sintel/mp4/h264/360/Sintel_360_10s_1MB.mp4",
            "https://vjs.zencdn.net/v/oceans.mp4"
        )
    )

    /** 所有会出现在 Sample 里的直链（用于离线探测脚本） */
    fun allProbeUrls(): List<String> {
        return (vodSources + feedSources + listOf(clipPreview))
            .flatMap { it.urls }
            .distinct()
    }
}
