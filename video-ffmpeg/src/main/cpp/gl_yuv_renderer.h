#ifndef GL_YUV_RENDERER_H
#define GL_YUV_RENDERER_H

#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <android/native_window.h>

/**
 * OpenGL ES 2.0 YUV420P → RGB GPU 着色器渲染器
 *
 * 零 CPU 色彩转码开销：
 *   Y / U / V 三个平面分别上传为 GL_LUMINANCE 纹理，
 *   Fragment Shader 在 GPU 上并行完成 BT.601 / BT.709 矩阵变换，
 *   直接输出到 ANativeWindow Surface。
 */
struct GlYuvRenderer {
    // EGL 上下文
    EGLDisplay display   = EGL_NO_DISPLAY;
    EGLContext context    = EGL_NO_CONTEXT;
    EGLSurface surface    = EGL_NO_SURFACE;
    EGLConfig  config     = nullptr;
    ANativeWindow* window = nullptr;

    // OpenGL 资源
    GLuint program        = 0;
    GLuint textures[3]    = {0, 0, 0}; // Y, U, V
    GLuint vbo            = 0;

    // Shader uniform locations
    GLint locYTexture     = -1;
    GLint locUTexture     = -1;
    GLint locVTexture     = -1;

    // 上一次上传的纹理尺寸 (用于检测分辨率变化)
    int lastWidth         = 0;
    int lastHeight        = 0;
    bool initialized      = false;
};

/**
 * 初始化 EGL + OpenGL ES 2.0 环境并编译 YUV Shader
 * @return true 成功
 */
bool glRendererInit(GlYuvRenderer* r, ANativeWindow* window);

/**
 * 将 YUV420P 三平面上传为 GPU 纹理并渲染到 Surface
 */
void glRendererDrawYuv(GlYuvRenderer* r,
                       const uint8_t* yData, int yStride,
                       const uint8_t* uData, int uvStride,
                       const uint8_t* vData,
                       int width, int height);

/**
 * 销毁 EGL 上下文和 OpenGL 资源
 */
void glRendererRelease(GlYuvRenderer* r);

#endif // GL_YUV_RENDERER_H
