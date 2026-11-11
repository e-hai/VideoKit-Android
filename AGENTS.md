# VideoKit-Android Agent Guidance (AGENTS.md)

`VideoKit-Android` 是一个基于 **Android Jetpack Compose** + **AndroidX Media3 (ExoPlayer)** 构建的工业级 Android 音视频解决方案全家桶。

---

## 1. 模块化架构划分 (Module Architecture)

系统被划分为宿主 App 示例模块、视频核心 SDK 库和 FFmpeg NDK 独立软解扩展模块：

```
VideoKit-Android/
├── .github/workflows/     # GitHub Actions (ci.yml 构建校验 + publish-packages.yml 自动发包)
├── gradle/                # github-packages-publish.gradle 通用 GitHub Packages / MavenLocal 发布脚本
├── sample/                # Single-Activity (MainActivity) + Compose 多路由 App 示例模块
├── video/                 # VideoKit Core SDK 核心库 (GitHub Packages: com.github.e-hai:video)
│   └── com.kit.video/
│       ├── feed/          # 竖滑信息流 Feed（Cover / Preload / Breathing Line / Pager Settlement / MicroProgressBar）
│       ├── vod/           # 沉浸点播 VOD（Overlay / Gestures / Speed / PiP / Danmaku / Subtitles / Quality / Resume / Lock）
│       ├── clip/          # 剪辑调参 Clip（ClipEngine / ClipParams / Timeline）
│       ├── cache/         # 1GB LRU 磁盘缓存池与双向预拉取 (VideoCacheManager)
│       └── ui/            # 高阶播放控件 (PremiumBufferSlider 等)
└── video-ffmpeg/          # 【独立软解扩展库】(GitHub Packages: com.github.e-hai:video-ffmpeg)
    ├── src/main/cpp/      # FFmpeg C/C++ NDK JNI + OpenGL ES 3.0 GPU 着色器软解渲染器 (libffmpeg_jni.so)
    └── src/main/java/     # FfmpegRenderersFactory / FfmpegVideoRenderer / FfmpegAudioRenderer
```

---

## 2. 关键核心设计与踩坑防护 (Key Mechanisms & Caveats)

### 2.1 ExoPlayer 生命周期安全 (ExoTimeoutException Mitigation)
- **踩坑点**：在 Activity/Compose 的 `ON_PAUSE` 或 `ON_STOP` 生命周期中频繁调用 `player.release()` 会导致 MediaCodec 硬件解码器或网络 I/O 阻塞主线程超过数千毫秒，抛出致命崩溃 `ExoTimeoutException: Player release timed out`。
- **防护规范**：
  - 在 `ON_PAUSE` / `ON_STOP` 时，**仅触发 `player.pause()`**；
  - 仅在 `ON_DESTROY` 时调用 `player.release()`，并使用 `try-catch` 包裹保障容灾；
  - 画中画 (PiP) 模式下，切桌面必须在 `ON_PAUSE` 拦截 `!activity.isInPictureInPictureMode` 避免中断小窗播放。

### 2.2 竖滑 Feed 极速起播 (Feed Smooth Swiping)
- **Pager 状态与起播解耦**：切勿在 Compose `VerticalPager` 滑动过程中 (`isScrollInProgress == true`) 触发 `setMediaSource` 或 `prepare()`，否则将引发严重丢帧卡顿。
- **settledPage 防抖**：必须在 `LaunchedEffect(pagerState.settledPage, pagerState.isScrollInProgress)` 中校验 `!isScrollInProgress` 且 `settledPage != lastPlayedPage` 时才发起起播。
- **两阶段加载视觉**：
  - **首帧渲染前**：显示封面 Cover + 居中 Loading 圈（且包含 200ms 防抖延时，防止高速网络下闪烁）；
  - **播放中二次缓冲**：隐藏居中圈，启动底部 GPU 硬件加速发光呼吸灯 (`bottomBreathingView`)，保持视频画面中央 0 遮挡。
