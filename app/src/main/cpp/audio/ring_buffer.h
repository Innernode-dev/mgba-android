/*
 * Lock-Free Single-Producer Single-Consumer (SPSC) Audio Ring Buffer
 * Part of mGBA Android Emulator Subsystem
 * Licensed under the Mozilla Public License, v. 2.0 (MPL-2.0).
 */

#ifndef RETRO_AUDIO_RING_BUFFER_H
#define RETRO_AUDIO_RING_BUFFER_H

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <algorithm>

namespace retro {
namespace audio {

/**
 * Lock-free SPSC ring buffer specifically tailored for 16-bit interleaved stereo PCM.
 * Capacity is fixed to a power of two for branchless, wrap-around bitmask indexing.
 * 1 Frame = 2 x int16_t (Left, Right) = 4 bytes.
 */
template <size_t FrameCapacity = 4096>
class SpscRingBuffer {
    static_assert((FrameCapacity & (FrameCapacity - 1)) == 0, "Capacity must be a power of two!");

public:
    static constexpr size_t CHANNELS = 2;
    static constexpr size_t SAMPLE_CAPACITY = FrameCapacity * CHANNELS;
    static constexpr size_t MASK = FrameCapacity - 1;

    SpscRingBuffer() : m_head(0), m_tail(0) {
        std::memset(m_buffer, 0, sizeof(m_buffer));
    }

    ~SpscRingBuffer() = default;

    // Disallow copy and assignment
    SpscRingBuffer(const SpscRingBuffer&) = delete;
    SpscRingBuffer& operator=(const SpscRingBuffer&) = delete;

    /**
     * Write stereo frames into the ring buffer (Producer thread only).
     * @param frames Interleaved stereo samples [L, R, L, R, ...].
     * @param count Number of frames (stereo pairs) to write.
     * @return Number of frames actually written.
     */
    size_t write(const int16_t* frames, size_t count) {
        if (!frames || count == 0) return 0;

        const size_t head = m_head.load(std::memory_order_relaxed);
        const size_t tail = m_tail.load(std::memory_order_acquire);
        const size_t available = FrameCapacity - (head - tail);

        const size_t toWrite = std::min(count, available);
        if (toWrite == 0) return 0;

        const size_t writeIndex = head & MASK;
        const size_t firstChunk = std::min(toWrite, FrameCapacity - writeIndex);
        const size_t secondChunk = toWrite - firstChunk;

        std::memcpy(&m_buffer[writeIndex * CHANNELS], frames, firstChunk * CHANNELS * sizeof(int16_t));
        if (secondChunk > 0) {
            std::memcpy(&m_buffer[0], &frames[firstChunk * CHANNELS], secondChunk * CHANNELS * sizeof(int16_t));
        }

        m_head.store(head + toWrite, std::memory_order_release);
        return toWrite;
    }

    /**
     * Lossy write: if buffer would overflow, overwrite oldest samples by advancing tail.
     * Prevents stalling emulation during bursts while preserving freshest audio.
     */
    size_t writeLossy(const int16_t* frames, size_t count) {
        if (!frames || count == 0) return 0;

        const size_t head = m_head.load(std::memory_order_relaxed);
        size_t tail = m_tail.load(std::memory_order_relaxed);
        const size_t spaceNeeded = count;

        if (spaceNeeded > FrameCapacity) {
            // If packet is larger than capacity, only keep the tail end of the packet
            frames += (spaceNeeded - FrameCapacity) * CHANNELS;
            count = FrameCapacity;
        }

        // Drop oldest frames if needed
        if ((head - tail) + count > FrameCapacity) {
            tail = head + count - FrameCapacity;
            m_tail.store(tail, std::memory_order_release);
        }

        const size_t writeIndex = head & MASK;
        const size_t firstChunk = std::min(count, FrameCapacity - writeIndex);
        const size_t secondChunk = count - firstChunk;

        std::memcpy(&m_buffer[writeIndex * CHANNELS], frames, firstChunk * CHANNELS * sizeof(int16_t));
        if (secondChunk > 0) {
            std::memcpy(&m_buffer[0], &frames[firstChunk * CHANNELS], secondChunk * CHANNELS * sizeof(int16_t));
        }

        m_head.store(head + count, std::memory_order_release);
        return count;
    }

    /**
     * Read stereo frames from the ring buffer (Consumer audio thread only).
     * If available data is less than requested, zero-fills remainder (digital silence).
     * @param destination Output buffer receiving interleaved stereo samples.
     * @param count Number of frames requested.
     * @return Number of actual valid frames read before zero-fill.
     */
    size_t read(int16_t* destination, size_t count) {
        if (!destination || count == 0) return 0;

        const size_t tail = m_tail.load(std::memory_order_relaxed);
        const size_t head = m_head.load(std::memory_order_acquire);
        const size_t available = head - tail;

        const size_t toRead = std::min(count, available);

        if (toRead > 0) {
            const size_t readIndex = tail & MASK;
            const size_t firstChunk = std::min(toRead, FrameCapacity - readIndex);
            const size_t secondChunk = toRead - firstChunk;

            std::memcpy(destination, &m_buffer[readIndex * CHANNELS], firstChunk * CHANNELS * sizeof(int16_t));
            if (secondChunk > 0) {
                std::memcpy(&destination[firstChunk * CHANNELS], &m_buffer[0], secondChunk * CHANNELS * sizeof(int16_t));
            }

            m_tail.store(tail + toRead, std::memory_order_release);
        }

        // Zero-fill underrun to prevent audible crackling or clicks
        if (toRead < count) {
            std::memset(&destination[toRead * CHANNELS], 0, (count - toRead) * CHANNELS * sizeof(int16_t));
        }

        return toRead;
    }

    /** Returns count of frames ready to be read. */
    size_t availableRead() const {
        const size_t head = m_head.load(std::memory_order_acquire);
        const size_t tail = m_tail.load(std::memory_order_acquire);
        return head - tail;
    }

    /** Returns count of frames available to write before full. */
    size_t availableWrite() const {
        return FrameCapacity - availableRead();
    }

    /** Capacity of the buffer in frames. */
    static constexpr size_t capacity() {
        return FrameCapacity;
    }

    /** Resets read/write pointers to empty state. */
    void clear() {
        m_head.store(0, std::memory_order_relaxed);
        m_tail.store(0, std::memory_order_relaxed);
    }

private:
    // Align atomic indices to separate 64-byte cache lines to eliminate false sharing
    alignas(64) std::atomic<size_t> m_head;
    alignas(64) std::atomic<size_t> m_tail;
    alignas(64) int16_t m_buffer[SAMPLE_CAPACITY];
};

} // namespace audio
} // namespace retro

#endif // RETRO_AUDIO_RING_BUFFER_H
