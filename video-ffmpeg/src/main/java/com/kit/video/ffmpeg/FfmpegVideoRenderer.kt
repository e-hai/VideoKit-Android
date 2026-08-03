package com.kit.video.ffmpeg

import android.os.Handler
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.video.DecoderVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import java.io.ByteArrayOutputStream

/**
 * Media3 自定义 FFmpeg 视频软解渲染器：
 * - 解码输出按官方契约填入 [VideoDecoderOutputBuffer] 的 YUV planes
 * - PlayerView Surface 路径下通过 [renderOutputBufferToSurface] 用该 buffer 上屏
 */
@OptIn(UnstableApi::class)
class FfmpegVideoRenderer(
    allowedJoiningTimeMs: Long = 2000,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int = 50
) : DecoderVideoRenderer(
    allowedJoiningTimeMs,
    eventHandler,
    eventListener,
    maxDroppedFramesToNotify
) {

    private val tag = "FfmpegVideoRenderer"
    private var decoder: FfmpegVideoDecoder? = null

    override fun getName(): String = "FfmpegVideoRenderer"

    override fun supportsFormat(format: Format): Int {
        val mimeType = format.sampleMimeType
            ?: return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        return if (mimeType == MimeTypes.VIDEO_H264 ||
            mimeType == MimeTypes.VIDEO_H265 ||
            mimeType == MimeTypes.VIDEO_VP8 ||
            mimeType == MimeTypes.VIDEO_VP9 ||
            mimeType == MimeTypes.VIDEO_AV1
        ) {
            RendererCapabilities.create(C.FORMAT_HANDLED)
        } else {
            RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        }
    }

    override fun createDecoder(
        format: Format,
        cryptoConfig: CryptoConfig?
    ): Decoder<DecoderInputBuffer, out VideoDecoderOutputBuffer, out DecoderException> {
        val mimeType = format.sampleMimeType ?: MimeTypes.VIDEO_H264
        val codecName = when {
            mimeType.contains("hevc") || mimeType.contains("h265") -> "hevc"
            mimeType.contains("vp9") -> "vp9"
            mimeType.contains("vp8") -> "vp8"
            mimeType.contains("av01") || mimeType.contains("av1") -> "av1"
            else -> "h264"
        }

        // H.264/HEVC：MP4 样本为 AVCC/HVCC，需转 Annex-B；VP8/VP9/AV1 通常不需要。
        val convertAvcc = codecName == "h264" || codecName == "hevc"
        val extraData = if (convertAvcc) {
            buildAnnexBInitializationData(format.initializationData)
        } else {
            concatInitializationData(format.initializationData)
        }

        Log.d(
            tag,
            "createDecoder codec=$codecName convertAvcc=$convertAvcc extra=${extraData?.size ?: 0}"
        )

        val ffmpegDecoder = FfmpegVideoDecoder(
            codecName = codecName,
            extraData = extraData,
            convertAvcc = convertAvcc
        )
        // PlayerView 默认挂 Surface，框架随后会 setDecoderOutputMode(SURFACE_YUV)
        ffmpegDecoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV)
        decoder = ffmpegDecoder
        return ffmpegDecoder
    }

    override fun renderOutputBufferToSurface(
        outputBuffer: VideoDecoderOutputBuffer,
        surface: Surface
    ) {
        decoder?.renderToSurface(outputBuffer, surface)
        outputBuffer.release()
    }

    override fun setDecoderOutputMode(outputMode: @C.VideoOutputMode Int) {
        decoder?.setOutputMode(outputMode)
    }

    override fun canReuseDecoder(
        decoderName: String,
        oldFormat: Format,
        newFormat: Format
    ): DecoderReuseEvaluation {
        return DecoderReuseEvaluation(
            decoderName,
            oldFormat,
            newFormat,
            DecoderReuseEvaluation.REUSE_RESULT_NO,
            DecoderReuseEvaluation.DISCARD_REASON_REUSE_NOT_IMPLEMENTED
        )
    }

    companion object {
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        /**
         * Media3 的 initializationData 通常是裸 SPS/PPS（无起始码）。
         * FFmpeg Annex-B extradata 需要 `00 00 00 01 + NAL` 串联。
         */
        fun buildAnnexBInitializationData(initializationData: List<ByteArray>): ByteArray? {
            if (initializationData.isEmpty()) return null
            val out = ByteArrayOutputStream()
            for (nal in initializationData) {
                if (nal.isEmpty()) continue
                val offset = startCodeSize(nal)
                out.write(START_CODE)
                out.write(nal, offset, nal.size - offset)
            }
            val bytes = out.toByteArray()
            return bytes.takeIf { it.isNotEmpty() }
        }

        private fun concatInitializationData(initializationData: List<ByteArray>): ByteArray? {
            if (initializationData.isEmpty()) return null
            val out = ByteArrayOutputStream()
            initializationData.forEach { out.write(it) }
            val bytes = out.toByteArray()
            return bytes.takeIf { it.isNotEmpty() }
        }

        private fun startCodeSize(nal: ByteArray): Int {
            return when {
                nal.size >= 4 &&
                    nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                    nal[2] == 0.toByte() && nal[3] == 1.toByte() -> 4
                nal.size >= 3 &&
                    nal[0] == 0.toByte() && nal[1] == 0.toByte() &&
                    nal[2] == 1.toByte() -> 3
                else -> 0
            }
        }
    }
}
