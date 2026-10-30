/*
 * mGBA Android JNI Bridge Implementation
 * Licensed under the Mozilla Public License, v. 2.0 (MPL-2.0).
 */

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <stdint.h>
#include <fcntl.h>
#include <unistd.h>
#include <time.h>
#include <math.h>
#include <pthread.h>

#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/core/version.h>
#include <mgba/core/serialize.h>
#include <mgba/core/interface.h>
#include <mgba/core/blip_buf.h>
#include <mgba-util/vfs.h>

#include "video/egl_renderer.h"
#include "audio/oboe_audio.h"

#define LOG_TAG "mGBA_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#define MAX_VIDEO_WIDTH 256
#define MAX_VIDEO_HEIGHT 256
#define SRAM_DEBOUNCE_INTERVAL_MS 1000

typedef struct NativeContext NativeContext;

struct AppAVStream {
    struct mAVStream stream;
    NativeContext *ctx;
};

/**
 * Context structure holding all native state for an emulator instance.
 */
struct NativeContext {
    pthread_mutex_t mutex;
    struct mCore *core;
    struct VFile *romVf;
    ANativeWindow *window;
    EGLRenderer *renderer;
    AudioEngineHandle audio;
    struct AppAVStream avStream;
    jobject bufferGlobalRef;

    int sampleRate;
    int bufferFrames;
    uint32_t keyMask;

    int frameskip;
    float fastForwardMultiplier;
    uint64_t frameCounter;

    int aspectRatioMode;
    int shaderFilter;
    int audioVolume;
    bool audioMute;

    uint32_t videoBuffer[MAX_VIDEO_WIDTH * MAX_VIDEO_HEIGHT];
    int surfaceWidth;
    int surfaceHeight;

    // Battery SRAM persistence state
    struct mCoreCallbacks coreCallbacks;
    volatile bool sramDirty;
    int64_t lastSramDirtyTimeMs;
    char currentSramPath[1024];
};

/* ========================================================================== */
/* Utility & Time Helpers                                                     */
/* ========================================================================== */

static int64_t _getTimeMs(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000LL + (ts.tv_nsec / 1000000LL);
}

static void _onSavedataUpdated(void *context) {
    NativeContext *ctx = (NativeContext *)context;
    if (ctx) {
        ctx->sramDirty = true;
        ctx->lastSramDirtyTimeMs = _getTimeMs();
        LOGD("SRAM marked dirty by core callback");
    }
}

static void _gbPostAudioBuffer(struct mAVStream* stream, struct blip_t* left, struct blip_t* right) {
    struct AppAVStream* appStream = (struct AppAVStream*)stream;
    NativeContext* ctx = appStream ? appStream->ctx : NULL;
    if (!ctx || !ctx->audio) return;

    int avail = blip_samples_avail(left);
    if (avail > 0) {
        int16_t pcmBuffer[avail * 2];
        blip_read_samples(left, pcmBuffer, avail, 1);
        blip_read_samples(right, pcmBuffer + 1, avail, 1);
        audioEngineWriteFrames(ctx->audio, pcmBuffer, (size_t)avail);
    }
}

static bool _atomicWriteFile(const char *path, const void *data, size_t size) {
    if (!path || !data || size == 0) {
        return false;
    }

    char tmpPath[1024];
    snprintf(tmpPath, sizeof(tmpPath), "%s.tmp", path);

    FILE *f = fopen(tmpPath, "wb");
    if (f) {
        size_t written = fwrite(data, 1, size, f);
        fflush(f);
        int fd = fileno(f);
        if (fd >= 0) {
            fsync(fd);
        }
        fclose(f);

        if (written == size) {
            // First attempt standard atomic rename
            if (rename(tmpPath, path) == 0) {
                return true;
            }
            // Android FUSE fallback: rename onto existing file can fail with EEXIST / EBUSY
            unlink(path);
            if (rename(tmpPath, path) == 0) {
                return true;
            }
        }
        unlink(tmpPath);
    }

    // Direct write fallback (guaranteed to succeed on all Android storages)
    FILE *direct = fopen(path, "wb");
    if (direct) {
        size_t written = fwrite(data, 1, size, direct);
        fflush(direct);
        int fd = fileno(direct);
        if (fd >= 0) {
            fsync(fd);
        }
        fclose(direct);
        return (written == size);
    }

    LOGE("Failed to write SRAM file: %s", path);
    return false;
}

