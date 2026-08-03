package com.kit.video.ffmpeg

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi

/**
 * 加载本模块唯一 JNI 桥接库 [libffmpeg_jni.so]（视频 + 音频），并查询 FFmpeg 是否编入了某解码器。
 */
@OptIn(UnstableApi::class)
object FfmpegLibrary {
    private const val TAG = "FfmpegLibrary"
    private const val LIB_NAME = "ffmpeg_jni"

    @Volatile
    private var available: Boolean? = null

    fun isAvailable(): Boolean {
        available?.let { return it }
        return synchronized(this) {
            available ?: run {
                val ok = try {
                    System.loadLibrary(LIB_NAME)
                    true
                } catch (t: Throwable) {
                    Log.e(TAG, "loadLibrary($LIB_NAME) failed", t)
                    false
                }
                available = ok
                ok
            }
        }
    }

    fun getVersion(): String? {
        if (!isAvailable()) return null
        return try {
            nativeGetVersion()
        } catch (_: Throwable) {
            null
        }
    }

    fun hasDecoder(codecName: String): Boolean {
        if (!isAvailable()) return false
        return try {
            nativeHasDecoder(codecName)
        } catch (_: Throwable) {
            false
        }
    }

    /** Media3 mime → FFmpeg decoder name */
    fun codecNameOf(mimeType: String): String? {
        return when (mimeType) {
            MimeTypes.AUDIO_AAC -> "aac"
            MimeTypes.AUDIO_MPEG, MimeTypes.AUDIO_MPEG_L1, MimeTypes.AUDIO_MPEG_L2 -> "mp3"
            MimeTypes.AUDIO_AC3 -> "ac3"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "eac3"
            MimeTypes.AUDIO_TRUEHD -> "truehd"
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD -> "dca"
            MimeTypes.AUDIO_VORBIS -> "vorbis"
            MimeTypes.AUDIO_OPUS -> "opus"
            MimeTypes.AUDIO_AMR_NB -> "amrnb"
            MimeTypes.AUDIO_AMR_WB -> "amrwb"
            MimeTypes.AUDIO_FLAC -> "flac"
            MimeTypes.AUDIO_ALAC -> "alac"
            MimeTypes.AUDIO_MLAW -> "pcm_mulaw"
            MimeTypes.AUDIO_ALAW -> "pcm_alaw"
            MimeTypes.VIDEO_H264 -> "h264"
            MimeTypes.VIDEO_H265 -> "hevc"
            MimeTypes.VIDEO_VP8 -> "vp8"
            MimeTypes.VIDEO_VP9 -> "vp9"
            MimeTypes.VIDEO_AV1 -> "av1"
            else -> null
        }
    }

    fun supportsAudioMime(mimeType: String): Boolean {
        val name = codecNameOf(mimeType) ?: return false
        return hasDecoder(name)
    }

    private external fun nativeHasDecoder(codecName: String): Boolean
    private external fun nativeGetVersion(): String
}
