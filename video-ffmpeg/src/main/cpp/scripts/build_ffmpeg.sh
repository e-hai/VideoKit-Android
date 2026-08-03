#!/bin/bash
# ==============================================================================
# VideoKit FFmpeg NDK 交叉编译脚本（FFmpeg 7.x / 8.x）
# ------------------------------------------------------------------------------
# 产物：动态库 libavcodec / libavutil / libswscale / libswresample / libavformat
#       → 自动同步到 ../../prebuilt/<ABI>/ 与 ../../include/
#
# 【如何开关功能】
#   1. 本脚本使用 bash 数组 FFMPEG_CONFIG_FLAGS 收集配置（避免 configure \ 续行被 # 打断）
#   2. 需要某功能：去掉该行行首的「# 」（保留行内右侧中文说明）
#   3. 关闭某功能：在该行最前面加「# 」
#   4. 修改后重新执行本脚本，再重新编译 :video-ffmpeg
#
# 【与 Media3 / 本模块的关系】
#   - Media3 负责解复用、同步；本 so 主要被 JNI 用来「按轨软解」
#   - Kotlin FfmpegLibrary.codecNameOf() 里映射到的 decoder 名，应在下方有对应开关
#   - 默认走 LGPL（--disable-gpl）：不要打开需要 GPL 的组件（如 libx264）
# ==============================================================================

set -euo pipefail

# 可在此处修改为您需要的 FFmpeg 官方最新 Release 版本 (例如: 7.1 / 8.1.2)
FFMPEG_VERSION="7.1"
NDK_PATH="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/25.2.9519653}"
HOST_TAG="darwin-x86_64" # macOS: darwin-x86_64, Linux: linux-x86_64
TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/$HOST_TAG"
API=24

echo "=================================================================="
echo "开始检查 NDK 工具链与 FFmpeg $FFMPEG_VERSION 源码环境..."
echo "NDK 路径: $NDK_PATH"
echo "=================================================================="

if [ ! -d "$TOOLCHAIN" ]; then
    echo "错误: 未在 $TOOLCHAIN 找到 Android NDK LLVM 工具链！"
    echo "请配置 ANDROID_NDK_HOME 环境变量或检查 NDK 路径。"
    exit 1
fi

# 1. 自动下载 FFmpeg 源码 (若本地不存在)
SRC_DIR="ffmpeg-$FFMPEG_VERSION"
if [ ! -d "$SRC_DIR" ]; then
    echo "正在下载 FFmpeg $FFMPEG_VERSION 官方源码包..."
    curl -O "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.bz2" || \
        curl -O "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VERSION.tar.xz"
    echo "正在解压 FFmpeg 源码..."
    tar -xjf "ffmpeg-$FFMPEG_VERSION.tar.bz2" 2>/dev/null || \
        tar -xf "ffmpeg-$FFMPEG_VERSION.tar.xz" 2>/dev/null || \
        tar -xzf "ffmpeg-$FFMPEG_VERSION.tar.gz"
fi

cd "$SRC_DIR"

