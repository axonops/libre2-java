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

#include "cache/result_cache.h"
#include <algorithm>
#include <vector>

namespace libre2 {
namespace cache {

//============================================================================
// Constructor / Destructor
//============================================================================

ResultCache::ResultCache(const CacheConfig& config)
    : config_(config),
      using_tbb_(config.pattern_result_cache_use_tbb) {
    // Both implementations always present (zero overhead when not used)
}

ResultCache::~ResultCache() {
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

std::optional<bool> ResultCache::get(
    uint64_t pattern_hash,
    const std::string& input_string,
    PatternResultCacheMetrics& metrics) {

    // Check if Result Cache enabled
    if (!config_.pattern_result_cache_enabled) {
        return std::nullopt;
    }

    uint64_t key = makeKey(pattern_hash, input_string);

    if (using_tbb_) {
        return getTBB(key, metrics);
    } else {
        return getStd(key, metrics);
    }
}

void ResultCache::put(
    uint64_t pattern_hash,
    const std::string& input_string,
    bool match_result,
    PatternResultCacheMetrics& metrics) {

    // Check if Result Cache enabled
    if (!config_.pattern_result_cache_enabled) {
        return;
    }

    // Check string threshold (don't cache large strings)
    if (input_string.size() > config_.pattern_result_cache_string_threshold_bytes) {
        return;  // Silent skip (this is not an error)
    }

    uint64_t key = makeKey(pattern_hash, input_string);

    if (using_tbb_) {
        putTBB(key, input_string, match_result, metrics);
    } else {
        putStd(key, input_string, match_result, metrics);
    }
}

size_t ResultCache::evict(
    PatternResultCacheMetrics& metrics,
    const std::chrono::steady_clock::time_point& now) {

    if (using_tbb_) {
        return evictTBB(metrics, now);
    } else {
        return evictStd(metrics, now);
    }
}

void ResultCache::clear() {
    if (using_tbb_) {
        tbb_cache_.clear();
        tbb_total_size_bytes_.store(0, std::memory_order_release);
    } else {
        std::unique_lock lock(std_mutex_);
        std_cache_.clear();
        std_total_size_bytes_ = 0;
    }
}

void ResultCache::snapshotMetrics(PatternResultCacheMetrics& metrics) const {
    if (using_tbb_) {
        metrics.current_entry_count = tbb_cache_.size();
        metrics.actual_size_bytes = tbb_total_size_bytes_.load(std::memory_order_acquire);
    } else {
        std::shared_lock lock(std_mutex_);
        metrics.current_entry_count = std_cache_.size();
        metrics.actual_size_bytes = std_total_size_bytes_;
    }

    metrics.target_capacity_bytes = config_.pattern_result_cache_target_capacity_bytes;
    metrics.utilization_ratio = metrics.target_capacity_bytes > 0
        ? static_cast<double>(metrics.actual_size_bytes) / metrics.target_capacity_bytes
        : 0.0;
    metrics.using_tbb = using_tbb_;
}

size_t ResultCache::size() const {
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

std::optional<bool> ResultCache::getStd(
    uint64_t key,
    PatternResultCacheMetrics& metrics) {

    try {
        std::shared_lock lock(std_mutex_);
        auto it = std_cache_.find(key);

        if (it != std_cache_.end()) {
            // CACHE HIT
            it->second.last_access = std::chrono::steady_clock::now();
            metrics.hits.fetch_add(1, std::memory_order_relaxed);
            return it->second.match_result;
        }

        // CACHE MISS
        metrics.misses.fetch_add(1, std::memory_order_relaxed);
        return std::nullopt;

    } catch (const std::exception&) {
        // Non-fatal error: log metric, return miss
        metrics.get_errors.fetch_add(1, std::memory_order_relaxed);
        return std::nullopt;
    }
}

void ResultCache::putStd(
    uint64_t key,
    const std::string& input_string,
    bool match_result,
    PatternResultCacheMetrics& metrics) {

    try {
        std::unique_lock lock(std_mutex_);

        // Check if already exists (update if so)
        auto it = std_cache_.find(key);
        if (it != std_cache_.end()) {
            bool old_result = it->second.match_result;
            it->second.match_result = match_result;
            it->second.last_access = std::chrono::steady_clock::now();

            if (old_result != match_result) {
                metrics.result_flips.fetch_add(1, std::memory_order_relaxed);
            }
            metrics.updates.fetch_add(1, std::memory_order_relaxed);
            return;  // Updated existing entry
        }

        // Insert new entry (fixed size - string not stored!)
        auto [inserted_it, inserted] = std_cache_.emplace(
            key, ResultCacheEntry(match_result, 0));

        if (inserted) {
            std_total_size_bytes_ += RESULT_CACHE_ENTRY_SIZE;
            metrics.inserts.fetch_add(1, std::memory_order_relaxed);
        }

    } catch (const std::exception&) {
        // Non-fatal error: log metric, skip caching
        metrics.put_errors.fetch_add(1, std::memory_order_relaxed);
    }
}

size_t ResultCache::evictStd(
    PatternResultCacheMetrics& metrics,
    const std::chrono::steady_clock::time_point& now) {

    std::unique_lock lock(std_mutex_);

    size_t evicted = 0;

    // TTL eviction pass
    for (auto it = std_cache_.begin(); it != std_cache_.end(); ) {
        auto age = now - it->second.last_access;

        if (age > config_.pattern_result_cache_ttl_ms) {
            size_t freed = it->second.approx_size_bytes;
            std_total_size_bytes_ -= freed;
            it = std_cache_.erase(it);

            metrics.ttl_evictions.fetch_add(1, std::memory_order_relaxed);
            metrics.total_evictions.fetch_add(1, std::memory_order_relaxed);
            metrics.total_bytes_freed.fetch_add(freed, std::memory_order_relaxed);
            evicted++;
            continue;
        }

        ++it;
    }

    // LRU eviction if over capacity (batch eviction for performance)
    while (std_total_size_bytes_ > config_.pattern_result_cache_target_capacity_bytes
           && !std_cache_.empty()) {

        // Collect keys (not iterators - prevents invalidation!)
        std::vector<uint64_t> lru_keys;
        for (auto& [key, entry] : std_cache_) {
            lru_keys.push_back(key);
        }

        if (lru_keys.empty()) break;

        // Partial sort to find N oldest
        size_t batch_size = std::min(size_t(100), lru_keys.size());
        std::partial_sort(lru_keys.begin(), lru_keys.begin() + batch_size, lru_keys.end(),
            [this](uint64_t a, uint64_t b) {
                auto it_a = std_cache_.find(a);
                auto it_b = std_cache_.find(b);
                if (it_a != std_cache_.end() && it_b != std_cache_.end()) {
                    return it_a->second.last_access < it_b->second.last_access;
                }
                return false;
            });

        // Evict batch using fresh lookups (safe from iterator invalidation)
        bool reached_capacity = false;
        for (size_t i = 0; i < batch_size; i++) {
            uint64_t key = lru_keys[i];

            auto it = std_cache_.find(key);
            if (it != std_cache_.end()) {
                size_t freed = it->second.approx_size_bytes;

                std_total_size_bytes_ -= freed;
                std_cache_.erase(it);

                metrics.lru_evictions.fetch_add(1, std::memory_order_relaxed);
                metrics.lru_evictions_bytes_freed.fetch_add(freed, std::memory_order_relaxed);
                metrics.total_evictions.fetch_add(1, std::memory_order_relaxed);
                metrics.total_bytes_freed.fetch_add(freed, std::memory_order_relaxed);
                evicted++;

                if (std_total_size_bytes_ <= config_.pattern_result_cache_target_capacity_bytes) {
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
// TBB concurrent_hash_map Implementation
//============================================================================

std::optional<bool> ResultCache::getTBB(
    uint64_t key,
    PatternResultCacheMetrics& metrics) {

    try {
        TBBMap::const_accessor acc;

        if (tbb_cache_.find(acc, key)) {
            // CACHE HIT
            acc->second.last_access = std::chrono::steady_clock::now();
            metrics.hits.fetch_add(1, std::memory_order_relaxed);
            return acc->second.match_result;
        }

        // CACHE MISS
        metrics.misses.fetch_add(1, std::memory_order_relaxed);
        return std::nullopt;

    } catch (const std::exception&) {
        // Non-fatal error: log metric, return miss
        metrics.get_errors.fetch_add(1, std::memory_order_relaxed);
        return std::nullopt;
    }
}

void ResultCache::putTBB(
    uint64_t key,
    const std::string& input_string,
    bool match_result,
    PatternResultCacheMetrics& metrics) {

    try {
        TBBMap::accessor acc;

        if (tbb_cache_.insert(acc, key)) {
            // Inserted new entry (fixed size - string not stored!)
            acc->second = ResultCacheEntry(match_result, 0);
            tbb_total_size_bytes_.fetch_add(RESULT_CACHE_ENTRY_SIZE, std::memory_order_relaxed);
            metrics.inserts.fetch_add(1, std::memory_order_relaxed);
        } else {
            // Entry already exists - update it
            bool old_result = acc->second.match_result;
            acc->second.match_result = match_result;
            acc->second.last_access = std::chrono::steady_clock::now();

            if (old_result != match_result) {
                metrics.result_flips.fetch_add(1, std::memory_order_relaxed);
            }
            metrics.updates.fetch_add(1, std::memory_order_relaxed);
        }

    } catch (const std::exception&) {
        // Non-fatal error: log metric, skip caching
        metrics.put_errors.fetch_add(1, std::memory_order_relaxed);
    }
}

size_t ResultCache::evictTBB(
    PatternResultCacheMetrics& metrics,
    const std::chrono::steady_clock::time_point& now) {

    size_t evicted = 0;
    std::vector<uint64_t> to_evict;

    // TTL eviction - collect keys to evict
    for (TBBMap::iterator it = tbb_cache_.begin(); it != tbb_cache_.end(); ++it) {
        auto age = now - it->second.last_access;

        if (age > config_.pattern_result_cache_ttl_ms) {
            to_evict.push_back(it->first);
        }
    }

    // Evict collected entries
    for (uint64_t key : to_evict) {
        TBBMap::accessor acc;

        if (tbb_cache_.find(acc, key)) {
            size_t freed = acc->second.approx_size_bytes;
            tbb_cache_.erase(acc);
            tbb_total_size_bytes_.fetch_sub(freed, std::memory_order_relaxed);

            metrics.ttl_evictions.fetch_add(1, std::memory_order_relaxed);
            metrics.total_evictions.fetch_add(1, std::memory_order_relaxed);
            metrics.total_bytes_freed.fetch_add(freed, std::memory_order_relaxed);
            evicted++;
        }
    }

    // LRU eviction if over capacity (batch eviction)
    size_t current_size = tbb_total_size_bytes_.load(std::memory_order_acquire);

    while (current_size > config_.pattern_result_cache_target_capacity_bytes && !tbb_cache_.empty()) {
        // Collect keys (consistent with std path, prevents iterator issues)
        std::vector<uint64_t> lru_keys;
        for (TBBMap::iterator it = tbb_cache_.begin(); it != tbb_cache_.end(); ++it) {
            lru_keys.push_back(it->first);
        }

        if (lru_keys.empty()) break;

        // Partial sort to find N oldest
        size_t batch_size = std::min(size_t(100), lru_keys.size());
        std::partial_sort(lru_keys.begin(), lru_keys.begin() + batch_size, lru_keys.end(),
            [this](uint64_t a, uint64_t b) {
                TBBMap::const_accessor acc_a, acc_b;
                bool found_a = tbb_cache_.find(acc_a, a);
                bool found_b = tbb_cache_.find(acc_b, b);
                if (found_a && found_b) {
                    return acc_a->second.last_access < acc_b->second.last_access;
                }
                return false;
            });

        // Evict batch using fresh lookups
        bool reached_capacity = false;
        for (size_t i = 0; i < batch_size; i++) {
            uint64_t key = lru_keys[i];

            TBBMap::accessor acc;
            if (tbb_cache_.find(acc, key)) {
                size_t freed = acc->second.approx_size_bytes;

                tbb_cache_.erase(acc);
                tbb_total_size_bytes_.fetch_sub(freed, std::memory_order_relaxed);

                metrics.lru_evictions.fetch_add(1, std::memory_order_relaxed);
                metrics.lru_evictions_bytes_freed.fetch_add(freed, std::memory_order_relaxed);
                metrics.total_evictions.fetch_add(1, std::memory_order_relaxed);
                metrics.total_bytes_freed.fetch_add(freed, std::memory_order_relaxed);
                evicted++;

                current_size = tbb_total_size_bytes_.load(std::memory_order_acquire);
                if (current_size <= config_.pattern_result_cache_target_capacity_bytes) {
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

uint64_t ResultCache::makeKey(uint64_t pattern_hash, const std::string& input_string) const {
    // Combine pattern hash + input string hash
    uint64_t input_hash = hash::hashString(input_string);

    // Combine two hashes (simple XOR + rotate is sufficient for cache key)
    return pattern_hash ^ (input_hash + 0x9e3779b97f4a7c15ULL + (pattern_hash << 6) + (pattern_hash >> 2));
}

}  // namespace cache
}  // namespace libre2
