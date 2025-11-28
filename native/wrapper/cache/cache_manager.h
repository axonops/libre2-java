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

namespace libre2 {
namespace cache {

/**
 * Cache Manager - orchestrates all three caches.
 *
 * Singleton instance manages pattern compilation, result caching,
 * deferred cleanup, and background eviction.
 */
class CacheManager {
public:
    explicit CacheManager(const CacheConfig& config);
    ~CacheManager();

    // TODO: Implement cache operations

private:
    CacheConfig config_;
    CacheMetrics metrics_;

    ResultCache result_cache_;
    PatternCache pattern_cache_;
    DeferredCache deferred_cache_;
    EvictionThread eviction_thread_;
};

}  // namespace cache
}  // namespace libre2
