package com.kit.video.ffmpeg

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * 自研 FFmpeg 音频 Renderer：对接 Media3 [DecoderAudioRenderer] + [AudioSink]。
 */
@OptIn(UnstableApi::class)
class FfmpegAudioRenderer : DecoderAudioRenderer<FfmpegAudioDecoder> {

    constructor() : this(null, null)

    constructor(
        eventHandler: Handler?,
        eventListener: AudioRendererEventListener?,
        vararg audioProcessors: AudioProcessor
    ) : this(
        eventHandler,
        eventListener,
        DefaultAudioSink.Builder()
            .setAudioProcessors(audioProcessors)
            .build()
    )

    constructor(
        eventHandler: Handler?,
        eventListener: AudioRendererEventListener?,
        audioSink: AudioSink
    ) : super(eventHandler, eventListener, audioSink)

    override fun getName(): String = "FfmpegAudioRenderer"

    override fun supportsFormatInternal(format: Format): @C.FormatSupport Int {
        val mimeType = format.sampleMimeType ?: return C.FORMAT_UNSUPPORTED_TYPE
        if (!FfmpegLibrary.isAvailable() || !MimeTypes.isAudio(mimeType)) {
            return C.FORMAT_UNSUPPORTED_TYPE
        }
        if (!FfmpegLibrary.supportsAudioMime(mimeType)) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE
        }
        if (!sinkSupportsFormat(format, C.ENCODING_PCM_16BIT) &&
            !sinkSupportsFormat(format, C.ENCODING_PCM_FLOAT)
        ) {
            return C.FORMAT_UNSUPPORTED_SUBTYPE
        }
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
            return C.FORMAT_UNSUPPORTED_DRM
        }
        return C.FORMAT_HANDLED
    }

    override fun supportsMixedMimeTypeAdaptation(): @RendererCapabilities.AdaptiveSupport Int {
        return ADAPTIVE_NOT_SEAMLESS
    }

    override fun createDecoder(
        format: Format,
        cryptoConfig: CryptoConfig?
    ): FfmpegAudioDecoder {
        val initialInputBufferSize =
            if (format.maxInputSize != Format.NO_VALUE) format.maxInputSize else DEFAULT_INPUT_BUFFER_SIZE
        return FfmpegAudioDecoder(
            format = format,
            initialInputBufferSize = initialInputBufferSize,
            outputFloat = shouldOutputFloat(format)
        )
    }

    override fun getOutputFormat(decoder: FfmpegAudioDecoder): Format {
        return Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setChannelCount(decoder.getChannelCount())
            .setSampleRate(decoder.getSampleRate())
            .setPcmEncoding(decoder.getEncoding())
            .build()
    }

    private fun sinkSupportsFormat(inputFormat: Format, pcmEncoding: @C.PcmEncoding Int): Boolean {
        return sinkSupportsFormat(
            Util.getPcmFormat(pcmEncoding, inputFormat.channelCount, inputFormat.sampleRate)
        )
    }

    private fun shouldOutputFloat(inputFormat: Format): Boolean {
        if (!sinkSupportsFormat(inputFormat, C.ENCODING_PCM_16BIT)) {
            return true
        }
        val floatSupport = getSinkFormatSupport(
            Util.getPcmFormat(C.ENCODING_PCM_FLOAT, inputFormat.channelCount, inputFormat.sampleRate)
        )
        return floatSupport == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY &&
            MimeTypes.AUDIO_AC3 != inputFormat.sampleMimeType
    }

    companion object {
        private const val DEFAULT_INPUT_BUFFER_SIZE = 960 * 6
    }
}
