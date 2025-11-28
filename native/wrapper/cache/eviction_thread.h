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

#pragma once

#include "cache/cache_config.h"
#include "cache/cache_metrics.h"
#include "cache/result_cache.h"
#include "cache/pattern_cache.h"
#include "cache/deferred_cache.h"
#include <atomic>
#include <memory>
#include <thread>

namespace libre2 {
namespace cache {

/**
 * Background Eviction Thread - periodically evicts expired/over-capacity entries.
 *
 * Runs every `eviction_check_interval_ms` (default: 100ms) and:
 * 1. Evicts from Pattern Result Cache (TTL + LRU)
 * 2. Evicts from Pattern Compilation Cache (TTL + LRU)
 * 3. Evicts from Deferred Cache (immediate + forced)
 * 4. Updates snapshot metrics for all caches
 *
 * Thread-safe start/stop with graceful shutdown.
 */
class EvictionThread {
public:
    /**
     * Create eviction thread (does not start automatically).
     *
     * @param config cache configuration
     * @param result_cache Pattern Result Cache
     * @param pattern_cache Pattern Compilation Cache
     * @param deferred_cache Deferred Cache
     * @param metrics combined metrics structure
     */
    EvictionThread(
        const CacheConfig& config,
        ResultCache& result_cache,
        PatternCache& pattern_cache,
        DeferredCache& deferred_cache,
        CacheMetrics& metrics);

    ~EvictionThread();

    /**
     * Start the eviction thread.
     * Safe to call multiple times (no-op if already running).
     */
    void start();

    /**
     * Stop the eviction thread.
     * Blocks until thread exits gracefully.
     * Safe to call multiple times (no-op if not running).
     */
    void stop();

    /**
     * Check if thread is currently running.
     */
    bool isRunning() const;

private:
    const CacheConfig& config_;
    ResultCache& result_cache_;
    PatternCache& pattern_cache_;
    DeferredCache& deferred_cache_;
    CacheMetrics& metrics_;

    std::unique_ptr<std::thread> thread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> stop_requested_{false};

    /**
     * Eviction thread main loop.
     */
    void evictionLoop();
};

}  // namespace cache
}  // namespace libre2