/* ========================================================================== */
/* Lifecycle & Teardown Helpers                                               */
/* ========================================================================== */

static void cleanupActiveCore(NativeContext *ctx, JNIEnv *env) {
    if (!ctx) return;

    // 1. Flush SRAM if path is bound
    if (ctx->currentSramPath[0] != '\0' && ctx->core) {
        void *sramData = NULL;
        size_t sramSize = ctx->core->savedataClone(ctx->core, &sramData);
        if (sramData && sramSize > 0) {
            _atomicWriteFile(ctx->currentSramPath, sramData, sramSize);
            free(sramData);
            LOGI("Flushed pending dirty SRAM during core cleanup (%zu bytes)", sramSize);
        }
        ctx->sramDirty = false;
    }

    // 2. Teardown active mCore
    if (ctx->core) {
        LOGI("Cleaning up active core %p", (void*)ctx->core);
        ctx->core->clearCoreCallbacks(ctx->core);
        ctx->core->unloadROM(ctx->core);
        mCoreConfigDeinit(&ctx->core->config);
        ctx->core->deinit(ctx->core);
        ctx->core = NULL;
    }

    // 3. Close active ROM VFile
    if (ctx->romVf) {
        LOGI("Closing active ROM VFile %p", (void*)ctx->romVf);
        ctx->romVf->close(ctx->romVf);
        ctx->romVf = NULL;
    }

    // 4. Release global reference to direct buffer
    if (ctx->bufferGlobalRef && env) {
        (*env)->DeleteGlobalRef(env, ctx->bufferGlobalRef);
        ctx->bufferGlobalRef = NULL;
    }

    // 5. Reset path and state variables
    ctx->currentSramPath[0] = '\0';
    ctx->sramDirty = false;
    ctx->lastSramDirtyTimeMs = 0;
    ctx->frameCounter = 0;
}

static bool setupCoreFromVF(NativeContext *ctx, struct VFile *vf, JNIEnv *env, jobject bufferObj) {
    if (!ctx || !vf) {
        LOGE("Invalid arguments to setupCoreFromVF");
        if (vf) vf->close(vf);
        return false;
    }

    // Ensure any previously active core is cleanly deallocated
    cleanupActiveCore(ctx, env);

    struct mCore *core = mCoreFindVF(vf);
    if (!core) {
        LOGE("mCoreFindVF failed to identify ROM format (.gba, .gb, .gbc)");
        vf->close(vf);
        return false;
    }

    mCoreInitConfig(core, NULL);
    if (!core->init(core)) {
        LOGE("Failed to initialize mCore");
        mCoreConfigDeinit(&core->config);
        free(core);
        vf->close(vf);
        return false;
    }

    // Bind 32-bit pixel buffer with MAX_VIDEO_WIDTH (256) stride
    core->setVideoBuffer(core, (color_t *)ctx->videoBuffer, MAX_VIDEO_WIDTH);

    // Attach stream callback for GB/GBC audio
    memset(&ctx->avStream, 0, sizeof(ctx->avStream));
    ctx->avStream.ctx = ctx;
    ctx->avStream.stream.postAudioBuffer = _gbPostAudioBuffer;
    core->setAVStream(core, &ctx->avStream.stream);

    // Configure blip buffers to target 48000 Hz
    blip_t* left = core->getAudioChannel(core, 0);
    blip_t* right = core->getAudioChannel(core, 1);
    if (left && right) {
        blip_set_rates(left, core->frequency(core), 48000.0);
        blip_set_rates(right, core->frequency(core), 48000.0);
    }
    core->setAudioBufferSize(core, 1024);

    if (!core->loadROM(core, vf)) {
        LOGE("mCore failed to load ROM from VFile");
        core->deinit(core);
        mCoreConfigDeinit(&core->config);
        vf->close(vf);
        return false;
    }

    core->reset(core);

    // Apply configured frameskip if set
    if (ctx->frameskip > 0) {
        mCoreConfigSetIntValue(&core->config, "frameskip", ctx->frameskip);
        core->reloadConfigOption(core, "frameskip", &core->config);
    }

    // Register savedataUpdated callback for SRAM persistence
    memset(&ctx->coreCallbacks, 0, sizeof(ctx->coreCallbacks));
    ctx->coreCallbacks.context = ctx;
    ctx->coreCallbacks.savedataUpdated = _onSavedataUpdated;
    core->addCoreCallbacks(core, &ctx->coreCallbacks);

    // Retain JNI global reference to direct ByteBuffer if supplied
    if (bufferObj && env) {
        ctx->bufferGlobalRef = (*env)->NewGlobalRef(env, bufferObj);
    }

    ctx->core = core;
    ctx->romVf = vf;
    ctx->frameCounter = 0;

    unsigned w = 0, h = 0;
    core->desiredVideoDimensions(core, &w, &h);
    LOGI("Successfully loaded ROM (platform=%d, width=%u, height=%u)", core->platform(core), w, h);
    return true;
}

