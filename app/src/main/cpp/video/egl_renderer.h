#ifndef RETRO_VIDEO_EGL_RENDERER_H
#define RETRO_VIDEO_EGL_RENDERER_H

#include <stdint.h>
#include <stdbool.h>
#include <android/native_window.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct EGLRenderer EGLRenderer;

/**
 * Creates and initializes a new EGLRenderer instance.
 * Attempts OpenGL ES 3.0 context creation with automatic fallback to OpenGL ES 2.0.
 * Returns NULL on critical EGL initialization failure.
 */
EGLRenderer* egl_renderer_create(void);

/**
 * Destroys the EGLRenderer instance and frees all GPU and EGL resources.
 */
void egl_renderer_destroy(EGLRenderer* renderer);

/**
 * Binds a new ANativeWindow surface or unbinds the current surface (if window is NULL).
 * Safe to call from any thread (deferred binding on render thread).
 */
void egl_renderer_set_surface(EGLRenderer* renderer, ANativeWindow* window);

/**
 * Updates the surface dimensions in physical pixels.
 */
void egl_renderer_set_surface_size(EGLRenderer* renderer, int width, int height);

/**
 * Configures the aspect ratio scaling mode:
 * 0: Native Aspect Ratio (3:2 for GBA, 10:9 for GB/GBC pillarboxed/letterboxed)
 * 1: Integer Scaling (pixel-perfect integer multiplier)
 * 2: Fullscreen Stretch
 */
void egl_renderer_set_aspect_ratio_mode(EGLRenderer* renderer, int mode);

/**
 * Configures the video shader filter:
 * 0: Nearest Neighbor (GL_NEAREST)
 * 1: Bilinear (GL_LINEAR)
 * 2: Authentic LCD-Grid shader
 */
void egl_renderer_set_shader_filter(EGLRenderer* renderer, int filter);

/**
 * Uploads the active emulation frame and presents it to the bound surface.
 * Must be called from the emulation/render thread.
 *
 * @param renderer EGLRenderer pointer
 * @param pixelBuffer Pointer to 32-bit RGBA pixel array
 * @param gameWidth Active width (e.g. 240 for GBA, 160 for GB)
 * @param gameHeight Active height (e.g. 160 for GBA, 144 for GB)
 * @param stride Memory stride in pixels (typically 256)
 */
void egl_renderer_draw_frame(EGLRenderer* renderer, const uint32_t* pixelBuffer, int gameWidth, int gameHeight, int stride);

#ifdef __cplusplus
}
#endif

#endif // RETRO_VIDEO_EGL_RENDERER_H
