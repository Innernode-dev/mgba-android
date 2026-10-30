#include <iostream>
#include <thread>
#include <atomic>
#include <vector>
#include <chrono>
#include <cassert>
#include <cstdlib>

// Include the ring buffer under test
#include "../app/src/main/cpp/audio/ring_buffer.h"

using namespace retro::audio;

// Test 1: Standard SPSC throughput and ordering stress test (write & read)
bool test_spsc_standard_concurrency() {
    std::cout << "[Test 1] SPSC Standard Write/Read Concurrency Stress..." << std::endl;
    SpscRingBuffer<4096> rb;
    const size_t TOTAL_FRAMES = 5'000'000;
    std::atomic<bool> producer_done{false};
    std::atomic<bool> failed{false};
    std::string failure_reason = "";

    // Producer thread
    std::thread producer([&]() {
        std::vector<int16_t> chunk(256 * 2);
        size_t frames_written = 0;
        int16_t seq = 0;

        while (frames_written < TOTAL_FRAMES) {
            size_t count = std::min((size_t)256, TOTAL_FRAMES - frames_written);
            for (size_t i = 0; i < count; ++i) {
                chunk[i * 2] = seq;
                chunk[i * 2 + 1] = seq;
                seq++;
            }

            size_t written = 0;
            while (written < count) {
                size_t w = rb.write(chunk.data() + written * 2, count - written);
                written += w;
                if (w == 0) {
                    std::this_thread::yield();
                }
            }
            frames_written += count;
        }
        producer_done.store(true, std::memory_order_release);
    });

    // Consumer thread
    std::thread consumer([&]() {
        std::vector<int16_t> dest(256 * 2);
        size_t frames_read = 0;
        int16_t expected_seq = 0;

        while (frames_read < TOTAL_FRAMES) {
            size_t r = rb.read(dest.data(), 256);
            if (r > 0) {
                for (size_t i = 0; i < r; ++i) {
                    if (dest[i * 2] != expected_seq || dest[i * 2 + 1] != expected_seq) {
                        failed.store(true);
                        failure_reason = "Data corruption or sequence mismatch!";
                        return;
                    }
                    expected_seq++;
                }
                frames_read += r;
            } else {
                if (producer_done.load(std::memory_order_acquire) && rb.availableRead() == 0) {
                    break;
                }
                std::this_thread::yield();
            }
        }
        if (frames_read != TOTAL_FRAMES) {
            failed.store(true);
            failure_reason = "Frames read (" + std::to_string(frames_read) + ") != expected (" + std::to_string(TOTAL_FRAMES) + ")";
        }
    });

    producer.join();
    consumer.join();

    if (failed.load()) {
        std::cout << "  FAIL: " << failure_reason << std::endl;
        return false;
    }
    std::cout << "  PASS: 5,000,000 frames transferred with zero data corruption and perfect ordering." << std::endl;
    return true;
}

// Test 2: Stress test writeLossy under concurrent overflow
bool test_spsc_writelossy_race() {
    std::cout << "[Test 2] SPSC writeLossy Under Concurrent Reading and Overflow..." << std::endl;
    SpscRingBuffer<4096> rb;
    std::atomic<bool> stop{false};
    std::atomic<size_t> tail_backward_events{0};
    std::atomic<size_t> capacity_overflow_events{0};

    // Consumer reads in small chunks (e.g. 64 frames)
    std::thread consumer([&]() {
        std::vector<int16_t> dest(64 * 2);
        while (!stop.load(std::memory_order_relaxed)) {
            size_t prev_tail = rb.availableRead();
            rb.read(dest.data(), 64);
        }
    });

    // Producer floods buffer using writeLossy to trigger overflow
    std::thread producer([&]() {
        std::vector<int16_t> chunk(512 * 2, 0x1234);
        for (int i = 0; i < 50000; ++i) {
            rb.writeLossy(chunk.data(), 512);

            // Check if availableRead > capacity (which indicates tail was moved backwards or corrupted)
            size_t avail = rb.availableRead();
            if (avail > 4096) {
                capacity_overflow_events.fetch_add(1);
            }
        }
        stop.store(true);
    });

    producer.join();
    consumer.join();

    std::cout << "  Capacity overflow events (availableRead > 4096): " << capacity_overflow_events.load() << std::endl;
    if (capacity_overflow_events.load() > 0) {
        std::cout << "  VULNERABILITY DETECTED: writeLossy allows m_tail to move backwards under concurrent read!" << std::endl;
        return false;
    }
    std::cout << "  PASS: No tail reversal detected during burst test." << std::endl;
    return true;
}

int main() {
    bool ok1 = test_spsc_standard_concurrency();
    bool ok2 = test_spsc_writelossy_race();

    std::cout << "\nRing Buffer Test Summary: Test 1=" << (ok1 ? "PASS" : "FAIL")
              << ", Test 2=" << (ok2 ? "PASS" : "FAIL") << std::endl;

    return (ok1 && ok2) ? 0 : 1;
}
