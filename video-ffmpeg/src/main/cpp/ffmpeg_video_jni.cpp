#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include "gl_yuv_renderer.h"

#include <cstring>
#include <deque>
#include <mutex>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libswscale/swscale.h>
}

#define LOG_TAG "FFmpegVideoJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kNalLengthSize = 4;
constexpr AVRational kMedia3TimeBase = {1, 1000000};

struct DecoderContext {
    std::mutex mutex;
    AVCodecContext* codecContext = nullptr;
    SwsContext* swsToYuv = nullptr;
    std::deque<AVFrame*> frameQueue;
    AVFrame* pendingYuvFrame = nullptr; // owned, YUV420P ready for copy
    bool convertAvcc = false;
};

bool hasAnnexBStartCode(const uint8_t* data, int size) {
    if (size >= 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 1) {
        return true;
    }
    return size >= 3 && data[0] == 0 && data[1] == 0 && data[2] == 1;
}

/**
 * Convert length-prefixed AVCC/HVCC access unit to Annex-B.
 * If already Annex-B, returns a copy of the original bytes.
 */
std::vector<uint8_t> toAnnexB(const uint8_t* data, int size, int nalLengthSize) {
    std::vector<uint8_t> out;
    if (data == nullptr || size <= 0) {
        return out;
    }
    if (hasAnnexBStartCode(data, size)) {
        out.assign(data, data + size);
        return out;
    }

    if (nalLengthSize < 1 || nalLengthSize > 4) {
        nalLengthSize = kNalLengthSize;
    }

    int offset = 0;
    while (offset + nalLengthSize <= size) {
        uint32_t nalSize = 0;
        for (int i = 0; i < nalLengthSize; i++) {
            nalSize = (nalSize << 8) | data[offset + i];
        }
        offset += nalLengthSize;
        if (nalSize == 0 || offset + static_cast<int>(nalSize) > size) {
            LOGE("AVCC parse failed at offset=%d nalSize=%u size=%d", offset, nalSize, size);
            out.clear();
            return out;
        }
        out.push_back(0);
        out.push_back(0);
        out.push_back(0);
        out.push_back(1);
        out.insert(out.end(), data + offset, data + offset + nalSize);
        offset += static_cast<int>(nalSize);
    }
    return out;
}

void clearFrameQueue(DecoderContext* ctx) {
    while (!ctx->frameQueue.empty()) {
        AVFrame* frame = ctx->frameQueue.front();
        ctx->frameQueue.pop_front();
        av_frame_free(&frame);
    }
    if (ctx->pendingYuvFrame) {
        av_frame_free(&ctx->pendingYuvFrame);
    }
}

AVFrame* convertToYuv420p(DecoderContext* ctx, AVFrame* src) {
    if (src->format == AV_PIX_FMT_YUV420P || src->format == AV_PIX_FMT_YUVJ420P) {
        AVFrame* clone = av_frame_clone(src);
        return clone;
    }

    AVFrame* dst = av_frame_alloc();
    if (!dst) {
        return nullptr;
    }
    dst->format = AV_PIX_FMT_YUV420P;
    dst->width = src->width;
    dst->height = src->height;
    if (av_frame_get_buffer(dst, 32) < 0) {
        av_frame_free(&dst);
        return nullptr;
    }

    ctx->swsToYuv = sws_getCachedContext(
            ctx->swsToYuv,
            src->width, src->height, static_cast<AVPixelFormat>(src->format),
            src->width, src->height, AV_PIX_FMT_YUV420P,
            SWS_BILINEAR, nullptr, nullptr, nullptr);
    if (!ctx->swsToYuv) {
        av_frame_free(&dst);
        return nullptr;
    }

    sws_scale(
            ctx->swsToYuv,
            src->data, src->linesize, 0, src->height,
            dst->data, dst->linesize);
    dst->pts = src->pts;
    return dst;
}

void releaseContextUnlocked(DecoderContext* ctx) {
    if (!ctx) {
        return;
    }
    clearFrameQueue(ctx);
    if (ctx->swsToYuv) {
        sws_freeContext(ctx->swsToYuv);
        ctx->swsToYuv = nullptr;
    }
    if (ctx->codecContext) {
        if (ctx->codecContext->extradata) {
            av_free(ctx->codecContext->extradata);
            ctx->codecContext->extradata = nullptr;
            ctx->codecContext->extradata_size = 0;
        }
        avcodec_free_context(&ctx->codecContext);
    }
}

