#include "egl_renderer.h"
#include "shaders.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <android/log.h>
#include <mutex>
#include <vector>
#include <string.h>
#include <stdlib.h>

#define LOG_TAG "EGLRenderer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct Vertex {
    float x, y;
    float u, v;
};

// Fullscreen quad in normalized device coordinates (NDC)
// Maps top-left (-1.0, 1.0) to UV (0.0, 0.0) and bottom-right (1.0, -1.0) to UV (1.0, 1.0)
static const Vertex kQuadVertices[4] = {
    { -1.0f,  1.0f, 0.0f, 0.0f }, // Top-Left
    { -1.0f, -1.0f, 0.0f, 1.0f }, // Bottom-Left
    {  1.0f,  1.0f, 1.0f, 0.0f }, // Top-Right
    {  1.0f, -1.0f, 1.0f, 1.0f }  // Bottom-Right
};

struct EGLRenderer {
    EGLDisplay display;
    EGLConfig config;
    EGLContext context;
    EGLSurface surface;
    int glesVersion;
    bool hasUnpackSubimage;

    ANativeWindow* currentWindow;
    ANativeWindow* pendingWindow;
    bool windowDirty;
    std::mutex surfaceMutex;

    int surfaceWidth;
    int surfaceHeight;
    int aspectRatioMode;
    int shaderFilter;

    GLuint textureId;
    int textureWidth;
    int textureHeight;

    GLuint vbo;
    GLuint programPassthrough;
    GLint locPassthroughTex;
    GLint locPassthroughPos;
    GLint locPassthroughUV;

    GLuint programLcdGrid;
    GLint locLcdTex;
    GLint locLcdSourceRes;
    GLint locLcdPos;
    GLint locLcdUV;

    std::vector<uint32_t> scratchBuffer;
};

static GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    if (!shader) {
        LOGE("glCreateShader failed for type %d", type);
        return 0;
    }
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            std::vector<char> infoLog(infoLen);
            glGetShaderInfoLog(shader, infoLen, nullptr, infoLog.data());
            LOGE("Shader compilation failed: %s", infoLog.data());
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static GLuint createProgram(const char* vsSource, const char* fsSource) {
    GLuint vs = compileShader(GL_VERTEX_SHADER, vsSource);
    if (!vs) return 0;

    GLuint fs = compileShader(GL_FRAGMENT_SHADER, fsSource);
    if (!fs) {
        glDeleteShader(vs);
        return 0;
    }

    GLuint program = glCreateProgram();
    if (!program) {
        glDeleteShader(vs);
        glDeleteShader(fs);
        return 0;
    }

    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);

    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        GLint infoLen = 0;
        glGetProgramiv(program, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 1) {
            std::vector<char> infoLog(infoLen);
            glGetProgramInfoLog(program, infoLen, nullptr, infoLog.data());
            LOGE("Program linking failed: %s", infoLog.data());
        }
        glDeleteProgram(program);
        program = 0;
    }

    glDeleteShader(vs);
    glDeleteShader(fs);
    return program;
}

static void initGLObjects(EGLRenderer* r) {
    // Compile Passthrough Program
    r->programPassthrough = createProgram(kVertexShaderSource, kPassthroughFragmentShaderSource);
    if (r->programPassthrough) {
        r->locPassthroughTex = glGetUniformLocation(r->programPassthrough, "uTexture");
        r->locPassthroughPos = glGetAttribLocation(r->programPassthrough, "aPosition");
        r->locPassthroughUV  = glGetAttribLocation(r->programPassthrough, "aTexCoord");
    }

    // Compile LCD Grid Program
    r->programLcdGrid = createProgram(kVertexShaderSource, kLcdGridFragmentShaderSource);
    if (r->programLcdGrid) {
        r->locLcdTex       = glGetUniformLocation(r->programLcdGrid, "uTexture");
        r->locLcdSourceRes = glGetUniformLocation(r->programLcdGrid, "uSourceRes");
        r->locLcdPos       = glGetAttribLocation(r->programLcdGrid, "aPosition");
        r->locLcdUV        = glGetAttribLocation(r->programLcdGrid, "aTexCoord");
    }

    // Create Vertex Buffer Object
    glGenBuffers(1, &r->vbo);
    glBindBuffer(GL_ARRAY_BUFFER, r->vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kQuadVertices), kQuadVertices, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    // Create Main Game Texture
    glGenTextures(1, &r->textureId);
    glBindTexture(GL_TEXTURE_2D, r->textureId);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glBindTexture(GL_TEXTURE_2D, 0);

    LOGI("GL objects initialized successfully (vbo=%u, tex=%u, progPass=%u, progLcd=%u)",
         r->vbo, r->textureId, r->programPassthrough, r->programLcdGrid);
}

