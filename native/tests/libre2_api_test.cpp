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
#include <gtest/gtest.h>
#include <thread>
#include <vector>

using namespace libre2::api;
using namespace libre2::cache;

/**
 * Facade API Tests - Test the high-level API for language wrappers.
 *
 * These tests verify the simple API works correctly with automatic caching.
 */
class Libre2APITest : public ::testing::Test {
protected:
    void SetUp() override {
        // Each test gets fresh cache (shutdown between tests)
        if (isCacheInitialized()) {
            shutdownCache();
        }
    }

    void TearDown() override {
        // Clean up after each test
        if (isCacheInitialized()) {
            shutdownCache();
        }
    }
};

// Compile without cache (direct compilation)
TEST_F(Libre2APITest, CompileWithoutCache) {
    EXPECT_FALSE(isCacheInitialized());

    std::string error;
    RE2Pattern* pattern = compilePattern("test.*", true, error);

    ASSERT_NE(pattern, nullptr) << "Compilation failed: " << error;
    EXPECT_TRUE(error.empty());
    EXPECT_FALSE(isCacheInitialized());  // Still no cache

    // Full match
    EXPECT_TRUE(fullMatch(pattern, "test123"));
    EXPECT_FALSE(fullMatch(pattern, "nomatch"));

    // Partial match
    EXPECT_TRUE(partialMatch(pattern, "test123"));
    EXPECT_TRUE(partialMatch(pattern, "xxx test123 yyy"));  // Pattern found in middle

    releasePattern(pattern);  // Immediate delete (no cache)
}

// Compile with cache
TEST_F(Libre2APITest, CompileWithCache) {
    initCache();  // Enable caching
    EXPECT_TRUE(isCacheInitialized());

    std::string error;
    RE2Pattern* pattern = compilePattern("test.*", true, error);

    ASSERT_NE(pattern, nullptr);
    EXPECT_EQ(pattern->refcount.load(), 1u);  // Cached, refcount=1

    EXPECT_TRUE(fullMatch(pattern, "test123"));
    EXPECT_TRUE(partialMatch(pattern, "test123"));

    releasePattern(pattern);  // Refcount → 0, cleaned by eviction
}

// Explicit initialization with defaults
TEST_F(Libre2APITest, ExplicitInitDefault) {
    EXPECT_FALSE(isCacheInitialized());

    initCache();  // Empty string = defaults

    EXPECT_TRUE(isCacheInitialized());

    std::string error;
    RE2Pattern* pattern = compilePattern("test", true, error);
    ASSERT_NE(pattern, nullptr);

    releasePattern(pattern);
}

// Explicit initialization with custom config
TEST_F(Libre2APITest, ExplicitInitCustom) {
    std::string custom_config = R"({
        "cache_enabled": true,
        "pattern_cache_target_capacity_bytes": 10485760,
        "pattern_cache_ttl_ms": 60000,
        "deferred_cache_ttl_ms": 120000,
        "auto_start_eviction_thread": false
    })";

    initCache(custom_config);
    EXPECT_TRUE(isCacheInitialized());

    std::string error;
    RE2Pattern* pattern = compilePattern("test", true, error);
    ASSERT_NE(pattern, nullptr);

    releasePattern(pattern);
}

// Cache reuse (same pattern twice)
TEST_F(Libre2APITest, CacheReuse) {
    initCache();  // Enable caching

    std::string error;

    RE2Pattern* p1 = compilePattern("test.*", true, error);
    RE2Pattern* p2 = compilePattern("test.*", true, error);

    // Should get same pointer (cached)
    EXPECT_EQ(p1, p2);
    EXPECT_EQ(p1->refcount.load(), 2u);

    releasePattern(p1);
    EXPECT_EQ(p2->refcount.load(), 1u);

    releasePattern(p2);
    EXPECT_EQ(p2->refcount.load(), 0u);
}

// Compilation error handling
TEST_F(Libre2APITest, CompilationError) {
    std::string error;
    RE2Pattern* pattern = compilePattern("[invalid", true, error);

    EXPECT_EQ(pattern, nullptr);
    EXPECT_FALSE(error.empty());
}

// Null pattern safety
TEST_F(Libre2APITest, NullPatternSafe) {
    releasePattern(nullptr);  // Should not crash
    EXPECT_FALSE(fullMatch(nullptr, "test"));  // Should return false
    EXPECT_FALSE(partialMatch(nullptr, "test"));  // Should return false
}

// Metrics accessible
TEST_F(Libre2APITest, GetMetrics) {
    initCache();  // Enable caching

    std::string error;
    RE2Pattern* pattern = compilePattern("test", true, error);

    std::string json = getMetricsJSON();

    EXPECT_FALSE(json.empty());
    EXPECT_NE(json.find("pattern_cache"), std::string::npos);

    releasePattern(pattern);
}

