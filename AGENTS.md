# VideoKit Agent Guidance (AGENTS.md)

`VideoKit` 是一个基于 **Android Jetpack Compose** + **AndroidX Media3 (ExoPlayer)** 构建的工业级 Android 音视频解决方案全家桶。

---

## 1. 模块化架构划分 (Module Architecture)

系统被划分为一个宿主 App 模块和三大独立音视频功能业务库：

```
VideoKit/
├── sample/                # Single-Activity (MainActivity) + Compose 多路由 App 示例模块
└── video/                 # VideoKit Core SDK 核心库
    └── com.kit.video/
        ├── shortvideo/    # 抖音级短视频滑动播放器模块 (Cover/Preload/Breathing Line/Pager Settlement)
        ├── longvideo/     # YouTube 级长视频播放器模块 (Overlay/Gestures/Speed/PiP/Premium Slider)
        └── editor/        # 视频编辑与裁剪/滤镜/调参引擎 (VideoEditorEngine/Timeline Track)
```

---

## 2. 关键核心设计与踩坑防护 (Key Mechanisms & Caveats)

### 2.1 ExoPlayer 生命周期安全 (ExoTimeoutException Mitigation)
- **踩坑点**：在 Activity/Compose 的 `ON_PAUSE` 或 `ON_STOP` 生命周期中频繁调用 `player.release()` 会导致 MediaCodec 硬件解码器或网络 I/O 阻塞主线程，抛出致命 `ExoTimeoutException: Player release timed out` 崩溃。
- **最佳实践**：
  - 在 `ON_PAUSE` / `ON_STOP` 时，仅触发 `player.pause()`。
  - 仅在 `ON_DESTROY` 时调用 `player.release()`，并使用 `try-catch` 包裹。

### 2.2 抖音级短视频滑动极速起播 (Short Video Smooth Swiping)
- **Pager 状态与起播解耦**：切勿在 Compose `VerticalPager` 滑动过程中 (`isScrollInProgress == true`) 触发 `setMediaSource` 或 `prepare()`，否则将导致严重掉帧。
- **settledPage 防抖**：必须在 `LaunchedEffect(pagerState.settledPage, pagerState.isScrollInProgress)` 中校验 `!isScrollInProgress` 且 `settledPage != lastPlayedPage` 时才发起起播。
- **两阶段加载视觉**：
  - **首帧渲染前**：显示封面 Cover + 居中 Loading 圈（且包含 200ms 防抖延时，防止网速极快时闪烁）；
  - **播放中二次缓冲**：隐藏居中圈，启动底部 GPU 硬件加速发光呼吸灯 (`bottomBreathingView`)，保持视频画面中央 0 遮挡。
- **Preload Queue 预加载队列**：在起播当前 `page` 时，后台异步线程使用 `CacheWriter` 提前将下一个视频 `page + 1` 的前 2MB 数据下载至 512MB LRU 磁盘缓存 (`SimpleCache`)，实现切页 0ms 闪电秒播。

### 2.3 YouTube 级长视频与系统级画中画 (Long Video & PiP Mode)
- **画面 0 原生控件干扰**：设置 `useController = false`，完全采用 Compose 自研沉浸 Overlay。
- **手势控制**：
  - 左屏上下滑动 $\rightarrow$ 动态调节屏幕亮度；
  - 右屏上下滑动 $\rightarrow$ 动态调节系统音量；
  - 双击左右两侧 $\rightarrow$ 快进/快退 10 秒。
- **系统级画中画 (Picture-in-Picture)**：
  - 当处于 `AppScreen.SinglePlay` 时按 Home 键切桌面，Activity 触发 `onUserLeaveHint()` 唤起 `enterPictureInPictureMode`；
  - 必须在 `LongVideoPlayerView` 的 `ON_PAUSE` 中拦截检查 `!activity.isInPictureInPictureMode` 才允许 pause，保证小窗 0 暂停持续播放；
  - 使用 `RemoteAction` + `PendingIntent` 向系统小窗下发【▶ 播放 / ❚❚ 暂停】控件。

---

## 3. 构建与验证指令

```bash
# 全量编译构建
./gradlew assembleDebug

# 增量清理构建
./gradlew clean assembleDebug
```
