#ifndef GL_YUV_RENDERER_H
#define GL_YUV_RENDERER_H

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES3/gl3ext.h>
#include <android/native_window.h>

/**
 * 工业级 OpenGL ES 3.0 YUV420P → RGB GPU 硬件着色器渲染器
 *
 * 核心升级与性能优势：
 * 1. 采用 OpenGL ES 3.0 / GLSL 3.00 ES 语法与单通道 GL_R8 / GL_RED 纹理标准；
 * 2. 引入硬件顶点数组对象 (VAO, Vertex Array Object)，实现 0 每帧绑定开销的极速 Draw Call；
 * 3. 零 CPU 色彩空间转码：Y / U / V 三个平面在 GPU 片段着色器中并行执行 BT.601 / BT.709 色彩空间变换；
 * 4. 直接输出到 ANativeWindow Surface，配合 EGL SwapBuffers 实现 60fps 丝滑渲染。
 */
struct GlYuvRenderer {
    // EGL 上下文
    EGLDisplay display   = EGL_NO_DISPLAY;
    EGLContext context   = EGL_NO_CONTEXT;
    EGLSurface surface   = EGL_NO_SURFACE;
    EGLConfig  config    = nullptr;
    ANativeWindow* window = nullptr;

    // OpenGL ES 3.0 资源
    GLuint program       = 0;
    GLuint textures[3]   = {0, 0, 0}; // Y, U, V
    GLuint vbo           = 0;
    GLuint vao           = 0;         // ES 3.0 顶点数组对象

    // Shader uniform locations
    GLint locYTexture    = -1;
    GLint locUTexture    = -1;
    GLint locVTexture    = -1;

    // 上一次上传的纹理尺寸 (用于检测分辨率变化)
    int lastWidth        = 0;
    int lastHeight       = 0;
    bool initialized     = false;
};

/**
 * 初始化 EGL + OpenGL ES 3.0 环境并编译 YUV300 ES 着色器
 * @return true 成功
 */
bool glRendererInit(GlYuvRenderer* r, ANativeWindow* window);

/**
 * 将 YUV420P 三平面上传为 GL_R8 GPU 纹理并渲染到 Surface
 */
void glRendererDrawYuv(GlYuvRenderer* r,
                       const uint8_t* yData, int yStride,
                       const uint8_t* uData, int uvStride,
                       const uint8_t* vData,
                       int width, int height);

/**
 * 销毁 EGL 上下文和 OpenGL ES 3.0 资源
 */
void glRendererRelease(GlYuvRenderer* r);

#endif // GL_YUV_RENDERER_H
