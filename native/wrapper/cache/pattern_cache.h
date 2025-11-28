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
#include "cache/murmur_hash3.h"
#include <oneapi/tbb/concurrent_hash_map.h>
#include <chrono>
#include <memory>
#include <shared_mutex>
#include <string>
#include <unordered_map>

namespace libre2 {
namespace cache {

/**
 * Pattern Compilation Cache - caches compiled RE2 patterns with reference counting.
 *
 * Dual implementation:
 * - std::unordered_map + shared_mutex (default, simpler)
 * - TBB concurrent_hash_map (optional, high-concurrency)
 *
 * CRITICAL REFCOUNT INVARIANT:
 * - Refcount MUST be incremented BEFORE releasing lock
 * - Prevents use-after-free race with eviction thread
 * - See REFCOUNT_INVARIANT.md for detailed explanation
 *
 * Thread-safe for concurrent compilation, lookup, and eviction.
 */
class PatternCache {
public:
    explicit PatternCache(const CacheConfig& config);
    ~PatternCache();

    /**
     * Get or compile pattern (increments refcount).
     *
     * Flow:
     * 1. Check cache (hit → increment refcount, return)
     * 2. Compile pattern (miss → compile, store, increment refcount, return)
     *
     * CRITICAL: Refcount incremented BEFORE lock released (prevents race).
     *
     * @param pattern_string regex pattern
     * @param case_sensitive case sensitivity flag
     * @param metrics metrics to update
     * @param error_msg output parameter for compilation errors
     * @return compiled pattern, or nullptr on error
     */
    std::shared_ptr<RE2Pattern> getOrCompile(
        const std::string& pattern_string,
        bool case_sensitive,
        PatternCacheMetrics& metrics,
        std::string& error_msg);

    /**
     * Release pattern (decrements refcount).
     *
     * Caller finished using pattern. Decrements refcount atomically.
     * If refcount → 0 and pattern in deferred cache, immediately evicts.
     *
     * @param pattern_string regex pattern
     * @param case_sensitive case sensitivity flag
     * @param metrics metrics to update
     * @param deferred_cache deferred cache for immediate eviction check
     */
    void releasePattern(
        const std::string& pattern_string,
        bool case_sensitive,
        PatternCacheMetrics& metrics,
        DeferredCache& deferred_cache);

    /**
     * Evict entries based on TTL and capacity (called by background thread).
     *
     * Eviction logic:
     * - TTL eviction: If (now - last_access) > TTL
     *   - refcount == 0: DELETE immediately
     *   - refcount > 0: MOVE to deferred cache
     * - LRU eviction: If size > target capacity
     *   - Find oldest entries
     *   - refcount == 0: DELETE immediately
     *   - refcount > 0: MOVE to deferred cache
     *
     * @param metrics metrics to update
     * @param deferred_cache deferred cache for in-use patterns
     * @param now current time
     * @return number of entries evicted
     */
    size_t evict(
        PatternCacheMetrics& metrics,
        DeferredCache& deferred_cache,
        const std::chrono::steady_clock::time_point& now);

    /**
     * Clear all entries (for shutdown).
     * Patterns with refcount > 0 moved to deferred cache.
     */
    void clear(DeferredCache& deferred_cache);

    /**
     * Update snapshot metrics (called by eviction thread).
     */
    void snapshotMetrics(PatternCacheMetrics& metrics) const;

    /**
     * Get current entry count (for testing).
     */
    size_t size() const;

private:
    struct PatternCacheEntry {
        std::shared_ptr<RE2Pattern> pattern;
        mutable std::chrono::steady_clock::time_point last_access;  // Mutable for TBB const_accessor

        PatternCacheEntry() = default;  // Default constructor for TBB

        PatternCacheEntry(std::shared_ptr<RE2Pattern> p)
            : pattern(std::move(p)),
              last_access(std::chrono::steady_clock::now()) {}
    };

    const CacheConfig& config_;
    const bool using_tbb_;

    // ========== std::unordered_map Implementation ==========
    std::unordered_map<uint64_t, PatternCacheEntry> std_cache_;
    mutable std::shared_mutex std_mutex_;
    size_t std_total_size_bytes_ = 0;

    // ========== TBB concurrent_hash_map Implementation ==========
    using TBBMap = tbb::concurrent_hash_map<uint64_t, PatternCacheEntry>;
    TBBMap tbb_cache_;
    std::atomic<size_t> tbb_total_size_bytes_{0};

    // ========== Implementation Methods ==========

    // std::unordered_map path
    std::shared_ptr<RE2Pattern> getOrCompileStd(
        uint64_t key,
        const std::string& pattern_string,
        bool case_sensitive,
        PatternCacheMetrics& metrics,
        std::string& error_msg);

    void releasePatternStd(
        uint64_t key,
        PatternCacheMetrics& metrics,
        DeferredCache& deferred_cache);

    size_t evictStd(
        PatternCacheMetrics& metrics,
        DeferredCache& deferred_cache,
        const std::chrono::steady_clock::time_point& now);

    // TBB concurrent_hash_map path
    std::shared_ptr<RE2Pattern> getOrCompileTBB(
        uint64_t key,
        const std::string& pattern_string,
        bool case_sensitive,
        PatternCacheMetrics& metrics,
        std::string& error_msg);

    void releasePatternTBB(
        uint64_t key,
        PatternCacheMetrics& metrics,
        DeferredCache& deferred_cache);

    size_t evictTBB(
        PatternCacheMetrics& metrics,
        DeferredCache& deferred_cache,
        const std::chrono::steady_clock::time_point& now);

    // Helpers
    uint64_t makeKey(const std::string& pattern, bool case_sensitive) const;
    std::shared_ptr<RE2Pattern> compilePattern(
        const std::string& pattern_string,
        bool case_sensitive,
        std::string& error_msg);
};

}  // namespace cache
}  // namespace libre2
