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
#include "cache/murmur_hash3.h"
#include <oneapi/tbb/concurrent_hash_map.h>
#include <chrono>
#include <optional>
#include <shared_mutex>
#include <string>
#include <unordered_map>

namespace libre2 {
namespace cache {

/**
 * Pattern Result Cache - caches (pattern, input_string) -> match result.
 *
 * Optional cache to avoid re-evaluating same pattern on same input.
 * Dual implementation:
 * - std::unordered_map + shared_mutex (default)
 * - TBB concurrent_hash_map (optional, high-concurrency)
 *
 * Key design:
 * - Cache key: MurmurHash3(pattern_hash + input_string_hash)
 * - Cache value: bool match_result
 * - String threshold: Only cache if input_string.size() <= threshold
 * - Error handling: Non-fatal (log metric, skip caching, continue normally)
 * - TTL + LRU eviction via background thread
 *
 * Thread-safe for concurrent get/put and eviction.
 */
class ResultCache {
public:
    explicit ResultCache(const CacheConfig& config);
    ~ResultCache();

    /**
     * Get cached match result if available.
     *
     * @param pattern_hash hash of pattern string
     * @param input_string the input string to match against
     * @param metrics metrics to update
     * @return cached result if found, std::nullopt if miss or error
     */
    std::optional<bool> get(
        uint64_t pattern_hash,
        const std::string& input_string,
        PatternResultCacheMetrics& metrics);

    /**
     * Put match result into cache.
     *
     * Checks string threshold before caching. Non-fatal errors logged to metrics.
     *
     * @param pattern_hash hash of pattern string
     * @param input_string the input string that was matched
     * @param match_result the match result to cache
     * @param metrics metrics to update
     */
    void put(
        uint64_t pattern_hash,
        const std::string& input_string,
        bool match_result,
        PatternResultCacheMetrics& metrics);

    /**
     * Evict entries based on TTL and capacity (called by background thread).
     *
     * Eviction logic:
     * - TTL eviction: If (now - last_access) > TTL → DELETE
     * - LRU eviction: If size > target capacity → DELETE oldest (batch)
     *
     * @param metrics metrics to update
     * @param now current time
     * @return number of entries evicted
     */
    size_t evict(
        PatternResultCacheMetrics& metrics,
        const std::chrono::steady_clock::time_point& now);

    /**
     * Clear all entries (for shutdown).
     */
    void clear();

    /**
     * Update snapshot metrics (called by eviction thread).
     */
    void snapshotMetrics(PatternResultCacheMetrics& metrics) const;

    /**
     * Get current entry count (for testing).
     */
    size_t size() const;

private:
    // Fixed memory cost per entry (does NOT include input string - only hash stored!)
    // Breakdown: 24 bytes (struct) + 40 bytes (hash table overhead) = 64 bytes
    static constexpr size_t RESULT_CACHE_ENTRY_SIZE = 64;

    struct ResultCacheEntry {
        bool match_result;
        mutable std::chrono::steady_clock::time_point last_access;  // Mutable for TBB const_accessor
        size_t approx_size_bytes;  // Fixed size per entry

        ResultCacheEntry() : match_result(false), approx_size_bytes(RESULT_CACHE_ENTRY_SIZE) {}

        ResultCacheEntry(bool result, size_t /*input_size_unused*/)
            : match_result(result),
              last_access(std::chrono::steady_clock::now()),
              approx_size_bytes(RESULT_CACHE_ENTRY_SIZE) {}  // Fixed size (string not stored!)
    };

    const CacheConfig& config_;
    const bool using_tbb_;

    // ========== std::unordered_map Implementation ==========
    std::unordered_map<uint64_t, ResultCacheEntry> std_cache_;
    mutable std::shared_mutex std_mutex_;
    size_t std_total_size_bytes_ = 0;

    // ========== TBB concurrent_hash_map Implementation ==========
    using TBBMap = tbb::concurrent_hash_map<uint64_t, ResultCacheEntry>;
    TBBMap tbb_cache_;
    std::atomic<size_t> tbb_total_size_bytes_{0};

    // ========== Implementation Methods ==========

    // std::unordered_map path
    std::optional<bool> getStd(
        uint64_t key,
        PatternResultCacheMetrics& metrics);

    void putStd(
        uint64_t key,
        const std::string& input_string,
        bool match_result,
        PatternResultCacheMetrics& metrics);

    size_t evictStd(
        PatternResultCacheMetrics& metrics,
        const std::chrono::steady_clock::time_point& now);

    // TBB concurrent_hash_map path
    std::optional<bool> getTBB(
        uint64_t key,
        PatternResultCacheMetrics& metrics);

    void putTBB(
        uint64_t key,
        const std::string& input_string,
        bool match_result,
        PatternResultCacheMetrics& metrics);

    size_t evictTBB(
        PatternResultCacheMetrics& metrics,
        const std::chrono::steady_clock::time_point& now);

    // Helpers
    uint64_t makeKey(uint64_t pattern_hash, const std::string& input_string) const;
};

}  // namespace cache
}  // namespace libre2