// Shutdown and reinit
TEST_F(Libre2APITest, ShutdownAndReinit) {
    std::string error;
    RE2Pattern* p1 = compilePattern("test", true, error);
    ASSERT_NE(p1, nullptr);

    releasePattern(p1);
    shutdownCache();

    EXPECT_FALSE(isCacheInitialized());

    // Re-initialize
    initCache();
    RE2Pattern* p2 = compilePattern("test", true, error);
    ASSERT_NE(p2, nullptr);

    releasePattern(p2);
}

// Thread safety: Concurrent compile with cache
TEST_F(Libre2APITest, ThreadSafeConcurrentCompile) {
    initCache();  // Enable caching

    const int num_threads = 20;
    const int iterations = 50;
    std::atomic<int> errors{0};

    std::vector<std::thread> threads;
    for (int t = 0; t < num_threads; t++) {
        threads.emplace_back([&errors, iterations]() {
            for (int i = 0; i < iterations; i++) {
                std::string error;
                RE2Pattern* p = compilePattern("test.*", true, error);

                if (p) {
                    bool matched = fullMatch(p, "test123");
                    (void)matched;  // Suppress unused warning
                    releasePattern(p);
                } else {
                    errors.fetch_add(1);
                }
            }
        });
    }

    for (auto& th : threads) {
        th.join();
    }

    EXPECT_EQ(errors.load(), 0);
}

// Double init should throw
TEST_F(Libre2APITest, DoubleInitThrows) {
    initCache();

    EXPECT_THROW(initCache(), std::runtime_error);
}

// Compile without init works (no caching)
TEST_F(Libre2APITest, CompileWithoutInitWorks) {
    // Don't call initCache()
    EXPECT_FALSE(isCacheInitialized());

    std::string error;
    RE2Pattern* p = compilePattern("test", true, error);

    ASSERT_NE(p, nullptr);
    EXPECT_EQ(p->refcount.load(), 0u);  // No caching, refcount stays 0

    releasePattern(p);  // Immediate delete
    // No crash
}

// Capture group extraction - 1 group
TEST_F(Libre2APITest, CaptureGroup_Single) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("(\\d+)", true, error);
    ASSERT_NE(p, nullptr);

    std::string captured;

    // Full match with capture
    EXPECT_TRUE(fullMatch(p, "12345", &captured));
    EXPECT_EQ(captured, "12345");

    // Partial match with capture
    captured.clear();
    EXPECT_TRUE(partialMatch(p, "abc 789 def", &captured));
    EXPECT_EQ(captured, "789");

    releasePattern(p);
}

// Capture group extraction - 2 groups
TEST_F(Libre2APITest, CaptureGroup_Double) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("(\\w+):(\\d+)", true, error);
    ASSERT_NE(p, nullptr);

    std::string group1, group2;

    // Full match with 2 captures
    EXPECT_TRUE(fullMatch(p, "host:8080", &group1, &group2));
    EXPECT_EQ(group1, "host");
    EXPECT_EQ(group2, "8080");

    // Partial match with 2 captures
    group1.clear();
    group2.clear();
    EXPECT_TRUE(partialMatch(p, "connect to server:9000 now", &group1, &group2));
    EXPECT_EQ(group1, "server");
    EXPECT_EQ(group2, "9000");

    releasePattern(p);
}

// Capture groups - no match
TEST_F(Libre2APITest, CaptureGroup_NoMatch) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("(\\d+)", true, error);
    ASSERT_NE(p, nullptr);

    std::string captured = "unchanged";

    // No match - capture string should remain unchanged
    EXPECT_FALSE(fullMatch(p, "abc", &captured));
    EXPECT_EQ(captured, "unchanged");

    releasePattern(p);
}

// Full match vs partial match behavior
TEST_F(Libre2APITest, FullVsPartialMatch) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("\\d+", true, error);
    ASSERT_NE(p, nullptr);

    // Full match requires ENTIRE text to match
    EXPECT_TRUE(fullMatch(p, "12345"));
    EXPECT_FALSE(fullMatch(p, "abc12345"));      // Has prefix
    EXPECT_FALSE(fullMatch(p, "12345def"));      // Has suffix
    EXPECT_FALSE(fullMatch(p, "abc12345def"));   // Has both

    // Partial match finds pattern ANYWHERE in text
    EXPECT_TRUE(partialMatch(p, "12345"));
    EXPECT_TRUE(partialMatch(p, "abc12345"));
    EXPECT_TRUE(partialMatch(p, "12345def"));
    EXPECT_TRUE(partialMatch(p, "abc12345def"));

    releasePattern(p);
}
