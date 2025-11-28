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

#include <atomic>
#include <chrono>
#include <cstdint>
#include <string>

namespace libre2 {
namespace cache {

/**
 * Metrics for Pattern Result Cache.
 */
struct PatternResultCacheMetrics {
    // Hit/Miss
    std::atomic<uint64_t> hits{0};
    std::atomic<uint64_t> misses{0};

    // Errors (non-fatal)
    std::atomic<uint64_t> get_errors{0};
    std::atomic<uint64_t> put_errors{0};

    // Evictions
    std::atomic<uint64_t> ttl_evictions{0};
    std::atomic<uint64_t> lru_evictions{0};
    std::atomic<uint64_t> lru_evictions_bytes_freed{0};
    std::atomic<uint64_t> total_evictions{0};
    std::atomic<uint64_t> total_bytes_freed{0};

    // Capacity (snapshot under lock)
    uint64_t current_entry_count = 0;
    uint64_t target_capacity_bytes = 0;
    uint64_t actual_size_bytes = 0;
    double utilization_ratio = 0.0;

    // Implementation info (snapshot)
    bool using_tbb = false;

    double hit_rate() const;
    std::string toJson() const;
};

/**
 * Metrics for Pattern Compilation Cache.
 */
struct PatternCacheMetrics {
    // Hit/Miss (reuse efficiency)
    std::atomic<uint64_t> hits{0};
    std::atomic<uint64_t> misses{0};

    // Errors
    std::atomic<uint64_t> compilation_errors{0};

    // Evictions
    std::atomic<uint64_t> ttl_evictions{0};
    std::atomic<uint64_t> lru_evictions{0};
    std::atomic<uint64_t> lru_evictions_bytes_freed{0};
    std::atomic<uint64_t> ttl_entries_moved_to_deferred{0};
    std::atomic<uint64_t> lru_entries_moved_to_deferred{0};
    std::atomic<uint64_t> total_evictions{0};
    std::atomic<uint64_t> total_bytes_freed{0};

    // Capacity (snapshot under lock)
    uint64_t current_entry_count = 0;
    uint64_t target_capacity_bytes = 0;
    uint64_t actual_size_bytes = 0;
    double utilization_ratio = 0.0;

    // Implementation info (snapshot)
    bool using_tbb = false;

    double hit_rate() const;
    std::string toJson() const;
};

/**
 * Metrics for Deferred Cache.
 */
struct DeferredCacheMetrics {
    // Evictions
    std::atomic<uint64_t> immediate_evictions{0};
    std::atomic<uint64_t> immediate_evictions_bytes_freed{0};
    std::atomic<uint64_t> forced_evictions{0};
    std::atomic<uint64_t> forced_evictions_bytes_freed{0};
    std::atomic<uint64_t> total_evictions{0};
    std::atomic<uint64_t> total_bytes_freed{0};

    // Capacity (snapshot under lock)
    uint64_t current_entry_count = 0;
    uint64_t actual_size_bytes = 0;

    std::string toJson() const;
};

/**
 * RE2 Library metrics (aggregate statistics).
 */
struct RE2LibraryMetrics {
    std::atomic<uint64_t> patterns_compiled{0};
    std::atomic<uint64_t> compilation_failures{0};
    std::atomic<uint64_t> case_sensitive_patterns{0};
    std::atomic<uint64_t> case_insensitive_patterns{0};

    // Snapshot metrics (updated during eviction)
    uint64_t total_program_size_bytes = 0;
    uint64_t avg_program_size_bytes = 0;
    uint64_t max_program_size_bytes = 0;
    uint64_t min_program_size_bytes = 0;

    double avg_capturing_groups = 0.0;
    uint64_t max_capturing_groups = 0;
    uint64_t patterns_with_named_groups = 0;

    std::string toJson() const;
};

/**
 * Combined metrics for all caches and RE2 library.
 */
struct CacheMetrics {
    PatternResultCacheMetrics pattern_result_cache;
    PatternCacheMetrics pattern_cache;
    DeferredCacheMetrics deferred_cache;
    RE2LibraryMetrics re2_library;

    std::chrono::system_clock::time_point generated_at;

    /**
     * Serialize all metrics to JSON.
     *
     * @return JSON string with all metrics
     */
    std::string toJson() const;
};

}  // namespace cache
}  // namespace libre2