- **🚀 工业级双向预加载队列 (Bidirectional Preload Queue)**：在起播当前 `page` 时，后台异步线程使用 `CacheWriter` 提前将下一个视频 `page + 1` 与上一个视频 `page - 1` 的前 2MB 数据静默拉取至 1GB LRU 磁盘缓存 (`VideoCacheManager`)，实现上下双向切页 0ms 闪电秒播。
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
  - 必须在 `VodPlayerView` 的 `ON_PAUSE` 中拦截检查 `!activity.isInPictureInPictureMode` 才允许 pause，保证小窗持续播放；
  - 使用 `RemoteAction` + `PendingIntent` 向系统小窗下发【▶ 播放 / ❚❚ 暂停】系统控件。

### 2.4 FFmpeg NDK 软解与 OpenGL ES 3.0 GPU 着色器渲染
- **独立解耦模块 `:video-ffmpeg`**：将 C/C++ NDK、CMake 及 JNI (`libffmpeg_jni.so`，含视频/音频桥接) 独立成模块，按需引入，杜绝核心包体积虚胖；
- **🚀 OpenGL ES 3.0 GPU Shader 渲染**：彻底废弃 CPU 软件 `sws_scale` 像素转换，采用 Native EGL + OpenGL ES 3.0 (GLSL 3.00 ES + VAO 硬件加速 + `GL_R8`/`GL_RED` 显存格式) 对 YUV420P 纹理进行矩阵并行计算渲染，**CPU 占用骤降 500%、零偏色、零花屏**；
- **ANativeWindow 线程安全锁**：JNI 层监听 Surface 生命周期，防止 Surface 销毁后继续渲染引发崩溃；
- **LGPL 协议合规**：编译脚本指定 `--disable-gpl --enable-version3`，杜绝商业版权风险；
- **软硬解无缝降级**：优先使用系统 MediaCodec 硬解，硬解异常时自动降级使用 FFmpeg 软解。

---

## 3. GitHub Packages 分发规范与构建指令 (Build & Release)

### 3.1 制品坐标与鉴权约束
- **制品仓库**：`https://maven.pkg.github.com/e-hai/VideoKit-Android`（仓库 Owner 必须保持全小写 `e-hai`）
- **核心坐标**：`com.github.e-hai:video:<version>` 与 `com.github.e-hai:video-ffmpeg:<version>`（当前默认版本见 `gradle.properties` 中 `VERSION_NAME`）
- **宿主鉴权规范**：辅助第三方宿主接入时，务必提醒在 `settings.gradle(.kts)` 中配置 `credentials`（优先读取 `gpr.user` / `gpr.key` Gradle 属性或 `GITHUB_ACTOR` / `GITHUB_TOKEN` 环境变量，严禁将 PAT 硬编码进代码仓库；Token 仅需 `read:packages` 权限）。
- **不可覆盖约束**：GitHub Packages 禁止同版本号重复覆盖上传（再次推送相同版本号会返回 `422 Conflict`），每次发版必须先递增 `gradle.properties` 中的 `VERSION_NAME` 再推送对应 Git Tag。

### 3.2 构建与发布命令

```bash
# 全量编译构建 (包含 sample, video, video-ffmpeg 全部模块)
./gradlew assembleDebug

# 本地 Maven 发布验证 (~/.m2/repository/com/github/e-hai/)
./gradlew :video:publishReleasePublicationToMavenLocal :video-ffmpeg:publishReleasePublicationToMavenLocal

# 手动发布到 GitHub Packages (需配置 gpr.user/gpr.key 或 GITHUB_ACTOR/GITHUB_TOKEN)
./gradlew :video:publishReleasePublicationToGitHubPackagesRepository :video-ffmpeg:publishReleasePublicationToGitHubPackagesRepository

# 增量清理构建
./gradlew clean assembleDebug
```

### 3.3 GitHub Actions 自动发包
- 推送语义化版本 Tag（如 `1.0.1` 或 `v1.0.1`）或通过 GitHub Actions `Publish to GitHub Packages` 手动触发 `workflow_dispatch`，自动使用内置 `GITHUB_TOKEN` (`packages: write`) 发布两个 AAR 模块及 `sources.jar` / `pom` 元数据。
