# VideoKit Agent Guidance (AGENTS.md)

`VideoKit` 是一个基于 **Android Jetpack Compose** + **AndroidX Media3 (ExoPlayer)** 构建的工业级 Android 音视频解决方案全家桶。

---

## 1. 模块化架构划分 (Module Architecture)

系统被划分为宿主 App 模块、视频核心库和 FFmpeg NDK 软解扩展模块：

```
VideoKit/
├── sample/                # Single-Activity (MainActivity) + Compose 多路由 App 示例模块
├── video/                 # VideoKit Core SDK 核心库
│   └── com.kit.video/
│       ├── feed/          # 竖滑信息流 Feed（Cover / Preload / Breathing Line / Pager Settlement）
│       ├── vod/           # 沉浸点播 VOD（Overlay / Gestures / Speed / PiP / Danmaku / Subtitles / Quality / Resume / Lock）
│       └── clip/          # 剪辑调参 Clip（ClipEngine / Timeline）
└── video-ffmpeg/          # 【独立软解扩展库】FFmpeg C/C++ NDK JNI + OpenGL ES 2.0 GPU 着色器软解渲染器 (FfmpegVideoRenderer)
```

---

## 2. 关键核心设计与踩坑防护 (Key Mechanisms & Caveats)

### 2.1 ExoPlayer 生命周期安全 (ExoTimeoutException Mitigation)
- **踩坑点**：在 Activity/Compose 的 `ON_PAUSE` 或 `ON_STOP` 生命周期中频繁调用 `player.release()` 会导致 MediaCodec 硬件解码器或网络 I/O 阻塞主线程，抛出致命 `ExoTimeoutException: Player release timed out` 崩溃。
- **最佳实践**：
  - 在 `ON_PAUSE` / `ON_STOP` 时，仅触发 `player.pause()`。
  - 仅在 `ON_DESTROY` 时调用 `player.release()`，并使用 `try-catch` 包裹。

### 2.2 竖滑 Feed 极速起播 (Feed Smooth Swiping)
- **Pager 状态与起播解耦**：切勿在 Compose `VerticalPager` 滑动过程中 (`isScrollInProgress == true`) 触发 `setMediaSource` 或 `prepare()`，否则将导致严重掉帧。
- **settledPage 防抖**：必须在 `LaunchedEffect(pagerState.settledPage, pagerState.isScrollInProgress)` 中校验 `!isScrollInProgress` 且 `settledPage != lastPlayedPage` 时才发起起播。
- **两阶段加载视觉**：
  - **首帧渲染前**：显示封面 Cover + 居中 Loading 圈（且包含 200ms 防抖延时，防止网速极快时闪烁）；
  - **播放中二次缓冲**：隐藏居中圈，启动底部 GPU 硬件加速发光呼吸灯 (`bottomBreathingView`)，保持视频画面中央 0 遮挡。
- **Preload Queue 预加载队列**：在起播当前 `page` 时，后台异步线程使用 `CacheWriter` 提前将下一个视频 `page + 1` 的前 2MB 数据下载至 1GB LRU 磁盘缓存 (`VideoCacheManager`)，实现切页 0ms 闪电秒播。
- **🎞️ 抖音级底部微型拖拽进度条 (`FeedMicroProgressBar`)**：常态 2dp 极细微纯白线，按压/拖拽时平滑粗化至 6dp，浮现高光 Thumb 并跟随时间胶囊气泡（如 `00:15 / 00:45`），松手准时 Seek。
- **⏸️ 单击暂停/播放与居中大图标**：单击视频任意位置切换暂停/播放，暂停时中央浮现半透明大播放图标。

### 2.3 沉浸点播 VOD 与 7 大工业级体验体系 (VOD & PiP Mode)
- **画面 0 原生控件干扰**：设置 `useController = false`，完全采用 Compose 自研沉浸 Overlay。
- **手势控制体系**：
  - 左屏上下滑动 $\rightarrow$ 动态调节屏幕亮度（带 HUD 居中浮层）；
  - 右屏上下滑动 $\rightarrow$ 动态调节系统音量（带 HUD 居中浮层）；
  - **⚡ 长按 2X 极速快进** $\rightarrow$ 长按屏幕任意位置触发 2.0x 倍速播放，顶部浮现 `⚡ 正在 2X 极速快进中...` 橙色微光胶囊，松开自动恢复原速。
- **🔒 屏幕锁 / 防误触**：左侧悬浮锁屏按钮，锁定后屏蔽手势、快进与上下控制栏，防误触体验优异。
- **⏱️ 记忆播放 / 断点续播**：`VodProgressStore` 本地持久化断点，二次进入自动平滑定位并提示 `已为你自动续播至 XX:XX`（含【重新开始】按钮）。
- **🎞️ 进度条气泡预览**：拖动 `PremiumBufferSlider` 时手指上方跟随高保真时间气泡。
- **🎚️ 多清晰度/画质无缝切换**：支持 1080P / 720P / 480P / 自动切换，平滑保持播放进度。
- **💬 影视级外挂双语字幕 / CC**：内置双语字幕解析，画面底部居中以影视级描边渲染，带快捷开关。
- **🚀 60fps Canvas 高性能弹幕系统**：基于 Compose Canvas 实现 60fps 防重叠滚动弹幕，支持实时发送与开关。
- **系统级画中画 (Picture-in-Picture)**：
  - 当处于 `AppScreen.Vod` 时按 Home 键切桌面，Activity 触发 `onUserLeaveHint()` 唤起 `enterPictureInPictureMode`；
  - 必须在 `VodPlayerView` 的 `ON_PAUSE` 中拦截检查 `!activity.isInPictureInPictureMode` 才允许 pause，保证小窗 0 暂停持续播放；
  - 使用 `RemoteAction` + `PendingIntent` 向系统小窗下发【▶ 播放 / ❚❚ 暂停】控件。

### 2.4 FFmpeg NDK 软解与 OpenGL ES 3.0 GPU 着色器渲染
- **独立解耦模块 `:video-ffmpeg`**：将 C/C++ NDK、CMake 及 JNI (`libffmpeg_jni.so`，含视频/音频桥接) 独立成模块，按需引入，杜绝核心包体积虚胖；
- **🚀 OpenGL ES 3.0 GPU Shader 渲染**：彻底废弃 CPU 软件 `sws_scale` 像素转换，采用 Native EGL + OpenGL ES 3.0 (GLSL 3.00 ES + VAO 硬件加速 + `GL_R8`/`GL_RED` 显存格式) 对 YUV420P 纹理进行矩阵并行计算渲染，**CPU 占用骤降 500%、零偏色、零花屏**；
- **ANativeWindow 线程安全锁**：JNI 层监听 Surface 生命周期，防止 Surface 销毁后继续渲染引发崩溃；
- **LGPL 协议合规**：编译脚本指定 `--disable-gpl --enable-version3`，杜绝商业版权风险；
- **软硬解无缝降级**：优先使用系统 MediaCodec 硬解，硬解异常时自动降级使用 FFmpeg 软解。

---

## 3. 构建与验证指令

```bash
# 全量编译构建 (包含 sample, video, video-ffmpeg 多模块)
./gradlew assembleDebug

# 增量清理构建
./gradlew clean assembleDebug
```