/* ========================================================================== */
/* JNI_OnLoad & Version Query                                                 */
/* ========================================================================== */

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm;
    (void)reserved;
    LOGI("mGBA JNI loaded: %s version %s", projectName, projectVersion);
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL
Java_com_retro_mgba_core_NativeCore_nativeGetVersion(JNIEnv *env, jclass clazz) {
    (void)clazz;
    const char *ver = projectVersion;
    if (!ver || strlen(ver) == 0) {
        ver = "0.10.5";
    }
    return (*env)->NewStringUTF(env, ver);
}

JNIEXPORT jstring JNICALL
Java_com_retro_mgba_core_NativeCore_nativeGetCoreVersion(JNIEnv *env, jclass clazz) {
    return Java_com_retro_mgba_core_NativeCore_nativeGetVersion(env, clazz);
}

/* ========================================================================== */
/* Lifecycle Functions                                                        */
/* ========================================================================== */

JNIEXPORT jlong JNICALL
Java_com_retro_mgba_core_NativeCore_nativeCreate(JNIEnv *env, jobject thiz, jint sampleRate, jint bufferFrames) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)calloc(1, sizeof(NativeContext));
    if (!ctx) {
        LOGE("Failed to allocate NativeContext");
        return 0;
    }

    pthread_mutex_init(&ctx->mutex, NULL);
    ctx->sampleRate = (sampleRate > 0) ? sampleRate : 48000;
    ctx->bufferFrames = (bufferFrames > 0) ? bufferFrames : 2048;
    ctx->fastForwardMultiplier = 1.0f;
    ctx->audioVolume = 100;
    ctx->audioMute = false;
    ctx->aspectRatioMode = 0;
    ctx->shaderFilter = 0;

    ctx->renderer = egl_renderer_create();
    if (!ctx->renderer) {
        LOGE("Failed to initialize EGLRenderer");
    }

    ctx->audio = audioEngineCreate(ctx->sampleRate, ctx->bufferFrames);
    if (!ctx->audio) {
        LOGE("Failed to initialize AudioEngine");
    } else {
        audioEngineSetVolume(ctx->audio, ctx->audioVolume);
        audioEngineSetMute(ctx->audio, ctx->audioMute);
    }

    LOGI("NativeContext created at %p (renderer=%p, audio=%p)", (void*)ctx, (void*)ctx->renderer, (void*)ctx->audio);
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT void JNICALL
Java_com_retro_mgba_core_NativeCore_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx) return;

    LOGI("Destroying NativeContext at %p", (void*)ctx);

    pthread_mutex_lock(&ctx->mutex);
    cleanupActiveCore(ctx, env);

    if (ctx->renderer) {
        egl_renderer_destroy(ctx->renderer);
        ctx->renderer = NULL;
    }

    if (ctx->audio) {
        audioEngineDestroy(ctx->audio);
        ctx->audio = NULL;
    }

    if (ctx->window) {
        ANativeWindow_release(ctx->window);
        ctx->window = NULL;
    }
    pthread_mutex_unlock(&ctx->mutex);

    pthread_mutex_destroy(&ctx->mutex);
    free(ctx);
}

/* ========================================================================== */
/* Multi-Format ROM Loading & Lifecycle                                       */
/* ========================================================================== */

