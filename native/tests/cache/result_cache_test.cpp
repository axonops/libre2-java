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

#include "cache/result_cache.h"
#include <gtest/gtest.h>
#include <thread>

using namespace libre2::cache;

/**
 * Result Cache Tests - Test BOTH std and TBB implementations.
 *
 * Tests are parameterized to run with both use_tbb=false and use_tbb=true.
 */
class ResultCacheTest : public ::testing::TestWithParam<bool> {
protected:
    CacheConfig makeConfig(bool use_tbb, bool enabled = true) {
        std::string json = std::string(R"({
            "pattern_result_cache_enabled": )") + (enabled ? "true" : "false") + R"(,
            "pattern_result_cache_target_capacity_bytes": )" +
            std::to_string(10 * 1024) + R"(,
            "pattern_result_cache_string_threshold_bytes": 1024,
            "pattern_result_cache_ttl_ms": 60000,
            "pattern_result_cache_use_tbb": )" + (use_tbb ? "true" : "false") + R"(,
            "pattern_cache_target_capacity_bytes": 10485760,
            "pattern_cache_ttl_ms": 300000,
            "deferred_cache_ttl_ms": 600000
        })";
        return CacheConfig::fromJson(json);
    }
};

// Basic put/get
TEST_P(ResultCacheTest, PutAndGet) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    ResultCache cache(config);
    PatternResultCacheMetrics metrics;

    uint64_t pattern_hash = 12345;
    std::string input = "test_input";

    // Put result
    cache.put(pattern_hash, input, true, metrics);

    // Get result
    auto result = cache.get(pattern_hash, input, metrics);

    ASSERT_TRUE(result.has_value());
    EXPECT_TRUE(result.value());
    EXPECT_EQ(metrics.hits.load(), 1u);
    EXPECT_EQ(metrics.misses.load(), 0u);
}

// Cache miss
TEST_P(ResultCacheTest, CacheMiss) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    ResultCache cache(config);
    PatternResultCacheMetrics metrics;

    uint64_t pattern_hash = 12345;
    std::string input = "not_cached";

    auto result = cache.get(pattern_hash, input, metrics);

    EXPECT_FALSE(result.has_value());
    EXPECT_EQ(metrics.hits.load(), 0u);
    EXPECT_EQ(metrics.misses.load(), 1u);
}

// Different inputs for same pattern
TEST_P(ResultCacheTest, DifferentInputs) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    ResultCache cache(config);
    PatternResultCacheMetrics metrics;

    uint64_t pattern_hash = 12345;

    cache.put(pattern_hash, "input1", true, metrics);
    cache.put(pattern_hash, "input2", false, metrics);

    auto result1 = cache.get(pattern_hash, "input1", metrics);
    auto result2 = cache.get(pattern_hash, "input2", metrics);

    ASSERT_TRUE(result1.has_value());
    EXPECT_TRUE(result1.value());

    ASSERT_TRUE(result2.has_value());
    EXPECT_FALSE(result2.value());

    EXPECT_EQ(cache.size(), 2u);
}

// String threshold - don't cache large strings
TEST_P(ResultCacheTest, StringThreshold) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    ResultCache cache(config);
    PatternResultCacheMetrics metrics;

    uint64_t pattern_hash = 12345;
    std::string large_input(2000, 'x');  // 2KB > 1KB threshold

    // Try to cache large string
    cache.put(pattern_hash, large_input, true, metrics);

    // Should not be cached
    auto result = cache.get(pattern_hash, large_input, metrics);
    EXPECT_FALSE(result.has_value());
    EXPECT_EQ(cache.size(), 0u);
    EXPECT_EQ(metrics.misses.load(), 1u);
}

// Update existing entry
TEST_P(ResultCacheTest, UpdateExisting) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    ResultCache cache(config);
    PatternResultCacheMetrics metrics;

    uint64_t pattern_hash = 12345;
    std::string input = "test";

    // Put initial result
    cache.put(pattern_hash, input, true, metrics);
    EXPECT_EQ(cache.size(), 1u);

    // Update with different result
    cache.put(pattern_hash, input, false, metrics);
    EXPECT_EQ(cache.size(), 1u);  // Still 1 entry

    // Verify updated
    auto result = cache.get(pattern_hash, input, metrics);
    ASSERT_TRUE(result.has_value());
    EXPECT_FALSE(result.value());  // Updated to false
}

