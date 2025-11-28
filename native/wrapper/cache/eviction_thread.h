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

namespace libre2 {
namespace cache {

/**
 * Background eviction thread for all caches.
 *
 * Runs every eviction_check_interval_ms (default 100ms).
 * Handles TTL + LRU eviction for all three caches.
 */
class EvictionThread {
public:
    explicit EvictionThread(const CacheConfig& config);
    ~EvictionThread();

    // TODO: Implement thread control

private:
    const CacheConfig& config_;
};

}  // namespace cache
}  // namespace libre2
