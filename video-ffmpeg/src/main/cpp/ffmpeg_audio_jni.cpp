#include <jni.h>
#include <android/log.h>

#include <cstring>
#include <mutex>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}

#define LOG_TAG "FFmpegAudioJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kErrInvalidData = -1;
constexpr int kErrOther = -2;

constexpr AVSampleFormat kPcm16 = AV_SAMPLE_FMT_S16;
constexpr AVSampleFormat kPcmFloat = AV_SAMPLE_FMT_FLT;

struct AudioDecoderContext {
    std::mutex mutex;
    AVCodecContext* codecContext = nullptr;
    SwrContext* swrContext = nullptr;
    AVSampleFormat requestSampleFmt = kPcm16;
};

static jmethodID g_growOutputBufferMethod = nullptr;

void logAvError(const char* what, int err) {
    char buf[256];
    av_strerror(err, buf, sizeof(buf));
    LOGE("%s: %s (%d)", what, buf, err);
}

void releaseSwr(AudioDecoderContext* ctx) {
    if (ctx->swrContext) {
        swr_free(&ctx->swrContext);
        ctx->swrContext = nullptr;
    }
}

void releaseAudioContext(AudioDecoderContext* ctx) {
    if (!ctx) return;
    releaseSwr(ctx);
    if (ctx->codecContext) {
        if (ctx->codecContext->extradata) {
            av_free(ctx->codecContext->extradata);
            ctx->codecContext->extradata = nullptr;
            ctx->codecContext->extradata_size = 0;
        }
        avcodec_free_context(&ctx->codecContext);
    }
    delete ctx;
}

