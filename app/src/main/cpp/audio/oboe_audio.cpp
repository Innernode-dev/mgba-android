/*
 * Low-Latency Audio Subsystem Implementation (Oboe / AAudio Backend)
 * Part of mGBA Android Emulator Subsystem
 * Licensed under the Mozilla Public License, v. 2.0 (MPL-2.0).
 */

#include "oboe_audio.h"
#include <android/log.h>
#include <atomic>
#include <algorithm>
#include <thread>
#include <mutex>

#define LOG_TAG "mGBA_Audio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#if defined(USE_OBOE) && __has_include(<oboe/Oboe.h>)
#include <oboe/Oboe.h>
#define HAS_OBOE_BACKEND 1
#else
#include <aaudio/AAudio.h>
#define HAS_OBOE_BACKEND 0
#endif

namespace retro {
namespace audio {

#if HAS_OBOE_BACKEND
// ============================================================================
// Google Oboe Implementation Backend
// ============================================================================
class AudioEngine::Impl : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    Impl() : m_stream(nullptr), m_volume(100), m_muted(false), m_underrunCount(0) {}

    ~Impl() {
        stop();
    }

    bool start(int sampleRate, int bufferFrames) {
        (void)bufferFrames;
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_stream) {
            return true;
        }

        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
               ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
               ->setSharingMode(oboe::SharingMode::Exclusive)
               ->setFormat(oboe::AudioFormat::I16)
               ->setChannelCount(oboe::ChannelCount::Stereo)
               ->setSampleRate(sampleRate > 0 ? sampleRate : 48000)
               ->setDataCallback(this)
               ->setErrorCallback(this);

        oboe::Result result = builder.openStream(m_stream);
        if (result != oboe::Result::OK) {
            LOGW("Failed to open exclusive Oboe stream (%s), trying Shared mode...", oboe::convertToText(result));
            builder.setSharingMode(oboe::SharingMode::Shared);
            result = builder.openStream(m_stream);
            if (result != oboe::Result::OK) {
                LOGE("Failed to open Shared Oboe stream: %s", oboe::convertToText(result));
                m_stream = nullptr;
                return false;
            }
        }

        result = m_stream->requestStart();
        if (result != oboe::Result::OK) {
            LOGE("Failed to start Oboe stream: %s", oboe::convertToText(result));
            m_stream->close();
            m_stream = nullptr;
            return false;
        }

        LOGI("Oboe audio stream started: rate=%d, framesPerBurst=%d, bufferCapacity=%d",
             m_stream->getSampleRate(), m_stream->getFramesPerBurst(), m_stream->getBufferCapacityInFrames());
        return true;
    }

    void stop() {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_stream) {
            m_stream->stop();
            m_stream->close();
            m_stream = nullptr;
        }
        m_ringBuffer.clear();
    }

    void pause() {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_stream) {
            m_stream->requestPause();
        }
    }

    void resume() {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_stream) {
            m_stream->requestStart();
        }
    }

    size_t writeFrames(const int16_t* frames, size_t count) {
        return m_ringBuffer.writeLossy(frames, count);
    }

    void setVolume(int volumePercent) {
        m_volume.store(std::max(0, std::min(100, volumePercent)), std::memory_order_relaxed);
    }

    int getVolume() const {
        return m_volume.load(std::memory_order_relaxed);
    }

    void setMute(bool mute) {
        m_muted.store(mute, std::memory_order_relaxed);
    }

    bool isMuted() const {
        return m_muted.load(std::memory_order_relaxed);
    }

    size_t getBufferedFrames() const {
        return m_ringBuffer.availableRead();
    }

    size_t getCapacityFrames() const {
        return m_ringBuffer.capacity();
    }

    void reset() {
        m_ringBuffer.clear();
        m_underrunCount.store(0, std::memory_order_relaxed);
    }

    uint64_t getUnderrunCount() const {
        return m_underrunCount.load(std::memory_order_relaxed);
    }

    // Oboe Audio Data Callback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override {
        int16_t* output = static_cast<int16_t*>(audioData);
        size_t framesRead = m_ringBuffer.read(output, static_cast<size_t>(numFrames));

        if (framesRead < static_cast<size_t>(numFrames)) {
            m_underrunCount.fetch_add(1, std::memory_order_relaxed);
        }

        // Apply volume attenuation or mute
        const bool muted = m_muted.load(std::memory_order_relaxed);
        const int vol = m_volume.load(std::memory_order_relaxed);

        if (muted) {
            std::memset(output, 0, numFrames * 2 * sizeof(int16_t));
        } else if (vol < 100) {
            const int32_t totalSamples = numFrames * 2;
            for (int32_t i = 0; i < totalSamples; ++i) {
                int32_t scaled = (static_cast<int32_t>(output[i]) * vol) / 100;
                output[i] = static_cast<int16_t>(std::max(-32768, std::min(32767, scaled)));
            }
        }

        return oboe::DataCallbackResult::Continue;
    }

    // Oboe Audio Error Callback (Headphone disconnect, route change)
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override {
        LOGW("Oboe stream closed unexpectedly (%s), attempting automatic restart...", oboe::convertToText(error));
        std::thread([this]() {
            stop();
            start(48000, 4096);
        }).detach();
    }

