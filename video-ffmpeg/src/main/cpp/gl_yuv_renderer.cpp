#include "gl_yuv_renderer.h"

#include <android/log.h>
#include <cstring>

#define GL_TAG "GlYuvRenderer"
#define GL_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, GL_TAG, __VA_ARGS__)
#define GL_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, GL_TAG, __VA_ARGS__)

// ============================================================================
// Shader 源码
// ============================================================================

static const char* kVertexShader = R"glsl(
    attribute vec4 aPosition;
    attribute vec2 aTexCoord;
    varying vec2 vTexCoord;
    void main() {
        gl_Position = aPosition;
        vTexCoord = aTexCoord;
    }
)glsl";

// BT.601 YUV → RGB 矩阵变换 (适配 SD 视频)
// BT.709 系数也提供注释，后续可按分辨率动态切换
static const char* kFragmentShader = R"glsl(
    precision mediump float;
    varying vec2 vTexCoord;
    uniform sampler2D yTexture;
    uniform sampler2D uTexture;
    uniform sampler2D vTexture;
    void main() {
        float y = texture2D(yTexture, vTexCoord).r;
        float u = texture2D(uTexture, vTexCoord).r - 0.5;
        float v = texture2D(vTexture, vTexCoord).r - 0.5;
        // BT.601 标准转换矩阵
        float r = y + 1.402 * v;
        float g = y - 0.344136 * u - 0.714136 * v;
        float b = y + 1.772 * u;
        gl_FragColor = vec4(r, g, b, 1.0);
    }
)glsl";

// 全屏四边形顶点 (position xy + texcoord uv)
// OpenGL 纹理坐标 Y 轴翻转：底部 = 0, 顶部 = 1
static const float kQuadVertices[] = {
    // position       texcoord
    -1.0f, -1.0f,    0.0f, 1.0f,  // 左下
     1.0f, -1.0f,    1.0f, 1.0f,  // 右下
    -1.0f,  1.0f,    0.0f, 0.0f,  // 左上
     1.0f,  1.0f,    1.0f, 0.0f,  // 右上
};

// ============================================================================
// 内部工具函数
// ============================================================================

static GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    if (shader == 0) {
        GL_LOGE("glCreateShader 失败");
        return 0;
    }
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 0) {
            char* buf = new char[infoLen];
            glGetShaderInfoLog(shader, infoLen, nullptr, buf);
            GL_LOGE("Shader 编译失败: %s", buf);
            delete[] buf;
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static GLuint createProgram(const char* vertexSource, const char* fragmentSource) {
    GLuint vertexShader = compileShader(GL_VERTEX_SHADER, vertexSource);
    if (vertexShader == 0) return 0;

    GLuint fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentSource);
    if (fragmentShader == 0) {
        glDeleteShader(vertexShader);
        return 0;
    }

    GLuint program = glCreateProgram();
    if (program == 0) {
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        return 0;
    }

    glAttachShader(program, vertexShader);
    glAttachShader(program, fragmentShader);

    // 绑定 attribute 位置 (在 link 之前)
    glBindAttribLocation(program, 0, "aPosition");
    glBindAttribLocation(program, 1, "aTexCoord");

    glLinkProgram(program);

    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint infoLen = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 0) {
            char* buf = new char[infoLen];
            glGetProgramInfoLog(program, infoLen, nullptr, buf);
            GL_LOGE("Program link 失败: %s", buf);
            delete[] buf;
        }
        glDeleteProgram(program);
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);
        return 0;
    }

    // Shader 链接后可以安全删除
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);

    return program;
}

// ============================================================================
// 公开 API
// ============================================================================

bool glRendererInit(GlYuvRenderer* r, ANativeWindow* window) {
    if (!r || !window) return false;

    // 如果已有旧的 EGL surface 绑定不同 window，先清理
    if (r->initialized && r->window != window) {
        glRendererRelease(r);
    }

    if (r->initialized) {
        // 已经初始化且 window 相同，跳过
        return true;
    }

    r->window = window;

    // ---- EGL Display ----
    r->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (r->display == EGL_NO_DISPLAY) {
        GL_LOGE("eglGetDisplay 失败");
        return false;
    }
    if (!eglInitialize(r->display, nullptr, nullptr)) {
        GL_LOGE("eglInitialize 失败");
        return false;
    }

    // ---- EGL Config ----
    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE,    EGL_WINDOW_BIT,
        EGL_RED_SIZE,        8,
        EGL_GREEN_SIZE,      8,
        EGL_BLUE_SIZE,       8,
        EGL_ALPHA_SIZE,      0,  // 不需要 Alpha 通道
        EGL_NONE
    };
    EGLint numConfigs = 0;
    if (!eglChooseConfig(r->display, configAttribs, &r->config, 1, &numConfigs) || numConfigs == 0) {
        GL_LOGE("eglChooseConfig 失败");
        return false;
    }

    // ---- EGL Context (OpenGL ES 2.0) ----
    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL_NONE
    };
    r->context = eglCreateContext(r->display, r->config, EGL_NO_CONTEXT, contextAttribs);
    if (r->context == EGL_NO_CONTEXT) {
        GL_LOGE("eglCreateContext 失败");
        return false;
    }

    // ---- EGL Surface (绑定 ANativeWindow) ----
    r->surface = eglCreateWindowSurface(r->display, r->config, r->window, nullptr);
    if (r->surface == EGL_NO_SURFACE) {
        GL_LOGE("eglCreateWindowSurface 失败");
        return false;
    }

    // ---- 激活上下文 ----
    if (!eglMakeCurrent(r->display, r->surface, r->surface, r->context)) {
        GL_LOGE("eglMakeCurrent 失败");
        return false;
    }

    // ---- 编译 Shader Program ----
    r->program = createProgram(kVertexShader, kFragmentShader);
    if (r->program == 0) {
        GL_LOGE("Shader program 创建失败");
        return false;
    }

    r->locYTexture = glGetUniformLocation(r->program, "yTexture");
    r->locUTexture = glGetUniformLocation(r->program, "uTexture");
    r->locVTexture = glGetUniformLocation(r->program, "vTexture");

    // ---- 创建 3 个纹理 (Y, U, V) ----
    glGenTextures(3, r->textures);
    for (int i = 0; i < 3; i++) {
        glBindTexture(GL_TEXTURE_2D, r->textures[i]);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    // ---- 创建全屏四边形 VBO ----
    glGenBuffers(1, &r->vbo);
    glBindBuffer(GL_ARRAY_BUFFER, r->vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kQuadVertices), kQuadVertices, GL_STATIC_DRAW);

    r->initialized = true;
    GL_LOGD("✅ OpenGL ES 2.0 YUV Shader 渲染器初始化成功!");
    return true;
}

