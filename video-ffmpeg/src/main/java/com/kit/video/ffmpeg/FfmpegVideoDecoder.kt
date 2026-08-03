package com.kit.video.ffmpeg

import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.VideoDecoderOutputBuffer
import java.nio.ByteBuffer

/**
 * FFmpeg 软件视频解码器（Media3 SimpleDecoder 契约）：
 * - 解码结果始终写入 [VideoDecoderOutputBuffer.yuvPlanes]（官方 YUV 数据形态）
 * - PlayerView 的 Surface 模式下，仍用同一 buffer 的 YUV 在
 *   [renderToSurface] 中上屏，避免全局单帧竞态
 */
@OptIn(UnstableApi::class)
class FfmpegVideoDecoder(
    private val codecName: String,
    extraData: ByteArray?,
    convertAvcc: Boolean,
    numInputBuffers: Int = 4,
    numOutputBuffers: Int = 4
) : SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegDecoderException>(
    arrayOfNulls(numInputBuffers),
    arrayOfNulls(numOutputBuffers)
) {

    companion object {
        private const val TAG = "FfmpegVideoDecoder"
    }

    private var nativeHandle: Long = 0L

    @Volatile
    private var outputMode: @C.VideoOutputMode Int = C.VIDEO_OUTPUT_MODE_NONE

    init {
        if (!FfmpegLibrary.isAvailable()) {
            throw FfmpegDecoderException("FFmpeg native library unavailable")
        }
        nativeHandle = nativeInit(
            codecName,
            extraData,
            extraData?.size ?: 0,
            convertAvcc
        )
        if (nativeHandle == 0L) {
            throw FfmpegDecoderException("FFmpeg 解码器初始化失败: $codecName")
        }
        Log.d(TAG, "SimpleDecoder ready: $codecName convertAvcc=$convertAvcc")
    }

    fun setOutputMode(outputMode: @C.VideoOutputMode Int) {
        this.outputMode = outputMode
    }

    override fun getName(): String = "ffmpeg-$codecName"

    override fun createInputBuffer(): DecoderInputBuffer {
        return DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
    }

    override fun createOutputBuffer(): VideoDecoderOutputBuffer {
        return VideoDecoderOutputBuffer { outputBuffer ->
            releaseOutputBuffer(outputBuffer)
        }
    }

    override fun createUnexpectedDecodeException(error: Throwable): FfmpegDecoderException {
        return FfmpegDecoderException("FFmpeg 解码异常: ${error.message}")
    }

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: VideoDecoderOutputBuffer,
        reset: Boolean
    ): FfmpegDecoderException? {
        if (nativeHandle == 0L) {
            return FfmpegDecoderException("Native decoder 未初始化")
        }

        if (reset) {
            nativeFlush(nativeHandle)
        }

        val data = inputBuffer.data
        if (data == null || data.remaining() <= 0) {
            outputBuffer.shouldBeSkipped = true
            return null
        }

        if (outputMode == C.VIDEO_OUTPUT_MODE_NONE) {
            outputBuffer.shouldBeSkipped = true
            return null
        }

        val size = data.remaining()
        val byteArray = ByteArray(size)
        data.get(byteArray)

        // [result, ptsUs, width, height]
        val frameInfo = LongArray(4)
        val result = nativeDecode(
            nativeHandle,
            byteArray,
            size,
            inputBuffer.timeUs,
            frameInfo
        )
        if (result <= 0) {
            // 0 = 需要更多输入 / EAGAIN；<0 = 错误但不致命，跳过本包
            outputBuffer.shouldBeSkipped = true
            return null
        }

        val framePtsUs = frameInfo[1]
        val width = frameInfo[2].toInt()
        val height = frameInfo[3].toInt()
        if (width <= 0 || height <= 0) {
            outputBuffer.shouldBeSkipped = true
            return null
        }

        // PlayerView 通常是 SURFACE_YUV；仍把 YUV 写入官方 buffer，供上屏使用。
        val mode = when (outputMode) {
            C.VIDEO_OUTPUT_MODE_YUV -> C.VIDEO_OUTPUT_MODE_YUV
            else -> C.VIDEO_OUTPUT_MODE_SURFACE_YUV
        }

        outputBuffer.init(framePtsUs, mode, null)
        outputBuffer.timeUs = framePtsUs
        outputBuffer.width = width
        outputBuffer.height = height

        val colorSpace = if (width >= 1280 || height >= 720) {
            C.COLOR_SPACE_BT709
        } else {
            C.COLOR_SPACE_BT601
        }

        if (!outputBuffer.initForYuvFrame(width, height, width, width / 2, colorSpace)) {
            return FfmpegDecoderException("初始化 YUV buffer 失败: ${width}x${height}")
        }

        val planes = outputBuffer.yuvPlanes
            ?: return FfmpegDecoderException("yuvPlanes 为空")
        val copied = nativeCopyYuvTo(
            nativeHandle,
            planes[0],
            planes[1],
            planes[2],
            width,
            height
        )
        if (!copied) {
            return FfmpegDecoderException("拷贝 YUV 到 Media3 buffer 失败")
        }

        return null
    }

    override fun release() {
        super.release()
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
            nativeHandle = 0L
        }
    }

    /**
     * Surface 上屏：使用 [outputBuffer] 内已填充的 YUV planes，不再依赖全局 AVFrame。
     */
    fun renderToSurface(outputBuffer: VideoDecoderOutputBuffer, surface: Surface) {
        val planes = outputBuffer.yuvPlanes ?: return
        val strides = outputBuffer.yuvStrides ?: return
        if (nativeHandle == 0L) return

        nativeRenderYuvToSurface(
            nativeHandle,
            surface,
            planes[0],
            planes[1],
            planes[2],
            outputBuffer.width,
            outputBuffer.height,
            strides[0],
            strides[1]
        )
    }

    private external fun nativeInit(
        codecName: String,
        extraData: ByteArray?,
        extraSize: Int,
        convertAvcc: Boolean
    ): Long

    private external fun nativeDecode(
        handle: Long,
        data: ByteArray,
        size: Int,
        ptsUs: Long,
        outFrameInfo: LongArray
    ): Int

    private external fun nativeCopyYuvTo(
        handle: Long,
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int
    ): Boolean

    private external fun nativeRenderYuvToSurface(
        handle: Long,
        surface: Surface,
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        width: Int,
        height: Int,
        yStride: Int,
        uvStride: Int
    )

    private external fun nativeFlush(handle: Long)
    private external fun nativeRelease(handle: Long)
}
