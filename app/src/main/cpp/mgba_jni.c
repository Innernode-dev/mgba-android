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
#include <stdatomic.h>

#include <mgba/core/core.h>
#include <mgba/core/config.h>
#include <mgba/core/version.h>
#include <mgba/core/serialize.h>
#include <mgba/core/interface.h>
#include <mgba/core/blip_buf.h>
#include <mgba/core/cheats.h>
#include <mgba/gba/interface.h>
#include <mgba/internal/gba/cheats.h>
#include <mgba/internal/gb/cheats.h>
#include <mgba-util/vfs.h>

#include "video/egl_renderer.h"
#include "audio/oboe_audio.h"
#include "net/gba_link_bridge.h"

#define LOG_TAG "mGBA_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#define MAX_VIDEO_WIDTH 256
#define MAX_VIDEO_HEIGHT 256
#define SRAM_DEBOUNCE_INTERVAL_MS 1000
#define REWIND_MAX_SLOTS 90
#define REWIND_CAPTURE_INTERVAL 6

typedef struct NativeContext NativeContext;

struct AppRotationSource {
    struct mRotationSource d;
    NativeContext *ctx;
};

struct AppLuminanceSource {
    struct GBALuminanceSource d;
    NativeContext *ctx;
};

struct AppRumble {
    struct mRumble d;
    NativeContext *ctx;
};

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
    GbaLinkBridge *linkBridge;
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

    // Rewind RAM ring buffer (bounded to REWIND_MAX_SLOTS snapshots)
    bool rewindEnabled;
    struct VFile *rewindSlots[REWIND_MAX_SLOTS];
    int rewindHead;  // index of next write slot
    int rewindCount; // current valid snapshot count (0..REWIND_MAX_SLOTS)

    // Hardware peripherals (Gyro/Tilt, Boktai Solar Sensor, Cartridge Rumble)
    struct AppRotationSource rotationSource;
    struct AppLuminanceSource luminanceSource;
    struct AppRumble rumble;
    atomic_int_fast32_t sensorTiltX;
    atomic_int_fast32_t sensorTiltY;
    atomic_int_fast32_t sensorGyroZ;
    atomic_uint_fast8_t solarLuminance;
    atomic_bool rumbleActive;
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

static void _rotSample(struct mRotationSource *source) { (void)source; }

static int32_t _rotReadTiltX(struct mRotationSource *source) {
    struct AppRotationSource *app = (struct AppRotationSource *)source;
    return app && app->ctx ? (int32_t)atomic_load(&app->ctx->sensorTiltX) : 0;
}

static int32_t _rotReadTiltY(struct mRotationSource *source) {
    struct AppRotationSource *app = (struct AppRotationSource *)source;
    return app && app->ctx ? (int32_t)atomic_load(&app->ctx->sensorTiltY) : 0;
}

static int32_t _rotReadGyroZ(struct mRotationSource *source) {
    struct AppRotationSource *app = (struct AppRotationSource *)source;
    return app && app->ctx ? (int32_t)atomic_load(&app->ctx->sensorGyroZ) : 0;
}

static void _lumSample(struct GBALuminanceSource *source) { (void)source; }

static uint8_t _lumRead(struct GBALuminanceSource *source) {
    struct AppLuminanceSource *app = (struct AppLuminanceSource *)source;
    return app && app->ctx ? (uint8_t)atomic_load(&app->ctx->solarLuminance) : 0xFF;
}

static void _rumbleSet(struct mRumble *rumble, int enable) {
    struct AppRumble *app = (struct AppRumble *)rumble;
    if (app && app->ctx) {
        atomic_store(&app->ctx->rumbleActive, enable != 0);
    }
}

static void _clearRewindBufferLocked(NativeContext *ctx) {
    if (!ctx) return;
    for (int i = 0; i < REWIND_MAX_SLOTS; ++i) {
        if (ctx->rewindSlots[i]) {
            ctx->rewindSlots[i]->close(ctx->rewindSlots[i]);
            ctx->rewindSlots[i] = NULL;
        }
    }
    ctx->rewindHead = 0;
    ctx->rewindCount = 0;
}

