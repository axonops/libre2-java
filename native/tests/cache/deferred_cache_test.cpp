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

#include "cache/deferred_cache.h"
#include <gtest/gtest.h>
#include <thread>

using namespace libre2::cache;

class DeferredCacheTest : public ::testing::Test {
protected:
    CacheConfig makeConfig() {
        std::string json = R"({
            "deferred_cache_ttl_ms": 60000,
            "pattern_cache_ttl_ms": 30000
        })";
        return CacheConfig::fromJson(json);
    }

    std::shared_ptr<RE2Pattern> makePattern(const std::string& pattern, uint32_t refcount = 1) {
        RE2::Options opts;
        opts.set_log_errors(false);
        auto regex = std::make_unique<RE2>(pattern, opts);
        auto p = std::make_shared<RE2Pattern>(std::move(regex), pattern, true);
        p->refcount.store(refcount);
        return p;
    }
};

// Basic operations
TEST_F(DeferredCacheTest, InitiallyEmpty) {
    CacheConfig config = makeConfig();
    DeferredCache cache(config);

    EXPECT_EQ(cache.size(), 0u);
}

TEST_F(DeferredCacheTest, AddPattern) {
    CacheConfig config = makeConfig();
    DeferredCache cache(config);
    DeferredCacheMetrics metrics;

    auto pattern = makePattern("test.*pattern", 2);
    cache.add(12345, pattern, metrics);

    EXPECT_EQ(cache.size(), 1u);
}

TEST_F(DeferredCacheTest, AddMultiplePatterns) {
    CacheConfig config = makeConfig();
    DeferredCache cache(config);
    DeferredCacheMetrics metrics;

    cache.add(1, makePattern("pattern1", 1), metrics);
    cache.add(2, makePattern("pattern2", 2), metrics);
    cache.add(3, makePattern("pattern3", 3), metrics);

    EXPECT_EQ(cache.size(), 3u);
}

TEST_F(DeferredCacheTest, AddNullPattern) {
    CacheConfig config = makeConfig();
    DeferredCache cache(config);
    DeferredCacheMetrics metrics;

    cache.add(12345, nullptr, metrics);

    EXPECT_EQ(cache.size(), 0u);  // Null ignored
}

// Immediate eviction (refcount → 0)
TEST_F(DeferredCacheTest, ImmediateEviction_RefcountZero) {
    CacheConfig config = makeConfig();
    DeferredCache cache(config);
    DeferredCacheMetrics metrics;

    auto pattern = makePattern("test.*", 1);
    cache.add(12345, pattern, metrics);
    EXPECT_EQ(cache.size(), 1u);

    // Decrement refcount to 0
    pattern->refcount.store(0);

    // Evict
    auto now = std::chrono::steady_clock::now();
    size_t evicted = cache.evict(metrics, now);

    EXPECT_EQ(evicted, 1u);
    EXPECT_EQ(cache.size(), 0u);
    EXPECT_EQ(metrics.immediate_evictions.load(), 1u);
    EXPECT_EQ(metrics.forced_evictions.load(), 0u);
    EXPECT_GT(metrics.immediate_evictions_bytes_freed.load(), 0u);
}

TEST_F(DeferredCacheTest, ImmediateEviction_MultiplePatterns) {
    CacheConfig config = makeConfig();
    DeferredCache cache(config);
    DeferredCacheMetrics metrics;

    auto p1 = makePattern("pattern1", 1);
    auto p2 = makePattern("pattern2", 2);
    auto p3 = makePattern("pattern3", 1);

    cache.add(1, p1, metrics);
    cache.add(2, p2, metrics);
    cache.add(3, p3, metrics);
    EXPECT_EQ(cache.size(), 3u);

    // Drop refcount to 0 for p1 and p3
    p1->refcount.store(0);
    p3->refcount.store(0);
    // p2 still has refcount=2

    auto now = std::chrono::steady_clock::now();
    size_t evicted = cache.evict(metrics, now);

    EXPECT_EQ(evicted, 2u);  // p1 and p3 evicted
    EXPECT_EQ(cache.size(), 1u);  // p2 remains
    EXPECT_EQ(metrics.immediate_evictions.load(), 2u);
}

// Forced eviction (TTL expired)
TEST_F(DeferredCacheTest, ForcedEviction_TTLExpired) {
    CacheConfig config = makeConfig();  // TTL = 60 seconds
    DeferredCache cache(config);
    DeferredCacheMetrics metrics;

    auto pattern = makePattern("test.*", 5);  // refcount still 5
    cache.add(12345, pattern, metrics);
    EXPECT_EQ(cache.size(), 1u);

    // Simulate time passing (61 seconds)
    auto now = std::chrono::steady_clock::now() + std::chrono::seconds(61);

    // Should force evict despite refcount > 0
    size_t evicted = cache.evict(metrics, now);

    EXPECT_EQ(evicted, 1u);
    EXPECT_EQ(cache.size(), 0u);
    EXPECT_EQ(metrics.immediate_evictions.load(), 0u);
    EXPECT_EQ(metrics.forced_evictions.load(), 1u);  // Leak detected!
    EXPECT_GT(metrics.forced_evictions_bytes_freed.load(), 0u);
}

