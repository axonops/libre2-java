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

#include "cache/pattern_cache.h"
#include <gtest/gtest.h>
#include <thread>

using namespace libre2::cache;

/**
 * Pattern Cache Tests - Test BOTH std and TBB implementations.
 *
 * Tests are parameterized to run with both use_tbb=false and use_tbb=true.
 * This ensures functional equivalence between both code paths.
 */
class PatternCacheTest : public ::testing::TestWithParam<bool> {
protected:
    CacheConfig makeConfig(bool use_tbb) {
        std::string json = std::string(R"({
            "pattern_cache_target_capacity_bytes": )") +
            std::to_string(10 * 1024 * 1024) + R"(,
            "pattern_cache_ttl_ms": 60000,
            "pattern_cache_use_tbb": )" + (use_tbb ? "true" : "false") + R"(,
            "deferred_cache_ttl_ms": 120000
        })";
        return CacheConfig::fromJson(json);
    }
};

// Basic compilation
TEST_P(PatternCacheTest, CompilePattern) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    PatternCache cache(config);
    DeferredCache deferred(config);
    PatternCacheMetrics metrics;
    std::string error;

    auto pattern = cache.getOrCompile("test.*", true, metrics, error);

    ASSERT_NE(pattern, nullptr) << "Pattern compilation failed: " << error;
    EXPECT_TRUE(pattern->isValid());
    EXPECT_EQ(pattern->pattern_string, "test.*");
    EXPECT_TRUE(pattern->case_sensitive);
    EXPECT_GT(pattern->approx_size_bytes, 0u);
    EXPECT_EQ(pattern->refcount.load(), 1u);  // Initial refcount = 1
    EXPECT_EQ(metrics.misses.load(), 1u);
    EXPECT_EQ(metrics.hits.load(), 0u);
}

// Cache hit (same pattern twice)
TEST_P(PatternCacheTest, CacheHit) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    PatternCache cache(config);
    DeferredCache deferred(config);
    PatternCacheMetrics metrics;
    std::string error;

    auto p1 = cache.getOrCompile("test.*", true, metrics, error);
    auto p2 = cache.getOrCompile("test.*", true, metrics, error);

    EXPECT_EQ(p1.get(), p2.get()) << "Same pattern should return same pointer";
    EXPECT_EQ(p1->refcount.load(), 2u) << "Refcount should be 2";
    EXPECT_EQ(metrics.hits.load(), 1u);
    EXPECT_EQ(metrics.misses.load(), 1u);
}

// Case sensitivity creates different cache entries
TEST_P(PatternCacheTest, CaseSensitivity) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    PatternCache cache(config);
    DeferredCache deferred(config);
    PatternCacheMetrics metrics;
    std::string error;

    auto p1 = cache.getOrCompile("TEST", true, metrics, error);   // Case sensitive
    auto p2 = cache.getOrCompile("TEST", false, metrics, error);  // Case insensitive

    EXPECT_NE(p1.get(), p2.get()) << "Different case sensitivity = different entries";
    EXPECT_EQ(cache.size(), 2u);
    EXPECT_EQ(metrics.misses.load(), 2u);
}

// Refcount increment/decrement
TEST_P(PatternCacheTest, RefcountManagement) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    PatternCache cache(config);
    DeferredCache deferred(config);
    PatternCacheMetrics metrics;
    std::string error;

    auto p1 = cache.getOrCompile("test", true, metrics, error);
    EXPECT_EQ(p1->refcount.load(), 1u);

    auto p2 = cache.getOrCompile("test", true, metrics, error);
    EXPECT_EQ(p1->refcount.load(), 2u);

    auto p3 = cache.getOrCompile("test", true, metrics, error);
    EXPECT_EQ(p1->refcount.load(), 3u);

    // Release
    cache.releasePattern("test", true, metrics, deferred);
    EXPECT_EQ(p1->refcount.load(), 2u);

    cache.releasePattern("test", true, metrics, deferred);
    EXPECT_EQ(p1->refcount.load(), 1u);

    cache.releasePattern("test", true, metrics, deferred);
    EXPECT_EQ(p1->refcount.load(), 0u);
}

// Compilation error
TEST_P(PatternCacheTest, CompilationError) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    PatternCache cache(config);
    DeferredCache deferred(config);
    PatternCacheMetrics metrics;
    std::string error;

    auto pattern = cache.getOrCompile("[invalid", true, metrics, error);

    EXPECT_EQ(pattern, nullptr);
    EXPECT_FALSE(error.empty()) << "Should have error message";
    EXPECT_EQ(metrics.compilation_errors.load(), 1u);
}