// TTL eviction
TEST_P(ResultCacheTest, TTLEviction) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    ResultCache cache(config);
    PatternResultCacheMetrics metrics;

    uint64_t pattern_hash = 12345;
    cache.put(pattern_hash, "test", true, metrics);
    EXPECT_EQ(cache.size(), 1u);

    // Simulate TTL expiration (61 seconds)
    auto now = std::chrono::steady_clock::now() + std::chrono::seconds(61);
    size_t evicted = cache.evict(metrics, now);

    EXPECT_EQ(evicted, 1u);
    EXPECT_EQ(cache.size(), 0u);
    EXPECT_EQ(metrics.ttl_evictions.load(), 1u);
}

// LRU eviction (over capacity)
TEST_P(ResultCacheTest, LRUEviction) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    ResultCache cache(config);
    PatternResultCacheMetrics metrics;

    // Add many entries until over capacity (10KB limit)
    for (int i = 0; i < 1000; i++) {
        std::string input = "input_" + std::to_string(i);
        cache.put(12345 + i, input, true, metrics);
    }

    // Should be over capacity
    cache.snapshotMetrics(metrics);
    EXPECT_GT(metrics.actual_size_bytes, 10 * 1024u);

    // Trigger LRU eviction
    auto now = std::chrono::steady_clock::now();
    size_t evicted = cache.evict(metrics, now);

    EXPECT_GT(evicted, 0u);
    EXPECT_GT(metrics.lru_evictions.load(), 0u);

    // Should be back under capacity
    cache.snapshotMetrics(metrics);
    EXPECT_LE(metrics.actual_size_bytes, 10 * 1024u);
}

// Clear cache
TEST_P(ResultCacheTest, Clear) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    ResultCache cache(config);
    PatternResultCacheMetrics metrics;

    cache.put(1, "input1", true, metrics);
    cache.put(2, "input2", false, metrics);
    cache.put(3, "input3", true, metrics);
    EXPECT_EQ(cache.size(), 3u);

    cache.clear();
    EXPECT_EQ(cache.size(), 0u);

    cache.snapshotMetrics(metrics);
    EXPECT_EQ(metrics.actual_size_bytes, 0u);
}

// Disabled cache
TEST_P(ResultCacheTest, DisabledCache) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb, false);  // Disabled
    ResultCache cache(config);
    PatternResultCacheMetrics metrics;

    // Try to cache
    cache.put(12345, "test", true, metrics);

    // Should not be cached
    auto result = cache.get(12345, "test", metrics);
    EXPECT_FALSE(result.has_value());
    EXPECT_EQ(cache.size(), 0u);
}

// Metrics tracking
TEST_P(ResultCacheTest, MetricsTracking) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    ResultCache cache(config);
    PatternResultCacheMetrics metrics;

    // Put 3 entries
    cache.put(1, "a", true, metrics);
    cache.put(2, "b", false, metrics);
    cache.put(3, "c", true, metrics);

    // 3 hits, 1 miss
    cache.get(1, "a", metrics);  // hit
    cache.get(2, "b", metrics);  // hit
    cache.get(3, "c", metrics);  // hit
    cache.get(4, "d", metrics);  // miss

    EXPECT_EQ(metrics.hits.load(), 3u);
    EXPECT_EQ(metrics.misses.load(), 1u);
    EXPECT_DOUBLE_EQ(metrics.hit_rate(), 75.0);  // 3/(3+1) = 75%

    // Snapshot metrics
    cache.snapshotMetrics(metrics);
    EXPECT_EQ(metrics.current_entry_count, 3u);
    EXPECT_GT(metrics.actual_size_bytes, 0u);
    EXPECT_EQ(metrics.using_tbb, use_tbb);
}

