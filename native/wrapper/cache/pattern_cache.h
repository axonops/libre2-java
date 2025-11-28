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

namespace libre2 {
namespace cache {

/**
 * Pattern Compilation Cache - caches compiled RE2 patterns with refcounting.
 *
 * Thread-safe with shared_mutex (RwLock).
 * Patterns with refcount > 0 moved to DeferredCache on eviction.
 */
class PatternCache {
public:
    explicit PatternCache(const CacheConfig& config);
    ~PatternCache();

    // TODO: Implement cache operations

private:
    const CacheConfig& config_;
};

}  // namespace cache
}  // namespace libre2