int transformError(int err) {
    return err == AVERROR_INVALIDDATA ? kErrInvalidData : kErrOther;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kit_video_ffmpeg_FfmpegLibrary_nativeHasDecoder(
        JNIEnv* env,
        jclass /* clazz */,
        jstring codecName) {
    if (!codecName) return JNI_FALSE;
    const char* name = env->GetStringUTFChars(codecName, nullptr);
    const AVCodec* codec = avcodec_find_decoder_by_name(name);
    env->ReleaseStringUTFChars(codecName, name);
    return codec != nullptr ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kit_video_ffmpeg_FfmpegLibrary_nativeGetVersion(
        JNIEnv* env,
        jclass /* clazz */) {
    return env->NewStringUTF(LIBAVCODEC_IDENT);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_kit_video_ffmpeg_FfmpegAudioDecoder_nativeInit(
        JNIEnv* env,
        jobject /* thiz */,
        jstring codecName,
        jbyteArray extraData,
        jboolean outputFloat,
        jint rawSampleRate,
        jint rawChannelCount) {
    if (!codecName) return 0;
    const char* name = env->GetStringUTFChars(codecName, nullptr);
    const AVCodec* codec = avcodec_find_decoder_by_name(name);
    env->ReleaseStringUTFChars(codecName, name);
    if (!codec) {
        LOGE("Audio codec not found");
        return 0;
    }

    auto* ctx = new AudioDecoderContext();
    ctx->requestSampleFmt = outputFloat ? kPcmFloat : kPcm16;
    ctx->codecContext = avcodec_alloc_context3(codec);
    if (!ctx->codecContext) {
        releaseAudioContext(ctx);
        return 0;
    }
    ctx->codecContext->request_sample_fmt = ctx->requestSampleFmt;
    ctx->codecContext->err_recognition = AV_EF_IGNORE_ERR;

    if (extraData != nullptr) {
        const jsize size = env->GetArrayLength(extraData);
        if (size > 0) {
            ctx->codecContext->extradata =
                    static_cast<uint8_t*>(av_malloc(size + AV_INPUT_BUFFER_PADDING_SIZE));
            if (!ctx->codecContext->extradata) {
                releaseAudioContext(ctx);
                return 0;
            }
            env->GetByteArrayRegion(extraData, 0, size,
                                    reinterpret_cast<jbyte*>(ctx->codecContext->extradata));
            memset(ctx->codecContext->extradata + size, 0, AV_INPUT_BUFFER_PADDING_SIZE);
            ctx->codecContext->extradata_size = size;
        }
    }

    if (ctx->codecContext->codec_id == AV_CODEC_ID_PCM_MULAW ||
        ctx->codecContext->codec_id == AV_CODEC_ID_PCM_ALAW) {
        ctx->codecContext->sample_rate = rawSampleRate;
        av_channel_layout_default(&ctx->codecContext->ch_layout, rawChannelCount);
    }

    const int openRet = avcodec_open2(ctx->codecContext, codec, nullptr);
    if (openRet < 0) {
        logAvError("avcodec_open2(audio)", openRet);
        releaseAudioContext(ctx);
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kit_video_ffmpeg_FfmpegAudioDecoder_nativeDecode(
        JNIEnv* env,
        jobject thiz,
        jlong handle,
        jobject inputData,
        jint inputSize,
        jobject decoderOutputBuffer,
        jobject outputData,
        jint outputSize) {
    auto* ctx = reinterpret_cast<AudioDecoderContext*>(handle);
    if (!ctx || !ctx->codecContext || !inputData || !outputData || inputSize < 0 || outputSize < 0) {
        return kErrOther;
    }

    std::lock_guard<std::mutex> lock(ctx->mutex);

    auto* inputBuffer = static_cast<uint8_t*>(env->GetDirectBufferAddress(inputData));
    auto* outputBuffer = static_cast<uint8_t*>(env->GetDirectBufferAddress(outputData));
    if (!inputBuffer || !outputBuffer) {
        return kErrOther;
    }

    AVPacket* packet = av_packet_alloc();
    if (!packet) return kErrOther;
    packet->data = inputBuffer;
    packet->size = inputSize;

    int sendRet = avcodec_send_packet(ctx->codecContext, packet);
    av_packet_free(&packet);
    if (sendRet < 0) {
        logAvError("avcodec_send_packet(audio)", sendRet);
        return transformError(sendRet);
    }

    int outSize = 0;
    while (true) {
        AVFrame* frame = av_frame_alloc();
        if (!frame) return kErrInvalidData;

        int receiveRet = avcodec_receive_frame(ctx->codecContext, frame);
        if (receiveRet == AVERROR(EAGAIN) || receiveRet == AVERROR_EOF) {
            av_frame_free(&frame);
            break;
        }
        if (receiveRet < 0) {
            logAvError("avcodec_receive_frame(audio)", receiveRet);
            av_frame_free(&frame);
            return transformError(receiveRet);
        }

        const AVSampleFormat sampleFormat = static_cast<AVSampleFormat>(frame->format);
        const int channelCount = frame->ch_layout.nb_channels > 0
                ? frame->ch_layout.nb_channels
                : ctx->codecContext->ch_layout.nb_channels;
        const int sampleRate = frame->sample_rate > 0
                ? frame->sample_rate
                : ctx->codecContext->sample_rate;
        const int sampleCount = frame->nb_samples;

        if (!ctx->swrContext) {
            AVChannelLayout outLayout{};
            av_channel_layout_default(&outLayout, channelCount);
            int swrRet = swr_alloc_set_opts2(
                    &ctx->swrContext,
                    &outLayout,
                    ctx->requestSampleFmt,
                    sampleRate,
                    &frame->ch_layout,
                    sampleFormat,
                    sampleRate,
                    0,
                    nullptr);
            if (swrRet < 0 || !ctx->swrContext || swr_init(ctx->swrContext) < 0) {
                LOGE("swr_init failed");
                av_channel_layout_uninit(&outLayout);
                av_frame_free(&frame);
                return kErrOther;
            }
            av_channel_layout_uninit(&outLayout);
        }

        const int outSampleSize = av_get_bytes_per_sample(ctx->requestSampleFmt);
        const int outSamples = swr_get_out_samples(ctx->swrContext, sampleCount);
        const int bufferOutSize = outSampleSize * channelCount * outSamples;
        if (outSize + bufferOutSize > outputSize) {
            const int required = outSize + bufferOutSize;
            if (!g_growOutputBufferMethod) {
                jclass clazz = env->GetObjectClass(thiz);
                g_growOutputBufferMethod = env->GetMethodID(
                        clazz,
                        "growOutputBuffer",
                        "(Landroidx/media3/decoder/SimpleDecoderOutputBuffer;I)Ljava/nio/ByteBuffer;");
            }
            if (!g_growOutputBufferMethod) {
                av_frame_free(&frame);
                return kErrOther;
            }
            jobject newBuf = env->CallObjectMethod(
                    thiz, g_growOutputBufferMethod, decoderOutputBuffer, required);
            if (env->ExceptionCheck() || !newBuf) {
                env->ExceptionClear();
                av_frame_free(&frame);
                return kErrOther;
            }
            outputBuffer = static_cast<uint8_t*>(env->GetDirectBufferAddress(newBuf));
            outputSize = required;
            if (!outputBuffer) {
                av_frame_free(&frame);
                return kErrOther;
            }
        }

        uint8_t* dst = outputBuffer + outSize;
        int converted = swr_convert(
                ctx->swrContext,
                &dst,
                outSamples,
                const_cast<const uint8_t**>(frame->extended_data),
                sampleCount);
        av_frame_free(&frame);
        if (converted < 0) {
            logAvError("swr_convert", converted);
            return kErrInvalidData;
        }
        outSize += outSampleSize * channelCount * converted;
    }
    return outSize;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kit_video_ffmpeg_FfmpegAudioDecoder_nativeGetChannelCount(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong handle) {
    auto* ctx = reinterpret_cast<AudioDecoderContext*>(handle);
    if (!ctx || !ctx->codecContext) return -1;
    return ctx->codecContext->ch_layout.nb_channels;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kit_video_ffmpeg_FfmpegAudioDecoder_nativeGetSampleRate(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong handle) {
    auto* ctx = reinterpret_cast<AudioDecoderContext*>(handle);
    if (!ctx || !ctx->codecContext) return -1;
    return ctx->codecContext->sample_rate;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_kit_video_ffmpeg_FfmpegAudioDecoder_nativeFlush(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong handle) {
    auto* ctx = reinterpret_cast<AudioDecoderContext*>(handle);
    if (!ctx || !ctx->codecContext) return 0;
    std::lock_guard<std::mutex> lock(ctx->mutex);
    avcodec_flush_buffers(ctx->codecContext);
    releaseSwr(ctx);
    return handle;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kit_video_ffmpeg_FfmpegAudioDecoder_nativeRelease(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong handle) {
    auto* ctx = reinterpret_cast<AudioDecoderContext*>(handle);
    if (!ctx) return;
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        releaseSwr(ctx);
        if (ctx->codecContext) {
            if (ctx->codecContext->extradata) {
                av_free(ctx->codecContext->extradata);
                ctx->codecContext->extradata = nullptr;
            }
            avcodec_free_context(&ctx->codecContext);
        }
    }
    delete ctx;
}
