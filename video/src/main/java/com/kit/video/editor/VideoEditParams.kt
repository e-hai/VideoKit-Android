package com.kit.video.editor

/**
 * 视频编辑参数模型（包含裁切范围、滤镜名称与图像调节配置）
 */
data class VideoEditParams(
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 0L,
    val filterName: String = "Original",
    val brightness: Float = 0.5f,
    val contrast: Float = 0.5f,
    val saturation: Float = 0.5f
)
