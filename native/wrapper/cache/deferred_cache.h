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
#include <re2/re2.h>
#include <chrono>
#include <memory>
#include <shared_mutex>
#include <string>
#include <unordered_map>

namespace libre2 {
namespace cache {

/**
 * RE2 Pattern wrapper for caching.
 *
 * Holds compiled RE2 pattern with metadata for cache management.
 */
struct RE2Pattern {
    std::unique_ptr<RE2> compiled_regex;
    std::atomic<uint32_t> refcount{0};
    std::chrono::steady_clock::time_point last_access;
    std::string pattern_string;
    bool case_sensitive;
    size_t approx_size_bytes;

    RE2Pattern(std::unique_ptr<RE2> regex, const std::string& pattern, bool cs)
        : compiled_regex(std::move(regex)),
          last_access(std::chrono::steady_clock::now()),
          pattern_string(pattern),
          case_sensitive(cs),
          approx_size_bytes(0) {
        if (compiled_regex && compiled_regex->ok()) {
            approx_size_bytes = compiled_regex->ProgramSize();
        }
    }

    bool isValid() const {
        return compiled_regex && compiled_regex->ok();
    }
};

/**
 * Deferred Cache - holds patterns with refcount > 0 after eviction.
 *
 * Purpose: Leak protection for patterns still in use when evicted from Pattern Cache.
 * Thread-safe with shared_mutex (RwLock).
 * No TBB variant needed (low volume, rare operations).
 */
class DeferredCache {
public:
    explicit DeferredCache(const CacheConfig& config);
    ~DeferredCache();

    /**
     * Add pattern to deferred cache (moved from Pattern Cache on eviction).
     *
     * @param pattern_key unique key (MurmurHash3 of pattern string + case flag)
     * @param pattern compiled pattern with refcount > 0
     * @param metrics metrics to update
     */
    void add(
        uint64_t pattern_key,
        std::shared_ptr<RE2Pattern> pattern,
        DeferredCacheMetrics& metrics);

    /**
     * Evict entries based on refcount and TTL (called by background thread).
     *
     * Eviction logic:
     * - If refcount == 0: Immediate eviction (leak fixed, pattern freed)
     * - If (now - entered) > TTL: Forced eviction (LEAK DETECTED, log warning)
     *
     * @param metrics metrics to update
     * @param now current time
     * @return number of entries evicted
     */
    size_t evict(
        DeferredCacheMetrics& metrics,
        const std::chrono::steady_clock::time_point& now);

    /**
     * Clear all entries (for shutdown).
     * Forcibly evicts all patterns regardless of refcount.
     */
    void clear();

    /**
     * Update snapshot metrics (called by eviction thread).
     *
     * @param metrics metrics to update
     */
    void snapshotMetrics(DeferredCacheMetrics& metrics) const;

    /**
     * Get current entry count (for testing).
     */
    size_t size() const;

private:
    struct DeferredEntry {
        std::shared_ptr<RE2Pattern> pattern;
        std::chrono::steady_clock::time_point entered_deferred;
        size_t approx_size_bytes;

        DeferredEntry(std::shared_ptr<RE2Pattern> p)
            : pattern(std::move(p)),
              entered_deferred(std::chrono::steady_clock::now()),
              approx_size_bytes(pattern ? pattern->approx_size_bytes : 0) {}
    };

    const CacheConfig& config_;
    mutable std::shared_mutex mutex_;
    std::unordered_map<uint64_t, DeferredEntry> cache_;
    size_t total_size_bytes_ = 0;
};

}  // namespace cache
}  // namespace libre2