TEST_F(DeferredCacheTest, ForcedEviction_NoEvictionBeforeTTL) {
    CacheConfig config = makeConfig();  // TTL = 60 seconds
    DeferredCache cache(config);
    DeferredCacheMetrics metrics;

    auto pattern = makePattern("test.*", 5);
    cache.add(12345, pattern, metrics);

    // Time passes but less than TTL (59 seconds)
    auto now = std::chrono::steady_clock::now() + std::chrono::seconds(59);

    size_t evicted = cache.evict(metrics, now);

    EXPECT_EQ(evicted, 0u);  // Not evicted yet
    EXPECT_EQ(cache.size(), 1u);
    EXPECT_EQ(metrics.forced_evictions.load(), 0u);
}

// Mixed eviction scenarios
TEST_F(DeferredCacheTest, MixedEviction_ImmediateAndForced) {
    CacheConfig config = makeConfig();  // TTL = 60 seconds
    DeferredCache cache(config);
    DeferredCacheMetrics metrics;

    auto p1 = makePattern("pattern1", 1);  // Will go to 0 (immediate)
    auto p2 = makePattern("pattern2", 5);  // Will stay > 0, force evict (TTL expired)

    cache.add(1, p1, metrics);
    cache.add(2, p2, metrics);
    EXPECT_EQ(cache.size(), 2u);

    // Drop p1 refcount to 0
    p1->refcount.store(0);

    // Simulate time (61 seconds - expires both but p1 evicted by refcount, p2 by TTL)
    auto now = std::chrono::steady_clock::now() + std::chrono::seconds(61);

    size_t evicted = cache.evict(metrics, now);

    EXPECT_EQ(evicted, 2u);  // p1 (immediate) + p2 (forced)
    EXPECT_EQ(cache.size(), 0u);
    EXPECT_EQ(metrics.immediate_evictions.load(), 1u);
    EXPECT_EQ(metrics.forced_evictions.load(), 1u);
}

// Metrics tests
TEST_F(DeferredCacheTest, Metrics_BytesTracked) {
    CacheConfig config = makeConfig();
    DeferredCache cache(config);
    DeferredCacheMetrics metrics;

    // Add pattern with known size
    auto pattern = makePattern("test.*pattern");
    cache.add(12345, pattern, metrics);

    // Snapshot metrics
    cache.snapshotMetrics(metrics);

    EXPECT_EQ(metrics.current_entry_count, 1u);
    EXPECT_GT(metrics.actual_size_bytes, 0u);  // Should have some size
}

TEST_F(DeferredCacheTest, Metrics_BytesFreed) {
    CacheConfig config = makeConfig();
    DeferredCache cache(config);
    DeferredCacheMetrics metrics;

    auto pattern = makePattern("test.*", 1);
    cache.add(12345, pattern, metrics);

    size_t size_before = pattern->approx_size_bytes;

    // Drop refcount and evict
    pattern->refcount.store(0);
    auto now = std::chrono::steady_clock::now();
    cache.evict(metrics, now);

    EXPECT_EQ(metrics.immediate_evictions_bytes_freed.load(), size_before);
    EXPECT_EQ(metrics.total_bytes_freed.load(), size_before);
}

TEST_F(DeferredCacheTest, Metrics_TotalEvictions) {
    CacheConfig config = makeConfig();
    DeferredCache cache(config);
    DeferredCacheMetrics metrics;

    auto p1 = makePattern("p1", 1);
    auto p2 = makePattern("p2", 5);

    cache.add(1, p1, metrics);
    cache.add(2, p2, metrics);

    // p1 → immediate, p2 → forced
    p1->refcount.store(0);
    auto now = std::chrono::steady_clock::now() + std::chrono::seconds(61);
    cache.evict(metrics, now);

    EXPECT_EQ(metrics.total_evictions.load(), 2u);  // 1 immediate + 1 forced
}

// Clear operation
TEST_F(DeferredCacheTest, Clear) {
    CacheConfig config = makeConfig();
    DeferredCache cache(config);
    DeferredCacheMetrics metrics;

    cache.add(1, makePattern("p1", 5), metrics);
    cache.add(2, makePattern("p2", 3), metrics);
    cache.add(3, makePattern("p3", 2), metrics);
    EXPECT_EQ(cache.size(), 3u);

    cache.clear();

    EXPECT_EQ(cache.size(), 0u);
}

