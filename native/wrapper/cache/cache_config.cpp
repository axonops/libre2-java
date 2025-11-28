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

#include "cache/cache_config.h"
#include <nlohmann/json.hpp>
#include <stdexcept>
#include <sstream>

using json = nlohmann::json;

namespace libre2 {
namespace cache {

CacheConfig CacheConfig::fromJson(const std::string& json_str) {
    CacheConfig config;

    try {
        json j = json::parse(json_str);

        // Global caching
        config.cache_enabled = j.value("cache_enabled", true);

        // Pattern Result Cache (optional)
        config.pattern_result_cache_enabled = j.value("pattern_result_cache_enabled", true);
        config.pattern_result_cache_target_capacity_bytes =
            j.value("pattern_result_cache_target_capacity_bytes", 100 * 1024 * 1024UL);
        config.pattern_result_cache_string_threshold_bytes =
            j.value("pattern_result_cache_string_threshold_bytes", 10 * 1024UL);
        config.pattern_result_cache_ttl_ms = std::chrono::milliseconds(
            j.value("pattern_result_cache_ttl_ms", 300000));  // 5 min default
        config.pattern_result_cache_use_tbb = j.value("pattern_result_cache_use_tbb", false);

        // Pattern Compilation Cache (reference-counted)
        config.pattern_cache_target_capacity_bytes =
            j.value("pattern_cache_target_capacity_bytes", 100 * 1024 * 1024UL);
        config.pattern_cache_ttl_ms = std::chrono::milliseconds(
            j.value("pattern_cache_ttl_ms", 300000));  // 5 min default
        config.pattern_cache_use_tbb = j.value("pattern_cache_use_tbb", false);
        config.pattern_cache_lru_batch_size = j.value("pattern_cache_lru_batch_size", 100UL);

        // Deferred Cache (leak protection)
        config.deferred_cache_ttl_ms = std::chrono::milliseconds(
            j.value("deferred_cache_ttl_ms", 600000));  // 10 min default

        // Background Eviction Thread
        config.auto_start_eviction_thread = j.value("auto_start_eviction_thread", true);
        config.eviction_check_interval_ms = std::chrono::milliseconds(
            j.value("eviction_check_interval_ms", 100));  // 100ms default

        // Validate
        config.validate();

        return config;

    } catch (const json::parse_error& e) {
        std::ostringstream msg;
        msg << "Failed to parse cache configuration JSON: " << e.what();
        throw std::runtime_error(msg.str());
    } catch (const json::type_error& e) {
        std::ostringstream msg;
        msg << "Invalid type in cache configuration JSON: " << e.what();
        throw std::runtime_error(msg.str());
    }
}

void CacheConfig::validate() const {
    // Global validation - if cache disabled, skip other checks
    if (!cache_enabled) {
        return;
    }

    // Pattern Result Cache validation (if enabled)
    if (pattern_result_cache_enabled) {
        if (pattern_result_cache_target_capacity_bytes == 0) {
            throw std::invalid_argument(
                "pattern_result_cache_target_capacity_bytes must be > 0 when enabled");
        }
        if (pattern_result_cache_string_threshold_bytes == 0) {
            throw std::invalid_argument(
                "pattern_result_cache_string_threshold_bytes must be > 0");
        }
        if (pattern_result_cache_ttl_ms.count() <= 0) {
            throw std::invalid_argument(
                "pattern_result_cache_ttl_ms must be > 0 when enabled");
        }
    }

    // Pattern Compilation Cache validation
    if (pattern_cache_target_capacity_bytes == 0) {
        throw std::invalid_argument(
            "pattern_cache_target_capacity_bytes must be > 0");
    }
    if (pattern_cache_ttl_ms.count() <= 0) {
        throw std::invalid_argument(
            "pattern_cache_ttl_ms must be > 0");
    }
    if (pattern_cache_lru_batch_size == 0) {
        throw std::invalid_argument(
            "pattern_cache_lru_batch_size must be > 0");
    }

    // Deferred Cache validation
    if (deferred_cache_ttl_ms.count() <= 0) {
        throw std::invalid_argument(
            "deferred_cache_ttl_ms must be > 0");
    }

    // Deferred TTL should be > Pattern TTL (leak protection)
    if (deferred_cache_ttl_ms <= pattern_cache_ttl_ms) {
        throw std::invalid_argument(
            "deferred_cache_ttl_ms must be > pattern_cache_ttl_ms (leak protection)");
    }

    // Eviction thread validation
    if (eviction_check_interval_ms.count() <= 0) {
        throw std::invalid_argument(
            "eviction_check_interval_ms must be > 0");
    }

    // Eviction interval should be reasonable (warn if > 60s)
    if (eviction_check_interval_ms.count() > 60000) {
        // This is a warning, not an error - still valid but suboptimal
        // Could log warning here if we had logging initialized
    }
}

std::string CacheConfig::toJson() const {
    json j;

    // Global
    j["cache_enabled"] = cache_enabled;

    // Pattern Result Cache
    j["pattern_result_cache_enabled"] = pattern_result_cache_enabled;
    j["pattern_result_cache_target_capacity_bytes"] = pattern_result_cache_target_capacity_bytes;
    j["pattern_result_cache_string_threshold_bytes"] = pattern_result_cache_string_threshold_bytes;
    j["pattern_result_cache_ttl_ms"] = pattern_result_cache_ttl_ms.count();
    j["pattern_result_cache_use_tbb"] = pattern_result_cache_use_tbb;

    // Pattern Compilation Cache
    j["pattern_cache_target_capacity_bytes"] = pattern_cache_target_capacity_bytes;
    j["pattern_cache_ttl_ms"] = pattern_cache_ttl_ms.count();
    j["pattern_cache_use_tbb"] = pattern_cache_use_tbb;
    j["pattern_cache_lru_batch_size"] = pattern_cache_lru_batch_size;

    // Deferred Cache
    j["deferred_cache_ttl_ms"] = deferred_cache_ttl_ms.count();

    // Eviction Thread
    j["auto_start_eviction_thread"] = auto_start_eviction_thread;
    j["eviction_check_interval_ms"] = eviction_check_interval_ms.count();

    return j.dump(2);  // Pretty-print with 2-space indent
}

}  // namespace cache
}  // namespace libre2
