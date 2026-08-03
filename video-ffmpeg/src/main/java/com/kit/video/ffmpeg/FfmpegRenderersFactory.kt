package com.kit.video.ffmpeg

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * 自研 FFmpeg 音视频软解统一工厂（方案 B：不依赖官方 decoder_ffmpeg 模块）。
 *
 * 策略固定为：**MediaCodec 硬解优先**，FFmpeg Renderer 挂在列表末尾作能力兜底
 * （格式硬解不支持时由 ExoPlayer 选到 FFmpeg）。
 *
 * ```
 * ExoPlayer（解复用 + A/V 同步 + 轨选择）
 *   ├─ Video：MediaCodec → [FfmpegVideoRenderer]
 *   └─ Audio：MediaCodec → [FfmpegAudioRenderer]
 *         ↓
 *   同一 libffmpeg_jni.so → 同一套 prebuilt libav*.so
 * ```
 */
@OptIn(UnstableApi::class)
class FfmpegRenderersFactory(
    context: Context
) : DefaultRenderersFactory(context) {

    init {
        setEnableDecoderFallback(true)
        // 关闭父类对官方扩展的反射加载，音视频软解均由本工厂显式注册
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)
    }

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        super.buildVideoRenderers(
            context,
            EXTENSION_RENDERER_MODE_OFF,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out
        )

        out.add(
            FfmpegVideoRenderer(
                allowedJoiningTimeMs = allowedVideoJoiningTimeMs,
                eventHandler = eventHandler,
                eventListener = eventListener,
                maxDroppedFramesToNotify = 50
            )
        )
        Log.i(TAG, "Video: FfmpegVideoRenderer registered as fallback")
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        super.buildAudioRenderers(
            context,
            EXTENSION_RENDERER_MODE_OFF,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )

        if (!FfmpegLibrary.isAvailable()) {
            Log.w(TAG, "Audio: FFmpeg native unavailable, MediaCodec only")
            return
        }

        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
        Log.i(TAG, "Audio: FfmpegAudioRenderer registered as fallback")
    }

    companion object {
        private const val TAG = "FfmpegRenderersFactory"
    }
}
