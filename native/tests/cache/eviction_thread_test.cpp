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

#include "cache/eviction_thread.h"
#include <gtest/gtest.h>
#include <thread>

using namespace libre2::cache;

class EvictionThreadTest : public ::testing::Test {
protected:
    CacheConfig makeConfig() {
        std::string json = R"({
            "pattern_result_cache_enabled": true,
            "pattern_result_cache_target_capacity_bytes": 10240,
            "pattern_result_cache_string_threshold_bytes": 1024,
            "pattern_result_cache_ttl_ms": 100,
            "pattern_cache_target_capacity_bytes": 10240,
            "pattern_cache_ttl_ms": 100,
            "deferred_cache_ttl_ms": 200,
            "eviction_check_interval_ms": 50
        })";
        return CacheConfig::fromJson(json);
    }
};

// Start and stop
TEST_F(EvictionThreadTest, StartAndStop) {
    CacheConfig config = makeConfig();
    ResultCache result_cache(config);
    PatternCache pattern_cache(config);
    DeferredCache deferred_cache(config);
    CacheMetrics metrics;

    EvictionThread thread(config, result_cache, pattern_cache, deferred_cache, metrics);

    EXPECT_FALSE(thread.isRunning());

    thread.start();
    EXPECT_TRUE(thread.isRunning());

    thread.stop();
    EXPECT_FALSE(thread.isRunning());
}

// Multiple start calls (safe)
TEST_F(EvictionThreadTest, MultipleStarts) {
    CacheConfig config = makeConfig();
    ResultCache result_cache(config);
    PatternCache pattern_cache(config);
    DeferredCache deferred_cache(config);
    CacheMetrics metrics;

    EvictionThread thread(config, result_cache, pattern_cache, deferred_cache, metrics);

    thread.start();
    thread.start();  // Should be no-op
    thread.start();  // Should be no-op

    EXPECT_TRUE(thread.isRunning());

    thread.stop();
}

// Multiple stop calls (safe)
TEST_F(EvictionThreadTest, MultipleStops) {
    CacheConfig config = makeConfig();
    ResultCache result_cache(config);
    PatternCache pattern_cache(config);
    DeferredCache deferred_cache(config);
    CacheMetrics metrics;

    EvictionThread thread(config, result_cache, pattern_cache, deferred_cache, metrics);

    thread.start();
    thread.stop();
    thread.stop();  // Should be no-op
    thread.stop();  // Should be no-op

    EXPECT_FALSE(thread.isRunning());
}

// Thread actually runs eviction
TEST_F(EvictionThreadTest, EvictionActuallyRuns) {
    CacheConfig config = makeConfig();
    ResultCache result_cache(config);
    PatternCache pattern_cache(config);
    DeferredCache deferred_cache(config);
    CacheMetrics metrics;

    // Add entries to Result Cache
    for (int i = 0; i < 10; i++) {
        result_cache.put(i, "test", true, metrics.pattern_result_cache);
    }

    EXPECT_EQ(result_cache.size(), 10u);

    // Start eviction thread
    EvictionThread thread(config, result_cache, pattern_cache, deferred_cache, metrics);
    thread.start();

    // Wait for 2-3 eviction cycles (50ms × 3 = 150ms > 100ms TTL)
    std::this_thread::sleep_for(std::chrono::milliseconds(200));

    thread.stop();

    // Entries should have been evicted (TTL expired)
    EXPECT_LT(result_cache.size(), 10u);
    EXPECT_GT(metrics.pattern_result_cache.ttl_evictions.load(), 0u);
}

// Metrics updated periodically
TEST_F(EvictionThreadTest, MetricsUpdated) {
    CacheConfig config = makeConfig();
    ResultCache result_cache(config);
    PatternCache pattern_cache(config);
    DeferredCache deferred_cache(config);
    CacheMetrics metrics;

    // Add entries
    result_cache.put(1, "test", true, metrics.pattern_result_cache);

    std::string error;
    pattern_cache.getOrCompile("pattern", true, metrics.pattern_cache, error);

    // Start thread
    EvictionThread thread(config, result_cache, pattern_cache, deferred_cache, metrics);
    thread.start();

    // Wait for metrics snapshot
    std::this_thread::sleep_for(std::chrono::milliseconds(100));

    thread.stop();

    // Metrics should be populated
    EXPECT_GT(metrics.pattern_result_cache.current_entry_count, 0u);
    EXPECT_GT(metrics.pattern_cache.current_entry_count, 0u);
}

// Graceful shutdown
TEST_F(EvictionThreadTest, GracefulShutdown) {
    CacheConfig config = makeConfig();
    ResultCache result_cache(config);
    PatternCache pattern_cache(config);
    DeferredCache deferred_cache(config);
    CacheMetrics metrics;

    EvictionThread thread(config, result_cache, pattern_cache, deferred_cache, metrics);

    thread.start();
    EXPECT_TRUE(thread.isRunning());

    // Stop should block until thread exits
    thread.stop();
    EXPECT_FALSE(thread.isRunning());

    // No crash on destruction
}

// Destructor stops thread
TEST_F(EvictionThreadTest, DestructorStopsThread) {
    CacheConfig config = makeConfig();
    ResultCache result_cache(config);
    PatternCache pattern_cache(config);
    DeferredCache deferred_cache(config);
    CacheMetrics metrics;

    {
        EvictionThread thread(config, result_cache, pattern_cache, deferred_cache, metrics);
        thread.start();
        EXPECT_TRUE(thread.isRunning());
        // Destructor called here
    }

    // No crash, thread cleanly stopped
}

// All caches evicted
TEST_F(EvictionThreadTest, AllCachesEvicted) {
    CacheConfig config = makeConfig();
    ResultCache result_cache(config);
    PatternCache pattern_cache(config);
    DeferredCache deferred_cache(config);
    CacheMetrics metrics;

    // Add to all caches
    result_cache.put(1, "test", true, metrics.pattern_result_cache);

    std::string error;
    auto pattern = pattern_cache.getOrCompile("pattern", true, metrics.pattern_cache, error);
    PatternCache::releasePattern(pattern, metrics.pattern_cache);  // Refcount → 0

    // Start eviction
    EvictionThread thread(config, result_cache, pattern_cache, deferred_cache, metrics);
    thread.start();

    // Wait for eviction (TTL = 100ms, interval = 50ms)
    std::this_thread::sleep_for(std::chrono::milliseconds(200));

    thread.stop();

    // All should be evicted
    EXPECT_EQ(result_cache.size(), 0u);
    EXPECT_EQ(pattern_cache.size(), 0u);
}