// Batch eviction performance
TEST_P(ResultCacheTest, BatchEvictionEfficiency) {
    bool use_tbb = GetParam();

    std::string json = std::string(R"({
        "pattern_result_cache_enabled": true,
        "pattern_result_cache_target_capacity_bytes": 1000,
        "pattern_result_cache_string_threshold_bytes": 1024,
        "pattern_result_cache_ttl_ms": 300000,
        "pattern_result_cache_use_tbb": )") + (use_tbb ? "true" : "false") + R"(,
        "pattern_cache_target_capacity_bytes": 10485760,
        "pattern_cache_ttl_ms": 300000,
        "deferred_cache_ttl_ms": 600000
    })";

    CacheConfig config = CacheConfig::fromJson(json);
    ResultCache cache(config);
    PatternResultCacheMetrics metrics;

    // Add many entries until way over capacity
    for (int i = 0; i < 200; i++) {
        std::string input = "input_" + std::to_string(i);
        cache.put(i, input, i % 2 == 0, metrics);
    }

    cache.snapshotMetrics(metrics);
    EXPECT_GT(metrics.actual_size_bytes, 1000u);

    // Trigger batch eviction
    auto now = std::chrono::steady_clock::now();
    size_t evicted = cache.evict(metrics, now);

    EXPECT_GT(evicted, 0u);

    cache.snapshotMetrics(metrics);
    EXPECT_LE(metrics.actual_size_bytes, 1000u);
}

// Thread safety: Concurrent put/get
TEST_P(ResultCacheTest, ThreadSafe_ConcurrentPutGet) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    ResultCache cache(config);
    PatternResultCacheMetrics metrics;

    const int num_threads = 10;
    const int iterations = 100;

    std::vector<std::thread> threads;
    for (int t = 0; t < num_threads; t++) {
        threads.emplace_back([&cache, &metrics, t, iterations]() {
            for (int i = 0; i < iterations; i++) {
                uint64_t pattern = t * 1000 + i;
                std::string input = "thread_" + std::to_string(t) + "_iter_" + std::to_string(i);

                // Put and get
                cache.put(pattern, input, i % 2 == 0, metrics);
                cache.get(pattern, input, metrics);
            }
        });
    }

    for (auto& th : threads) {
        th.join();
    }

    // Should have entries from all threads
    EXPECT_GT(cache.size(), 0u);
    EXPECT_GT(metrics.hits.load() + metrics.misses.load(), 0u);
}

// Thread safety: Same key from multiple threads
TEST_P(ResultCacheTest, ThreadSafe_SameKey) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    ResultCache cache(config);
    PatternResultCacheMetrics metrics;

    const int num_threads = 20;
    const int iterations = 50;

    std::vector<std::thread> threads;
    for (int t = 0; t < num_threads; t++) {
        threads.emplace_back([&cache, &metrics, iterations]() {
            for (int i = 0; i < iterations; i++) {
                // All threads use same key
                cache.put(99999, "shared_input", true, metrics);
                auto result = cache.get(99999, "shared_input", metrics);
                // Result may or may not be present depending on timing
            }
        });
    }

    for (auto& th : threads) {
        th.join();
    }

    // Should have exactly 1 entry (same key)
    EXPECT_EQ(cache.size(), 1u);

    // Final result should be true
    auto final = cache.get(99999, "shared_input", metrics);
    ASSERT_TRUE(final.has_value());
    EXPECT_TRUE(final.value());
}

// JSON metrics export
TEST_P(ResultCacheTest, MetricsJSON) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    ResultCache cache(config);
    PatternResultCacheMetrics metrics;

    cache.put(1, "test", true, metrics);
    cache.get(1, "test", metrics);  // hit
    cache.get(2, "miss", metrics);   // miss

    cache.snapshotMetrics(metrics);
    std::string json = metrics.toJson();

    EXPECT_NE(json.find("\"hits\""), std::string::npos);
    EXPECT_NE(json.find("\"misses\""), std::string::npos);
    EXPECT_NE(json.find("\"hit_rate\""), std::string::npos);
}

// Run tests with both std and TBB implementations
INSTANTIATE_TEST_SUITE_P(
    BothImplementations,
    ResultCacheTest,
    ::testing::Values(false, true),
    [](const ::testing::TestParamInfo<bool>& info) {
        return info.param ? "TBB" : "Std";
    }
);