private:
    std::shared_ptr<oboe::AudioStream> m_stream;
    std::mutex m_mutex;
    SpscRingBuffer<4096> m_ringBuffer;
    std::atomic<int> m_volume;
    std::atomic<bool> m_muted;
    std::atomic<uint64_t> m_underrunCount;
};

#else
// ============================================================================
// Native Android AAudio C API Backend (API 26+, Zero External Dependencies)
// ============================================================================
class AudioEngine::Impl {
public:
    Impl() : m_stream(nullptr), m_volume(100), m_muted(false), m_underrunCount(0) {}

    ~Impl() {
        stop();
    }

    bool start(int sampleRate, int bufferFrames) {
        (void)bufferFrames;
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_stream) {
            return true;
        }

        AAudioStreamBuilder* builder = nullptr;
        aaudio_result_t result = AAudio_createStreamBuilder(&builder);
        if (result != AAUDIO_OK) {
            LOGE("AAudio_createStreamBuilder failed: %s", AAudio_convertResultToText(result));
            return false;
        }

        AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
        AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_EXCLUSIVE);
        AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
        AAudioStreamBuilder_setChannelCount(builder, 2);
        AAudioStreamBuilder_setSampleRate(builder, sampleRate > 0 ? sampleRate : 48000);
        AAudioStreamBuilder_setDataCallback(builder, aaudioDataCallback, this);
        AAudioStreamBuilder_setErrorCallback(builder, aaudioErrorCallback, this);

        result = AAudioStreamBuilder_openStream(builder, &m_stream);
        if (result != AAUDIO_OK) {
            LOGW("AAudio exclusive mode open failed (%s), trying SHARED mode...", AAudio_convertResultToText(result));
            AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
            result = AAudioStreamBuilder_openStream(builder, &m_stream);
            if (result != AAUDIO_OK) {
                LOGE("AAudio stream open failed: %s", AAudio_convertResultToText(result));
                AAudioStreamBuilder_delete(builder);
                m_stream = nullptr;
                return false;
            }
        }
        AAudioStreamBuilder_delete(builder);

        result = AAudioStream_requestStart(m_stream);
        if (result != AAUDIO_OK) {
            LOGE("AAudioStream_requestStart failed: %s", AAudio_convertResultToText(result));
            AAudioStream_close(m_stream);
            m_stream = nullptr;
            return false;
        }

        LOGI("Native AAudio stream started: rate=%d, burst=%d, capacity=%d",
             AAudioStream_getSampleRate(m_stream),
             AAudioStream_getFramesPerBurst(m_stream),
             AAudioStream_getBufferCapacityInFrames(m_stream));
        return true;
    }

    void stop() {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_stream) {
            AAudioStream_requestStop(m_stream);
            AAudioStream_close(m_stream);
            m_stream = nullptr;
        }
        m_ringBuffer.clear();
    }

    void pause() {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_stream) {
            AAudioStream_requestPause(m_stream);
        }
    }

    void resume() {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_stream) {
            AAudioStream_requestStart(m_stream);
        }
    }

    size_t writeFrames(const int16_t* frames, size_t count) {
        return m_ringBuffer.writeLossy(frames, count);
    }

    void setVolume(int volumePercent) {
        m_volume.store(std::max(0, std::min(100, volumePercent)), std::memory_order_relaxed);
    }

    int getVolume() const {
        return m_volume.load(std::memory_order_relaxed);
    }

    void setMute(bool mute) {
        m_muted.store(mute, std::memory_order_relaxed);
    }

    bool isMuted() const {
        return m_muted.load(std::memory_order_relaxed);
    }

    size_t getBufferedFrames() const {
        return m_ringBuffer.availableRead();
    }

    size_t getCapacityFrames() const {
        return m_ringBuffer.capacity();
    }

    void reset() {
        m_ringBuffer.clear();
        m_underrunCount.store(0, std::memory_order_relaxed);
    }

    uint64_t getUnderrunCount() const {
        return m_underrunCount.load(std::memory_order_relaxed);
    }