JNIEXPORT jboolean JNICALL
Java_com_retro_mgba_core_NativeCore_nativeLoadRomPath(JNIEnv *env, jobject thiz, jlong handle, jstring path) {
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !path) return JNI_FALSE;

    const char *cpath = (*env)->GetStringUTFChars(env, path, NULL);
    if (!cpath) return JNI_FALSE;

    LOGI("nativeLoadRomPath: %s", cpath);
    struct VFile *vf = VFileOpen(cpath, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, path, cpath);

    if (!vf) {
        LOGE("VFileOpen failed to open file path");
        return JNI_FALSE;
    }

    pthread_mutex_lock(&ctx->mutex);
    bool ok = setupCoreFromVF(ctx, vf, env, NULL);
    pthread_mutex_unlock(&ctx->mutex);

    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_retro_mgba_core_NativeCore_nativeLoadRomFd(JNIEnv *env, jobject thiz, jlong handle, jint fd) {
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || fd < 0) return JNI_FALSE;

    LOGI("nativeLoadRomFd: fd=%d", fd);
    int dupFd = dup(fd);
    if (dupFd < 0) {
        LOGE("Failed to dup file descriptor: %d", fd);
        return JNI_FALSE;
    }

    struct VFile *vf = VFileFromFD(dupFd);
    if (!vf) {
        LOGE("VFileFromFD failed for fd: %d", dupFd);
        close(dupFd);
        return JNI_FALSE;
    }

    pthread_mutex_lock(&ctx->mutex);
    bool ok = setupCoreFromVF(ctx, vf, env, NULL);
    pthread_mutex_unlock(&ctx->mutex);

    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_retro_mgba_core_NativeCore_nativeLoadRomBuffer(JNIEnv *env, jobject thiz, jlong handle, jobject buffer, jint size) {
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !buffer || size <= 0) return JNI_FALSE;

    void *bufPtr = (*env)->GetDirectBufferAddress(env, buffer);
    if (!bufPtr) {
        LOGE("Failed to get direct buffer address");
        return JNI_FALSE;
    }

    LOGI("nativeLoadRomBuffer: size=%d bytes", size);
    struct VFile *vf = VFileFromConstMemory(bufPtr, (size_t)size);
    if (!vf) {
        LOGE("VFileFromConstMemory failed");
        return JNI_FALSE;
    }

    pthread_mutex_lock(&ctx->mutex);
    bool ok = setupCoreFromVF(ctx, vf, env, buffer);
    pthread_mutex_unlock(&ctx->mutex);

    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_retro_mgba_core_NativeCore_nativeUnloadRom(JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx) return;

    pthread_mutex_lock(&ctx->mutex);
    cleanupActiveCore(ctx, env);
    pthread_mutex_unlock(&ctx->mutex);
}

JNIEXPORT void JNICALL
Java_com_retro_mgba_core_NativeCore_nativeReset(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx) return;

    pthread_mutex_lock(&ctx->mutex);
    if (ctx->core) {
        ctx->core->reset(ctx->core);
    }
    pthread_mutex_unlock(&ctx->mutex);
}

/* ========================================================================== */
/* Frame Stepping, Rendering & Timing Pacing                                  */
/* ========================================================================== */

