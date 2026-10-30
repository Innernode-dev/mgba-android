package com.retro.mgba.core

import android.graphics.Bitmap
import android.view.Surface
import java.nio.ByteBuffer

/**
 * JNI Bridge wrapper for the native mGBA emulation core (MPL-2.0).
 * Handles native library loading, core lifecycle, video/audio configuration,
 * ROM loading via paths/SAF/buffers, savestate/SRAM persistence, and performance timing.
 */
class NativeCore {

    /**
     * Native context pointer (points to heap-allocated NativeContext in mgba_jni.c).
     * 0L indicates an uninitialized or destroyed core context.
     */
    var handle: Long = 0L
        private set

    /**
     * Retains direct ByteBuffer reference during active buffer emulation to prevent GC collection.
     */
    private var activeDirectBuffer: ByteBuffer? = null

    /**
     * Allocates and initializes the native emulator core context.
     *
     * @param sampleRate Target audio mixer sample rate in Hz (default: 48000 Hz).
     * @param bufferFrames Audio ring buffer capacity in frames (default: 2048 frames).
     * @return True if context allocation succeeded; false otherwise.
     */
    fun create(sampleRate: Int = 48000, bufferFrames: Int = 2048): Boolean {
        if (handle != 0L) {
            destroy()
        }
        handle = nativeCreate(sampleRate, bufferFrames)
        return handle != 0L
    }