static void _captureRewindSnapshotLocked(NativeContext *ctx) {
    if (!ctx || !ctx->core || !ctx->rewindEnabled) return;
    struct VFile *vf = VFileMemChunk(NULL, 0);
    if (!vf) return;

    if (mCoreSaveStateNamed(ctx->core, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC)) {
        if (ctx->rewindSlots[ctx->rewindHead]) {
            ctx->rewindSlots[ctx->rewindHead]->close(ctx->rewindSlots[ctx->rewindHead]);
        }
        ctx->rewindSlots[ctx->rewindHead] = vf;
        ctx->rewindHead = (ctx->rewindHead + 1) % REWIND_MAX_SLOTS;
        if (ctx->rewindCount < REWIND_MAX_SLOTS) {
            ctx->rewindCount++;
        }
    } else {
        vf->close(vf);
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

    // 1. Detach Link Cable network bridge and free rewind RAM buffer
    if (ctx->linkBridge) {
        gba_link_bridge_detach_core(ctx->linkBridge);
    }
    _clearRewindBufferLocked(ctx);
    atomic_store(&ctx->rumbleActive, false);

    // 2. Flush SRAM if path is bound
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

    // Register hardware peripherals (Gyro/Tilt, Cartridge Rumble, GBA Boktai Solar Sensor)
    ctx->rotationSource.d.sample = _rotSample;
    ctx->rotationSource.d.readTiltX = _rotReadTiltX;
    ctx->rotationSource.d.readTiltY = _rotReadTiltY;
    ctx->rotationSource.d.readGyroZ = _rotReadGyroZ;
    ctx->rotationSource.ctx = ctx;
    core->setPeripheral(core, mPERIPH_ROTATION, &ctx->rotationSource.d);

    ctx->rumble.d.setRumble = _rumbleSet;
    ctx->rumble.ctx = ctx;
    core->setPeripheral(core, mPERIPH_RUMBLE, &ctx->rumble.d);

    if (core->platform(core) == mPLATFORM_GBA) {
        ctx->luminanceSource.d.sample = _lumSample;
        ctx->luminanceSource.d.readLuminance = _lumRead;
        ctx->luminanceSource.ctx = ctx;
        core->setPeripheral(core, mPERIPH_GBA_LUMINANCE, &ctx->luminanceSource.d);
    }

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
    atomic_init(&ctx->sensorTiltX, 0);
    atomic_init(&ctx->sensorTiltY, 0);
    atomic_init(&ctx->sensorGyroZ, 0);
    atomic_init(&ctx->solarLuminance, 0xFF);
    atomic_init(&ctx->rumbleActive, false);

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

    ctx->linkBridge = gba_link_bridge_create();
    if (!ctx->linkBridge) {
        LOGE("Failed to initialize GbaLinkBridge");
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

    if (ctx->linkBridge) {
        gba_link_bridge_signal_stop(ctx->linkBridge);
    }

    pthread_mutex_lock(&ctx->mutex);
    cleanupActiveCore(ctx, env);

    if (ctx->linkBridge) {
        gba_link_bridge_destroy(ctx->linkBridge);
        ctx->linkBridge = NULL;
    }

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

    // 1b. Periodic RAM snapshot for Rewind when enabled
    if (ctx->rewindEnabled && (ctx->frameCounter % REWIND_CAPTURE_INTERVAL) == 0) {
        _captureRewindSnapshotLocked(ctx);
    }

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

/* ========================================================================== */
/* Cheats Subsystem (GameShark, Action Replay, CodeBreaker, Game Genie, Raw)  */
/* ========================================================================== */

static void _clearAllCheatsSafe(struct mCheatDevice *device) {
    if (!device) return;
    while (mCheatSetsSize(&device->cheats) > 0) {
        size_t last = mCheatSetsSize(&device->cheats) - 1;
        struct mCheatSet *set = *mCheatSetsGetPointer(&device->cheats, last);
        if (set) {
            set->enabled = false;
            mCheatRefresh(device, set);
            mCheatRemoveSet(device, set);
            mCheatSetDeinit(set);
        } else {
            mCheatSetsShift(&device->cheats, last, 1);
        }
    }
}

JNIEXPORT void JNICALL
Java_com_retro_mgba_core_NativeCore_nativeClearCheats(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx) return;

    pthread_mutex_lock(&ctx->mutex);
    if (ctx->core && ctx->core->cheatDevice) {
        struct mCheatDevice *device = ctx->core->cheatDevice(ctx->core);
        _clearAllCheatsSafe(device);
    }
    pthread_mutex_unlock(&ctx->mutex);
}

JNIEXPORT jboolean JNICALL
Java_com_retro_mgba_core_NativeCore_nativeAddCheatSet(
    JNIEnv *env,
    jobject thiz,
    jlong handle,
    jstring name,
    jobjectArray lines,
    jint type,
    jboolean enabled
) {
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !lines) return JNI_FALSE;

    jsize lineCount = (*env)->GetArrayLength(env, lines);
    if (lineCount <= 0) return JNI_FALSE;

    pthread_mutex_lock(&ctx->mutex);
    if (!ctx->core || !ctx->core->cheatDevice) {
        pthread_mutex_unlock(&ctx->mutex);
        return JNI_FALSE;
    }

    struct mCheatDevice *device = ctx->core->cheatDevice(ctx->core);
    if (!device || !device->createSet) {
        pthread_mutex_unlock(&ctx->mutex);
        return JNI_FALSE;
    }

    const char *cname = name ? (*env)->GetStringUTFChars(env, name, NULL) : NULL;
    struct mCheatSet *set = device->createSet(device, cname ? cname : "Cheat");
    if (cname) {
        (*env)->ReleaseStringUTFChars(env, name, cname);
    }
    if (!set) {
        pthread_mutex_unlock(&ctx->mutex);
        return JNI_FALSE;
    }

    // Propagate GBA Master Code / encryption state from previous cheat set if present
    size_t existingCount = mCheatSetsSize(&device->cheats);
    if (existingCount > 0 && set->copyProperties) {
        struct mCheatSet *prev = *mCheatSetsGetPointer(&device->cheats, existingCount - 1);
        if (prev) {
            set->copyProperties(set, prev);
        }
    }

    int addedLines = 0;
    bool allValid = true;
    for (jsize i = 0; i < lineCount; ++i) {
        jstring jline = (jstring)(*env)->GetObjectArrayElement(env, lines, i);
        if (!jline) continue;
        const char *cline = (*env)->GetStringUTFChars(env, jline, NULL);
        if (cline) {
            if (cline[0] != '\0') {
                if (mCheatAddLine(set, cline, (int)type)) {
                    addedLines++;
                } else {
                    LOGW("Rejected invalid cheat line: %s (type=%d)", cline, (int)type);
                    allValid = false;
                }
            }
            (*env)->ReleaseStringUTFChars(env, jline, cline);
        }
        (*env)->DeleteLocalRef(env, jline);
        if (!allValid) break;
    }

    if (!allValid || addedLines == 0) {
        mCheatSetDeinit(set);
        pthread_mutex_unlock(&ctx->mutex);
        return JNI_FALSE;
    }

    set->enabled = (bool)enabled;
    mCheatAddSet(device, set);
    mCheatRefresh(device, set);
    pthread_mutex_unlock(&ctx->mutex);

    LOGI("Applied cheat set (%d lines, type=%d, enabled=%d)", addedLines, (int)type, (int)enabled);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_retro_mgba_core_NativeCore_nativeValidateCheatLines(
    JNIEnv *env,
    jclass clazz,
    jint platform,
    jobjectArray lines,
    jint type
) {
    (void)clazz;
    if (!lines) return JNI_FALSE;

    jsize lineCount = (*env)->GetArrayLength(env, lines);
    if (lineCount <= 0) return JNI_FALSE;

    struct mCheatDevice *device = NULL;
    if (platform == mPLATFORM_GB) {
        device = GBCheatDeviceCreate();
    } else {
        device = GBACheatDeviceCreate();
    }
    if (!device || !device->createSet) {
        if (device) mCheatDeviceDestroy(device);
        return JNI_FALSE;
    }

    struct mCheatSet *set = device->createSet(device, "Validate");
    if (!set) {
        mCheatDeviceDestroy(device);
        return JNI_FALSE;
    }

    int addedLines = 0;
    bool allValid = true;
    for (jsize i = 0; i < lineCount; ++i) {
        jstring jline = (jstring)(*env)->GetObjectArrayElement(env, lines, i);
        if (!jline) continue;
        const char *cline = (*env)->GetStringUTFChars(env, jline, NULL);
        if (cline) {
            if (cline[0] != '\0') {
                if (mCheatAddLine(set, cline, (int)type)) {
                    addedLines++;
                } else {
                    allValid = false;
                }
            }
            (*env)->ReleaseStringUTFChars(env, jline, cline);
        }
        (*env)->DeleteLocalRef(env, jline);
        if (!allValid) break;
    }

    mCheatSetDeinit(set);
    mCheatDeviceDestroy(device);
    return (allValid && addedLines > 0) ? JNI_TRUE : JNI_FALSE;
}

/* ========================================================================== */
/* GBA Link Cable TCP/IP Network Bridge JNI Exports                           */
/* ========================================================================== */

JNIEXPORT jboolean JNICALL
Java_com_retro_mgba_core_NativeCore_nativeLinkStartHost(JNIEnv *env, jobject thiz, jlong handle, jint port) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !ctx->linkBridge) return JNI_FALSE;

    gba_link_bridge_signal_stop(ctx->linkBridge);
    pthread_mutex_lock(&ctx->mutex);
    bool ok = false;
    if (ctx->core) {
        ok = gba_link_bridge_start_host(ctx->linkBridge, ctx->core, (int)port);
    }
    pthread_mutex_unlock(&ctx->mutex);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_retro_mgba_core_NativeCore_nativeLinkConnect(JNIEnv *env, jobject thiz, jlong handle, jstring host, jint port) {
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !ctx->linkBridge || !host) return JNI_FALSE;

    const char *chost = (*env)->GetStringUTFChars(env, host, NULL);
    if (!chost) return JNI_FALSE;

    gba_link_bridge_signal_stop(ctx->linkBridge);
    pthread_mutex_lock(&ctx->mutex);
    bool ok = false;
    if (ctx->core) {
        ok = gba_link_bridge_connect(ctx->linkBridge, ctx->core, chost, (int)port);
    }
    pthread_mutex_unlock(&ctx->mutex);

    (*env)->ReleaseStringUTFChars(env, host, chost);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_retro_mgba_core_NativeCore_nativeLinkDisconnect(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !ctx->linkBridge) return;

    gba_link_bridge_signal_stop(ctx->linkBridge);
    pthread_mutex_lock(&ctx->mutex);
    gba_link_bridge_disconnect(ctx->linkBridge);
    pthread_mutex_unlock(&ctx->mutex);
}