// Thread safety
TEST_F(DeferredCacheTest, ThreadSafe_ConcurrentAdd) {
    CacheConfig config = makeConfig();
    DeferredCache cache(config);
    DeferredCacheMetrics metrics;

    const int num_threads = 4;
    const int patterns_per_thread = 25;

    std::vector<std::thread> threads;
    for (int t = 0; t < num_threads; t++) {
        threads.emplace_back([&cache, &metrics, t, patterns_per_thread]() {
            for (int i = 0; i < patterns_per_thread; i++) {
                uint64_t key = t * patterns_per_thread + i;
                auto pattern = std::make_shared<RE2Pattern>(
                    std::make_unique<RE2>("test", RE2::Options()),
                    "test", true);
                cache.add(key, pattern, metrics);
            }
        });
    }

    for (auto& t : threads) {
        t.join();
    }

    EXPECT_EQ(cache.size(), num_threads * patterns_per_thread);
}

TEST_F(DeferredCacheTest, ThreadSafe_ConcurrentEvict) {
    CacheConfig config = makeConfig();
    DeferredCache cache(config);
    DeferredCacheMetrics metrics;

    // Add patterns
    std::vector<std::shared_ptr<RE2Pattern>> patterns;
    for (int i = 0; i < 100; i++) {
        auto p = makePattern("pattern" + std::to_string(i), 1);
        patterns.push_back(p);
        cache.add(i, p, metrics);
    }

    // Multiple threads evicting concurrently
    std::vector<std::thread> threads;
    for (int t = 0; t < 4; t++) {
        threads.emplace_back([&cache, &metrics, &patterns, t]() {
            // Drop refcount for our subset
            for (size_t i = t * 25; i < (t + 1) * 25; i++) {
                patterns[i]->refcount.store(0);
            }

            // Evict
            auto now = std::chrono::steady_clock::now();
            cache.evict(metrics, now);
        });
    }

    for (auto& t : threads) {
        t.join();
    }

    // All should be evicted
    EXPECT_EQ(cache.size(), 0u);
    EXPECT_EQ(metrics.immediate_evictions.load(), 100u);
}

// Edge cases
TEST_F(DeferredCacheTest, EvictEmpty) {
    CacheConfig config = makeConfig();
    DeferredCache cache(config);
    DeferredCacheMetrics metrics;

    auto now = std::chrono::steady_clock::now();
    size_t evicted = cache.evict(metrics, now);

    EXPECT_EQ(evicted, 0u);
}

TEST_F(DeferredCacheTest, AddDuplicateKey) {
    CacheConfig config = makeConfig();
    DeferredCache cache(config);
    DeferredCacheMetrics metrics;

    auto p1 = makePattern("pattern1", 1);
    auto p2 = makePattern("pattern2", 2);

    cache.add(12345, p1, metrics);
    EXPECT_EQ(cache.size(), 1u);

    // Add again with same key (should be ignored)
    cache.add(12345, p2, metrics);
    EXPECT_EQ(cache.size(), 1u);  // Still 1
}

TEST_F(DeferredCacheTest, VeryShortTTL) {
    std::string json = R"({
        "deferred_cache_ttl_ms": 100,
        "pattern_cache_ttl_ms": 50
    })";
    CacheConfig config = CacheConfig::fromJson(json);
    DeferredCache cache(config);
    DeferredCacheMetrics metrics;

    auto pattern = makePattern("test", 5);
    cache.add(1, pattern, metrics);

    // Wait for TTL to expire
    std::this_thread::sleep_for(std::chrono::milliseconds(150));

    auto now = std::chrono::steady_clock::now();
    size_t evicted = cache.evict(metrics, now);

    EXPECT_EQ(evicted, 1u);
    EXPECT_EQ(metrics.forced_evictions.load(), 1u);
}

// Snapshot metrics
TEST_F(DeferredCacheTest, SnapshotMetrics) {
    CacheConfig config = makeConfig();
    DeferredCache cache(config);
    DeferredCacheMetrics metrics;

    cache.add(1, makePattern("p1"), metrics);
    cache.add(2, makePattern("p2"), metrics);
    cache.add(3, makePattern("p3"), metrics);

    cache.snapshotMetrics(metrics);

    EXPECT_EQ(metrics.current_entry_count, 3u);
    EXPECT_GT(metrics.actual_size_bytes, 0u);
}

TEST_F(DeferredCacheTest, SnapshotMetrics_AfterEviction) {
    CacheConfig config = makeConfig();
    DeferredCache cache(config);
    DeferredCacheMetrics metrics;

    auto p1 = makePattern("p1", 1);
    auto p2 = makePattern("p2", 1);
    cache.add(1, p1, metrics);
    cache.add(2, p2, metrics);

    // Evict one
    p1->refcount.store(0);
    auto now = std::chrono::steady_clock::now();
    cache.evict(metrics, now);

    cache.snapshotMetrics(metrics);

    EXPECT_EQ(metrics.current_entry_count, 1u);  // Only p2 remains
}
