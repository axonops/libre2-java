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

#include "cache/pattern_cache.h"
#include <algorithm>
#include <iostream>
#include <vector>

namespace libre2 {
namespace cache {

//============================================================================
// Constructor / Destructor
//============================================================================

PatternCache::PatternCache(const CacheConfig& config)
    : config_(config),
      using_tbb_(config.pattern_cache_use_tbb) {
    // Both implementations always present (zero overhead when not used)
}

PatternCache::~PatternCache() {
    // Note: Patterns may still have refcount > 0
    // Caller should have called clear() with deferred cache before destruction
    if (using_tbb_) {
        tbb_cache_.clear();
    } else {
        std::unique_lock lock(std_mutex_);
        std_cache_.clear();
    }
}

//============================================================================
// Public API (Dispatches to std or TBB implementation)
//============================================================================

std::shared_ptr<RE2Pattern> PatternCache::getOrCompile(
    const std::string& pattern_string,
    const api::PatternOptions& options,
    PatternCacheMetrics& metrics,
    std::string& error_msg) {

    uint64_t key = makeKey(pattern_string, options);

    if (using_tbb_) {
        return getOrCompileTBB(key, pattern_string, options, metrics, error_msg);
    } else {
        return getOrCompileStd(key, pattern_string, options, metrics, error_msg);
    }
}

void PatternCache::releasePattern(
    std::shared_ptr<RE2Pattern>& pattern_ptr,
    PatternCacheMetrics& metrics) {

    if (!pattern_ptr) {
        return;  // Null pattern - nothing to release
    }

    // Decrement refcount (atomic, works regardless of cache location)
    uint32_t prev_refcount = pattern_ptr->refcount.fetch_sub(1, std::memory_order_acq_rel);

    // Track metrics
    metrics.pattern_releases.fetch_add(1, std::memory_order_relaxed);
    if (prev_refcount == 1) {
        // Pattern refcount went to 0
        metrics.patterns_released_to_zero.fetch_add(1, std::memory_order_relaxed);
    }

    // Release shared_ptr reference
    pattern_ptr.reset();
}

void PatternCache::releasePattern(RE2Pattern* pattern) {
    if (!pattern) {
        return;  // Null pattern - nothing to release
    }

    // Decrement refcount (atomic, works regardless of cache location)
    // Note: Metrics not tracked for raw pointer calls (used by facade layer)
    pattern->refcount.fetch_sub(1, std::memory_order_acq_rel);

    // Pattern will be cleaned by eviction thread when refcount=0
}

size_t PatternCache::evict(
    PatternCacheMetrics& metrics,
    DeferredCache& deferred_cache,
    const std::chrono::steady_clock::time_point& now) {

    if (using_tbb_) {
        return evictTBB(metrics, deferred_cache, now);
    } else {
        return evictStd(metrics, deferred_cache, now);
    }
}

void PatternCache::clear(DeferredCache& deferred_cache) {
    if (using_tbb_) {
        // Iterate and move in-use patterns to deferred
        for (TBBMap::iterator it = tbb_cache_.begin(); it != tbb_cache_.end(); ++it) {
            if (it->second.pattern->refcount.load(std::memory_order_acquire) > 0) {
                DeferredCacheMetrics dummy_metrics;
                deferred_cache.add(it->first, it->second.pattern, dummy_metrics);
            }
        }
        tbb_cache_.clear();
        tbb_total_size_bytes_.store(0);
    } else {
        std::unique_lock lock(std_mutex_);
        for (auto& [key, entry] : std_cache_) {
            if (entry.pattern->refcount.load(std::memory_order_acquire) > 0) {
                DeferredCacheMetrics dummy_metrics;
                deferred_cache.add(key, entry.pattern, dummy_metrics);
            }
        }
        std_cache_.clear();
        std_total_size_bytes_ = 0;
    }
}

void PatternCache::snapshotMetrics(PatternCacheMetrics& metrics) const {
    if (using_tbb_) {
        metrics.current_entry_count = tbb_cache_.size();
        metrics.actual_size_bytes = tbb_total_size_bytes_.load();
    } else {
        std::shared_lock lock(std_mutex_);
        metrics.current_entry_count = std_cache_.size();
        metrics.actual_size_bytes = std_total_size_bytes_;
    }

    metrics.target_capacity_bytes = config_.pattern_cache_target_capacity_bytes;
    metrics.utilization_ratio = metrics.target_capacity_bytes > 0
        ? static_cast<double>(metrics.actual_size_bytes) / metrics.target_capacity_bytes
        : 0.0;
    metrics.using_tbb = using_tbb_;
}

size_t PatternCache::size() const {
    if (using_tbb_) {
        return tbb_cache_.size();
    } else {
        std::shared_lock lock(std_mutex_);
        return std_cache_.size();
    }
}

//============================================================================
// std::unordered_map Implementation
//============================================================================

std::shared_ptr<RE2Pattern> PatternCache::getOrCompileStd(
    uint64_t key,
    const std::string& pattern_string,
    const api::PatternOptions& options,
    PatternCacheMetrics& metrics,
    std::string& error_msg) {

    // Try cache lookup first (shared lock - allows concurrent reads)
    {
        std::shared_lock lock(std_mutex_);
        auto it = std_cache_.find(key);

        if (it != std_cache_.end()) {
            // CACHE HIT
            // CRITICAL: Increment refcount WHILE lock held (prevents race)
            it->second.pattern->refcount.fetch_add(1, std::memory_order_acq_rel);
            it->second.last_access = std::chrono::steady_clock::now();

            metrics.hits.fetch_add(1);

            auto result = it->second.pattern;
            // Lock released by destructor
            return result;  // Safe - refcount already +1
        }
    }

    // CACHE MISS - need to compile
    metrics.misses.fetch_add(1);

    // Compile pattern (no lock held - compilation can be slow)
    auto pattern = compilePattern(pattern_string, options, error_msg);
    if (!pattern) {
        metrics.compilation_errors.fetch_add(1);
        return nullptr;
    }

    // Add to cache (exclusive lock for write)
    {
        std::unique_lock lock(std_mutex_);

        // Double-check not added by another thread while we were compiling
        auto it = std_cache_.find(key);
        if (it != std_cache_.end()) {
            // Another thread compiled it - use theirs, discard ours
            it->second.pattern->refcount.fetch_add(1, std::memory_order_acq_rel);
            it->second.last_access = std::chrono::steady_clock::now();
            return it->second.pattern;
        }

        // Insert our compiled pattern
        pattern->refcount.store(1, std::memory_order_release);  // Initial refcount
        auto [inserted_it, inserted] = std_cache_.emplace(key, PatternCacheEntry(pattern));

        if (inserted) {
            std_total_size_bytes_ += pattern->approx_size_bytes;
        }

        return pattern;
    }
}

// releasePatternStd() removed - now using static releasePattern() above

size_t PatternCache::evictStd(
    PatternCacheMetrics& metrics,
    DeferredCache& deferred_cache,
    const std::chrono::steady_clock::time_point& now) {

    std::unique_lock lock(std_mutex_);

    size_t evicted = 0;

    // TTL eviction pass
    for (auto it = std_cache_.begin(); it != std_cache_.end(); ) {
        auto age = now - it->second.last_access;

        if (age > config_.pattern_cache_ttl_ms) {
            uint32_t rc = it->second.pattern->refcount.load(std::memory_order_acquire);
            size_t freed = it->second.pattern->approx_size_bytes;

            if (rc == 0) {
                // Safe to delete immediately
                std_total_size_bytes_ -= freed;
                it = std_cache_.erase(it);

                metrics.ttl_evictions.fetch_add(1);
                metrics.total_evictions.fetch_add(1);
                metrics.total_bytes_freed.fetch_add(freed);
            } else {
                // Still in use - move to deferred cache
                DeferredCacheMetrics deferred_metrics;
                deferred_cache.add(it->first, it->second.pattern, deferred_metrics);
                std_total_size_bytes_ -= freed;
                it = std_cache_.erase(it);

                metrics.ttl_entries_moved_to_deferred.fetch_add(1);
                metrics.total_evictions.fetch_add(1);
                metrics.total_bytes_freed.fetch_add(freed);
            }

            evicted++;
            continue;
        }

        ++it;
    }

    // LRU eviction if over capacity (batch eviction for O(n + k log k) performance)
    while (std_total_size_bytes_ > config_.pattern_cache_target_capacity_bytes && !std_cache_.empty()) {
        // Step 1: Collect candidates with refcount == 0
        std::vector<std::unordered_map<uint64_t, PatternCacheEntry>::iterator> candidates;
        for (auto it = std_cache_.begin(); it != std_cache_.end(); ++it) {
            if (it->second.pattern->refcount.load(std::memory_order_acquire) == 0) {
                candidates.push_back(it);
            }
        }

        if (candidates.empty()) break;  // No evictable entries

        // Step 2: Partial sort to find N oldest (batch_size)
        size_t batch_size = std::min(config_.pattern_cache_lru_batch_size, candidates.size());
        std::partial_sort(candidates.begin(), candidates.begin() + batch_size, candidates.end(),
            [](const auto& a, const auto& b) {
                return a->second.last_access < b->second.last_access;
            });

        // Step 3: Evict batch
        bool reached_capacity = false;
        for (size_t i = 0; i < batch_size; i++) {
            auto it = candidates[i];
            size_t freed = it->second.pattern->approx_size_bytes;

            std_total_size_bytes_ -= freed;
            std_cache_.erase(it);

            metrics.lru_evictions.fetch_add(1);
            metrics.lru_evictions_bytes_freed.fetch_add(freed);
            metrics.total_evictions.fetch_add(1);
            metrics.total_bytes_freed.fetch_add(freed);
            evicted++;

            // Stop if back under capacity
            if (std_total_size_bytes_ <= config_.pattern_cache_target_capacity_bytes) {
                reached_capacity = true;
                break;
            }
        }

        if (reached_capacity) break;
    }

    return evicted;
}

//============================================================================
// TBB concurrent_hash_map Implementation
//============================================================================

std::shared_ptr<RE2Pattern> PatternCache::getOrCompileTBB(
    uint64_t key,
    const std::string& pattern_string,
    const api::PatternOptions& options,
    PatternCacheMetrics& metrics,
    std::string& error_msg) {

    // Try cache lookup first (TBB accessor for read)
    {
        TBBMap::const_accessor acc;

        if (tbb_cache_.find(acc, key)) {
            // CACHE HIT
            // CRITICAL: Increment refcount WHILE accessor alive (holds lock)
            acc->second.pattern->refcount.fetch_add(1, std::memory_order_acq_rel);
            acc->second.last_access = std::chrono::steady_clock::now();

            metrics.hits.fetch_add(1);

            auto result = acc->second.pattern;
            // Accessor destructor releases lock
            return result;  // Safe - refcount already +1
        }
    }

    // CACHE MISS - need to compile
    metrics.misses.fetch_add(1);

    // Compile pattern (no lock held)
    auto pattern = compilePattern(pattern_string, options, error_msg);
    if (!pattern) {
        metrics.compilation_errors.fetch_add(1);
        return nullptr;
    }

    // Add to cache (TBB accessor for write)
    {
        TBBMap::accessor acc;

        if (tbb_cache_.insert(acc, key)) {
            // We inserted new entry
            pattern->refcount.store(1, std::memory_order_release);  // Initial refcount
            acc->second = PatternCacheEntry(pattern);
            tbb_total_size_bytes_.fetch_add(pattern->approx_size_bytes);
            return pattern;
        } else {
            // Another thread inserted while we were compiling
            // Use their pattern, discard ours
            acc->second.pattern->refcount.fetch_add(1, std::memory_order_acq_rel);
            acc->second.last_access = std::chrono::steady_clock::now();
            return acc->second.pattern;
        }
    }
}

// releasePatternTBB() removed - now using static releasePattern() above

size_t PatternCache::evictTBB(
    PatternCacheMetrics& metrics,
    DeferredCache& deferred_cache,
    const std::chrono::steady_clock::time_point& now) {

    size_t evicted = 0;
    std::vector<uint64_t> to_evict;

    // TTL eviction - collect keys to evict
    for (TBBMap::iterator it = tbb_cache_.begin(); it != tbb_cache_.end(); ++it) {
        auto age = now - it->second.last_access;

        if (age > config_.pattern_cache_ttl_ms) {
            to_evict.push_back(it->first);
        }
    }

    // Evict collected entries
    for (uint64_t key : to_evict) {
        TBBMap::accessor acc;

        if (tbb_cache_.find(acc, key)) {
            uint32_t rc = acc->second.pattern->refcount.load(std::memory_order_acquire);
            size_t freed = acc->second.pattern->approx_size_bytes;

            if (rc == 0) {
                // Safe to delete
                tbb_cache_.erase(acc);
                tbb_total_size_bytes_.fetch_sub(freed);

                metrics.ttl_evictions.fetch_add(1);
                metrics.total_evictions.fetch_add(1);
                metrics.total_bytes_freed.fetch_add(freed);
            } else {
                // Move to deferred
                DeferredCacheMetrics deferred_metrics;
                deferred_cache.add(key, acc->second.pattern, deferred_metrics);
                tbb_cache_.erase(acc);
                tbb_total_size_bytes_.fetch_sub(freed);

                metrics.ttl_entries_moved_to_deferred.fetch_add(1);
                metrics.total_evictions.fetch_add(1);
                metrics.total_bytes_freed.fetch_add(freed);
            }

            evicted++;
        }
    }

    // LRU eviction if over capacity (batch eviction for O(n + k log k) performance)
    size_t current_size = tbb_total_size_bytes_.load();

    while (current_size > config_.pattern_cache_target_capacity_bytes && !tbb_cache_.empty()) {
        // Step 1: Collect candidates with refcount == 0
        std::vector<TBBMap::iterator> candidates;
        for (TBBMap::iterator it = tbb_cache_.begin(); it != tbb_cache_.end(); ++it) {
            if (it->second.pattern->refcount.load(std::memory_order_acquire) == 0) {
                candidates.push_back(it);
            }
        }

        if (candidates.empty()) break;  // No evictable entries

        // Step 2: Partial sort to find N oldest (batch_size)
        size_t batch_size = std::min(config_.pattern_cache_lru_batch_size, candidates.size());
        std::partial_sort(candidates.begin(), candidates.begin() + batch_size, candidates.end(),
            [](const auto& a, const auto& b) {
                return a->second.last_access < b->second.last_access;
            });

        // Step 3: Evict batch
        bool reached_capacity = false;
        for (size_t i = 0; i < batch_size; i++) {
            uint64_t key = candidates[i]->first;

            TBBMap::accessor acc;
            if (tbb_cache_.find(acc, key)) {
                size_t freed = acc->second.pattern->approx_size_bytes;

                tbb_cache_.erase(acc);
                tbb_total_size_bytes_.fetch_sub(freed);

                metrics.lru_evictions.fetch_add(1);
                metrics.lru_evictions_bytes_freed.fetch_add(freed);
                metrics.total_evictions.fetch_add(1);
                metrics.total_bytes_freed.fetch_add(freed);
                evicted++;

                // Check if back under capacity
                current_size = tbb_total_size_bytes_.load();
                if (current_size <= config_.pattern_cache_target_capacity_bytes) {
                    reached_capacity = true;
                    break;
                }
            }
        }

        if (reached_capacity) break;
    }

    return evicted;
}

//============================================================================
// Helper Methods
//============================================================================

uint64_t PatternCache::makeKey(const std::string& pattern, const api::PatternOptions& options) const {
    // Hash: pattern_string + options hash
    uint64_t pattern_hash = hash::hashString(pattern);
    uint64_t options_hash = options.hash();
    // Combine hashes with XOR
    return pattern_hash ^ options_hash;
}

std::shared_ptr<RE2Pattern> PatternCache::compilePattern(
    const std::string& pattern_string,
    const api::PatternOptions& options,
    std::string& error_msg) {

    // Convert PatternOptions to RE2::Options
    RE2::Options re2_opts = options.toRE2Options();
    re2_opts.set_log_errors(false);  // Ensure errors not logged to stderr

    auto regex = std::make_unique<RE2>(pattern_string, re2_opts);

    if (!regex->ok()) {
        error_msg = regex->error();
        return nullptr;
    }

    // Create pattern with full options
    return std::make_shared<RE2Pattern>(std::move(regex), pattern_string, options);
}

}  // namespace cache
}  // namespace libre2
