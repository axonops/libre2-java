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
#include <gtest/gtest.h>
#include <nlohmann/json.hpp>

using namespace libre2::cache;
using json = nlohmann::json;

class CacheConfigTest : public ::testing::Test {};

// Test default configuration
TEST_F(CacheConfigTest, DefaultConfiguration) {
    std::string json_str = "{}";  // Empty JSON, should use all defaults
    CacheConfig config = CacheConfig::fromJson(json_str);

    // Global
    EXPECT_TRUE(config.cache_enabled);

    // Pattern Result Cache
    EXPECT_TRUE(config.pattern_result_cache_enabled);
    EXPECT_EQ(config.pattern_result_cache_target_capacity_bytes, 100 * 1024 * 1024UL);
    EXPECT_EQ(config.pattern_result_cache_string_threshold_bytes, 10 * 1024UL);
    EXPECT_EQ(config.pattern_result_cache_ttl_ms.count(), 300000);  // 5 min

    // Pattern Compilation Cache
    EXPECT_EQ(config.pattern_cache_target_capacity_bytes, 100 * 1024 * 1024UL);
    EXPECT_EQ(config.pattern_cache_ttl_ms.count(), 300000);  // 5 min

    // Deferred Cache
    EXPECT_EQ(config.deferred_cache_ttl_ms.count(), 600000);  // 10 min

    // Eviction Thread
    EXPECT_TRUE(config.auto_start_eviction_thread);
    EXPECT_EQ(config.eviction_check_interval_ms.count(), 100);  // 100ms
}

// Test custom configuration
TEST_F(CacheConfigTest, CustomConfiguration) {
    json j;
    j["cache_enabled"] = true;
    j["pattern_result_cache_enabled"] = false;  // Disable result cache
    j["pattern_result_cache_target_capacity_bytes"] = 50 * 1024 * 1024;
    j["pattern_result_cache_string_threshold_bytes"] = 5 * 1024;
    j["pattern_result_cache_ttl_ms"] = 60000;  // 1 min
    j["pattern_cache_target_capacity_bytes"] = 200 * 1024 * 1024;
    j["pattern_cache_ttl_ms"] = 600000;  // 10 min
    j["deferred_cache_ttl_ms"] = 1200000;  // 20 min
    j["auto_start_eviction_thread"] = false;
    j["eviction_check_interval_ms"] = 1000;  // 1 second

    CacheConfig config = CacheConfig::fromJson(j.dump());

    EXPECT_TRUE(config.cache_enabled);
    EXPECT_FALSE(config.pattern_result_cache_enabled);
    EXPECT_EQ(config.pattern_result_cache_target_capacity_bytes, 50 * 1024 * 1024UL);
    EXPECT_EQ(config.pattern_result_cache_string_threshold_bytes, 5 * 1024UL);
    EXPECT_EQ(config.pattern_result_cache_ttl_ms.count(), 60000);
    EXPECT_EQ(config.pattern_cache_target_capacity_bytes, 200 * 1024 * 1024UL);
    EXPECT_EQ(config.pattern_cache_ttl_ms.count(), 600000);
    EXPECT_EQ(config.deferred_cache_ttl_ms.count(), 1200000);
    EXPECT_FALSE(config.auto_start_eviction_thread);
    EXPECT_EQ(config.eviction_check_interval_ms.count(), 1000);
}

// Test cache disabled
TEST_F(CacheConfigTest, CacheDisabled) {
    json j;
    j["cache_enabled"] = false;
    j["pattern_cache_target_capacity_bytes"] = 0;  // Invalid if cache enabled
    j["pattern_cache_ttl_ms"] = 0;  // Invalid if cache enabled

    // Should NOT throw - validation skipped when cache disabled
    EXPECT_NO_THROW({
        CacheConfig config = CacheConfig::fromJson(j.dump());
        EXPECT_FALSE(config.cache_enabled);
    });
}

