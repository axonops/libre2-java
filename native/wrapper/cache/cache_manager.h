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
#include "cache/deferred_cache.h"
#include "cache/eviction_thread.h"
#include "cache/pattern_cache.h"
#include "cache/result_cache.h"
#include <memory>
#include <string>

namespace libre2 {
namespace cache {

/**
 * Cache Manager - orchestrates all three caches and background eviction.
 *
 * Provides single entry point for:
 * - Pattern Result Cache (optional, match result caching)
 * - Pattern Compilation Cache (reference-counted compiled patterns)
 * - Deferred Cache (leak protection)
 * - Background eviction thread (periodic TTL + LRU cleanup)
 *
 * Lifecycle:
 * 1. Construct with configuration
 * 2. Optionally start eviction thread (or auto-start if configured)
 * 3. Use caches via getter methods
 * 4. Stop eviction thread on shutdown
 * 5. Destruct (cleans up all caches)
 */
class CacheManager {
public:
    explicit CacheManager(const CacheConfig& config);
    ~CacheManager();

    // Disable copy/move
    CacheManager(const CacheManager&) = delete;
    CacheManager& operator=(const CacheManager&) = delete;

    /**
     * Start background eviction thread.
     * Safe to call multiple times (no-op if already running).
     */
    void startEvictionThread();

    /**
     * Stop background eviction thread.
     * Blocks until thread exits gracefully.
     * Safe to call multiple times (no-op if not running).
     */
    void stopEvictionThread();

    /**
     * Check if eviction thread is running.
     */
    bool isEvictionThreadRunning() const;

    /**
     * Get current metrics snapshot (all caches).
     * Thread-safe - can be called while eviction thread running.
     *
     * @return JSON string with all metrics
     */
    std::string getMetricsJSON() const;

    /**
     * Clear all caches (for testing or reset).
     * Stops eviction thread first, clears all caches, moves in-use patterns to deferred.
     */
    void clearAllCaches();

    // Cache accessors (for testing and direct use)
    ResultCache& resultCache() { return result_cache_; }
    PatternCache& patternCache() { return pattern_cache_; }
    DeferredCache& deferredCache() { return deferred_cache_; }
    CacheMetrics& metrics() { return metrics_; }

private:
    CacheConfig config_;
    CacheMetrics metrics_;

    // Caches (order matters for initialization)
    ResultCache result_cache_;
    PatternCache pattern_cache_;
    DeferredCache deferred_cache_;

    // Eviction thread (initialized last, references all caches)
    std::unique_ptr<EvictionThread> eviction_thread_;
};

}  // namespace cache
}  // namespace libre2
