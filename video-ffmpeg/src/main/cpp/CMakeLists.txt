cmake_minimum_required(VERSION 3.22.1)
project("video-ffmpeg")

# ==============================================================================
# 单套 FFmpeg .so + 单一 JNI 桥接库 libffmpeg_jni.so
#   - ffmpeg_video_jni.cpp  : 视频软解
#   - ffmpeg_audio_jni.cpp  : 音频软解
#   - prebuilt/${ABI}/libav*.so : 唯一 FFmpeg 运行时
# ==============================================================================

set(FFMPEG_LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/prebuilt/${ANDROID_ABI})
set(FFMPEG_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/include)

find_library(log-lib log)
find_library(android-lib android)

set(FFMPEG_SHARED_LIBS avutil swresample swscale avcodec avformat)
foreach(ffmpeg_lib ${FFMPEG_SHARED_LIBS})
    set(_path ${FFMPEG_LIB_DIR}/lib${ffmpeg_lib}.so)
    if(EXISTS ${_path})
        add_library(${ffmpeg_lib} SHARED IMPORTED)
        set_target_properties(${ffmpeg_lib} PROPERTIES IMPORTED_LOCATION ${_path})
        list(APPEND FFMPEG_LINK_LIBS ${ffmpeg_lib})
    else()
        message(WARNING "Missing FFmpeg shared lib: ${_path}")
    endif()
endforeach()

add_library(
    ffmpeg_jni
    SHARED
    ffmpeg_video_jni.cpp
    ffmpeg_audio_jni.cpp
)

target_include_directories(ffmpeg_jni PRIVATE ${FFMPEG_INCLUDE_DIR})

if(FFMPEG_LINK_LIBS)
    target_link_libraries(ffmpeg_jni ${FFMPEG_LINK_LIBS} ${log-lib} ${android-lib})
else()
    target_link_libraries(ffmpeg_jni ${log-lib} ${android-lib})
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_link_options(ffmpeg_jni PRIVATE "-Wl,-Bsymbolic")
endif()
target_link_options(ffmpeg_jni PRIVATE "-Wl,-z,max-page-size=16384")