JNIEXPORT jboolean JNICALL
Java_com_retro_mgba_core_NativeCore_nativeRunFrame(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx) return JNI_FALSE;

    pthread_mutex_lock(&ctx->mutex);
    if (!ctx->core) {
        pthread_mutex_unlock(&ctx->mutex);
        return JNI_FALSE;
    }

    // 1. Run one emulation frame in mGBA
    ctx->core->runFrame(ctx->core);
    ctx->frameCounter++;

    // 2. Extract GBA audio samples (for GBA platform)
    if (ctx->core->platform(ctx->core) == mPLATFORM_GBA) {
        blip_t* left = ctx->core->getAudioChannel(ctx->core, 0);
        blip_t* right = ctx->core->getAudioChannel(ctx->core, 1);
        if (left && right) {
            int avail = blip_samples_avail(left);
            if (avail > 0) {
                int16_t pcmBuffer[avail * 2];
                blip_read_samples(left, pcmBuffer, avail, 1);
                blip_read_samples(right, pcmBuffer + 1, avail, 1);
                if (ctx->audio) {
                    audioEngineWriteFrames(ctx->audio, pcmBuffer, (size_t)avail);
                }
            }
        }
    }
    // (GB/GBC audio is extracted automatically via _gbPostAudioBuffer during runFrame)

    // 3. Video Presentation to EGL Surface
    unsigned w = 0, h = 0;
    ctx->core->desiredVideoDimensions(ctx->core, &w, &h);
    if (w == 0 || h == 0) {
        w = 240;
        h = 160;
    }

    const float ff = ctx->fastForwardMultiplier;
    bool shouldRender = true;

    if (ff > 1.05f) {
        // Fast forward display throttling: skip frames to avoid VSync blocking
        int skipFactor = (int)ceilf(ff);
        if (skipFactor < 1) skipFactor = 1;
        shouldRender = (ctx->frameCounter % (uint64_t)skipFactor) == 0;
    } else if (ctx->frameskip > 0) {
        // Hardware frameskip: render 1 out of (frameskip + 1) frames
        shouldRender = (ctx->frameCounter % (uint64_t)(ctx->frameskip + 1)) == 0;
    }

    if (shouldRender && ctx->renderer) {
        egl_renderer_draw_frame(ctx->renderer, ctx->videoBuffer, (int)w, (int)h, MAX_VIDEO_WIDTH);
    }

    // 4. Clock Synchronization & Pacing
    if (ff > 1.05f) {
        // Fast forward: mute audio and pace via timer
        if (ctx->audio) {
            audioEngineSetMute(ctx->audio, true);
            audioEngineReset(ctx->audio);
        }
        int targetMicros = (int)(16743.0f / ff);
        if (targetMicros > 0) {
            usleep((useconds_t)(targetMicros > 1000 ? targetMicros : 1000));
        }
    } else {
        // Normal 1.0x speed: restore configured mute state and pace to audio watermark
        if (ctx->audio) {
            audioEngineSetMute(ctx->audio, ctx->audioMute);
            size_t buffered = audioEngineGetBufferedFrames(ctx->audio);
            const size_t targetWatermark = 2048; // ~42.6 ms buffer
            const size_t highWatermark   = 3072; // ~64.0 ms buffer

            if (buffered > highWatermark) {
                // Producer is ahead of audio DAC: sleep to let audio consumer drain
                int excessFrames = (int)(buffered - targetWatermark);
                int sleepMicros = (excessFrames * 1000000) / 48000;
                if (sleepMicros > 0 && sleepMicros < 35000) {
                    usleep((useconds_t)sleepMicros);
                }
            }
        }
    }

    // 5. Debounced automatic SRAM flush check
    if (ctx->sramDirty && ctx->currentSramPath[0] != '\0') {
        int64_t now = _getTimeMs();
        if (now - ctx->lastSramDirtyTimeMs >= SRAM_DEBOUNCE_INTERVAL_MS) {
            void *sramData = NULL;
            size_t sramSize = ctx->core->savedataClone(ctx->core, &sramData);
            if (sramData && sramSize > 0) {
                if (_atomicWriteFile(ctx->currentSramPath, sramData, sramSize)) {
                    ctx->sramDirty = false;
                    LOGI("Debounced atomic flush completed: %s (%zu bytes)", ctx->currentSramPath, sramSize);
                }
                free(sramData);
            }
        }
    }

    pthread_mutex_unlock(&ctx->mutex);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_retro_mgba_core_NativeCore_nativeSetKeys(JNIEnv *env, jobject thiz, jlong handle, jint keyMask) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx) return;

    pthread_mutex_lock(&ctx->mutex);
    ctx->keyMask = (uint32_t)keyMask;
    if (ctx->core) {
        ctx->core->setKeys(ctx->core, (uint32_t)keyMask);
    }
    pthread_mutex_unlock(&ctx->mutex);
}

/* ========================================================================== */
/* Surface & Video Configuration                                              */
/* ========================================================================== */

JNIEXPORT void JNICALL
Java_com_retro_mgba_core_NativeCore_nativeSetSurface(JNIEnv *env, jobject thiz, jlong handle, jobject surface) {
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx) return;

    pthread_mutex_lock(&ctx->mutex);
    if (ctx->window) {
        ANativeWindow_release(ctx->window);
        ctx->window = NULL;
    }

    if (surface) {
        ctx->window = ANativeWindow_fromSurface(env, surface);
        LOGI("Native surface bound: %p", (void*)ctx->window);
    } else {
        LOGI("Native surface unbound");
    }

    if (ctx->renderer) {
        egl_renderer_set_surface(ctx->renderer, ctx->window);
    }
    pthread_mutex_unlock(&ctx->mutex);
}

JNIEXPORT void JNICALL
Java_com_retro_mgba_core_NativeCore_nativeSetSurfaceSize(JNIEnv *env, jobject thiz, jlong handle, jint width, jint height) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx) return;

    pthread_mutex_lock(&ctx->mutex);
    ctx->surfaceWidth = width;
    ctx->surfaceHeight = height;
    if (ctx->renderer) {
        egl_renderer_set_surface_size(ctx->renderer, width, height);
    }
    pthread_mutex_unlock(&ctx->mutex);
}

