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

#include "libre2_api.h"
#include "cache/cache_manager.h"
#include "cache/murmur_hash3.h"
#include <atomic>
#include <memory>
#include <mutex>
#include <stdexcept>

namespace libre2 {
namespace api {

//============================================================================
// Global State (NO Lazy Init - Explicit Only)
//============================================================================

static std::atomic<cache::CacheManager*> g_cache_manager{nullptr};
static std::mutex g_init_mutex;

//============================================================================
// Public API
//============================================================================

cache::RE2Pattern* compilePattern(
    const std::string& pattern,
    bool case_sensitive,
    std::string& error_out) {

    cache::CacheManager* mgr = g_cache_manager.load(std::memory_order_acquire);

    if (mgr == nullptr) {
        // NO CACHE - Compile directly with raw RE2
        RE2::Options opts;
        opts.set_case_sensitive(case_sensitive);
        opts.set_log_errors(false);

        auto regex = std::make_unique<RE2>(pattern, opts);

        if (!regex->ok()) {
            error_out = regex->error();
            return nullptr;
        }

        // Create RE2Pattern wrapper (no caching, refcount stays 0)
        auto* pattern_wrapper = new cache::RE2Pattern(std::move(regex), pattern, case_sensitive);
        return pattern_wrapper;
    }

    // CACHE ENABLED - Use cached compilation
    cache::PatternCacheMetrics metrics;  // Local metrics
    auto shared_pattern = mgr->patternCache().getOrCompile(pattern, case_sensitive, metrics, error_out);

    if (!shared_pattern) {
        return nullptr;  // Compilation error
    }

    // Return raw pointer (cache holds shared_ptr)
    return shared_pattern.get();
}

void releasePattern(cache::RE2Pattern* pattern) {
    if (!pattern) {
        return;  // Null-safe
    }

    cache::CacheManager* mgr = g_cache_manager.load(std::memory_order_acquire);

    if (mgr == nullptr) {
        // NO CACHE - Pattern was compiled directly, delete immediately
        delete pattern;
        return;
    }

    // CACHE ENABLED - Decrement refcount (eviction cleans up when refcount=0)
    cache::PatternCache::releasePattern(pattern);
}

bool fullMatch(cache::RE2Pattern* pattern, std::string_view text) {
    if (!pattern || !pattern->isValid()) {
        return false;  // Invalid pattern
    }

    return RE2::FullMatch(text, *pattern->compiled_regex);
}

bool fullMatch(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string* capture1) {

    if (!pattern || !pattern->isValid()) {
        return false;  // Invalid pattern
    }

    return RE2::FullMatch(text, *pattern->compiled_regex, capture1);
}

bool fullMatch(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string* capture1,
    std::string* capture2) {

    if (!pattern || !pattern->isValid()) {
        return false;  // Invalid pattern
    }

    return RE2::FullMatch(text, *pattern->compiled_regex, capture1, capture2);
}

bool partialMatch(cache::RE2Pattern* pattern, std::string_view text) {
    if (!pattern || !pattern->isValid()) {
        return false;  // Invalid pattern
    }

    return RE2::PartialMatch(text, *pattern->compiled_regex);
}

bool partialMatch(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string* capture1) {

    if (!pattern || !pattern->isValid()) {
        return false;  // Invalid pattern
    }

    return RE2::PartialMatch(text, *pattern->compiled_regex, capture1);
}

bool partialMatch(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string* capture1,
    std::string* capture2) {

    if (!pattern || !pattern->isValid()) {
        return false;  // Invalid pattern
    }

    return RE2::PartialMatch(text, *pattern->compiled_regex, capture1, capture2);
}

std::string getMetricsJSON() {
    cache::CacheManager* mgr = g_cache_manager.load(std::memory_order_acquire);

    if (!mgr) {
        // Cache not initialized - return empty metrics
        cache::CacheMetrics empty;
        empty.generated_at = std::chrono::system_clock::now();
        return empty.toJson();
    }

    return mgr->getMetricsJSON();
}

void initCache(const std::string& json_config) {
    std::lock_guard<std::mutex> lock(g_init_mutex);

    if (g_cache_manager.load(std::memory_order_acquire) != nullptr) {
        throw std::runtime_error("Cache already initialized");
    }

    // Parse config (or use defaults if empty)
    cache::CacheConfig config;
    if (json_config.empty()) {
        // Default configuration (all caching enabled, auto-start eviction)
        std::string default_json = R"({
            "cache_enabled": true,
            "pattern_result_cache_enabled": true,
            "pattern_result_cache_target_capacity_bytes": 104857600,
            "pattern_result_cache_string_threshold_bytes": 10240,
            "pattern_result_cache_ttl_ms": 300000,
            "pattern_result_cache_use_tbb": false,
            "pattern_cache_target_capacity_bytes": 104857600,
            "pattern_cache_ttl_ms": 300000,
            "pattern_cache_use_tbb": false,
            "pattern_cache_lru_batch_size": 100,
            "deferred_cache_ttl_ms": 600000,
            "auto_start_eviction_thread": true,
            "eviction_check_interval_ms": 100
        })";
        config = cache::CacheConfig::fromJson(default_json);
    } else {
        config = cache::CacheConfig::fromJson(json_config);
    }

    // Create cache manager
    cache::CacheManager* new_mgr = new cache::CacheManager(config);
    g_cache_manager.store(new_mgr, std::memory_order_release);
}

void shutdownCache() {
    cache::CacheManager* mgr = g_cache_manager.exchange(nullptr, std::memory_order_acq_rel);

    if (mgr) {
        delete mgr;  // Destructor stops eviction, clears caches
    }
}

bool isCacheInitialized() {
    return g_cache_manager.load(std::memory_order_acquire) != nullptr;
}

}  // namespace api
}  // namespace libre2
