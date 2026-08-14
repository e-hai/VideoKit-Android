# VideoKit 🎬

[![Android AGP](https://img.shields.io/badge/AGP-9.3.1-brightgreen.svg)](https://developer.android.com/studio/releases/gradle-plugin)
[![Gradle](https://img.shields.io/badge/Gradle-9.5.0-blue.svg)](https://gradle.org)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![Media3](https://img.shields.io/badge/Media3-1.10.1-red.svg)](https://developer.android.com/guide/topics/media/media3)
[![Compose](https://img.shields.io/badge/Compose-Material3-orange.svg)](https://developer.android.com/jetpack/compose)

**VideoKit** 是一个基于 **Android Jetpack Compose** 与 **AndroidX Media3 (ExoPlayer)** 构建的工业级 Android 音视频 SDK 全家桶。涵盖 **竖滑 Feed**、**沉浸点播 VOD（7大工业级特性 + PiP）**、**剪辑调参 Clip**，以及基于 **OpenGL ES 2.0 GPU Shader 渲染** 的 FFmpeg NDK 软解扩展（`:video-ffmpeg`）。

---

## 📐 架构与设计图示 (Architecture Diagrams)

### 1. 独立模块系统架构图 (System Architecture)

```mermaid
graph TD
    App[sample App - Single Activity / Compose Router] --> CoreVideo[video 核心 SDK - Feed / VOD / Clip]
    App -. 按需引入 .-> FFmpegModule[video-ffmpeg 独立软解模块]

    subgraph video 核心能力
        CoreVideo --> Feed[feed · FeedCoverPlayerView + Preload]
        CoreVideo --> Vod[vod · VodPlayer / Overlay / 手势 / 弹幕 / 字幕 / 续播]
        CoreVideo --> Clip[clip · ClipEngine]
    end

    subgraph video-ffmpeg 自研软解
        FFmpegModule --> Factory[FfmpegRenderersFactory]
        Factory --> VRen[FfmpegVideoRenderer]
        Factory --> ARen[FfmpegAudioRenderer]
        VRen --> JNI[libffmpeg_jni.so JNI]
        ARen --> JNI
        JNI --> GL[OpenGL ES 3.0 GPU Shader 渲染引擎 (VAO + GL_R8)]
        JNI --> SO[一套 prebuilt libavcodec / avutil / swresample / swscale]
    end
```

### 2. Media3 / FFmpeg / OpenGL ES GPU 渲染管线

本项目采用 **分轨软解 + GPU 硬件着色器加速渲染**：

```text
URL / 文件
    │
    ▼
┌──────────────────────────────────────────────────────────┐
│ Media3 ExoPlayer（播放引擎）                                │
│  · 解复用（Extractor / MediaSource）                        │
│  · 轨选择、缓冲、seek、倍速、A/V 同步时钟                      │
│  · 生命周期、错误回调                                        │
│  · 默认解码：MediaCodecVideoRenderer / MediaCodecAudioRenderer│
└───────────────┬──────────────────────────┬───────────────┘
                │ 视频轨（可选替换）            │ 音频轨（可选替换）
                ▼                            ▼
┌──────────────────────────┐   ┌──────────────────────────┐
│ FfmpegVideoRenderer      │   │ FfmpegAudioRenderer      │
│ （Kotlin，Media3 扩展点）  │   │ （Kotlin，Media3 扩展点）  │
│ DecoderVideoRenderer 契约 │   │ DecoderAudioRenderer 契约 │
└────────────┬─────────────┘   └────────────┬─────────────┘
             │                              │
             ▼                              ▼
┌──────────────────────────────────────────────────────────┐
│ JNI：libffmpeg_jni.so                                 │
│  · 把 Java/Kotlin ByteBuffer、Surface 转成 C 可操作内存     │
│  · 调用 FFmpeg C API（avcodec_send/receive、swr…）        │
│  · 🚀 视频：YUV 纹理 → OpenGL ES 2.0 Shader GPU 矩阵转换  │
│  · 音频：PCM → SimpleDecoderOutputBuffer → AudioSink        │
└────────────┬─────────────────────────────────────────────┘
             ▼
┌──────────────────────────────────────────────────────────┐
│ FFmpeg 运行时（一套 so，build_ffmpeg.sh 编译）               │
│  libavcodec / libavutil / libswscale / libswresample …     │
│  · 真正的压缩码流高效解码算法                                │
└──────────────────────────────────────────────────────────┘
```

| 层级 | 职责 | 核心优势 |
|------|------|--------|
| **Media3** | 解复用、同步、缓冲、选轨、把 sample 送给 Renderer、Surface 时序 | 工业级播放控制与生命周期管理 |
| **Renderer（Kotlin）** | 实现 Media3 扩展接口；管理 Decoder 生命周期与时钟 | 标准 SimpleDecoder / DecoderVideoRenderer 契约 |
| **JNI + OpenGL ES** | C 语言底层 YUV420P 上传为 GPU 纹理，Fragment Shader 并行转码 | **零 CPU 色彩转换开销、零偏色、零花屏** |
| **FFmpeg（`libav*`）** | H.264/HEVC/VP9/AV1/AAC… 高性能软件解码 | 覆盖老旧机型与非常规格式 |

---

## 🌟 核心功能矩阵 (Feature Matrix)

| 业务板块 | 核心特性 | 技术亮点 |
| :--- | :--- | :--- |
| **竖滑 Feed (`feed`)** | 信息流滑动秒播 | Cover / 200ms 防抖起播、底部呼吸灯、`settledPage` 沉降起播、Preload Queue |
| **沉浸点播 VOD (`vod`)** | **7 大工业级点播体验全家桶** | ⚡ **长按 2X 极速快进**（带发光 HUD）<br>🔒 **屏幕锁 / 防误触**<br>⏱️ **记忆播放 / 断点自动续播**<br>🎞️ **进度条拖动时间气泡预览**<br>🎚️ **多清晰度/画质无缝切换** (1080P/720P/480P)<br>💬 **影视级外挂双语字幕 / CC**<br>🚀 **60fps Canvas 高性能防重叠弹幕**<br>📱 **系统级画中画 (PiP Mode)** |
| **剪辑 Clip (`clip`)** | 裁剪与调参 | `ClipEngine` / `ClipParams` |
| **FFmpeg (`video-ffmpeg`)** | **OpenGL ES GPU 渲染软解** | **同一套 so** + **OpenGL ES 2.0 Shader 硬件加速**，硬解优先，软解兜底 |
| **稳定防护** | 生命周期与网络 | `ExoTimeoutException` 防护、断网自动镜像切换 |

---

## 🚀 快速开始与使用指南 (Usage Guide)

### 1. 接入竖滑 Feed (`FeedCoverPlayerView`)

```kotlin
@Composable
fun FeedPage(videoUrl: String, feedManager: FeedPlayerManager) {
    AndroidView(
        factory = { context ->
            FeedCoverPlayerView(context).also { coverView ->
                feedManager.playVideoFromCoverView(coverView, videoUrl)
                feedManager.preloadNextVideo(nextVideoUrl)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
```

### 2. 接入沉浸点播 VOD（Compose 全功能组件）

```kotlin
VodPlayer(
    url = videoUrl,
    title = "VideoKit 沉浸式点播",
    renderersFactory = FfmpegRenderersFactory(context),
    speed = 1.0f,
    onBackClick = onBack,
    onSpeedClick = { /* 弹出倍速面板 */ },
    onQualityClick = { /* 弹出画质面板 */ }
)
```

> **注**：`VodPlayer` 内置支持 **长按 2x 快进**、**屏幕防误触锁**、**断点续播提示**、**Canvas 弹幕** 与 **双语字幕**，开箱即用。

### 3. 启用自研 FFmpeg 音视频软解（OpenGL ES 2.0 加速）

```kotlin
// 同一 Factory：视频 + 音频均为本模块自研 Renderer，共用 libffmpeg_jni.so
// 策略：MediaCodec 硬解优先，FFmpeg 挂在 Renderer 列表末尾作兜底
val renderersFactory = FfmpegRenderersFactory(context)
val player = ExoPlayer.Builder(context, renderersFactory).build()
```

| 轨道 | Renderer | Native | 渲染后端 |
|------|----------|--------|---------|
| Video | `com.kit.video.ffmpeg.FfmpegVideoRenderer` | `ffmpeg_video_jni.cpp` | **OpenGL ES 2.0 GPU Shader** |
| Audio | `com.kit.video.ffmpeg.FfmpegAudioRenderer` | `ffmpeg_audio_jni.cpp` | AudioTrack (PCM) |
| FFmpeg 本体 | — | `prebuilt/*/libav*.so`（只一套） | LGPL v3.0 合规 |
| 解复用 / 同步 | ExoPlayer | Media3 | Media3 Extractor |

APK native 布局：

```text
lib/arm64-v8a/
  libavcodec.so libavutil.so libswresample.so libswscale.so libavformat.so
  libffmpeg_jni.so          ← 视频 JNI + 音频 JNI + OpenGL ES 渲染引擎 打在同一库
```

---

## 🛠️ FFmpeg NDK 编译脚本配置指南 (build_ffmpeg.sh)

权威开关列表在脚本内数组 [`build_ffmpeg.sh`](video-ffmpeg/src/main/cpp/scripts/build_ffmpeg.sh) → `FFMPEG_CONFIG_FLAGS`。  
使用 **`--disable-everything` + 按行 enable**：行首加/去 `#` 才真正关闭/打开对应功能。

### 1. 运行编译脚本

```bash
cd video-ffmpeg/src/main/cpp/scripts
export ANDROID_NDK_HOME=/Users/your_name/Library/Android/sdk/ndk/25.2.9519653
chmod +x build_ffmpeg.sh
./build_ffmpeg.sh
```

### 2. 配置分类与开关方式

| 分类 | 默认 | 作用 |
|------|------|------|
| 核心库 | avutil/avcodec/avformat/swscale/swresample | JNI 软解与像素/PCM 转换 |
| 视频 decoder | h264/hevc/vp9/av1 | 画面软解；另有 vp8/mpeg4/rv40… 注释可选 |
| 音频 decoder | aac/mp3 | 音轨软解；另有 ac3/eac3/**dca**/opus/flac… 与 `FfmpegLibrary` 对齐 |
| parser | 与默认 decoder 配套 | 帧边界 / seek 辅助 |
| demuxer | mov/matroska/flv | 容器拆轨（播放主路径仍是 Media3 Extractor） |
| bsf / muxer / encoder / network / mediacodec | 默认注释 | 导出、FFmpeg 自拉流、硬解加速等按需开 |
| 合规 | `--disable-gpl --enable-version3` | LGPL 分发；勿开 GPL/`nonfree` 除非清楚后果 |

---

## 🛠️ 项目构建说明 (Build & Verify)

```bash
# 全量 Android Debug 构建 (编译 sample, video, video-ffmpeg 全部多模块)
./gradlew assembleDebug

# 极净清理与全量构建
./gradlew clean assembleDebug
```

---

## 📄 License
VideoKit is licensed under the Apache 2.0 License.
