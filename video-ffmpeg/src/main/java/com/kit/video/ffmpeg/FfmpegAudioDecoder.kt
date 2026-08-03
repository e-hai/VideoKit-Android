package com.kit.video.ffmpeg

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import java.nio.ByteBuffer

/**
 * 自研 FFmpeg 音频软解（Media3 [SimpleDecoder] 契约）：
 * 压缩音轨 → JNI/FFmpeg → PCM，由 [FfmpegAudioRenderer] 送入 AudioSink。
 */
@OptIn(UnstableApi::class)
class FfmpegAudioDecoder(
    format: Format,
    numInputBuffers: Int = 16,
    numOutputBuffers: Int = 16,
    initialInputBufferSize: Int,
    outputFloat: Boolean
) : SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfmpegDecoderException>(
    arrayOfNulls(numInputBuffers),
    arrayOfNulls(numOutputBuffers)
) {

    companion object {
        private const val ERROR_INVALID_DATA = -1
        private const val ERROR_OTHER = -2
        private const val INITIAL_OUTPUT_PCM16 = 65535
        private const val INITIAL_OUTPUT_FLOAT = INITIAL_OUTPUT_PCM16 * 2

        fun buildExtraData(mimeType: String, initializationData: List<ByteArray>): ByteArray? {
            if (initializationData.isEmpty()) return null
            return when (mimeType) {
                MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_OPUS -> initializationData[0]
                MimeTypes.AUDIO_VORBIS -> {
                    if (initializationData.size < 2) return initializationData[0]
                    val h0 = initializationData[0]
                    val h1 = initializationData[1]
                    val out = ByteArray(h0.size + h1.size + 6)
                    out[0] = (h0.size shr 8).toByte()
                    out[1] = (h0.size and 0xFF).toByte()
                    System.arraycopy(h0, 0, out, 2, h0.size)
                    out[h0.size + 4] = (h1.size shr 8).toByte()
                    out[h0.size + 5] = (h1.size and 0xFF).toByte()
                    System.arraycopy(h1, 0, out, h0.size + 6, h1.size)
                    out
                }
                else -> initializationData[0]
            }
        }
    }

    private val codecName: String
    private val extraData: ByteArray?
    private val encoding: @C.PcmEncoding Int =
        if (outputFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT

    private var nativeHandle: Long = 0L
    private var outputBufferSize: Int =
        if (outputFloat) INITIAL_OUTPUT_FLOAT else INITIAL_OUTPUT_PCM16
    private var hasOutputFormat = false

    @Volatile
    private var channelCount: Int = 0

    @Volatile
    private var sampleRate: Int = 0

    init {
        if (!FfmpegLibrary.isAvailable()) {
            throw FfmpegDecoderException("FFmpeg native library unavailable")
        }
        val mime = format.sampleMimeType
            ?: throw FfmpegDecoderException("Audio mime is null")
        codecName = FfmpegLibrary.codecNameOf(mime)
            ?: throw FfmpegDecoderException("Unsupported audio mime: $mime")
        extraData = buildExtraData(mime, format.initializationData)
        nativeHandle = nativeInit(
            codecName,
            extraData,
            outputFloat,
            format.sampleRate,
            format.channelCount
        )
        if (nativeHandle == 0L) {
            throw FfmpegDecoderException("Audio decoder init failed: $codecName")
        }
        setInitialInputBufferSize(initialInputBufferSize)
    }

    override fun getName(): String = "ffmpeg-audio-$codecName"

    override fun createInputBuffer(): DecoderInputBuffer {
        return DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)
    }

    override fun createOutputBuffer(): SimpleDecoderOutputBuffer {
        return SimpleDecoderOutputBuffer { outputBuffer ->
            releaseOutputBuffer(outputBuffer)
        }
    }

    override fun createUnexpectedDecodeException(error: Throwable): FfmpegDecoderException {
        return FfmpegDecoderException("Unexpected audio decode error: ${error.message}", error)
    }

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: SimpleDecoderOutputBuffer,
        reset: Boolean
    ): FfmpegDecoderException? {
        if (reset) {
            nativeHandle = nativeFlush(nativeHandle)
            if (nativeHandle == 0L) {
                return FfmpegDecoderException("Audio decoder flush failed")
            }
        }

        val inputData = inputBuffer.data
        if (inputData == null || inputData.remaining() <= 0) {
            outputBuffer.shouldBeSkipped = true
            return null
        }

        val inputSize = inputData.limit()
        var outputData = outputBuffer.init(inputBuffer.timeUs, outputBufferSize)
        val result = nativeDecode(
            nativeHandle,
            inputData,
            inputSize,
            outputBuffer,
            outputData,
            outputBufferSize
        )

        when {
            result == ERROR_OTHER -> return FfmpegDecoderException("Audio decode error (see logcat)")
            result == ERROR_INVALID_DATA || result == 0 -> {
                outputBuffer.shouldBeSkipped = true
                return null
            }
            result < 0 -> return FfmpegDecoderException("Audio decode failed: $result")
        }

        if (!hasOutputFormat) {
            channelCount = nativeGetChannelCount(nativeHandle)
            sampleRate = nativeGetSampleRate(nativeHandle)
            if (sampleRate == 0 && codecName == "alac" && extraData != null && extraData.size >= 4) {
                sampleRate = ByteBuffer.wrap(extraData, extraData.size - 4, 4).int
            }
            hasOutputFormat = true
        }

        outputData = Util.castNonNull(outputBuffer.data)
        outputData.position(0)
        outputData.limit(result)
        return null
    }

    /** Called from JNI when PCM 输出缓冲不够大。 */
    @Suppress("unused")
    private fun growOutputBuffer(
        outputBuffer: SimpleDecoderOutputBuffer,
        requiredSize: Int
    ): ByteBuffer {
        outputBufferSize = requiredSize
        return outputBuffer.grow(requiredSize)
    }

    override fun release() {
        super.release()
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
            nativeHandle = 0L
        }
    }

    fun getChannelCount(): Int = channelCount
    fun getSampleRate(): Int = sampleRate
    fun getEncoding(): @C.PcmEncoding Int = encoding

    private external fun nativeInit(
        codecName: String,
        extraData: ByteArray?,
        outputFloat: Boolean,
        rawSampleRate: Int,
        rawChannelCount: Int
    ): Long

    private external fun nativeDecode(
        handle: Long,
        inputData: ByteBuffer,
        inputSize: Int,
        decoderOutputBuffer: SimpleDecoderOutputBuffer,
        outputData: ByteBuffer,
        outputSize: Int
    ): Int

    private external fun nativeGetChannelCount(handle: Long): Int
    private external fun nativeGetSampleRate(handle: Long): Int
    private external fun nativeFlush(handle: Long): Long
    private external fun nativeRelease(handle: Long)
}