// Test validation: zero capacity
TEST_F(CacheConfigTest, Validation_ZeroCapacity) {
    json j;
    j["cache_enabled"] = true;
    j["pattern_cache_target_capacity_bytes"] = 0;  // INVALID
    j["pattern_cache_ttl_ms"] = 300000;
    j["deferred_cache_ttl_ms"] = 600000;

    EXPECT_THROW({
        CacheConfig::fromJson(j.dump());
    }, std::invalid_argument);
}

// Test validation: zero TTL
TEST_F(CacheConfigTest, Validation_ZeroTTL) {
    json j;
    j["cache_enabled"] = true;
    j["pattern_cache_target_capacity_bytes"] = 100 * 1024 * 1024;
    j["pattern_cache_ttl_ms"] = 0;  // INVALID
    j["deferred_cache_ttl_ms"] = 600000;

    EXPECT_THROW({
        CacheConfig::fromJson(j.dump());
    }, std::invalid_argument);
}

// Test validation: deferred TTL <= pattern TTL
TEST_F(CacheConfigTest, Validation_DeferredTTLTooShort) {
    json j;
    j["cache_enabled"] = true;
    j["pattern_cache_target_capacity_bytes"] = 100 * 1024 * 1024;
    j["pattern_cache_ttl_ms"] = 600000;  // 10 min
    j["deferred_cache_ttl_ms"] = 300000;  // 5 min - INVALID (should be > pattern TTL)

    EXPECT_THROW({
        CacheConfig::fromJson(j.dump());
    }, std::invalid_argument);
}

// Test validation: deferred TTL == pattern TTL
TEST_F(CacheConfigTest, Validation_DeferredTTLEqual) {
    json j;
    j["cache_enabled"] = true;
    j["pattern_cache_target_capacity_bytes"] = 100 * 1024 * 1024;
    j["pattern_cache_ttl_ms"] = 600000;
    j["deferred_cache_ttl_ms"] = 600000;  // Equal - INVALID

    EXPECT_THROW({
        CacheConfig::fromJson(j.dump());
    }, std::invalid_argument);
}

// Test validation: negative eviction interval
TEST_F(CacheConfigTest, Validation_NegativeEvictionInterval) {
    json j;
    j["cache_enabled"] = true;
    j["pattern_cache_target_capacity_bytes"] = 100 * 1024 * 1024;
    j["pattern_cache_ttl_ms"] = 300000;
    j["deferred_cache_ttl_ms"] = 600000;
    j["eviction_check_interval_ms"] = -100;  // INVALID

    EXPECT_THROW({
        CacheConfig::fromJson(j.dump());
    }, std::invalid_argument);
}

// Test JSON round-trip (serialize → deserialize → serialize)
TEST_F(CacheConfigTest, JsonRoundTrip) {
    json original;
    original["cache_enabled"] = true;
    original["pattern_result_cache_enabled"] = true;
    original["pattern_result_cache_target_capacity_bytes"] = 50 * 1024 * 1024;
    original["pattern_result_cache_string_threshold_bytes"] = 5 * 1024;
    original["pattern_result_cache_ttl_ms"] = 120000;
    original["pattern_cache_target_capacity_bytes"] = 75 * 1024 * 1024;
    original["pattern_cache_ttl_ms"] = 180000;
    original["deferred_cache_ttl_ms"] = 360000;
    original["auto_start_eviction_thread"] = false;
    original["eviction_check_interval_ms"] = 500;

    // Parse
    CacheConfig config1 = CacheConfig::fromJson(original.dump());

    // Serialize
    std::string serialized = config1.toJson();

    // Parse again
    CacheConfig config2 = CacheConfig::fromJson(serialized);

    // Should be identical
    EXPECT_EQ(config1.cache_enabled, config2.cache_enabled);
    EXPECT_EQ(config1.pattern_result_cache_enabled, config2.pattern_result_cache_enabled);
    EXPECT_EQ(config1.pattern_result_cache_target_capacity_bytes,
              config2.pattern_result_cache_target_capacity_bytes);
    EXPECT_EQ(config1.pattern_cache_target_capacity_bytes,
              config2.pattern_cache_target_capacity_bytes);
    EXPECT_EQ(config1.pattern_cache_ttl_ms.count(),
              config2.pattern_cache_ttl_ms.count());
    EXPECT_EQ(config1.deferred_cache_ttl_ms.count(),
              config2.deferred_cache_ttl_ms.count());
    EXPECT_EQ(config1.eviction_check_interval_ms.count(),
              config2.eviction_check_interval_ms.count());
}

