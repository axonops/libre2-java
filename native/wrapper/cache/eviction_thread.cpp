/*
 * Copyright 2025 AxonOps
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "cache/eviction_thread.h"
#include <chrono>

namespace libre2 {
namespace cache {

EvictionThread::EvictionThread(
    const CacheConfig& config,
    ResultCache& result_cache,
    PatternCache& pattern_cache,
    DeferredCache& deferred_cache,
    CacheMetrics& metrics)
    : config_(config),
      result_cache_(result_cache),
      pattern_cache_(pattern_cache),
      deferred_cache_(deferred_cache),
      metrics_(metrics) {
    // Thread created but not started
}

EvictionThread::~EvictionThread() {
    // Ensure thread stopped before destruction
    stop();
}

void EvictionThread::start() {
    bool expected = false;
    if (!running_.compare_exchange_strong(expected, true, std::memory_order_acq_rel)) {
        return;  // Already running
    }

    stop_requested_.store(false, std::memory_order_release);

    // Start thread
    thread_ = std::make_unique<std::thread>(&EvictionThread::evictionLoop, this);
}

void EvictionThread::stop() {
    bool expected = true;
    if (!running_.compare_exchange_strong(expected, false, std::memory_order_acq_rel)) {
        return;  // Not running
    }

    // Request stop
    stop_requested_.store(true, std::memory_order_release);

    // Wait for thread to exit
    if (thread_ && thread_->joinable()) {
        thread_->join();
    }

    thread_.reset();
}

bool EvictionThread::isRunning() const {
    return running_.load(std::memory_order_acquire);
}

void EvictionThread::evictionLoop() {
    while (!stop_requested_.load(std::memory_order_acquire)) {
        auto now = std::chrono::steady_clock::now();

        // Evict from all caches
        try {
            // 1. Pattern Result Cache (if enabled)
            if (config_.pattern_result_cache_enabled) {
                result_cache_.evict(metrics_.pattern_result_cache, now);
                result_cache_.snapshotMetrics(metrics_.pattern_result_cache);
            }

            // 2. Pattern Compilation Cache
            pattern_cache_.evict(metrics_.pattern_cache, deferred_cache_, now);
            pattern_cache_.snapshotMetrics(metrics_.pattern_cache);

            // 3. Deferred Cache
            deferred_cache_.evict(metrics_.deferred_cache, now);
            deferred_cache_.snapshotMetrics(metrics_.deferred_cache);

            // Update timestamp
            metrics_.generated_at = std::chrono::system_clock::now();

        } catch (const std::exception&) {
            // Eviction errors are non-fatal - continue running
            // TODO: Log when C++ logging infrastructure added
        }

        // Sleep until next cycle
        std::this_thread::sleep_for(config_.eviction_check_interval_ms);
    }

    // Thread exiting
    running_.store(false, std::memory_order_release);
}

}  // namespace cache
}  // namespace libre2