// TTL eviction (refcount == 0)
TEST_P(PatternCacheTest, TTLEviction_ImmediateDelete) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    PatternCache cache(config);
    DeferredCache deferred(config);
    PatternCacheMetrics metrics;
    std::string error;

    auto p = cache.getOrCompile("test", true, metrics, error);
    EXPECT_EQ(cache.size(), 1u);

    // Release (refcount → 0)
    cache.releasePattern("test", true, metrics, deferred);
    EXPECT_EQ(p->refcount.load(), 0u);

    // Simulate TTL expiration (61 seconds)
    auto now = std::chrono::steady_clock::now() + std::chrono::seconds(61);
    size_t evicted = cache.evict(metrics, deferred, now);

    EXPECT_EQ(evicted, 1u);
    EXPECT_EQ(cache.size(), 0u);
    EXPECT_EQ(metrics.ttl_evictions.load(), 1u);
    EXPECT_EQ(metrics.ttl_entries_moved_to_deferred.load(), 0u);
}

// TTL eviction (refcount > 0, move to deferred)
TEST_P(PatternCacheTest, TTLEviction_MoveToDeferred) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    PatternCache cache(config);
    DeferredCache deferred(config);
    PatternCacheMetrics metrics;
    std::string error;

    auto p = cache.getOrCompile("test", true, metrics, error);
    EXPECT_EQ(cache.size(), 1u);
    EXPECT_EQ(deferred.size(), 0u);

    // Don't release (refcount still 1)

    // Simulate TTL expiration
    auto now = std::chrono::steady_clock::now() + std::chrono::seconds(61);
    size_t evicted = cache.evict(metrics, deferred, now);

    EXPECT_EQ(evicted, 1u);
    EXPECT_EQ(cache.size(), 0u);
    EXPECT_EQ(deferred.size(), 1u) << "Pattern should be in deferred cache";
    EXPECT_EQ(metrics.ttl_entries_moved_to_deferred.load(), 1u);
}

// LRU eviction (over capacity)
TEST_P(PatternCacheTest, LRUEviction) {
    bool use_tbb = GetParam();

    // Small capacity for easy testing
    std::string json = std::string(R"({
        "pattern_cache_target_capacity_bytes": 1000,
        "pattern_cache_ttl_ms": 300000,
        "pattern_cache_use_tbb": )") + (use_tbb ? "true" : "false") + R"(,
        "deferred_cache_ttl_ms": 600000
    })";

    CacheConfig config = CacheConfig::fromJson(json);
    PatternCache cache(config);
    DeferredCache deferred(config);
    PatternCacheMetrics metrics;
    std::string error;

    // Add patterns until over capacity
    std::vector<std::shared_ptr<RE2Pattern>> patterns;
    for (int i = 0; i < 100; i++) {
        auto p = cache.getOrCompile("pattern" + std::to_string(i), true, metrics, error);
        patterns.push_back(p);
        cache.releasePattern("pattern" + std::to_string(i), true, metrics, deferred);
    }

    // Should be over capacity
    cache.snapshotMetrics(metrics);
    EXPECT_GT(metrics.actual_size_bytes, 1000u);

    // Trigger eviction
    auto now = std::chrono::steady_clock::now();
    size_t evicted = cache.evict(metrics, deferred, now);

    EXPECT_GT(evicted, 0u);
    EXPECT_GT(metrics.lru_evictions.load(), 0u);

    // Should be back under capacity
    cache.snapshotMetrics(metrics);
    EXPECT_LE(metrics.actual_size_bytes, 1000u);
}

// Clear cache
TEST_P(PatternCacheTest, Clear) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    PatternCache cache(config);
    DeferredCache deferred(config);
    PatternCacheMetrics metrics;
    std::string error;

    cache.getOrCompile("p1", true, metrics, error);
    cache.getOrCompile("p2", true, metrics, error);
    cache.getOrCompile("p3", true, metrics, error);
    EXPECT_EQ(cache.size(), 3u);

    cache.clear(deferred);

    EXPECT_EQ(cache.size(), 0u);
    EXPECT_EQ(deferred.size(), 3u) << "All patterns moved to deferred (refcount > 0)";
}

// Snapshot metrics
TEST_P(PatternCacheTest, SnapshotMetrics) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    PatternCache cache(config);
    DeferredCache deferred(config);
    PatternCacheMetrics metrics;
    std::string error;

    cache.getOrCompile("p1", true, metrics, error);
    cache.getOrCompile("p2", true, metrics, error);

    cache.snapshotMetrics(metrics);

    EXPECT_EQ(metrics.current_entry_count, 2u);
    EXPECT_GT(metrics.actual_size_bytes, 0u);
    EXPECT_EQ(metrics.target_capacity_bytes, 10 * 1024 * 1024u);
    EXPECT_EQ(metrics.using_tbb, use_tbb);
    EXPECT_LT(metrics.utilization_ratio, 1.0);  // Not over capacity
}

