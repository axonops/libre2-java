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

#include "cache/cache_metrics.h"
#include <nlohmann/json.hpp>
#include <iomanip>
#include <sstream>

using json = nlohmann::json;

namespace libre2 {
namespace cache {

// Helper to format ISO 8601 timestamp
static std::string formatISO8601(const std::chrono::system_clock::time_point& tp) {
    auto time_t = std::chrono::system_clock::to_time_t(tp);
    std::tm tm;
    #ifdef _WIN32
        gmtime_s(&tm, &time_t);
    #else
        gmtime_r(&time_t, &tm);
    #endif

    std::ostringstream oss;
    oss << std::put_time(&tm, "%Y-%m-%dT%H:%M:%SZ");
    return oss.str();
}

//============================================================================
// Pattern Result Cache Metrics
//============================================================================

double PatternResultCacheMetrics::hit_rate() const {
    uint64_t h = hits.load();
    uint64_t m = misses.load();
    return (h + m) > 0 ? (100.0 * h) / (h + m) : 0.0;
}

std::string PatternResultCacheMetrics::toJson() const {
    json j;

    // Hit/Miss
    j["hits"] = hits.load();
    j["misses"] = misses.load();
    j["hit_rate"] = hit_rate();

    // Errors
    j["get_errors"] = get_errors.load();
    j["put_errors"] = put_errors.load();

    // Evictions
    json evictions;
    evictions["ttl"] = ttl_evictions.load();
    evictions["lru"] = lru_evictions.load();
    evictions["lru_bytes_freed"] = lru_evictions_bytes_freed.load();
    evictions["total_evictions"] = total_evictions.load();
    evictions["total_bytes_freed"] = total_bytes_freed.load();
    j["evictions"] = evictions;

    // Capacity (snapshot)
    json capacity;
    capacity["target_bytes"] = target_capacity_bytes;
    capacity["actual_bytes"] = actual_size_bytes;
    capacity["entry_count"] = current_entry_count;
    capacity["utilization_ratio"] = utilization_ratio;
    j["capacity"] = capacity;

    // Implementation info
    j["using_tbb"] = using_tbb;

    return j.dump();
}

//============================================================================
// Pattern Compilation Cache Metrics
//============================================================================

double PatternCacheMetrics::hit_rate() const {
    uint64_t h = hits.load();
    uint64_t m = misses.load();
    return (h + m) > 0 ? (100.0 * h) / (h + m) : 0.0;
}

std::string PatternCacheMetrics::toJson() const {
    json j;

    // Hit/Miss
    j["hits"] = hits.load();
    j["misses"] = misses.load();
    j["hit_rate"] = hit_rate();

    // Errors
    j["compilation_errors"] = compilation_errors.load();

    // Evictions
    json evictions;
    evictions["ttl"] = ttl_evictions.load();
    evictions["lru"] = lru_evictions.load();
    evictions["lru_bytes_freed"] = lru_evictions_bytes_freed.load();
    evictions["ttl_moved_to_deferred"] = ttl_entries_moved_to_deferred.load();
    evictions["lru_moved_to_deferred"] = lru_entries_moved_to_deferred.load();
    evictions["total_evictions"] = total_evictions.load();
    evictions["total_bytes_freed"] = total_bytes_freed.load();
    j["evictions"] = evictions;

    // Capacity (snapshot)
    json capacity;
    capacity["target_bytes"] = target_capacity_bytes;
    capacity["actual_bytes"] = actual_size_bytes;
    capacity["entry_count"] = current_entry_count;
    capacity["utilization_ratio"] = utilization_ratio;
    j["capacity"] = capacity;

    // Implementation info
    j["using_tbb"] = using_tbb;

    return j.dump();
}

//============================================================================
// Deferred Cache Metrics
//============================================================================

std::string DeferredCacheMetrics::toJson() const {
    json j;

    // Evictions
    json evictions;
    evictions["immediate"] = immediate_evictions.load();
    evictions["immediate_bytes_freed"] = immediate_evictions_bytes_freed.load();
    evictions["forced"] = forced_evictions.load();
    evictions["forced_bytes_freed"] = forced_evictions_bytes_freed.load();
    evictions["total_evictions"] = total_evictions.load();
    evictions["total_bytes_freed"] = total_bytes_freed.load();
    j["evictions"] = evictions;

    // Capacity (snapshot)
    json capacity;
    capacity["actual_bytes"] = actual_size_bytes;
    capacity["entry_count"] = current_entry_count;
    j["capacity"] = capacity;

    return j.dump();
}

//============================================================================
// RE2 Library Metrics
//============================================================================

std::string RE2LibraryMetrics::toJson() const {
    json j;

    // Program size statistics
    json program_size;
    program_size["total_bytes"] = total_program_size_bytes;
    program_size["average_bytes"] = avg_program_size_bytes;
    program_size["max_bytes"] = max_program_size_bytes;
    program_size["min_bytes"] = min_program_size_bytes;
    j["program_size"] = program_size;

    // Pattern statistics
    json patterns;
    patterns["total_compiled"] = patterns_compiled.load();
    patterns["compilation_failures"] = compilation_failures.load();
    patterns["case_sensitive"] = case_sensitive_patterns.load();
    patterns["case_insensitive"] = case_insensitive_patterns.load();
    j["patterns"] = patterns;

    // Capturing groups statistics
    json groups;
    groups["avg_per_pattern"] = avg_capturing_groups;
    groups["max_per_pattern"] = max_capturing_groups;
    groups["patterns_with_named_groups"] = patterns_with_named_groups;
    j["capturing_groups"] = groups;

    return j.dump();
}

//============================================================================
// Combined Cache Metrics
//============================================================================

std::string CacheMetrics::toJson() const {
    json j;

    // Parse each cache's JSON and insert into main object
    j["pattern_result_cache"] = json::parse(pattern_result_cache.toJson());
    j["pattern_cache"] = json::parse(pattern_cache.toJson());
    j["deferred_cache"] = json::parse(deferred_cache.toJson());
    j["re2_library"] = json::parse(re2_library.toJson());

    // Timestamp
    j["generated_at"] = formatISO8601(generated_at);

    return j.dump(2);  // Pretty-print with 2-space indent
}

}  // namespace cache
}  // namespace libre2