void glRendererDrawYuv(GlYuvRenderer* r,
                       const uint8_t* yData, int yStride,
                       const uint8_t* uData, int uvStride,
                       const uint8_t* vData,
                       int width, int height) {
    if (!r || !r->initialized || !yData || !uData || !vData || width <= 0 || height <= 0) {
        return;
    }

    // 确保 EGL 上下文在当前线程激活
    if (eglGetCurrentContext() != r->context) {
        eglMakeCurrent(r->display, r->surface, r->surface, r->context);
    }

    int uvWidth = width / 2;
    int uvHeight = height / 2;

    // 设置视口
    glViewport(0, 0, width, height);

    // ---- 上传 Y 纹理 ----
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, r->textures[0]);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    if (yStride == width) {
        // stride == width，可以整块上传
        glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, width, height, 0,
                     GL_LUMINANCE, GL_UNSIGNED_BYTE, yData);
    } else {
        // stride != width，需要先分配再逐行上传
        if (r->lastWidth != width || r->lastHeight != height) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, width, height, 0,
                         GL_LUMINANCE, GL_UNSIGNED_BYTE, nullptr);
        }
        for (int row = 0; row < height; row++) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, row, width, 1,
                            GL_LUMINANCE, GL_UNSIGNED_BYTE, yData + row * yStride);
        }
    }

    // ---- 上传 U 纹理 ----
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, r->textures[1]);
    if (uvStride == uvWidth) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, uvWidth, uvHeight, 0,
                     GL_LUMINANCE, GL_UNSIGNED_BYTE, uData);
    } else {
        if (r->lastWidth != width || r->lastHeight != height) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, uvWidth, uvHeight, 0,
                         GL_LUMINANCE, GL_UNSIGNED_BYTE, nullptr);
        }
        for (int row = 0; row < uvHeight; row++) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, row, uvWidth, 1,
                            GL_LUMINANCE, GL_UNSIGNED_BYTE, uData + row * uvStride);
        }
    }

    // ---- 上传 V 纹理 ----
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, r->textures[2]);
    if (uvStride == uvWidth) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, uvWidth, uvHeight, 0,
                     GL_LUMINANCE, GL_UNSIGNED_BYTE, vData);
    } else {
        if (r->lastWidth != width || r->lastHeight != height) {
            glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, uvWidth, uvHeight, 0,
                         GL_LUMINANCE, GL_UNSIGNED_BYTE, nullptr);
        }
        for (int row = 0; row < uvHeight; row++) {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, row, uvWidth, 1,
                            GL_LUMINANCE, GL_UNSIGNED_BYTE, vData + row * uvStride);
        }
    }

    r->lastWidth = width;
    r->lastHeight = height;

    // ---- 绘制全屏四边形 ----
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(r->program);

    // 绑定纹理单元
    glUniform1i(r->locYTexture, 0);
    glUniform1i(r->locUTexture, 1);
    glUniform1i(r->locVTexture, 2);

    // 绑定 VBO 并设置顶点属性
    glBindBuffer(GL_ARRAY_BUFFER, r->vbo);
    glEnableVertexAttribArray(0); // aPosition
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)0);
    glEnableVertexAttribArray(1); // aTexCoord
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)(2 * sizeof(float)));

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    // ---- 提交帧到 Surface ----
    eglSwapBuffers(r->display, r->surface);
}

void glRendererRelease(GlYuvRenderer* r) {
    if (!r) return;

    if (r->display != EGL_NO_DISPLAY) {
        eglMakeCurrent(r->display, r->surface, r->surface, r->context);

        if (r->program != 0) {
            glDeleteProgram(r->program);
            r->program = 0;
        }
        if (r->textures[0] != 0) {
            glDeleteTextures(3, r->textures);
            memset(r->textures, 0, sizeof(r->textures));
        }
        if (r->vbo != 0) {
            glDeleteBuffers(1, &r->vbo);
            r->vbo = 0;
        }

        eglMakeCurrent(r->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (r->surface != EGL_NO_SURFACE) {
            eglDestroySurface(r->display, r->surface);
            r->surface = EGL_NO_SURFACE;
        }
        if (r->context != EGL_NO_CONTEXT) {
            eglDestroyContext(r->display, r->context);
            r->context = EGL_NO_CONTEXT;
        }
        eglTerminate(r->display);
        r->display = EGL_NO_DISPLAY;
    }

    r->window = nullptr;
    r->lastWidth = 0;
    r->lastHeight = 0;
    r->initialized = false;
    GL_LOGD("🏁 OpenGL ES 2.0 YUV Shader 渲染器已释放");
}