# ------------------------------------------------------------------------------
# 可开关配置表（取消行首 # 即启用；右侧注释说明对应业务能力）
# 使用 --disable-everything 后「只有列出的 enable 才会编进 so」，注释才真正等于关闭。
# ------------------------------------------------------------------------------
build_ffmpeg_config_flags() {
    FFMPEG_CONFIG_FLAGS=(
        # ======================== 基础构建形态 ========================
        --enable-shared                       # 产出 .so 动态库（与 CMake IMPORTED SHARED 一致）
        --disable-static                      # 不产出 .a，减小中间产物、避免误链静态 FFmpeg
        --enable-pic                          # Position Independent Code，Android 动态库必需
        --disable-doc                         # 不生成文档，加快编译
        --disable-programs                    # 不编 ffmpeg/ffprobe 可执行文件，只要库
        --disable-debug                       # Release 体积；排 native 问题时可改开 --enable-debug
        --disable-symver                      # 部分 NDK/链接器对符号版本不友好，建议关

        # ======================== 裁剪：默认全关再点名开启 ========================
        --disable-everything                  # 关键：未在下方 enable 的编解码/解复用一律不编入

        # ======================== 核心库（JNI 软解依赖） ========================
        --enable-avutil                       # 基础工具（内存、日志、像素/采样格式）
        --enable-avcodec                      # 编解码核心（Decoder / Encoder）
        --enable-avformat                     # 容器读写（Demuxer / Muxer）；即便 Media3 解复用也可保留
        --enable-swscale                      # 视频像素格式转换（YUV 相关，视频 JNI 使用）
        --enable-swresample                   # 音频重采样 / 声道路由（PCM 输出，音频 JNI 使用）
        # --enable-avfilter                   # libavfilter 滤镜图（本模块软解未用；导出特效时可开）
        # --enable-avdevice                   # 采集设备（摄像头/麦克风），Android 播放场景不需要
        # --enable-postproc                   # 后处理（GPL 色彩等），LGPL 构建保持关闭

        # ======================== 协议合规（务必保留） ========================
        --disable-gpl                         # 禁用 GPL 组件，降低商业分发侵权风险
        --enable-version3                     # 允许 LGPL v3 组件（与 --disable-gpl 搭配）
        # --enable-nonfree                    # 非免费组件（如部分 AAC 编码器），商业慎用，默认关

        # ======================== 视频解码器（软解画面） ========================
        # —— 默认开启：与 FfmpegVideoRenderer / 主流点播一致 ——
        --enable-decoder=h264                 # H.264/AVC：MP4、HLS、绝大多数短视频
        --enable-decoder=hevc                 # H.265/HEVC：4K / 高压缩点播
        --enable-decoder=vp9                  # VP9：YouTube / WebM 常见
        --enable-decoder=av1                  # AV1：新一代开源视频（软解 CPU 开销大）
        # —— 可选：取消行首 # 解锁 ——
        # --enable-decoder=vp8                # VP8：旧版 WebM / 部分直播
        # --enable-decoder=mpeg4              # MPEG-4 Part 2：DivX / Xvid / 老 MP4
        # --enable-decoder=mpeg2video         # MPEG-2：DVD / 部分广播 TS
        # --enable-decoder=mpeg1video         # MPEG-1：VCD / 极老素材
        # --enable-decoder=mjpeg              # Motion JPEG：监控 / 连拍视频
        # --enable-decoder=theora             # Theora：Ogg 开源视频
        # --enable-decoder=vc1                # VC-1：部分 WMV / 蓝光衍生
        # --enable-decoder=wmv3               # WMV9：Windows Media Video 9
        # --enable-decoder=msmpeg4v3          # 早期 AVI / DivX 变种
        # --enable-decoder=rv40               # RealVideo 4：RMVB
        # --enable-decoder=rv30               # RealVideo 3：更老的 RM
        # --enable-decoder=h263               # H.263：早期 3GP / 视频通话素材
        # --enable-decoder=vp6f               # VP6：旧 FLV Flash 视频

        # ======================== 音频解码器（软解音轨 → PCM） ========================
        # —— 默认开启：与常见 MP4/网络音视频一致 ——
        --enable-decoder=aac                  # AAC：MP4/M4A/HLS 默认音轨（FfmpegLibrary: aac）
        --enable-decoder=mp3                  # MP3：兼容素材 / 部分容器（FfmpegLibrary: mp3）
        # —— 可选：与 FfmpegLibrary.codecNameOf 对齐，按需打开 ——
        # --enable-decoder=ac3                # Dolby Digital AC-3：影碟 / 部分直播（mime: ac3）
        # --enable-decoder=eac3               # Dolby Digital Plus：流媒体多声道（mime: eac3）
        # --enable-decoder=dca                # DTS：影院音轨（FFmpeg 名是 dca，不是 dts）
        # --enable-decoder=truehd             # Dolby TrueHD：无损影院音轨
        # --enable-decoder=opus               # Opus：WebM / WebRTC / 高效语音
        # --enable-decoder=vorbis             # Vorbis：Ogg / 部分 WebM
        # --enable-decoder=flac               # FLAC：无损音频文件
        # --enable-decoder=alac               # Apple Lossless：M4A 苹果无损
        # --enable-decoder=amrnb              # AMR-NB：老手机录音 / 3GP
        # --enable-decoder=amrwb              # AMR-WB：宽频 AMR
        # --enable-decoder=pcm_s16le          # PCM 16-bit LE：WAV 常见
        # --enable-decoder=pcm_s24le          # PCM 24-bit LE：高解析 WAV
        # --enable-decoder=pcm_f32le          # PCM float：部分专业素材
        # --enable-decoder=pcm_mulaw          # μ-law：G.711 / 部分通话录音（mime: mulaw）
        # --enable-decoder=pcm_alaw           # A-law：G.711 另一变体（mime: alaw）
        # --enable-decoder=wmav2              # WMA v2：Windows Media 音频
        # --enable-decoder=cook               # RealAudio COOK：配套 RMVB
        # --enable-decoder=ape                # Monkey's Audio：无损 APE（体积大，按需）

        # ======================== 帧解析器 Parser（切 NALU / 辅助 seek） ========================
        # 建议：开启了某 decoder，就打开对应 parser
        --enable-parser=h264                  # 解析 H.264 NAL，辅助帧边界
        --enable-parser=hevc                  # 解析 HEVC NAL
        --enable-parser=vp9                   # VP9 frame 解析
        --enable-parser=av1                   # AV1 OBU / 帧解析
        --enable-parser=aac                   # AAC ADTS/帧解析
        --enable-parser=mpegaudio             # MP1/MP2/MP3 帧解析
        # --enable-parser=vp8                 # 配合 vp8 decoder
        # --enable-parser=mpeg4video          # 配合 mpeg4
        # --enable-parser=mpegvideo           # 配合 mpeg1/2
        # --enable-parser=opus                # 配合 opus
        # --enable-parser=vorbis              # 配合 vorbis
        # --enable-parser=flac                # 配合 flac
        # --enable-parser=ac3                 # 配合 ac3/eac3
        # --enable-parser=dca                 # 配合 DTS(dca)
        # --enable-parser=cook                # 配合 RealAudio

        # ======================== 码流过滤器 BSF（容器 ↔ 裸流转换） ========================
        # 本模块视频 JNI 已手写 AVCC→Annex-B；若改走 FFmpeg BSF 可打开：
        # --enable-bsf=h264_mp4toannexb       # MP4(AVCC) → Annex-B，供部分裸流解码路径
        # --enable-bsf=hevc_mp4toannexb       # MP4(HVCC) → Annex-B
        # --enable-bsf=aac_adtstoasc          # ADTS → ASC，AAC 入 MP4 封装时常用
        # --enable-bsf=extract_extradata      # 从码流抽 SPS/PPS 等 extradata

        # ======================== 解封装 Demuxer（容器拆轨） ========================
        # 说明：播放主路径由 Media3 Extractor 解复用；此处供 avformat / 工具链 / 未来扩展
        --enable-demuxer=mov                  # MP4 / MOV / M4A / 3GP 家族
        --enable-demuxer=matroska             # MKV / WebM
        --enable-demuxer=flv                  # FLV 短视频 / 旧直播
        # --enable-demuxer=mpegts             # MPEG-TS：HLS 切片 / IPTV / 广播
        # --enable-demuxer=mpegps             # MPEG-PS：VOB / 部分 DVD
        # --enable-demuxer=avi                # AVI 经典容器
        # --enable-demuxer=asf                # ASF/WMV/WMA 容器
        # --enable-demuxer=rm                 # RM / RMVB
        # --enable-demuxer=ogg                # Ogg（Vorbis/Opus/Theora）
        # --enable-demuxer=wav                # WAV
        # --enable-demuxer=aac                # 纯 AAC ADTS 裸文件
        # --enable-demuxer=mp3                # 纯 MP3 文件
        # --enable-demuxer=flac               # 纯 FLAC 文件
        # --enable-demuxer=hls                # HLS 播放列表解复用（需网络协议配合）
        # --enable-demuxer=dash               # DASH MPD（需网络协议配合）
        # --enable-demuxer=rtsp               # RTSP 直播（需网络协议配合）
        # --enable-demuxer=gif                # GIF 动图当视频读
        # --enable-demuxer=image2             # 图片序列（转场/导出偶用）

        # ======================== 封装 Muxer + 软编码（导出 / 转码，默认全关） ========================
        # 仅「手机端 FFmpeg 软编导出」需要；纯播放软解请保持注释以控体积
        # --enable-encoder=aac                # AAC 软编码（导出音轨）
        # --enable-encoder=mjpeg              # MJPEG 软编码（简单画面导出）
        # --enable-encoder=pcm_s16le          # PCM 写入 WAV
        # --enable-encoder=png                # PNG 封面/帧导出（依赖 zlib，一般内置）
        # --enable-muxer=mp4                  # 写出 MP4
        # --enable-muxer=ipod                 # M4A 音频封装
        # --enable-muxer=matroska             # 写出 MKV
        # --enable-muxer=wav                  # 写出 WAV
        # --enable-muxer=adts                 # 写出 AAC ADTS
        # --enable-muxer=null                 # 空封装（压测 / 管道）
        # 注意：libx264 / libx265 / libmp3lame 等外置库编码器需要额外交叉编译第三方库，
        #       且多数涉及 GPL/商业专利，不在本 LGPL 脚本默认范围内。

        # ======================== 网络 / IO 协议 ========================
        # 播放主路径仍走 Media3 DataSource；下列仅在「用 avformat 自己打开 URL」时需要
        --enable-protocol=file                # 本地文件（配合 demuxer 做工具/自测，体积很小）
        # --enable-protocol=pipe              # 管道输入
        # --enable-network                    # 网络总开关（开 http/rtmp 等前必须先开）
        # --enable-protocol=http              # HTTP 点播
        # --enable-protocol=https             # HTTPS（通常还需 --enable-openssl 或 gnutls）
        # --enable-protocol=tls               # TLS 底层
        # --enable-protocol=tcp               # TCP
        # --enable-protocol=udp               # UDP / 部分直播
        # --enable-protocol=rtp               # RTP
        # --enable-protocol=rtmp              # RTMP 直播推拉
        # --enable-protocol=crypto            # 加密协议辅助

        # ======================== 硬件加速（本模块定位为 CPU 软解，默认全关） ========================
        # --enable-mediacodec                 # Android MediaCodec 硬解（与系统硬解重复，一般不必）
        # --enable-jni                        # MediaCodec 路径依赖的 JNI 支持
        # --enable-hwaccel=h264_mediacodec
        # --enable-hwaccel=hevc_mediacodec
    )
}