JNIEXPORT void JNICALL
Java_com_retro_mgba_core_NativeCore_nativeSetAspectRatioMode(JNIEnv *env, jobject thiz, jlong handle, jint mode) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (ctx) {
        pthread_mutex_lock(&ctx->mutex);
        ctx->aspectRatioMode = mode;
        if (ctx->renderer) {
            egl_renderer_set_aspect_ratio_mode(ctx->renderer, mode);
        }
        pthread_mutex_unlock(&ctx->mutex);
    }
}

JNIEXPORT void JNICALL
Java_com_retro_mgba_core_NativeCore_nativeSetShaderFilter(JNIEnv *env, jobject thiz, jlong handle, jint filter) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (ctx) {
        pthread_mutex_lock(&ctx->mutex);
        ctx->shaderFilter = filter;
        if (ctx->renderer) {
            egl_renderer_set_shader_filter(ctx->renderer, filter);
        }
        pthread_mutex_unlock(&ctx->mutex);
    }
}

JNIEXPORT jint JNICALL
Java_com_retro_mgba_core_NativeCore_nativeGetVideoWidth(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !ctx->core) return 0;

    unsigned w = 0, h = 0;
    ctx->core->desiredVideoDimensions(ctx->core, &w, &h);
    return (jint)w;
}

JNIEXPORT jint JNICALL
Java_com_retro_mgba_core_NativeCore_nativeGetVideoHeight(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !ctx->core) return 0;

    unsigned w = 0, h = 0;
    ctx->core->desiredVideoDimensions(ctx->core, &w, &h);
    return (jint)h;
}

/* ========================================================================== */
/* Audio Control                                                              */
/* ========================================================================== */

JNIEXPORT void JNICALL
Java_com_retro_mgba_core_NativeCore_nativeSetAudioVolume(JNIEnv *env, jobject thiz, jlong handle, jint volumePercent) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (ctx) {
        pthread_mutex_lock(&ctx->mutex);
        ctx->audioVolume = volumePercent;
        if (ctx->audio) {
            audioEngineSetVolume(ctx->audio, volumePercent);
        }
        pthread_mutex_unlock(&ctx->mutex);
    }
}

JNIEXPORT void JNICALL
Java_com_retro_mgba_core_NativeCore_nativeSetAudioMute(JNIEnv *env, jobject thiz, jlong handle, jboolean mute) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (ctx) {
        pthread_mutex_lock(&ctx->mutex);
        ctx->audioMute = (bool)mute;
        if (ctx->audio) {
            audioEngineSetMute(ctx->audio, (bool)mute);
        }
        pthread_mutex_unlock(&ctx->mutex);
    }
}

/* ========================================================================== */
/* Save States Subsystem                                                      */
/* ========================================================================== */