EGLRenderer* egl_renderer_create(void) {
    EGLRenderer* r = new EGLRenderer();
    memset(r, 0, sizeof(EGLRenderer));

    r->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (r->display == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        delete r;
        return nullptr;
    }

    EGLint major = 0, minor = 0;
    if (!eglInitialize(r->display, &major, &minor)) {
        LOGE("eglInitialize failed");
        delete r;
        return nullptr;
    }
    LOGI("EGL initialized: version %d.%d", major, minor);

    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE,    EGL_WINDOW_BIT,
        EGL_RED_SIZE,        8,
        EGL_GREEN_SIZE,      8,
        EGL_BLUE_SIZE,       8,
        EGL_ALPHA_SIZE,      8,
        EGL_DEPTH_SIZE,      0,
        EGL_STENCIL_SIZE,    0,
        EGL_NONE
    };

    EGLint numConfigs = 0;
    if (!eglChooseConfig(r->display, configAttribs, &r->config, 1, &numConfigs) || numConfigs < 1) {
        LOGE("eglChooseConfig failed");
        eglTerminate(r->display);
        delete r;
        return nullptr;
    }

    // Attempt OpenGL ES 3.0 context creation first
    const EGLint ctxAttribsGLES3[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };
    r->context = eglCreateContext(r->display, r->config, EGL_NO_CONTEXT, ctxAttribsGLES3);
    r->glesVersion = 3;

    // Fallback to OpenGL ES 2.0 if 3.0 is unsupported
    if (r->context == EGL_NO_CONTEXT) {
        LOGI("OpenGL ES 3.0 context creation failed; falling back to OpenGL ES 2.0");
        const EGLint ctxAttribsGLES2[] = {
            EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL_NONE
        };
        r->context = eglCreateContext(r->display, r->config, EGL_NO_CONTEXT, ctxAttribsGLES2);
        r->glesVersion = 2;
    }

    if (r->context == EGL_NO_CONTEXT) {
        LOGE("Failed to create both OpenGL ES 3.0 and 2.0 contexts");
        eglTerminate(r->display);
        delete r;
        return nullptr;
    }

    LOGI("Created OpenGL ES %d context successfully", r->glesVersion);

    r->surface = EGL_NO_SURFACE;
    r->currentWindow = nullptr;
    r->pendingWindow = nullptr;
    r->windowDirty = false;
    r->aspectRatioMode = 0;
    r->shaderFilter = 0;

    return r;
}

