package com.kit.video.editor

import android.content.Context
import android.util.Log

/**
 * 视频编辑引擎：负责计算裁剪区间、滤镜矩阵应用及渲染配置导出
 */
class VideoEditorEngine(private val context: Context) {

    private var currentParams = VideoEditParams()

    fun updateParams(params: VideoEditParams) {
        this.currentParams = params
        Log.d(TAG, "更新编辑参数: $params")
    }

    fun getTrimDurationMs(): Long {
        return (currentParams.trimEndMs - currentParams.trimStartMs).coerceAtLeast(0L)
    }

    fun applyFilter(filterName: String) {
        currentParams = currentParams.copy(filterName = filterName)
        Log.d(TAG, "应用滤镜: $filterName")
    }

    fun updateAdjustment(brightness: Float, contrast: Float, saturation: Float) {
        currentParams = currentParams.copy(
            brightness = brightness,
            contrast = contrast,
            saturation = saturation
        )
    }

    companion object {
        private const val TAG = "VideoEditorEngine"
    }
}