JNIEXPORT jboolean JNICALL
Java_com_retro_mgba_core_NativeCore_nativeSaveState(JNIEnv *env, jobject thiz, jlong handle, jint slot, jstring stateDir) {
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !stateDir) return JNI_FALSE;

    // Strict slot range validation
    if (slot < 0 || slot > 9) {
        LOGE("Invalid save state slot: %d (must be 0..9)", slot);
        return JNI_FALSE;
    }

    const char *dir = (*env)->GetStringUTFChars(env, stateDir, NULL);
    if (!dir) return JNI_FALSE;

    char statePath[1024];
    snprintf(statePath, sizeof(statePath), "%s/slot_%d.ss", dir, slot);
    (*env)->ReleaseStringUTFChars(env, stateDir, dir);

    pthread_mutex_lock(&ctx->mutex);
    if (!ctx->core) {
        pthread_mutex_unlock(&ctx->mutex);
        return JNI_FALSE;
    }

    struct VFile *vf = VFileOpen(statePath, O_CREAT | O_TRUNC | O_WRONLY);
    if (!vf) {
        LOGE("Failed to create savestate file: %s", statePath);
        pthread_mutex_unlock(&ctx->mutex);
        return JNI_FALSE;
    }

    bool success = mCoreSaveStateNamed(ctx->core, vf,
                                       SAVESTATE_SAVEDATA | SAVESTATE_RTC | SAVESTATE_SCREENSHOT | SAVESTATE_METADATA);
    vf->close(vf);
    pthread_mutex_unlock(&ctx->mutex);

    LOGI("Save state to slot %d %s", slot, success ? "succeeded" : "failed");
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_retro_mgba_core_NativeCore_nativeLoadState(JNIEnv *env, jobject thiz, jlong handle, jint slot, jstring stateDir) {
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !stateDir) return JNI_FALSE;

    // Strict slot range validation
    if (slot < 0 || slot > 9) {
        LOGE("Invalid load state slot: %d (must be 0..9)", slot);
        return JNI_FALSE;
    }

    const char *dir = (*env)->GetStringUTFChars(env, stateDir, NULL);
    if (!dir) return JNI_FALSE;

    char statePath[1024];
    snprintf(statePath, sizeof(statePath), "%s/slot_%d.ss", dir, slot);
    (*env)->ReleaseStringUTFChars(env, stateDir, dir);

    pthread_mutex_lock(&ctx->mutex);
    if (!ctx->core) {
        pthread_mutex_unlock(&ctx->mutex);
        return JNI_FALSE;
    }

    struct VFile *vf = VFileOpen(statePath, O_RDONLY);
    if (!vf) {
        LOGE("Failed to open savestate file: %s", statePath);
        pthread_mutex_unlock(&ctx->mutex);
        return JNI_FALSE;
    }

    bool success = mCoreLoadStateNamed(ctx->core, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC | SAVESTATE_SCREENSHOT);
    vf->close(vf);
    pthread_mutex_unlock(&ctx->mutex);

    LOGI("Load state from slot %d %s", slot, success ? "succeeded" : "failed");
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_retro_mgba_core_NativeCore_nativeExtractStateThumbnail(JNIEnv *env, jobject thiz, jlong handle, jstring statePath) {
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !statePath) return NULL;

    const char *path = (*env)->GetStringUTFChars(env, statePath, NULL);
    if (!path) return NULL;

    pthread_mutex_lock(&ctx->mutex);
    if (!ctx->core) {
        pthread_mutex_unlock(&ctx->mutex);
        (*env)->ReleaseStringUTFChars(env, statePath, path);
        return NULL;
    }

    struct VFile *vf = VFileOpen(path, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, statePath, path);
    if (!vf) {
        pthread_mutex_unlock(&ctx->mutex);
        return NULL;
    }

    struct mStateExtdata extdata;
    mStateExtdataInit(&extdata);
    jbyteArray result = NULL;

    if (mCoreExtractExtdata(ctx->core, vf, &extdata)) {
        struct mStateExtdataItem item;
        if (mStateExtdataGet(&extdata, EXTDATA_SCREENSHOT, &item) && item.size > 0 && item.data) {
            result = (*env)->NewByteArray(env, (jsize)item.size);
            if (result) {
                (*env)->SetByteArrayRegion(env, result, 0, (jsize)item.size, (const jbyte *)item.data);
            }
        }
    }

    mStateExtdataDeinit(&extdata);
    vf->close(vf);
    pthread_mutex_unlock(&ctx->mutex);

    return result;
}

/* ========================================================================== */
/* Battery SRAM Persistence                                                   */
/* ========================================================================== */

