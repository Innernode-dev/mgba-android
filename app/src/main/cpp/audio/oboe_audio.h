/*
 * Low-Latency Audio Subsystem Interface (Oboe / AAudio)
 * Part of mGBA Android Emulator Subsystem
 * Licensed under the Mozilla Public License, v. 2.0 (MPL-2.0).
 */

#ifndef RETRO_AUDIO_OBOE_AUDIO_H
#define RETRO_AUDIO_OBOE_AUDIO_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

#ifdef __cplusplus
#include <cstdint>
#include <cstddef>
#include <memory>
#include "ring_buffer.h"

namespace retro {
namespace audio {

class AudioEngine {
public:
    AudioEngine();
    ~AudioEngine();

    /**
     * Start the low-latency audio stream.
     * @param sampleRate Target sample rate in Hz (default 48000).
     * @param bufferFrames Ring buffer capacity in frames (default 4096).
     * @return true if stream opened and started successfully.
     */
    bool start(int sampleRate = 48000, int bufferFrames = 4096);

    /** Stop and tear down audio stream. */
    void stop();

    /** Pause stream playback without tearing down buffer. */
    void pause();

    /** Resume playback after pause. */
    void resume();

    /**
     * Enqueue stereo PCM frames from emulation loop.
     * @param interleavedStereo 16-bit interleaved stereo samples [L, R, L, R, ...].
     * @param frameCount Number of stereo frames.
     * @return Number of frames actually queued.
     */
    size_t writeFrames(const int16_t* interleavedStereo, size_t frameCount);

    /** Set master volume level (0 to 100%). Clamped strictly to [0, 100]. */
    void setVolume(int volumePercent);

    /** Get current volume level (0 to 100%). */
    int getVolume() const;

    /** Toggle audio mute state. */
    void setMute(bool mute);

    /** Query audio mute state. */
    bool isMuted() const;

    /** Get current number of buffered frames ready to play. */
    size_t getBufferedFrames() const;

    /** Get total ring buffer capacity in frames. */
    size_t getCapacityFrames() const;

    /** Clear ring buffer contents and underrun counters. */
    void reset();

    /** Query total underrun occurrences. */
    uint64_t getUnderrunCount() const;

private:
    class Impl;
    std::unique_ptr<Impl> m_impl;
};

} // namespace audio
} // namespace retro
#endif // __cplusplus

/* ========================================================================== */
/* C-Compatible ABI Exports for mgba_jni.c                                    */
/* ========================================================================== */
#ifdef __cplusplus
extern "C" {
#endif

typedef void* AudioEngineHandle;

AudioEngineHandle audioEngineCreate(int sampleRate, int bufferFrames);
void audioEngineDestroy(AudioEngineHandle handle);
bool audioEngineStart(AudioEngineHandle handle);
void audioEngineStop(AudioEngineHandle handle);
void audioEnginePause(AudioEngineHandle handle);
void audioEngineResume(AudioEngineHandle handle);
size_t audioEngineWriteFrames(AudioEngineHandle handle, const int16_t* interleaved, size_t frames);
void audioEngineSetVolume(AudioEngineHandle handle, int volumePercent);
int audioEngineGetVolume(AudioEngineHandle handle);
void audioEngineSetMute(AudioEngineHandle handle, bool mute);
bool audioEngineIsMuted(AudioEngineHandle handle);
size_t audioEngineGetBufferedFrames(AudioEngineHandle handle);
void audioEngineReset(AudioEngineHandle handle);

#ifdef __cplusplus
}
#endif

#endif // RETRO_AUDIO_OBOE_AUDIO_H