function build_target() {
    local ARCH=$1
    local CPU=$2
    local TARGET_TRIPLE=$3
    local ABI_NAME=$4
    local CC="$TOOLCHAIN/bin/${TARGET_TRIPLE}${API}-clang"
    local CXX="$TOOLCHAIN/bin/${TARGET_TRIPLE}${API}-clang++"
    local SYSROOT="$TOOLCHAIN/sysroot"
    local PREFIX
    PREFIX="$(pwd)/../android/$ARCH"

    echo "=================================================================="
    echo "正在为 $ARCH ($CPU) 交叉编译 FFmpeg LGPL 动态库..."
    echo "使用 CC 编译器: $CC"
    echo "=================================================================="

    build_ffmpeg_config_flags

    ./configure \
        --prefix="$PREFIX" \
        --target-os=android \
        --arch="$ARCH" \
        --cpu="$CPU" \
        --enable-cross-compile \
        --cc="$CC" \
        --cxx="$CXX" \
        --sysroot="$SYSROOT" \
        --extra-cflags="-O3 -fPIC" \
        --extra-ldflags="-Wl,-z,max-page-size=16384" \
        "${FFMPEG_CONFIG_FLAGS[@]}"

    make clean
    make -j"$(sysctl -n hw.ncpu 2>/dev/null || nproc 2>/dev/null || echo 8)"
    make install

    # 自动同步产物至 CMake prebuilt 预编译目录
    local DEST_LIB_DIR DEST_INC_DIR
    DEST_LIB_DIR="$(pwd)/../../prebuilt/$ABI_NAME"
    DEST_INC_DIR="$(pwd)/../../include"
    mkdir -p "$DEST_LIB_DIR"
    mkdir -p "$DEST_INC_DIR"
    cp -R "$PREFIX/lib/"*.so "$DEST_LIB_DIR/"
    cp -R "$PREFIX/include/"* "$DEST_INC_DIR/"

    echo "OK $ARCH 编译并安装成功"
    echo "产物目录: $DEST_LIB_DIR"
}

# 编译 arm64-v8a（与 video-ffmpeg/build.gradle ndk.abiFilters 一致）
build_target "arm64" "armv8-a" "aarch64-linux-android" "arm64-v8a"
