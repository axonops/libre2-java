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

#include <chrono>
#include <cstddef>
#include <string>

namespace libre2 {
namespace cache {

/**
 * Configuration for RE2 pattern caching.
 *
 * All parameters configurable via JSON. No hardcoded defaults in struct.
 */
struct CacheConfig {
    // Global
    bool cache_enabled;

    // Pattern Result Cache (optional)
    bool pattern_result_cache_enabled;
    size_t pattern_result_cache_target_capacity_bytes;
    size_t pattern_result_cache_string_threshold_bytes;
    std::chrono::milliseconds pattern_result_cache_ttl_ms;
    bool pattern_result_cache_use_tbb;  // Use TBB concurrent_hash_map (default: false)

    // Pattern Compilation Cache (reference-counted)
    size_t pattern_cache_target_capacity_bytes;
    std::chrono::milliseconds pattern_cache_ttl_ms;
    bool pattern_cache_use_tbb;  // Use TBB concurrent_hash_map (default: false)
    size_t pattern_cache_lru_batch_size;  // Batch eviction size (default: 100)

    // Deferred Cache (leak protection)
    std::chrono::milliseconds deferred_cache_ttl_ms;

    // Background Eviction Thread
    bool auto_start_eviction_thread;
    std::chrono::milliseconds eviction_check_interval_ms;

    /**
     * Parse configuration from JSON string.
     *
     * @param json JSON configuration string
     * @return parsed configuration with defaults applied
     * @throws std::runtime_error if JSON invalid or validation fails
     */
    static CacheConfig fromJson(const std::string& json);

    /**
     * Validate configuration parameters.
     *
     * @throws std::invalid_argument if configuration invalid
     */
    void validate() const;

    /**
     * Serialize configuration to JSON (for debugging).
     *
     * @return JSON string
     */
    std::string toJson() const;
};

}  // namespace cache
}  // namespace libre2
