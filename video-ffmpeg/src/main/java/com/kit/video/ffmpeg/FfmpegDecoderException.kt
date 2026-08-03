package com.kit.video.ffmpeg

import androidx.media3.decoder.DecoderException

/** FFmpeg 音视频软解统一异常。 */
class FfmpegDecoderException : DecoderException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable?) : super(message, cause)
}
