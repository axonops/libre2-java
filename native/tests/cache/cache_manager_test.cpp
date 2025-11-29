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
#include "pattern_options.h"
#include <gtest/gtest.h>
#include <thread>

using namespace libre2::cache;
using namespace libre2::api;

// Helper: Create PatternOptions with case sensitivity flag
inline PatternOptions opts(bool case_sensitive) {
    return PatternOptions::fromCaseSensitive(case_sensitive);
}

class CacheManagerTest : public ::testing::Test {
protected:
    CacheConfig makeConfig(bool auto_start = true) {
        std::string json = std::string(R"({
            "pattern_result_cache_enabled": true,
            "pattern_result_cache_target_capacity_bytes": 10240,
            "pattern_result_cache_string_threshold_bytes": 1024,
            "pattern_result_cache_ttl_ms": 100,
            "pattern_cache_target_capacity_bytes": 10240,
            "pattern_cache_ttl_ms": 100,
            "deferred_cache_ttl_ms": 200,
            "auto_start_eviction_thread": )") + (auto_start ? "true" : "false") + R"(,
            "eviction_check_interval_ms": 50
        })";
        return CacheConfig::fromJson(json);
    }
};

// Construction
TEST_F(CacheManagerTest, Construction) {
    CacheConfig config = makeConfig(false);  // Don't auto-start
    CacheManager manager(config);

    EXPECT_FALSE(manager.isEvictionThreadRunning());
    EXPECT_EQ(manager.resultCache().size(), 0u);
    EXPECT_EQ(manager.patternCache().size(), 0u);
    EXPECT_EQ(manager.deferredCache().size(), 0u);
}

// Auto-start eviction thread
TEST_F(CacheManagerTest, AutoStartEvictionThread) {
    CacheConfig config = makeConfig(true);  // Auto-start
    CacheManager manager(config);

    EXPECT_TRUE(manager.isEvictionThreadRunning());
}

// Manual start/stop eviction thread
TEST_F(CacheManagerTest, ManualEvictionControl) {
    CacheConfig config = makeConfig(false);  // Don't auto-start
    CacheManager manager(config);

    EXPECT_FALSE(manager.isEvictionThreadRunning());

    manager.startEvictionThread();
    EXPECT_TRUE(manager.isEvictionThreadRunning());

    manager.stopEvictionThread();
    EXPECT_FALSE(manager.isEvictionThreadRunning());
}

// Access all caches
TEST_F(CacheManagerTest, CacheAccessors) {
    CacheConfig config = makeConfig(false);
    CacheManager manager(config);

    // Local metrics for test
    PatternResultCacheMetrics result_metrics;
    PatternCacheMetrics pattern_metrics;

    // Add to Result Cache
    manager.resultCache().put(1, "test", true, result_metrics);
    EXPECT_EQ(manager.resultCache().size(), 1u);

    // Add to Pattern Cache
    std::string error;
    auto pattern = manager.patternCache().getOrCompile("test.*", opts(true), pattern_metrics, error);
    EXPECT_NE(pattern, nullptr);
    EXPECT_EQ(manager.patternCache().size(), 1u);
}

// Metrics JSON export
TEST_F(CacheManagerTest, MetricsJSON) {
    CacheConfig config = makeConfig(false);
    CacheManager manager(config);

    PatternResultCacheMetrics metrics;

    // Add some data
    manager.resultCache().put(1, "test", true, metrics);

    std::string json = manager.getMetricsJSON();

    EXPECT_NE(json.find("pattern_result_cache"), std::string::npos);
    EXPECT_NE(json.find("pattern_cache"), std::string::npos);
    EXPECT_NE(json.find("deferred_cache"), std::string::npos);
    EXPECT_NE(json.find("re2_library"), std::string::npos);
}

// Clear all caches
TEST_F(CacheManagerTest, ClearAllCaches) {
    CacheConfig config = makeConfig(false);
    CacheManager manager(config);

    PatternResultCacheMetrics result_metrics;
    PatternCacheMetrics pattern_metrics;

    // Add to all caches
    manager.resultCache().put(1, "test", true, result_metrics);

    std::string error;
    auto pattern = manager.patternCache().getOrCompile("pattern", opts(true), pattern_metrics, error);

    EXPECT_EQ(manager.resultCache().size(), 1u);
    EXPECT_EQ(manager.patternCache().size(), 1u);

    // Clear all
    manager.clearAllCaches();

    EXPECT_EQ(manager.resultCache().size(), 0u);
    EXPECT_EQ(manager.patternCache().size(), 0u);
    EXPECT_EQ(manager.deferredCache().size(), 0u);
}

// Eviction thread actually runs
TEST_F(CacheManagerTest, EvictionThreadRuns) {
    CacheConfig config = makeConfig(false);  // Manual start
    CacheManager manager(config);

    PatternResultCacheMetrics metrics;

    // Add entries
    for (int i = 0; i < 10; i++) {
        manager.resultCache().put(i, "test", true, metrics);
    }

    EXPECT_EQ(manager.resultCache().size(), 10u);

    // Start eviction
    manager.startEvictionThread();

    // Wait for TTL expiration (100ms) + eviction cycles (50ms)
    std::this_thread::sleep_for(std::chrono::milliseconds(200));

    manager.stopEvictionThread();

    // Entries should be evicted
    EXPECT_LT(manager.resultCache().size(), 10u);
}

// Integration: Pattern Cache + Deferred Cache
TEST_F(CacheManagerTest, PatternCacheWithDeferred) {
    CacheConfig config = makeConfig(false);
    CacheManager manager(config);

    PatternCacheMetrics metrics;

    // Compile pattern
    std::string error;
    auto pattern = manager.patternCache().getOrCompile("test.*", opts(true), metrics, error);
    ASSERT_NE(pattern, nullptr);
    EXPECT_EQ(manager.patternCache().size(), 1u);

    // Release it
    PatternCache::releasePattern(pattern, metrics);

    // Should still be in cache (refcount = 0)
    EXPECT_EQ(manager.patternCache().size(), 1u);

    // Trigger eviction with expired TTL
    auto now = std::chrono::steady_clock::now() + std::chrono::seconds(61);
    manager.patternCache().evict(metrics, manager.deferredCache(), now);

    // Should be evicted (refcount was 0)
    EXPECT_EQ(manager.patternCache().size(), 0u);
    EXPECT_EQ(manager.deferredCache().size(), 0u);
}

// Destructor cleanup
TEST_F(CacheManagerTest, DestructorCleanup) {
    CacheConfig config = makeConfig(true);  // Auto-start

    {
        CacheManager manager(config);
        EXPECT_TRUE(manager.isEvictionThreadRunning());

        PatternResultCacheMetrics metrics;

        // Add some data
        manager.resultCache().put(1, "test", true, metrics);

        // Destructor will be called here
    }

    // No crash, clean shutdown
}