    /**
     * Releases the native core context, unloads any active ROM, and frees native resources.
     */
    fun destroy() {
        activeDirectBuffer = null
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    // --- ROM Lifecycle ---

    fun loadRomPath(path: String): Boolean {
        activeDirectBuffer = null
        return if (handle != 0L) nativeLoadRomPath(handle, path) else false
    }

    fun loadRomFd(fd: Int): Boolean {
        activeDirectBuffer = null
        return if (handle != 0L) nativeLoadRomFd(handle, fd) else false
    }

    fun loadRomBuffer(buffer: ByteBuffer, size: Int): Boolean {
        if (handle == 0L || !buffer.isDirect) return false
        val ok = nativeLoadRomBuffer(handle, buffer, size)
        if (ok) {
            activeDirectBuffer = buffer
        }
        return ok
    }

    fun unloadRom() {
        activeDirectBuffer = null
        if (handle != 0L) nativeUnloadRom(handle)
    }

    fun reset() {
        if (handle != 0L) nativeReset(handle)
    }

    fun runFrame(): Boolean {
        return if (handle != 0L) nativeRunFrame(handle) else false
    }

    // --- Input Subsystem ---

    fun setKeys(keyMask: Int) {
        if (handle != 0L) nativeSetKeys(handle, keyMask)
    }

    // --- Video & Surface Presentation ---

    fun setSurface(surface: Surface?) {
        if (handle != 0L) nativeSetSurface(handle, surface)
    }

    fun setSurfaceSize(width: Int, height: Int) {
        if (handle != 0L) nativeSetSurfaceSize(handle, width, height)
    }

    fun setAspectRatioMode(mode: Int) {
        if (handle != 0L) nativeSetAspectRatioMode(handle, mode)
    }

    fun setShaderFilter(filter: Int) {
        if (handle != 0L) nativeSetShaderFilter(handle, filter)
    }

    fun getVideoWidth(): Int {
        return if (handle != 0L) nativeGetVideoWidth(handle) else 0
    }

    fun getVideoHeight(): Int {
        return if (handle != 0L) nativeGetVideoHeight(handle) else 0
    }

    // --- Audio Control ---

    fun setAudioVolume(volumePercent: Int) {
        if (handle != 0L) nativeSetAudioVolume(handle, volumePercent)
    }

    fun setAudioMute(mute: Boolean) {
        if (handle != 0L) nativeSetAudioMute(handle, mute)
    }

    // --- State & SRAM Persistence ---

    fun saveState(slot: Int, stateDir: String): Boolean {
        require(slot in 0..9) { "Slot must be in range 0..9, got $slot" }
        return if (handle != 0L) nativeSaveState(handle, slot, stateDir) else false
    }

    fun loadState(slot: Int, stateDir: String): Boolean {
        require(slot in 0..9) { "Slot must be in range 0..9, got $slot" }
        return if (handle != 0L) nativeLoadState(handle, slot, stateDir) else false
    }

    fun extractStateThumbnail(statePath: String): ByteArray? {
        return if (handle != 0L) nativeExtractStateThumbnail(handle, statePath) else null
    }

    /**
     * Helper converting raw 32-bit RGBA thumbnail bytes returned by nativeExtractStateThumbnail
     * into an Android Bitmap for DuckStation preview cards.
     */
    fun extractStateThumbnailBitmap(statePath: String): Bitmap? {
        val bytes = extractStateThumbnail(statePath) ?: return null
        val (width, height) = when (bytes.size) {
            240 * 160 * 4 -> 240 to 160
            160 * 144 * 4 -> 160 to 144
            else -> return null
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        return bitmap
    }

    fun setSramPath(sramPath: String): Boolean {
        return if (handle != 0L) nativeSetSramPath(handle, sramPath) else false
    }

    fun flushSram(sramPath: String): Boolean {
        return if (handle != 0L) nativeFlushSram(handle, sramPath) else false
    }

    fun isSramDirty(): Boolean {
        return if (handle != 0L) nativeIsSramDirty(handle) else false
    }

    // --- Performance & Timing ---

    fun setFrameskip(frameskip: Int) {
        if (handle != 0L) nativeSetFrameskip(handle, frameskip.coerceIn(0, 10))
    }

    fun setFastForward(multiplier: Float) {
        if (handle != 0L) nativeSetFastForward(handle, multiplier)
    }

    // --- Metadata Queries ---

    fun getPlatform(): Int {
        return if (handle != 0L) nativeGetPlatform(handle) else -1
    }

    fun getGameTitle(): String? {
        return if (handle != 0L) nativeGetGameTitle(handle) else null
    }

    fun getGameCode(): String? {
        return if (handle != 0L) nativeGetGameCode(handle) else null
    }

    // --- External Native Declarations (Direct JNI Bindings) ---

    external fun nativeCreate(sampleRate: Int, bufferFrames: Int): Long
    external fun nativeDestroy(handle: Long)

    external fun nativeLoadRomPath(handle: Long, path: String): Boolean
    external fun nativeLoadRomFd(handle: Long, fd: Int): Boolean
    external fun nativeLoadRomBuffer(handle: Long, buffer: ByteBuffer, size: Int): Boolean
    external fun nativeUnloadRom(handle: Long)
    external fun nativeReset(handle: Long)

    external fun nativeRunFrame(handle: Long): Boolean
    external fun nativeSetKeys(handle: Long, keyMask: Int)

    external fun nativeSetSurface(handle: Long, surface: Surface?)
    external fun nativeSetSurfaceSize(handle: Long, width: Int, height: Int)
    external fun nativeSetAspectRatioMode(handle: Long, mode: Int)
    external fun nativeSetShaderFilter(handle: Long, filter: Int)
    external fun nativeGetVideoWidth(handle: Long): Int
    external fun nativeGetVideoHeight(handle: Long): Int

    external fun nativeSetAudioVolume(handle: Long, volumePercent: Int)
    external fun nativeSetAudioMute(handle: Long, mute: Boolean)

    external fun nativeSaveState(handle: Long, slot: Int, stateDir: String): Boolean
    external fun nativeLoadState(handle: Long, slot: Int, stateDir: String): Boolean
    external fun nativeExtractStateThumbnail(handle: Long, statePath: String): ByteArray?
    external fun nativeSetSramPath(handle: Long, sramPath: String): Boolean
    external fun nativeFlushSram(handle: Long, sramPath: String): Boolean
    external fun nativeIsSramDirty(handle: Long): Boolean

    external fun nativeSetFrameskip(handle: Long, frameskip: Int)
    external fun nativeSetFastForward(handle: Long, multiplier: Float)

    external fun nativeGetPlatform(handle: Long): Int
    external fun nativeGetGameTitle(handle: Long): String?
    external fun nativeGetGameCode(handle: Long): String?

    companion object {
        // GBA Hardware Key Mask Constants
        const val KEY_A = 1 shl 0
        const val KEY_B = 1 shl 1
        const val KEY_SELECT = 1 shl 2
        const val KEY_START = 1 shl 3
        const val KEY_RIGHT = 1 shl 4
        const val KEY_LEFT = 1 shl 5
        const val KEY_UP = 1 shl 6
        const val KEY_DOWN = 1 shl 7
        const val KEY_R = 1 shl 8
        const val KEY_L = 1 shl 9

        // Platform Identifiers
        const val PLATFORM_GBA = 0
        const val PLATFORM_GB = 1

        // Aspect Ratio Modes
        const val ASPECT_RATIO_NATIVE = 0  // 3:2 (GBA) or 10:9 (GB/GBC)
        const val ASPECT_RATIO_INTEGER = 1 // Pixel-perfect integer scale
        const val ASPECT_RATIO_STRETCH = 2 // Fullscreen stretched

        // Scaling Filters / Shaders
        const val FILTER_NEAREST = 0
        const val FILTER_BILINEAR = 1
        const val FILTER_LCD_GRID = 2

        init {
            // Load upstream mGBA core shared library first, followed by JNI bridge
            System.loadLibrary("mgba")
            System.loadLibrary("mgba_jni")
        }

        /**
         * Returns the compiled upstream mGBA core version (e.g. "0.10.5").
         * Directly verifies that the native libraries are loaded and linked.
         */
        @JvmStatic
        external fun nativeGetVersion(): String

        /**
         * Alias for nativeGetVersion().
         */
        @JvmStatic
        external fun nativeGetCoreVersion(): String
    }
}