DecoderContext* fromHandle(jlong handle) {
    return reinterpret_cast<DecoderContext*>(handle);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_kit_video_ffmpeg_FfmpegVideoDecoder_nativeInit(
        JNIEnv* env,
        jobject /* thiz */,
        jstring codecName,
        jbyteArray extraData,
        jint extraSize,
        jboolean convertAvcc) {
    const char* cCodecName = env->GetStringUTFChars(codecName, nullptr);
    LOGD("Init decoder: %s extradata=%d convertAvcc=%d", cCodecName, extraSize, convertAvcc);

    const AVCodec* codec = avcodec_find_decoder_by_name(cCodecName);
    if (!codec) {
        LOGE("Decoder not found: %s", cCodecName);
        env->ReleaseStringUTFChars(codecName, cCodecName);
        return 0;
    }

    auto* ctx = new DecoderContext();
    ctx->convertAvcc = convertAvcc == JNI_TRUE;
    ctx->codecContext = avcodec_alloc_context3(codec);
    if (!ctx->codecContext) {
        LOGE("avcodec_alloc_context3 failed");
        env->ReleaseStringUTFChars(codecName, cCodecName);
        delete ctx;
        return 0;
    }

    // Media3 PTS is in microseconds.
    ctx->codecContext->pkt_timebase = kMedia3TimeBase;
    ctx->codecContext->time_base = kMedia3TimeBase;

    if (extraData != nullptr && extraSize > 0) {
        jbyte* extraBytes = env->GetByteArrayElements(extraData, nullptr);
        ctx->codecContext->extradata =
                static_cast<uint8_t*>(av_malloc(extraSize + AV_INPUT_BUFFER_PADDING_SIZE));
        if (!ctx->codecContext->extradata) {
            env->ReleaseByteArrayElements(extraData, extraBytes, JNI_ABORT);
            env->ReleaseStringUTFChars(codecName, cCodecName);
            releaseContextUnlocked(ctx);
            delete ctx;
            return 0;
        }
        memcpy(ctx->codecContext->extradata, extraBytes, extraSize);
        memset(ctx->codecContext->extradata + extraSize, 0, AV_INPUT_BUFFER_PADDING_SIZE);
        ctx->codecContext->extradata_size = extraSize;
        env->ReleaseByteArrayElements(extraData, extraBytes, JNI_ABORT);
        LOGD("Configured Annex-B extradata (%d bytes)", extraSize);
    }

    if (avcodec_open2(ctx->codecContext, codec, nullptr) < 0) {
        LOGE("avcodec_open2 failed for %s", cCodecName);
        env->ReleaseStringUTFChars(codecName, cCodecName);
        releaseContextUnlocked(ctx);
        delete ctx;
        return 0;
    }

    LOGD("Decoder opened: %s", codec->name);
    env->ReleaseStringUTFChars(codecName, cCodecName);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_kit_video_ffmpeg_FfmpegVideoDecoder_nativeDecode(
        JNIEnv* env,
        jobject /* thiz */,
        jlong handle,
        jbyteArray data,
        jint size,
        jlong ptsUs,
        jlongArray outFrameInfo) {
    DecoderContext* ctx = fromHandle(handle);
    if (!ctx || !ctx->codecContext || data == nullptr || size <= 0 || outFrameInfo == nullptr) {
        return -1;
    }

    std::lock_guard<std::mutex> lock(ctx->mutex);

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) {
        return -1;
    }

    std::vector<uint8_t> payload;
    if (ctx->convertAvcc) {
        payload = toAnnexB(reinterpret_cast<const uint8_t*>(bytes), size, kNalLengthSize);
    } else {
        payload.assign(reinterpret_cast<const uint8_t*>(bytes),
                       reinterpret_cast<const uint8_t*>(bytes) + size);
    }
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    if (payload.empty()) {
        return -1;
    }

    uint8_t* packetBuf = static_cast<uint8_t*>(
            av_malloc(payload.size() + AV_INPUT_BUFFER_PADDING_SIZE));
    if (!packetBuf) {
        return -1;
    }
    memcpy(packetBuf, payload.data(), payload.size());
    memset(packetBuf + payload.size(), 0, AV_INPUT_BUFFER_PADDING_SIZE);

    AVPacket* pkt = av_packet_alloc();
    if (!pkt) {
        av_free(packetBuf);
        return -1;
    }
    // av_packet_from_data takes ownership of packetBuf.
    if (av_packet_from_data(pkt, packetBuf, static_cast<int>(payload.size())) < 0) {
        av_free(packetBuf);
        av_packet_free(&pkt);
        return -1;
    }
    pkt->pts = ptsUs;
    pkt->dts = ptsUs;

    int sendRet = avcodec_send_packet(ctx->codecContext, pkt);
    av_packet_free(&pkt);
    if (sendRet < 0 && sendRet != AVERROR(EAGAIN)) {
        char errBuf[128];
        av_strerror(sendRet, errBuf, sizeof(errBuf));
        LOGW("avcodec_send_packet: %s", errBuf);
        // Still try to drain existing frames.
    }

    while (true) {
        AVFrame* frame = av_frame_alloc();
        if (!frame) {
            break;
        }
        int receiveRet = avcodec_receive_frame(ctx->codecContext, frame);
        if (receiveRet == 0) {
            ctx->frameQueue.push_back(frame);
            continue;
        }
        av_frame_free(&frame);
        if (receiveRet != AVERROR(EAGAIN) && receiveRet != AVERROR_EOF) {
            char errBuf[128];
            av_strerror(receiveRet, errBuf, sizeof(errBuf));
            LOGW("avcodec_receive_frame: %s", errBuf);
        }
        break;
    }

    if (ctx->frameQueue.empty()) {
        return 0; // need more input
    }

    AVFrame* raw = ctx->frameQueue.front();
    ctx->frameQueue.pop_front();

    if (ctx->pendingYuvFrame) {
        av_frame_free(&ctx->pendingYuvFrame);
    }
    ctx->pendingYuvFrame = convertToYuv420p(ctx, raw);
    av_frame_free(&raw);

    if (!ctx->pendingYuvFrame ||
        ctx->pendingYuvFrame->width <= 0 ||
        ctx->pendingYuvFrame->height <= 0) {
        return -1;
    }

    int64_t framePts = ctx->pendingYuvFrame->pts;
    if (framePts == AV_NOPTS_VALUE) {
        framePts = ptsUs;
    }

    jlong info[4];
    info[0] = 1;
    info[1] = framePts;
    info[2] = ctx->pendingYuvFrame->width;
    info[3] = ctx->pendingYuvFrame->height;
    env->SetLongArrayRegion(outFrameInfo, 0, 4, info);
    return 1;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kit_video_ffmpeg_FfmpegVideoDecoder_nativeCopyYuvTo(
        JNIEnv* env,
        jobject /* thiz */,
        jlong handle,
        jobject yBuffer,
        jobject uBuffer,
        jobject vBuffer,
        jint width,
        jint height) {
    DecoderContext* ctx = fromHandle(handle);
    if (!ctx) {
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(ctx->mutex);
    AVFrame* frame = ctx->pendingYuvFrame;
    if (!frame || frame->width <= 0 || frame->height <= 0) {
        return JNI_FALSE;
    }

    auto* yData = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    auto* uData = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    auto* vData = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    if (!yData || !uData || !vData) {
        return JNI_FALSE;
    }

    const int w = frame->width;
    const int h = frame->height;
    if (w != width || h != height) {
        LOGW("Copy size mismatch frame=%dx%d dest=%dx%d", w, h, width, height);
    }

    for (int i = 0; i < h; i++) {
        memcpy(yData + i * w, frame->data[0] + i * frame->linesize[0], w);
    }
    for (int i = 0; i < h / 2; i++) {
        memcpy(uData + i * (w / 2), frame->data[1] + i * frame->linesize[1], w / 2);
        memcpy(vData + i * (w / 2), frame->data[2] + i * frame->linesize[2], w / 2);
    }

    // Frame data now lives in Media3 output buffer; drop native pending frame.
    av_frame_free(&ctx->pendingYuvFrame);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kit_video_ffmpeg_FfmpegVideoDecoder_nativeRenderYuvToSurface(
        JNIEnv* env,
        jobject /* thiz */,
        jlong /* handle */,
        jobject surface,
        jobject yBuffer,
        jobject uBuffer,
        jobject vBuffer,
        jint width,
        jint height,
        jint yStride,
        jint uvStride) {
    if (!surface || width <= 0 || height <= 0) {
        return;
    }

    auto* yData = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    auto* uData = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    auto* vData = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    if (!yData || !uData || !vData) {
        LOGE("Invalid YUV planes for GL render");
        return;
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        LOGE("ANativeWindow_fromSurface failed");
        return;
    }

    // 使用线程局部 GL 渲染器 (ExoPlayer 渲染线程固定)
    static thread_local GlYuvRenderer glRenderer;

    if (!glRenderer.initialized || glRenderer.window != window) {
        // Surface 变化或首次调用，(重新)初始化 EGL + OpenGL
        if (glRenderer.initialized) {
            glRendererRelease(&glRenderer);
        }
        if (!glRendererInit(&glRenderer, window)) {
            LOGE("GL 渲染器初始化失败，回退到 sws_scale");
            ANativeWindow_release(window);
            // 回退: sws_scale CPU 渲染 (保底)
            ANativeWindow_setBuffersGeometry(window, width, height, WINDOW_FORMAT_RGBX_8888);
            return;
        }
    }

    // GPU 渲染: 上传 YUV 纹理 → Fragment Shader 转 RGB → eglSwapBuffers
    glRendererDrawYuv(&glRenderer, yData, yStride, uData, uvStride, vData, width, height);

    ANativeWindow_release(window);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kit_video_ffmpeg_FfmpegVideoDecoder_nativeFlush(
        JNIEnv* env,
        jobject /* thiz */,
        jlong handle) {
    DecoderContext* ctx = fromHandle(handle);
    if (!ctx) {
        return;
    }
    std::lock_guard<std::mutex> lock(ctx->mutex);
    if (ctx->codecContext) {
        avcodec_flush_buffers(ctx->codecContext);
    }
    clearFrameQueue(ctx);
    LOGD("Decoder flushed");
}

extern "C" JNIEXPORT void JNICALL
Java_com_kit_video_ffmpeg_FfmpegVideoDecoder_nativeRelease(
        JNIEnv* /* env */,
        jobject /* thiz */,
        jlong handle) {
    DecoderContext* ctx = fromHandle(handle);
    if (!ctx) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(ctx->mutex);
        releaseContextUnlocked(ctx);
    }
    delete ctx;
    LOGD("Decoder released");
}
