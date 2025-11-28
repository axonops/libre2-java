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

#include "cache/deferred_cache.h"
#include <iostream>
#include <sstream>

namespace libre2 {
namespace cache {

DeferredCache::DeferredCache(const CacheConfig& config) : config_(config) {
    // No initialization needed (map creates on-demand)
}

DeferredCache::~DeferredCache() {
    // Clear all entries on destruction
    std::unique_lock lock(mutex_);
    cache_.clear();
    total_size_bytes_ = 0;
}

void DeferredCache::add(
    uint64_t pattern_key,
    std::shared_ptr<RE2Pattern> pattern,
    DeferredCacheMetrics& metrics) {

    if (!pattern) {
        return;  // Ignore null patterns
    }

    std::unique_lock lock(mutex_);

    // Add to cache
    auto [it, inserted] = cache_.emplace(pattern_key, DeferredEntry(pattern));
    if (inserted) {
        total_size_bytes_ += it->second.approx_size_bytes;
        metrics.total_entries_added.fetch_add(1, std::memory_order_relaxed);
    }
    // If not inserted (key already exists), we just skip (shouldn't happen, but safe)
}

size_t DeferredCache::evict(
    DeferredCacheMetrics& metrics,
    const std::chrono::steady_clock::time_point& now) {

    std::unique_lock lock(mutex_);

    size_t evicted = 0;

    for (auto it = cache_.begin(); it != cache_.end(); ) {
        auto& entry = it->second;
        uint32_t current_refcount = entry.pattern->refcount.load(std::memory_order_acquire);

        // Immediate eviction: refcount dropped to 0
        if (current_refcount == 0) {
            size_t freed = entry.approx_size_bytes;
            total_size_bytes_ -= freed;

            it = cache_.erase(it);

            metrics.immediate_evictions.fetch_add(1);
            metrics.immediate_evictions_bytes_freed.fetch_add(freed);
            metrics.total_evictions.fetch_add(1);
            metrics.total_bytes_freed.fetch_add(freed);

            evicted++;
            continue;
        }

        // Forced eviction: TTL expired (LEAK DETECTED!)
        auto age = now - entry.entered_deferred;
        if (age > config_.deferred_cache_ttl_ms) {
            size_t freed = entry.approx_size_bytes;
            total_size_bytes_ -= freed;

            // TODO: Replace with proper logger when C++ logging infrastructure added
            // For now, stderr is acceptable for critical leak detection warnings
            std::cerr << "RE2 LEAK WARNING: Pattern in deferred cache for "
                      << std::chrono::duration_cast<std::chrono::minutes>(age).count()
                      << " minutes (refcount=" << current_refcount
                      << "), forcing eviction to prevent memory leak" << std::endl;

            it = cache_.erase(it);

            metrics.forced_evictions.fetch_add(1);
            metrics.forced_evictions_bytes_freed.fetch_add(freed);
            metrics.total_evictions.fetch_add(1);
            metrics.total_bytes_freed.fetch_add(freed);

            evicted++;
            continue;
        }

        ++it;
    }

    return evicted;
}

void DeferredCache::clear() {
    std::unique_lock lock(mutex_);
    cache_.clear();
    total_size_bytes_ = 0;
}

void DeferredCache::snapshotMetrics(DeferredCacheMetrics& metrics) const {
    std::shared_lock lock(mutex_);
    metrics.current_entry_count = cache_.size();
    metrics.actual_size_bytes = total_size_bytes_;
}

size_t DeferredCache::size() const {
    std::shared_lock lock(mutex_);
    return cache_.size();
}

std::string DeferredCache::dumpDeferredCache() const {
    std::shared_lock lock(mutex_);

    std::ostringstream oss;
    oss << "Deferred Cache Dump (" << cache_.size() << " entries, "
        << total_size_bytes_ << " bytes):\n";

    auto now = std::chrono::steady_clock::now();
    for (const auto& [key, entry] : cache_) {
        auto age_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            now - entry.entered_deferred).count();
        uint32_t rc = entry.pattern->refcount.load(std::memory_order_acquire);

        oss << "  Key: " << std::hex << key << std::dec
            << ", Pattern: \"" << entry.pattern->pattern_string << "\""
            << ", Refcount: " << rc
            << ", Age: " << age_ms << "ms"
            << ", Size: " << entry.approx_size_bytes << " bytes\n";
    }

    return oss.str();
}

}  // namespace cache
}  // namespace libre2