JNIEXPORT jint JNICALL
Java_com_retro_mgba_core_NativeCore_nativeLinkGetState(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !ctx->linkBridge) return 0;
    return (jint)gba_link_bridge_get_state(ctx->linkBridge);
}

JNIEXPORT jint JNICALL
Java_com_retro_mgba_core_NativeCore_nativeLinkGetPlayerId(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !ctx->linkBridge) return -1;
    return (jint)gba_link_bridge_get_player_id(ctx->linkBridge);
}

JNIEXPORT jint JNICALL
Java_com_retro_mgba_core_NativeCore_nativeLinkGetPlayerCount(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !ctx->linkBridge) return 1;
    return (jint)gba_link_bridge_get_player_count(ctx->linkBridge);
}

JNIEXPORT jint JNICALL
Java_com_retro_mgba_core_NativeCore_nativeLinkGetPingMs(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !ctx->linkBridge) return -1;
    return (jint)gba_link_bridge_get_ping_ms(ctx->linkBridge);
}

JNIEXPORT jboolean JNICALL
Java_com_retro_mgba_core_NativeCore_nativeLinkIsHost(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !ctx->linkBridge) return JNI_FALSE;
    return gba_link_bridge_is_host(ctx->linkBridge) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_retro_mgba_core_NativeCore_nativeLinkGetLastError(JNIEnv *env, jobject thiz, jlong handle) {
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !ctx->linkBridge) return NULL;
    char buf[160];
    gba_link_bridge_get_error(ctx->linkBridge, buf, sizeof(buf));
    if (buf[0] == '\0') return NULL;
    return (*env)->NewStringUTF(env, buf);
}