JNIEXPORT jboolean JNICALL
Java_com_retro_mgba_core_NativeCore_nativeSetSramPath(JNIEnv *env, jobject thiz, jlong handle, jstring sramPath) {
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !sramPath) return JNI_FALSE;

    const char *path = (*env)->GetStringUTFChars(env, sramPath, NULL);
    if (!path) return JNI_FALSE;

    pthread_mutex_lock(&ctx->mutex);
    strncpy(ctx->currentSramPath, path, sizeof(ctx->currentSramPath) - 1);
    ctx->currentSramPath[sizeof(ctx->currentSramPath) - 1] = '\0';

    if (ctx->core) {
        // 1. Officially bind save file into mGBA core for direct in-game battery writes
        bool bound = mCoreLoadSaveFile(ctx->core, path, false);
        LOGI("mCoreLoadSaveFile bound for %s (result=%d)", path, bound);

        // 2. If an existing .sav file is found on disk, explicitly restore it into GBA/GB memory
        if (access(path, F_OK) == 0) {
            FILE *f = fopen(path, "rb");
            if (f) {
                fseek(f, 0, SEEK_END);
                long size = ftell(f);
                fseek(f, 0, SEEK_SET);
                if (size > 0) {
                    void *buf = malloc(size);
                    if (buf) {
                        if (fread(buf, 1, size, f) == (size_t)size) {
                            bool ok = ctx->core->savedataRestore(ctx->core, buf, size, true);
                            LOGI("Restored battery save from %s (%ld bytes, writeback=true, result=%d)", path, size, ok);
                        }
                        free(buf);
                    }
                }
                fclose(f);
            }
        }
    }
    pthread_mutex_unlock(&ctx->mutex);

    (*env)->ReleaseStringUTFChars(env, sramPath, path);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_retro_mgba_core_NativeCore_nativeFlushSram(JNIEnv *env, jobject thiz, jlong handle, jstring sramPath) {
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !sramPath) return JNI_FALSE;

    pthread_mutex_lock(&ctx->mutex);
    if (!ctx->core) {
        pthread_mutex_unlock(&ctx->mutex);
        return JNI_FALSE;
    }

    void *sramData = NULL;
    size_t sramSize = ctx->core->savedataClone(ctx->core, &sramData);
    if (!sramData || sramSize == 0) {
        pthread_mutex_unlock(&ctx->mutex);
        return JNI_FALSE;
    }

    const char *path = (*env)->GetStringUTFChars(env, sramPath, NULL);
    if (!path) {
        free(sramData);
        pthread_mutex_unlock(&ctx->mutex);
        return JNI_FALSE;
    }

    bool success = _atomicWriteFile(path, sramData, sramSize);
    if (success) {
        ctx->sramDirty = false;
    }

    (*env)->ReleaseStringUTFChars(env, sramPath, path);
    free(sramData);
    pthread_mutex_unlock(&ctx->mutex);

    LOGI("Flush SRAM to disk %s (%zu bytes)", success ? "succeeded" : "failed", sramSize);
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_retro_mgba_core_NativeCore_nativeIsSramDirty(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    return (ctx && ctx->sramDirty) ? JNI_TRUE : JNI_FALSE;
}

/* ========================================================================== */
/* Timing & Performance Control                                               */
/* ========================================================================== */

JNIEXPORT void JNICALL
Java_com_retro_mgba_core_NativeCore_nativeSetFrameskip(JNIEnv *env, jobject thiz, jlong handle, jint frameskip) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx) return;

    if (frameskip < 0) frameskip = 0;
    if (frameskip > 10) frameskip = 10;

    pthread_mutex_lock(&ctx->mutex);
    ctx->frameskip = frameskip;
    if (ctx->core) {
        mCoreConfigSetIntValue(&ctx->core->config, "frameskip", frameskip);
        ctx->core->reloadConfigOption(ctx->core, "frameskip", &ctx->core->config);
    }
    pthread_mutex_unlock(&ctx->mutex);

    LOGI("Configured hardware frameskip: %d", frameskip);
}

JNIEXPORT void JNICALL
Java_com_retro_mgba_core_NativeCore_nativeSetFastForward(JNIEnv *env, jobject thiz, jlong handle, jfloat multiplier) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx) return;

    pthread_mutex_lock(&ctx->mutex);
    ctx->fastForwardMultiplier = (multiplier > 0.1f) ? multiplier : 1.0f;
    // When fast-forwarding, automatically mute audio
    if (ctx->fastForwardMultiplier > 1.0f) {
        if (ctx->audio) {
            audioEngineSetMute(ctx->audio, true);
        }
    } else {
        if (ctx->audio) {
            audioEngineSetMute(ctx->audio, ctx->audioMute);
        }
    }
    pthread_mutex_unlock(&ctx->mutex);

    LOGI("Configured fast forward multiplier: %.2fx (audioMute=%d)", ctx->fastForwardMultiplier, ctx->audioMute);
}

/* ========================================================================== */
/* Metadata & Platform Queries                                                */
/* ========================================================================== */

JNIEXPORT jint JNICALL
Java_com_retro_mgba_core_NativeCore_nativeGetPlatform(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !ctx->core) return -1;
    return (jint)ctx->core->platform(ctx->core);
}

JNIEXPORT jstring JNICALL
Java_com_retro_mgba_core_NativeCore_nativeGetGameTitle(JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !ctx->core) return NULL;

    char title[64];
    memset(title, 0, sizeof(title));
    ctx->core->getGameTitle(ctx->core, title);
    return (*env)->NewStringUTF(env, title);
}

JNIEXPORT jstring JNICALL
Java_com_retro_mgba_core_NativeCore_nativeGetGameCode(JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !ctx->core) return NULL;

    char code[16];
    memset(code, 0, sizeof(code));
    ctx->core->getGameCode(ctx->core, code);
    return (*env)->NewStringUTF(env, code);
}