private:
    static aaudio_data_callback_result_t aaudioDataCallback(
            AAudioStream* stream,
            void* userData,
            void* audioData,
            int32_t numFrames) {
        (void)stream;
        Impl* self = static_cast<Impl*>(userData);
        int16_t* output = static_cast<int16_t*>(audioData);

        size_t framesRead = self->m_ringBuffer.read(output, static_cast<size_t>(numFrames));
        if (framesRead < static_cast<size_t>(numFrames)) {
            self->m_underrunCount.fetch_add(1, std::memory_order_relaxed);
        }

        const bool muted = self->m_muted.load(std::memory_order_relaxed);
        const int vol = self->m_volume.load(std::memory_order_relaxed);

        if (muted) {
            std::memset(output, 0, numFrames * 2 * sizeof(int16_t));
        } else if (vol < 100) {
            const int32_t totalSamples = numFrames * 2;
            for (int32_t i = 0; i < totalSamples; ++i) {
                int32_t scaled = (static_cast<int32_t>(output[i]) * vol) / 100;
                output[i] = static_cast<int16_t>(std::max(-32768, std::min(32767, scaled)));
            }
        }

        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    static void aaudioErrorCallback(AAudioStream* stream, void* userData, aaudio_result_t error) {
        (void)stream;
        LOGW("AAudio stream error (%s), scheduling reconnect...", AAudio_convertResultToText(error));
        Impl* self = static_cast<Impl*>(userData);
        std::thread([self]() {
            self->stop();
            self->start(48000, 4096);
        }).detach();
    }

    AAudioStream* m_stream;
    std::mutex m_mutex;
    SpscRingBuffer<4096> m_ringBuffer;
    std::atomic<int> m_volume;
    std::atomic<bool> m_muted;
    std::atomic<uint64_t> m_underrunCount;
};
#endif

// ============================================================================
// Public C++ AudioEngine Bridge
// ============================================================================
AudioEngine::AudioEngine() : m_impl(std::make_unique<Impl>()) {}
AudioEngine::~AudioEngine() = default;

bool AudioEngine::start(int sampleRate, int bufferFrames) { return m_impl->start(sampleRate, bufferFrames); }
void AudioEngine::stop() { m_impl->stop(); }
void AudioEngine::pause() { m_impl->pause(); }
void AudioEngine::resume() { m_impl->resume(); }
size_t AudioEngine::writeFrames(const int16_t* interleavedStereo, size_t frameCount) { return m_impl->writeFrames(interleavedStereo, frameCount); }
void AudioEngine::setVolume(int volumePercent) { m_impl->setVolume(volumePercent); }
int AudioEngine::getVolume() const { return m_impl->getVolume(); }
void AudioEngine::setMute(bool mute) { m_impl->setMute(mute); }
bool AudioEngine::isMuted() const { return m_impl->isMuted(); }
size_t AudioEngine::getBufferedFrames() const { return m_impl->getBufferedFrames(); }
size_t AudioEngine::getCapacityFrames() const { return m_impl->getCapacityFrames(); }
void AudioEngine::reset() { m_impl->reset(); }
uint64_t AudioEngine::getUnderrunCount() const { return m_impl->getUnderrunCount(); }

} // namespace audio
} // namespace retro

// ============================================================================
// Public C ABI Implementations
// ============================================================================
extern "C" {

AudioEngineHandle audioEngineCreate(int sampleRate, int bufferFrames) {
    auto engine = new retro::audio::AudioEngine();
    engine->start(sampleRate, bufferFrames);
    return static_cast<AudioEngineHandle>(engine);
}

void audioEngineDestroy(AudioEngineHandle handle) {
    if (handle) {
        auto engine = static_cast<retro::audio::AudioEngine*>(handle);
        engine->stop();
        delete engine;
    }
}

bool audioEngineStart(AudioEngineHandle handle) {
    if (!handle) return false;
    return static_cast<retro::audio::AudioEngine*>(handle)->start();
}

void audioEngineStop(AudioEngineHandle handle) {
    if (handle) static_cast<retro::audio::AudioEngine*>(handle)->stop();
}

void audioEnginePause(AudioEngineHandle handle) {
    if (handle) static_cast<retro::audio::AudioEngine*>(handle)->pause();
}

void audioEngineResume(AudioEngineHandle handle) {
    if (handle) static_cast<retro::audio::AudioEngine*>(handle)->resume();
}

size_t audioEngineWriteFrames(AudioEngineHandle handle, const int16_t* interleaved, size_t frames) {
    if (!handle) return 0;
    return static_cast<retro::audio::AudioEngine*>(handle)->writeFrames(interleaved, frames);
}

void audioEngineSetVolume(AudioEngineHandle handle, int volumePercent) {
    if (handle) static_cast<retro::audio::AudioEngine*>(handle)->setVolume(volumePercent);
}

int audioEngineGetVolume(AudioEngineHandle handle) {
    if (!handle) return 0;
    return static_cast<retro::audio::AudioEngine*>(handle)->getVolume();
}

void audioEngineSetMute(AudioEngineHandle handle, bool mute) {
    if (handle) static_cast<retro::audio::AudioEngine*>(handle)->setMute(mute);
}

bool audioEngineIsMuted(AudioEngineHandle handle) {
    if (!handle) return true;
    return static_cast<retro::audio::AudioEngine*>(handle)->isMuted();
}

size_t audioEngineGetBufferedFrames(AudioEngineHandle handle) {
    if (!handle) return 0;
    return static_cast<retro::audio::AudioEngine*>(handle)->getBufferedFrames();
}

void audioEngineReset(AudioEngineHandle handle) {
    if (handle) static_cast<retro::audio::AudioEngine*>(handle)->reset();
}

} // extern "C"