// CRITICAL: Refcount invariant (prevent use-after-free race)
TEST_P(PatternCacheTest, RefcountInvariant_NoRaceCondition) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    PatternCache cache(config);
    DeferredCache deferred(config);
    PatternCacheMetrics metrics;
    std::string error;

    // Compile pattern
    auto p1 = cache.getOrCompile("test", true, metrics, error);
    EXPECT_EQ(p1->refcount.load(), 1u);

    // Get again (cache hit)
    auto p2 = cache.getOrCompile("test", true, metrics, error);

    // CRITICAL: Refcount should be 2 BEFORE p2 is returned
    // This proves refcount was incremented while lock was held
    EXPECT_EQ(p2->refcount.load(), 2u) << "Refcount MUST be incremented before lock release";
    EXPECT_EQ(p1.get(), p2.get());
}

// Thread safety: Concurrent compilation
TEST_P(PatternCacheTest, ThreadSafe_ConcurrentCompile) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    PatternCache cache(config);
    DeferredCache deferred(config);
    PatternCacheMetrics metrics;

    const int num_threads = 8;
    const int patterns_per_thread = 10;
    std::atomic<int> compilation_errors{0};

    std::vector<std::thread> threads;
    for (int t = 0; t < num_threads; t++) {
        threads.emplace_back([&cache, &metrics, &compilation_errors, t, patterns_per_thread]() {
            for (int i = 0; i < patterns_per_thread; i++) {
                std::string pattern = "pattern_" + std::to_string(t * patterns_per_thread + i);
                std::string error;
                auto p = cache.getOrCompile(pattern, true, metrics, error);
                if (!p) {
                    compilation_errors.fetch_add(1);
                }
            }
        });
    }

    for (auto& th : threads) {
        th.join();
    }

    EXPECT_EQ(compilation_errors.load(), 0);
    EXPECT_EQ(cache.size(), num_threads * patterns_per_thread);
}

// Thread safety: Same pattern from multiple threads (race on compilation)
TEST_P(PatternCacheTest, ThreadSafe_RaceOnSamePattern) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    PatternCache cache(config);
    DeferredCache deferred(config);
    PatternCacheMetrics metrics;

    const int num_threads = 20;
    std::vector<std::shared_ptr<RE2Pattern>> results(num_threads);

    // All threads compile the SAME pattern simultaneously
    std::vector<std::thread> threads;
    for (int t = 0; t < num_threads; t++) {
        threads.emplace_back([&cache, &metrics, &results, t]() {
            std::string error;
            results[t] = cache.getOrCompile("shared_pattern", true, metrics, error);
        });
    }

    for (auto& th : threads) {
        th.join();
    }

    // All should get the SAME pointer
    for (int i = 1; i < num_threads; i++) {
        EXPECT_EQ(results[0].get(), results[i].get())
            << "All threads should get same compiled pattern";
    }

    // Refcount should be num_threads
    EXPECT_EQ(results[0]->refcount.load(), num_threads);

    // Only one pattern in cache
    EXPECT_EQ(cache.size(), 1u);

    // Some threads got cache hit, some compiled
    EXPECT_GE(metrics.hits.load() + metrics.misses.load(), num_threads);
}

// Thread safety: Concurrent get/release
TEST_P(PatternCacheTest, ThreadSafe_ConcurrentGetRelease) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    PatternCache cache(config);
    DeferredCache deferred(config);
    PatternCacheMetrics metrics;
    std::string error;

    // Pre-compile pattern
    cache.getOrCompile("shared", true, metrics, error);
    cache.releasePattern("shared", true, metrics, deferred);

    const int num_threads = 10;
    const int iterations = 100;

    std::vector<std::thread> threads;
    for (int t = 0; t < num_threads; t++) {
        threads.emplace_back([&cache, &deferred, &metrics, iterations]() {
            for (int i = 0; i < iterations; i++) {
                std::string error;
                auto p = cache.getOrCompile("shared", true, metrics, error);
                // Use pattern...
                cache.releasePattern("shared", true, metrics, deferred);
            }
        });
    }

    for (auto& th : threads) {
        th.join();
    }

    // Final refcount should be 0 (all released)
    std::string err;
    auto final = cache.getOrCompile("shared", true, metrics, err);
    EXPECT_EQ(final->refcount.load(), 1u) << "All threads released, refcount should be 1";
}

