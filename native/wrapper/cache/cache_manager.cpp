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

#include "cache/cache_manager.h"

namespace libre2 {
namespace cache {

CacheManager::CacheManager(const CacheConfig& config)
    : config_(config),
      result_cache_(config),
      pattern_cache_(config),
      deferred_cache_(config) {

    // Create eviction thread (initialized after caches)
    eviction_thread_ = std::make_unique<EvictionThread>(
        config_,
        result_cache_,
        pattern_cache_,
        deferred_cache_,
        metrics_);

    // Auto-start if configured
    if (config_.auto_start_eviction_thread) {
        startEvictionThread();
    }
}

CacheManager::~CacheManager() {
    // Stop eviction thread first
    stopEvictionThread();

    // Clear all caches (move in-use patterns to deferred)
    pattern_cache_.clear(deferred_cache_);
    result_cache_.clear();

    // Final cleanup of deferred cache
    deferred_cache_.clear();
}

void CacheManager::startEvictionThread() {
    if (eviction_thread_) {
        eviction_thread_->start();
    }
}

void CacheManager::stopEvictionThread() {
    if (eviction_thread_) {
        eviction_thread_->stop();
    }
}

bool CacheManager::isEvictionThreadRunning() const {
    return eviction_thread_ && eviction_thread_->isRunning();
}

std::string CacheManager::getMetricsJSON() const {
    // Snapshot all caches first
    result_cache_.snapshotMetrics(const_cast<PatternResultCacheMetrics&>(metrics_.pattern_result_cache));
    pattern_cache_.snapshotMetrics(const_cast<PatternCacheMetrics&>(metrics_.pattern_cache));
    deferred_cache_.snapshotMetrics(const_cast<DeferredCacheMetrics&>(metrics_.deferred_cache));

    // Update timestamp
    const_cast<CacheMetrics&>(metrics_).generated_at = std::chrono::system_clock::now();

    return metrics_.toJson();
}

void CacheManager::clearAllCaches() {
    // Stop eviction first
    stopEvictionThread();

    // Clear all caches
    pattern_cache_.clear(deferred_cache_);
    result_cache_.clear();
    deferred_cache_.clear();

    // Note: Metrics are cumulative counters, not reset
    // Snapshot metrics will show 0 entries after clear

    // Restart eviction if it was running
    if (config_.auto_start_eviction_thread) {
        startEvictionThread();
    }
}

}  // namespace cache
}  // namespace libre2