void egl_renderer_destroy(EGLRenderer* r) {
    if (!r) return;

    {
        std::lock_guard<std::mutex> lock(r->surfaceMutex);
        if (r->display != EGL_NO_DISPLAY) {
            eglMakeCurrent(r->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (r->surface != EGL_NO_SURFACE) {
                eglDestroySurface(r->display, r->surface);
                r->surface = EGL_NO_SURFACE;
            }
            if (r->programPassthrough) glDeleteProgram(r->programPassthrough);
            if (r->programLcdGrid)     glDeleteProgram(r->programLcdGrid);
            if (r->vbo)                glDeleteBuffers(1, &r->vbo);
            if (r->textureId)          glDeleteTextures(1, &r->textureId);

            if (r->context != EGL_NO_CONTEXT) {
                eglDestroyContext(r->display, r->context);
                r->context = EGL_NO_CONTEXT;
            }
            eglTerminate(r->display);
            r->display = EGL_NO_DISPLAY;
        }

        if (r->currentWindow) {
            ANativeWindow_release(r->currentWindow);
            r->currentWindow = nullptr;
        }
        if (r->pendingWindow) {
            ANativeWindow_release(r->pendingWindow);
            r->pendingWindow = nullptr;
        }
    }

    delete r;
}

void egl_renderer_set_surface(EGLRenderer* r, ANativeWindow* window) {
    if (!r) return;
    std::lock_guard<std::mutex> lock(r->surfaceMutex);
    if (window) {
        ANativeWindow_acquire(window);
    }
    if (r->pendingWindow) {
        ANativeWindow_release(r->pendingWindow);
    }
    r->pendingWindow = window;
    r->windowDirty = true;
}

void egl_renderer_set_surface_size(EGLRenderer* r, int width, int height) {
    if (!r) return;
    r->surfaceWidth = width;
    r->surfaceHeight = height;
}

void egl_renderer_set_aspect_ratio_mode(EGLRenderer* r, int mode) {
    if (!r) return;
    r->aspectRatioMode = mode;
}

void egl_renderer_set_shader_filter(EGLRenderer* r, int filter) {
    if (!r) return;
    r->shaderFilter = filter;
}

static void updateSurfaceOnDrawThread(EGLRenderer* r) {
    if (!r->windowDirty) return;
    r->windowDirty = false;

    if (r->surface != EGL_NO_SURFACE) {
        eglMakeCurrent(r->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(r->display, r->surface);
        r->surface = EGL_NO_SURFACE;
    }

    if (r->currentWindow) {
        ANativeWindow_release(r->currentWindow);
        r->currentWindow = nullptr;
    }

    r->currentWindow = r->pendingWindow;
    r->pendingWindow = nullptr;

    if (r->currentWindow) {
        EGLint format = 0;
        eglGetConfigAttrib(r->display, r->config, EGL_NATIVE_VISUAL_ID, &format);
        ANativeWindow_setBuffersGeometry(r->currentWindow, 0, 0, format);

        r->surface = eglCreateWindowSurface(r->display, r->config, r->currentWindow, nullptr);
        if (r->surface == EGL_NO_SURFACE) {
            LOGE("eglCreateWindowSurface failed");
            return;
        }

        if (!eglMakeCurrent(r->display, r->surface, r->surface, r->context)) {
            LOGE("eglMakeCurrent failed");
            eglDestroySurface(r->display, r->surface);
            r->surface = EGL_NO_SURFACE;
            return;
        }

        // Check extension for row unpack on ES 2.0
        const char* ext = (const char*)glGetString(GL_EXTENSIONS);
        r->hasUnpackSubimage = (r->glesVersion >= 3) || (ext && strstr(ext, "GL_EXT_unpack_subimage") != nullptr);

        if (r->vbo == 0) {
            initGLObjects(r);
        }
        LOGI("Bound new EGLSurface on render thread (dimensions %dx%d)",
             ANativeWindow_getWidth(r->currentWindow), ANativeWindow_getHeight(r->currentWindow));
    }
}

void egl_renderer_draw_frame(EGLRenderer* r, const uint32_t* pixelBuffer, int gameWidth, int gameHeight, int stride) {
    if (!r || !pixelBuffer || gameWidth <= 0 || gameHeight <= 0) return;

    {
        std::lock_guard<std::mutex> lock(r->surfaceMutex);
        if (r->windowDirty) {
            updateSurfaceOnDrawThread(r);
        }
    }

    if (r->surface == EGL_NO_SURFACE || !r->currentWindow) {
        return; // No active surface to present to
    }

    // Query physical surface dimensions if not set explicitly
    int surfW = r->surfaceWidth > 0 ? r->surfaceWidth : ANativeWindow_getWidth(r->currentWindow);
    int surfH = r->surfaceHeight > 0 ? r->surfaceHeight : ANativeWindow_getHeight(r->currentWindow);
    if (surfW <= 0 || surfH <= 0) return;

    // Reallocate texture if game dimensions changed
    if (r->textureWidth != gameWidth || r->textureHeight != gameHeight) {
        glBindTexture(GL_TEXTURE_2D, r->textureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, gameWidth, gameHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        r->textureWidth = gameWidth;
        r->textureHeight = gameHeight;
    }

    // Upload active screen rectangle from video buffer
    glBindTexture(GL_TEXTURE_2D, r->textureId);
    if (r->hasUnpackSubimage) {
        glPixelStorei(GL_UNPACK_ROW_LENGTH, stride);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, gameWidth, gameHeight, GL_RGBA, GL_UNSIGNED_BYTE, pixelBuffer);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
    } else {
        size_t totalPixels = (size_t)gameWidth * (size_t)gameHeight;
        if (r->scratchBuffer.size() < totalPixels) {
            r->scratchBuffer.resize(totalPixels);
        }
        for (int y = 0; y < gameHeight; ++y) {
            memcpy(&r->scratchBuffer[y * gameWidth], &pixelBuffer[y * stride], gameWidth * sizeof(uint32_t));
        }
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, gameWidth, gameHeight, GL_RGBA, GL_UNSIGNED_BYTE, r->scratchBuffer.data());
    }

    // Configure texture filtering
    GLint filterParam = (r->shaderFilter == 1) ? GL_LINEAR : GL_NEAREST;
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filterParam);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filterParam);

    // Mathematical Viewport Calculation
    int vpX = 0, vpY = 0, vpW = surfW, vpH = surfH;
    switch (r->aspectRatioMode) {
        case 0: { // Native Aspect Ratio (3:2 for GBA, 10:9 for GB/GBC)
            if (surfW * gameHeight > surfH * gameWidth) {
                // Surface is wider -> Pillarbox (bars left & right)
                vpH = surfH;
                vpW = (surfH * gameWidth + gameHeight / 2) / gameHeight;
                vpX = (surfW - vpW) / 2;
                vpY = 0;
            } else {
                // Surface is taller (Portrait) -> Dock to TOP so controls fit comfortably on bottom
                vpW = surfW;
                vpH = (surfW * gameHeight + gameWidth / 2) / gameWidth;
                vpX = 0;
                vpY = surfH - vpH; // Top of OpenGL viewport
            }
            break;
        }
        case 1: { // Integer Scaling (pixel-perfect)
            int scaleX = surfW / gameWidth;
            int scaleY = surfH / gameHeight;
            int scale = (scaleX < scaleY) ? scaleX : scaleY;
            if (scale < 1) scale = 1;
            vpW = scale * gameWidth;
            vpH = scale * gameHeight;
            vpX = (surfW - vpW) / 2;
            vpY = (surfH - vpH) / 2;
            break;
        }
        case 2: // Stretch (Fullscreen)
        default: {
            vpX = 0;
            vpY = 0;
            vpW = surfW;
            vpH = surfH;
            break;
        }
    }

    // Step 1: Clear the entire surface to black (creates clean pillarboxes/letterboxes)
    glViewport(0, 0, surfW, surfH);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    // Step 2: Render game quad into aspect-ratio-corrected viewport
    glViewport(vpX, vpY, vpW, vpH);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);

    GLuint activeProgram = (r->shaderFilter == 2) ? r->programLcdGrid : r->programPassthrough;
    GLint locPos = (r->shaderFilter == 2) ? r->locLcdPos : r->locPassthroughPos;
    GLint locUV  = (r->shaderFilter == 2) ? r->locLcdUV  : r->locPassthroughUV;
    GLint locTex = (r->shaderFilter == 2) ? r->locLcdTex : r->locPassthroughTex;

    glUseProgram(activeProgram);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, r->textureId);
    glUniform1i(locTex, 0);

    if (r->shaderFilter == 2) {
        glUniform2f(r->locLcdSourceRes, (float)gameWidth, (float)gameHeight);
    }

    glBindBuffer(GL_ARRAY_BUFFER, r->vbo);
    glEnableVertexAttribArray(locPos);
    glVertexAttribPointer(locPos, 2, GL_FLOAT, GL_FALSE, sizeof(Vertex), (const void*)offsetof(Vertex, x));

    glEnableVertexAttribArray(locUV);
    glVertexAttribPointer(locUV, 2, GL_FLOAT, GL_FALSE, sizeof(Vertex), (const void*)offsetof(Vertex, u));

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glDisableVertexAttribArray(locPos);
    glDisableVertexAttribArray(locUV);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glUseProgram(0);

    // Swap EGL buffers to present frame to display
    eglSwapBuffers(r->display, r->surface);
}