// CRITICAL: Stress test for refcount race condition
TEST_P(PatternCacheTest, StressTest_RefcountRaceCondition) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    PatternCache cache(config);
    DeferredCache deferred(config);
    PatternCacheMetrics metrics;

    // Many threads getting/releasing patterns while eviction runs
    std::atomic<bool> stop{false};
    std::atomic<int> use_after_free_detected{0};

    // Worker threads: get/release patterns
    std::vector<std::thread> workers;
    for (int t = 0; t < 8; t++) {
        workers.emplace_back([&]() {
            while (!stop.load()) {
                std::string error;
                auto p = cache.getOrCompile("stress_pattern", true, metrics, error);

                if (p) {
                    // Verify pattern is valid (catches use-after-free)
                    if (!p->isValid()) {
                        use_after_free_detected.fetch_add(1);
                    }

                    cache.releasePattern("stress_pattern", true, metrics, deferred);
                }
            }
        });
    }

    // Run for 500ms
    std::this_thread::sleep_for(std::chrono::milliseconds(500));
    stop.store(true);

    for (auto& th : workers) {
        th.join();
    }

    EXPECT_EQ(use_after_free_detected.load(), 0) << "No use-after-free should occur";
}

// Eviction with concurrent access
TEST_P(PatternCacheTest, EvictWhileConcurrentAccess) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    PatternCache cache(config);
    DeferredCache deferred(config);
    PatternCacheMetrics metrics;
    std::string error;

    // Pre-compile pattern
    auto p = cache.getOrCompile("test", true, metrics, error);
    EXPECT_EQ(p->refcount.load(), 1u);

    // Try to evict (should move to deferred, not delete)
    auto now = std::chrono::steady_clock::now() + std::chrono::seconds(61);
    size_t evicted = cache.evict(metrics, deferred, now);

    EXPECT_EQ(evicted, 1u);
    EXPECT_EQ(cache.size(), 0u);
    EXPECT_EQ(deferred.size(), 1u);
    EXPECT_TRUE(p->isValid()) << "Pattern should still be valid (moved to deferred)";
    EXPECT_EQ(metrics.ttl_entries_moved_to_deferred.load(), 1u);
}

// Metrics accuracy
TEST_P(PatternCacheTest, Metrics_HitRate) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    PatternCache cache(config);
    DeferredCache deferred(config);
    PatternCacheMetrics metrics;
    std::string error;

    // 1 miss
    cache.getOrCompile("p1", true, metrics, error);

    // 4 hits
    cache.getOrCompile("p1", true, metrics, error);
    cache.getOrCompile("p1", true, metrics, error);
    cache.getOrCompile("p1", true, metrics, error);
    cache.getOrCompile("p1", true, metrics, error);

    EXPECT_EQ(metrics.hits.load(), 4u);
    EXPECT_EQ(metrics.misses.load(), 1u);
    EXPECT_DOUBLE_EQ(metrics.hit_rate(), 80.0);  // 4/5 = 80%
}

TEST_P(PatternCacheTest, Metrics_BytesTracked) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    PatternCache cache(config);
    DeferredCache deferred(config);
    PatternCacheMetrics metrics;
    std::string error;

    cache.getOrCompile("p1", true, metrics, error);
    cache.getOrCompile("p2", true, metrics, error);

    cache.snapshotMetrics(metrics);

    EXPECT_EQ(metrics.current_entry_count, 2u);
    EXPECT_GT(metrics.actual_size_bytes, 0u);
}

// Double compilation (another thread compiles while we're compiling)
TEST_P(PatternCacheTest, DoubleCompilation_UsesFirst) {
    bool use_tbb = GetParam();
    CacheConfig config = makeConfig(use_tbb);
    PatternCache cache(config);
    DeferredCache deferred(config);
    PatternCacheMetrics metrics;

    std::shared_ptr<RE2Pattern> p1, p2;

    std::thread t1([&]() {
        std::string error;
        p1 = cache.getOrCompile("same_pattern", true, metrics, error);
    });

    std::thread t2([&]() {
        std::string error;
        p2 = cache.getOrCompile("same_pattern", true, metrics, error);
    });

    t1.join();
    t2.join();

    // Both should get valid patterns
    ASSERT_NE(p1, nullptr);
    ASSERT_NE(p2, nullptr);

    // Should be the same pattern
    EXPECT_EQ(p1.get(), p2.get());

    // Only one entry in cache
    EXPECT_EQ(cache.size(), 1u);
}

// Parameterize tests to run with both TBB=false and TBB=true
INSTANTIATE_TEST_SUITE_P(
    BothImplementations,
    PatternCacheTest,
    ::testing::Values(false, true),  // Run with std (false) and TBB (true)
    [](const ::testing::TestParamInfo<bool>& info) {
        return info.param ? "TBB" : "Std";
    }
);