// Test invalid JSON
TEST_F(CacheConfigTest, InvalidJson) {
    std::string invalid = "{invalid json}";
    EXPECT_THROW({
        CacheConfig::fromJson(invalid);
    }, std::runtime_error);
}

// Test wrong type in JSON
TEST_F(CacheConfigTest, WrongTypeInJson) {
    json j;
    j["cache_enabled"] = "true";  // String instead of boolean - should still work with nlohmann/json
    j["pattern_cache_target_capacity_bytes"] = "not a number";  // INVALID

    EXPECT_THROW({
        CacheConfig::fromJson(j.dump());
    }, std::runtime_error);
}

// Test partial configuration (some defaults, some custom)
TEST_F(CacheConfigTest, PartialConfiguration) {
    json j;
    j["pattern_cache_target_capacity_bytes"] = 200 * 1024 * 1024;
    j["eviction_check_interval_ms"] = 50;
    // Other fields use defaults

    CacheConfig config = CacheConfig::fromJson(j.dump());

    // Custom values
    EXPECT_EQ(config.pattern_cache_target_capacity_bytes, 200 * 1024 * 1024UL);
    EXPECT_EQ(config.eviction_check_interval_ms.count(), 50);

    // Defaults
    EXPECT_TRUE(config.cache_enabled);
    EXPECT_TRUE(config.pattern_result_cache_enabled);
    EXPECT_EQ(config.pattern_cache_ttl_ms.count(), 300000);
}

// Test very large capacity
TEST_F(CacheConfigTest, VeryLargeCapacity) {
    json j;
    j["pattern_cache_target_capacity_bytes"] = 10UL * 1024 * 1024 * 1024;  // 10GB
    j["pattern_cache_ttl_ms"] = 300000;
    j["deferred_cache_ttl_ms"] = 600000;

    EXPECT_NO_THROW({
        CacheConfig config = CacheConfig::fromJson(j.dump());
        EXPECT_EQ(config.pattern_cache_target_capacity_bytes, 10UL * 1024 * 1024 * 1024);
    });
}

// Test very short TTL
TEST_F(CacheConfigTest, VeryShortTTL) {
    json j;
    j["pattern_cache_target_capacity_bytes"] = 100 * 1024 * 1024;
    j["pattern_cache_ttl_ms"] = 1000;  // 1 second
    j["deferred_cache_ttl_ms"] = 2000;  // 2 seconds

    EXPECT_NO_THROW({
        CacheConfig config = CacheConfig::fromJson(j.dump());
        EXPECT_EQ(config.pattern_cache_ttl_ms.count(), 1000);
        EXPECT_EQ(config.deferred_cache_ttl_ms.count(), 2000);
    });
}

// Test serialization format
TEST_F(CacheConfigTest, SerializationFormat) {
    json j;
    j["cache_enabled"] = true;
    j["pattern_cache_target_capacity_bytes"] = 100 * 1024 * 1024;
    j["pattern_cache_ttl_ms"] = 300000;
    j["deferred_cache_ttl_ms"] = 600000;

    CacheConfig config = CacheConfig::fromJson(j.dump());
    std::string serialized = config.toJson();

    // Should be valid JSON
    json parsed = json::parse(serialized);
    EXPECT_TRUE(parsed.is_object());

    // Should contain all fields
    EXPECT_TRUE(parsed.contains("cache_enabled"));
    EXPECT_TRUE(parsed.contains("pattern_cache_target_capacity_bytes"));
    EXPECT_TRUE(parsed.contains("pattern_cache_ttl_ms"));
    EXPECT_TRUE(parsed.contains("deferred_cache_ttl_ms"));
    EXPECT_TRUE(parsed.contains("eviction_check_interval_ms"));
}