/* ========================================================================== */
/* Rewind, Soft ROM Patching (IPS/UPS/BPS), and Hardware Sensors              */
/* ========================================================================== */

JNIEXPORT void JNICALL
Java_com_retro_mgba_core_NativeCore_nativeSetRewindEnabled(JNIEnv *env, jobject thiz, jlong handle, jboolean enabled) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx) return;

    pthread_mutex_lock(&ctx->mutex);
    ctx->rewindEnabled = (bool)enabled;
    if (!ctx->rewindEnabled) {
        _clearRewindBufferLocked(ctx);
    }
    pthread_mutex_unlock(&ctx->mutex);
}

JNIEXPORT jboolean JNICALL
Java_com_retro_mgba_core_NativeCore_nativeRewindStep(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx) return JNI_FALSE;

    pthread_mutex_lock(&ctx->mutex);
    if (!ctx->core || ctx->rewindCount <= 0) {
        pthread_mutex_unlock(&ctx->mutex);
        return JNI_FALSE;
    }

    ctx->rewindHead = (ctx->rewindHead - 1 + REWIND_MAX_SLOTS) % REWIND_MAX_SLOTS;
    struct VFile *vf = ctx->rewindSlots[ctx->rewindHead];
    ctx->rewindSlots[ctx->rewindHead] = NULL;
    ctx->rewindCount--;

    bool ok = false;
    if (vf) {
        vf->seek(vf, 0, SEEK_SET);
        ok = mCoreLoadStateNamed(ctx->core, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC);
        vf->close(vf);
    }

    if (ok) {
        if (ctx->audio) {
            audioEngineReset(ctx->audio);
        }
        // Step one frame so the video buffer reflects the rewound state immediately
        ctx->core->runFrame(ctx->core);
        if (ctx->renderer) {
            unsigned w = 0, h = 0;
            ctx->core->desiredVideoDimensions(ctx->core, &w, &h);
            if (w == 0 || h == 0) { w = 240; h = 160; }
            egl_renderer_draw_frame(ctx->renderer, ctx->videoBuffer, (int)w, (int)h, MAX_VIDEO_WIDTH);
        }
    }

    pthread_mutex_unlock(&ctx->mutex);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_retro_mgba_core_NativeCore_nativeLoadPatchPath(JNIEnv *env, jobject thiz, jlong handle, jstring patchPath) {
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx || !patchPath) return JNI_FALSE;

    const char *cpath = (*env)->GetStringUTFChars(env, patchPath, NULL);
    if (!cpath) return JNI_FALSE;

    struct VFile *vf = VFileOpen(cpath, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, patchPath, cpath);
    if (!vf) {
        LOGE("Failed to open patch file");
        return JNI_FALSE;
    }

    pthread_mutex_lock(&ctx->mutex);
    bool ok = false;
    if (ctx->core && ctx->core->loadPatch) {
        ok = ctx->core->loadPatch(ctx->core, vf);
        if (ok) {
            _clearRewindBufferLocked(ctx);
        }
    }
    vf->close(vf);
    pthread_mutex_unlock(&ctx->mutex);

    LOGI("Applied ROM patch: result=%d", (int)ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_retro_mgba_core_NativeCore_nativeSetSensorRotation(JNIEnv *env, jobject thiz, jlong handle, jint tiltX, jint tiltY, jint gyroZ) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx) return;
    atomic_store(&ctx->sensorTiltX, (int32_t)tiltX);
    atomic_store(&ctx->sensorTiltY, (int32_t)tiltY);
    atomic_store(&ctx->sensorGyroZ, (int32_t)gyroZ);
}

JNIEXPORT void JNICALL
Java_com_retro_mgba_core_NativeCore_nativeSetSolarLevel(JNIEnv *env, jobject thiz, jlong handle, jint level) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx) return;

    uint8_t lum = 0xFF;
    if (level >= 1 && level <= 10) {
        int val = 0xFF - GBA_LUX_LEVELS[level - 1];
        lum = (uint8_t)(val < 0 ? 0 : val);
    }
    atomic_store(&ctx->solarLuminance, lum);
}

JNIEXPORT jboolean JNICALL
Java_com_retro_mgba_core_NativeCore_nativeIsRumbleActive(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    NativeContext *ctx = (NativeContext *)(intptr_t)handle;
    if (!ctx) return JNI_FALSE;
    return atomic_load(&ctx->rumbleActive) ? JNI_TRUE : JNI_FALSE;
}
