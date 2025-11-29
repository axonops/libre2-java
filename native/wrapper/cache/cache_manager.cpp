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
    // Create fresh snapshot (thread-safe - independent of eviction thread's metrics_)
    CacheMetrics snapshot;

    result_cache_.snapshotMetrics(snapshot.pattern_result_cache);
    pattern_cache_.snapshotMetrics(snapshot.pattern_cache);
    deferred_cache_.snapshotMetrics(snapshot.deferred_cache);

    snapshot.generated_at = std::chrono::system_clock::now();

    return snapshot.toJson();
}

void CacheManager::clearAllCaches() {
    // Remember if eviction was running before clear
    bool was_running = isEvictionThreadRunning();

    // Stop eviction thread
    stopEvictionThread();

    // Clear all caches
    pattern_cache_.clear(deferred_cache_);
    result_cache_.clear();
    deferred_cache_.clear();

    // Restart only if it WAS running before clear
    if (was_running) {
        startEvictionThread();
    }
}

}  // namespace cache
}  // namespace libre2
