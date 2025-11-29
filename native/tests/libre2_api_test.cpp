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

    // ========== TEST DATA (defined ONCE) ==========
    const std::string TEST_MATCH = "test123";
    const std::string TEST_NO_MATCH = "nomatch";
    const std::string TEST_PARTIAL_1 = "test123";
    const std::string TEST_PARTIAL_2 = "xxx test123 yyy";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    bool re2_full_match = RE2::FullMatch(TEST_MATCH, *pattern->compiled_regex);
    bool re2_full_no_match = RE2::FullMatch(TEST_NO_MATCH, *pattern->compiled_regex);
    bool re2_partial_1 = RE2::PartialMatch(TEST_PARTIAL_1, *pattern->compiled_regex);
    bool re2_partial_2 = RE2::PartialMatch(TEST_PARTIAL_2, *pattern->compiled_regex);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    bool wrapper_full_match = fullMatch(pattern, TEST_MATCH);
    bool wrapper_full_no_match = fullMatch(pattern, TEST_NO_MATCH);
    bool wrapper_partial_1 = partialMatch(pattern, TEST_PARTIAL_1);
    bool wrapper_partial_2 = partialMatch(pattern, TEST_PARTIAL_2);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(re2_full_match, wrapper_full_match) << "Full match 'test123' must match";
    EXPECT_EQ(re2_full_no_match, wrapper_full_no_match) << "Full match 'nomatch' must not match";
    EXPECT_EQ(re2_partial_1, wrapper_partial_1) << "Partial match 'test123' must match";
    EXPECT_EQ(re2_partial_2, wrapper_partial_2) << "Partial match in 'xxx test123 yyy' must match";
    // ===========================================================

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

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "test123";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    bool re2_full = RE2::FullMatch(INPUT_TEXT, *pattern->compiled_regex);
    bool re2_partial = RE2::PartialMatch(INPUT_TEXT, *pattern->compiled_regex);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    bool wrapper_full = fullMatch(pattern, INPUT_TEXT);
    bool wrapper_partial = partialMatch(pattern, INPUT_TEXT);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(re2_full, wrapper_full) << "Full match must match";
    EXPECT_EQ(re2_partial, wrapper_partial) << "Partial match must match";
    // ===========================================================

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

    // ========== TEST DATA (defined ONCE) ==========
    const std::string FULL_INPUT = "12345";
    const std::string PARTIAL_INPUT = "abc 789 def";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string re2_full_capture;
    bool re2_full = RE2::FullMatch(FULL_INPUT, *p->compiled_regex, &re2_full_capture);

    std::string re2_partial_capture;
    bool re2_partial = RE2::PartialMatch(PARTIAL_INPUT, *p->compiled_regex, &re2_partial_capture);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string wrapper_full_capture;
    bool wrapper_full = fullMatch(p, FULL_INPUT, &wrapper_full_capture);

    std::string wrapper_partial_capture;
    bool wrapper_partial = partialMatch(p, PARTIAL_INPUT, &wrapper_partial_capture);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(re2_full, wrapper_full) << "Full match result must match";
    EXPECT_EQ(re2_full_capture, wrapper_full_capture) << "Full match capture must be '12345'";

    EXPECT_EQ(re2_partial, wrapper_partial) << "Partial match result must match";
    EXPECT_EQ(re2_partial_capture, wrapper_partial_capture) << "Partial match capture must be '789'";
    // ===========================================================

    releasePattern(p);
}

// Capture group extraction - 2 groups
TEST_F(Libre2APITest, CaptureGroup_Double) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("(\\w+):(\\d+)", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string FULL_INPUT = "host:8080";
    const std::string PARTIAL_INPUT = "connect to server:9000 now";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string re2_full_g1, re2_full_g2;
    bool re2_full = RE2::FullMatch(FULL_INPUT, *p->compiled_regex, &re2_full_g1, &re2_full_g2);

    std::string re2_partial_g1, re2_partial_g2;
    bool re2_partial = RE2::PartialMatch(PARTIAL_INPUT, *p->compiled_regex, &re2_partial_g1, &re2_partial_g2);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string wrapper_full_g1, wrapper_full_g2;
    bool wrapper_full = fullMatch(p, FULL_INPUT, &wrapper_full_g1, &wrapper_full_g2);

    std::string wrapper_partial_g1, wrapper_partial_g2;
    bool wrapper_partial = partialMatch(p, PARTIAL_INPUT, &wrapper_partial_g1, &wrapper_partial_g2);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(re2_full, wrapper_full) << "Full match result must match";
    EXPECT_EQ(re2_full_g1, wrapper_full_g1) << "Full match group 1 must be 'host'";
    EXPECT_EQ(re2_full_g2, wrapper_full_g2) << "Full match group 2 must be '8080'";

    EXPECT_EQ(re2_partial, wrapper_partial) << "Partial match result must match";
    EXPECT_EQ(re2_partial_g1, wrapper_partial_g1) << "Partial match group 1 must be 'server'";
    EXPECT_EQ(re2_partial_g2, wrapper_partial_g2) << "Partial match group 2 must be '9000'";
    // ===========================================================

    releasePattern(p);
}

// Capture groups - no match
TEST_F(Libre2APITest, CaptureGroup_NoMatch) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("(\\d+)", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "abc";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string re2_captured = "unchanged";
    bool re2_result = RE2::FullMatch(INPUT_TEXT, *p->compiled_regex, &re2_captured);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string wrapper_captured = "unchanged";
    bool wrapper_result = fullMatch(p, INPUT_TEXT, &wrapper_captured);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(re2_result, wrapper_result) << "No match result must match";
    EXPECT_EQ(re2_captured, wrapper_captured) << "Capture must remain 'unchanged' on no match";
    // ===========================================================

    releasePattern(p);
}

// Full match vs partial match behavior
TEST_F(Libre2APITest, FullVsPartialMatch) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("\\d+", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string EXACT_MATCH = "12345";
    const std::string PREFIX = "abc12345";
    const std::string SUFFIX = "12345def";
    const std::string BOTH = "abc12345def";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    // Full match requires ENTIRE text to match
    bool re2_full_exact = RE2::FullMatch(EXACT_MATCH, *p->compiled_regex);
    bool re2_full_prefix = RE2::FullMatch(PREFIX, *p->compiled_regex);
    bool re2_full_suffix = RE2::FullMatch(SUFFIX, *p->compiled_regex);
    bool re2_full_both = RE2::FullMatch(BOTH, *p->compiled_regex);

    // Partial match finds pattern ANYWHERE in text
    bool re2_partial_exact = RE2::PartialMatch(EXACT_MATCH, *p->compiled_regex);
    bool re2_partial_prefix = RE2::PartialMatch(PREFIX, *p->compiled_regex);
    bool re2_partial_suffix = RE2::PartialMatch(SUFFIX, *p->compiled_regex);
    bool re2_partial_both = RE2::PartialMatch(BOTH, *p->compiled_regex);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    // Full match requires ENTIRE text to match
    bool wrapper_full_exact = fullMatch(p, EXACT_MATCH);
    bool wrapper_full_prefix = fullMatch(p, PREFIX);
    bool wrapper_full_suffix = fullMatch(p, SUFFIX);
    bool wrapper_full_both = fullMatch(p, BOTH);

    // Partial match finds pattern ANYWHERE in text
    bool wrapper_partial_exact = partialMatch(p, EXACT_MATCH);
    bool wrapper_partial_prefix = partialMatch(p, PREFIX);
    bool wrapper_partial_suffix = partialMatch(p, SUFFIX);
    bool wrapper_partial_both = partialMatch(p, BOTH);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    // Full match tests
    EXPECT_EQ(re2_full_exact, wrapper_full_exact) << "Full match exact must match";
    EXPECT_EQ(re2_full_prefix, wrapper_full_prefix) << "Full match with prefix must not match";
    EXPECT_EQ(re2_full_suffix, wrapper_full_suffix) << "Full match with suffix must not match";
    EXPECT_EQ(re2_full_both, wrapper_full_both) << "Full match with both must not match";

    // Partial match tests
    EXPECT_EQ(re2_partial_exact, wrapper_partial_exact) << "Partial match exact must match";
    EXPECT_EQ(re2_partial_prefix, wrapper_partial_prefix) << "Partial match with prefix must match";
    EXPECT_EQ(re2_partial_suffix, wrapper_partial_suffix) << "Partial match with suffix must match";
    EXPECT_EQ(re2_partial_both, wrapper_partial_both) << "Partial match with both must match";
    // ===========================================================

    releasePattern(p);
}

//=============================================================================
// PHASE 1.2.1: CONSUME/SCAN FUNCTIONS TESTS
//=============================================================================

// Consume - basic (no captures)
// CRITICAL: Test RE2 directly vs our wrapper - must behave identically
TEST_F(Libre2APITest, Consume_Basic) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("\\d+", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "123abc456";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string_view input_re2(INPUT_TEXT);
    bool result_re2 = RE2::Consume(&input_re2, *p->compiled_regex);
    std::string remaining_re2(input_re2.data(), input_re2.size());
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    const char* input_wrapper = INPUT_TEXT.data();
    int len_wrapper = INPUT_TEXT.size();
    bool result_wrapper = consume(p, &input_wrapper, &len_wrapper);
    std::string remaining_wrapper(input_wrapper, len_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper)
        << "Return value must match - RE2: " << result_re2 << " vs Wrapper: " << result_wrapper;
    EXPECT_EQ(remaining_re2, remaining_wrapper)
        << "Remaining text must match - RE2: '" << remaining_re2 << "' vs Wrapper: '" << remaining_wrapper << "'";
    // ===========================================================

    releasePattern(p);
}

// Consume - with 1 capture
TEST_F(Libre2APITest, Consume_OneCapture) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("(\\w+):(\\d+)", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "host:8080 server:9000";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string_view input_re2(INPUT_TEXT);
    std::string capture_re2;
    bool result_re2 = RE2::Consume(&input_re2, *p->compiled_regex, &capture_re2);
    std::string remaining_re2(input_re2.data(), input_re2.size());
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    const char* input_wrapper = INPUT_TEXT.data();
    int len_wrapper = INPUT_TEXT.size();
    std::string capture_wrapper;
    bool result_wrapper = consume(p, &input_wrapper, &len_wrapper, &capture_wrapper);
    std::string remaining_wrapper(input_wrapper, len_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper) << "Return value must match";
    EXPECT_EQ(capture_re2, capture_wrapper) << "Captured group must match - RE2: '" << capture_re2 << "' vs Wrapper: '" << capture_wrapper << "'";
    EXPECT_EQ(remaining_re2, remaining_wrapper) << "Remaining text must match";
    // ===========================================================

    releasePattern(p);
}

// Consume - with 2 captures
TEST_F(Libre2APITest, Consume_TwoCaptures) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("(\\w+):(\\d+)", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "host:8080 server:9000";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string_view input_re2(INPUT_TEXT);
    std::string capture1_re2, capture2_re2;
    bool result_re2 = RE2::Consume(&input_re2, *p->compiled_regex, &capture1_re2, &capture2_re2);
    std::string remaining_re2(input_re2.data(), input_re2.size());
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    const char* input_wrapper = INPUT_TEXT.data();
    int len_wrapper = INPUT_TEXT.size();
    std::string capture1_wrapper, capture2_wrapper;
    bool result_wrapper = consume(p, &input_wrapper, &len_wrapper, &capture1_wrapper, &capture2_wrapper);
    std::string remaining_wrapper(input_wrapper, len_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper) << "Return value must match";
    EXPECT_EQ(capture1_re2, capture1_wrapper) << "Capture 1 must match - RE2: '" << capture1_re2 << "' vs Wrapper: '" << capture1_wrapper << "'";
    EXPECT_EQ(capture2_re2, capture2_wrapper) << "Capture 2 must match - RE2: '" << capture2_re2 << "' vs Wrapper: '" << capture2_wrapper << "'";
    EXPECT_EQ(remaining_re2, remaining_wrapper) << "Remaining text must match";
    // ===========================================================

    releasePattern(p);
}

// Consume - no match at start
TEST_F(Libre2APITest, Consume_NoMatch) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("\\d+", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "abc123";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string_view input_re2(INPUT_TEXT);
    bool result_re2 = RE2::Consume(&input_re2, *p->compiled_regex);
    std::string remaining_re2(input_re2.data(), input_re2.size());
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    const char* input_wrapper = INPUT_TEXT.data();
    int len_wrapper = INPUT_TEXT.size();
    bool result_wrapper = consume(p, &input_wrapper, &len_wrapper);
    std::string remaining_wrapper(input_wrapper, len_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper)
        << "Return value must match - RE2: " << result_re2 << " vs Wrapper: " << result_wrapper;
    EXPECT_EQ(remaining_re2, remaining_wrapper)
        << "Remaining text must match - RE2: '" << remaining_re2 << "' vs Wrapper: '" << remaining_wrapper << "'";
    // ===========================================================

    releasePattern(p);
}

// Consume - empty match (zero-width)
TEST_F(Libre2APITest, Consume_EmptyMatch) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("\\d*", true, error);  // Matches zero or more digits
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "abc";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string_view input_re2(INPUT_TEXT);
    bool result_re2 = RE2::Consume(&input_re2, *p->compiled_regex);
    std::string remaining_re2(input_re2.data(), input_re2.size());
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    const char* input_wrapper = INPUT_TEXT.data();
    int len_wrapper = INPUT_TEXT.size();
    bool result_wrapper = consume(p, &input_wrapper, &len_wrapper);
    std::string remaining_wrapper(input_wrapper, len_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper)
        << "Return value must match - RE2: " << result_re2 << " vs Wrapper: " << result_wrapper;
    EXPECT_EQ(remaining_re2, remaining_wrapper)
        << "Remaining text must match - RE2: '" << remaining_re2 << "' vs Wrapper: '" << remaining_wrapper << "'";
    // ===========================================================

    releasePattern(p);
}

// Consume - multiple iterations (extract all)
TEST_F(Libre2APITest, Consume_MultipleIterations) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("(\\w+)\\s*", true, error);  // Word + optional space
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "hello world test";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string_view input_re2(INPUT_TEXT);
    std::vector<std::string> words_re2;
    std::string word_re2;
    while (RE2::Consume(&input_re2, *p->compiled_regex, &word_re2)) {
        words_re2.push_back(word_re2);
        if (input_re2.empty()) break;  // Prevent infinite loop on empty matches
    }
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    const char* input_wrapper = INPUT_TEXT.data();
    int len_wrapper = INPUT_TEXT.size();
    std::vector<std::string> words_wrapper;
    std::string word_wrapper;
    while (consume(p, &input_wrapper, &len_wrapper, &word_wrapper)) {
        words_wrapper.push_back(word_wrapper);
        if (len_wrapper == 0) break;  // Prevent infinite loop on empty matches
    }
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    ASSERT_EQ(words_re2.size(), words_wrapper.size())
        << "Word count must match - RE2: " << words_re2.size() << " vs Wrapper: " << words_wrapper.size();
    EXPECT_EQ(words_re2[0], words_wrapper[0]) << "Word 0 must match";
    EXPECT_EQ(words_re2[1], words_wrapper[1]) << "Word 1 must match";
    EXPECT_EQ(words_re2[2], words_wrapper[2]) << "Word 2 must match";
    // ===========================================================

    releasePattern(p);
}

// FindAndConsume - basic
TEST_F(Libre2APITest, FindAndConsume_Basic) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("\\d+", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "abc123def456";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string_view input_re2(INPUT_TEXT);
    bool result1_re2 = RE2::FindAndConsume(&input_re2, *p->compiled_regex);
    std::string remaining1_re2(input_re2.data(), input_re2.size());

    bool result2_re2 = RE2::FindAndConsume(&input_re2, *p->compiled_regex);
    std::string remaining2_re2(input_re2.data(), input_re2.size());

    bool result3_re2 = RE2::FindAndConsume(&input_re2, *p->compiled_regex);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    const char* input_wrapper = INPUT_TEXT.data();
    int len_wrapper = INPUT_TEXT.size();
    bool result1_wrapper = findAndConsume(p, &input_wrapper, &len_wrapper);
    std::string remaining1_wrapper(input_wrapper, len_wrapper);

    bool result2_wrapper = findAndConsume(p, &input_wrapper, &len_wrapper);
    std::string remaining2_wrapper(input_wrapper, len_wrapper);

    bool result3_wrapper = findAndConsume(p, &input_wrapper, &len_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result1_re2, result1_wrapper) << "First match result must match";
    EXPECT_EQ(remaining1_re2, remaining1_wrapper) << "First remaining text must match";

    EXPECT_EQ(result2_re2, result2_wrapper) << "Second match result must match";
    EXPECT_EQ(remaining2_re2, remaining2_wrapper) << "Second remaining text must match";

    EXPECT_EQ(result3_re2, result3_wrapper) << "Third match result must match";
    // ===========================================================

    releasePattern(p);
}

// FindAndConsume - with captures
TEST_F(Libre2APITest, FindAndConsume_Captures) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("(\\w+):(\\d+)", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "prefix host:8080 suffix server:9000 end";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string_view input_re2(INPUT_TEXT);
    std::string name1_re2, port1_re2;
    bool result1_re2 = RE2::FindAndConsume(&input_re2, *p->compiled_regex, &name1_re2, &port1_re2);
    std::string remaining1_re2(input_re2.data(), input_re2.size());

    std::string name2_re2, port2_re2;
    bool result2_re2 = RE2::FindAndConsume(&input_re2, *p->compiled_regex, &name2_re2, &port2_re2);
    std::string remaining2_re2(input_re2.data(), input_re2.size());
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    const char* input_wrapper = INPUT_TEXT.data();
    int len_wrapper = INPUT_TEXT.size();
    std::string name1_wrapper, port1_wrapper;
    bool result1_wrapper = findAndConsume(p, &input_wrapper, &len_wrapper, &name1_wrapper, &port1_wrapper);
    std::string remaining1_wrapper(input_wrapper, len_wrapper);

    std::string name2_wrapper, port2_wrapper;
    bool result2_wrapper = findAndConsume(p, &input_wrapper, &len_wrapper, &name2_wrapper, &port2_wrapper);
    std::string remaining2_wrapper(input_wrapper, len_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result1_re2, result1_wrapper) << "First match result must match";
    EXPECT_EQ(name1_re2, name1_wrapper) << "First name must match";
    EXPECT_EQ(port1_re2, port1_wrapper) << "First port must match";
    EXPECT_EQ(remaining1_re2, remaining1_wrapper) << "First remaining text must match";

    EXPECT_EQ(result2_re2, result2_wrapper) << "Second match result must match";
    EXPECT_EQ(name2_re2, name2_wrapper) << "Second name must match";
    EXPECT_EQ(port2_re2, port2_wrapper) << "Second port must match";
    EXPECT_EQ(remaining2_re2, remaining2_wrapper) << "Second remaining text must match";
    // ===========================================================

    releasePattern(p);
}

// FindAndConsume - extract all matches
TEST_F(Libre2APITest, FindAndConsume_ExtractAll) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("\\d+", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "a1b22c333d4444e55555";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string_view input_re2(INPUT_TEXT);
    std::vector<std::string> numbers_re2;
    std::string num_re2;
    while (RE2::FindAndConsume(&input_re2, *p->compiled_regex, &num_re2)) {
        numbers_re2.push_back(num_re2);
    }
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string mutable_input(INPUT_TEXT);  // Create mutable copy for wrapper
    const char* input_wrapper = mutable_input.data();
    int len_wrapper = mutable_input.size();
    std::vector<std::string> numbers_wrapper;
    std::string num_wrapper;
    while (findAndConsume(p, &input_wrapper, &len_wrapper, &num_wrapper)) {
        numbers_wrapper.push_back(num_wrapper);
    }
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    ASSERT_EQ(numbers_re2.size(), numbers_wrapper.size())
        << "Number count must match - RE2: " << numbers_re2.size() << " vs Wrapper: " << numbers_wrapper.size();
    for (size_t i = 0; i < numbers_re2.size(); i++) {
        EXPECT_EQ(numbers_re2[i], numbers_wrapper[i])
            << "Number " << i << " must match - RE2: '" << numbers_re2[i]
            << "' vs Wrapper: '" << numbers_wrapper[i] << "'";
    }
    // ===========================================================

    releasePattern(p);
}

// Consume vs FindAndConsume - behavior difference
TEST_F(Libre2APITest, ConsumeVsFindAndConsume) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("\\d+", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "abc123";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    // Test consume - must match at START
    std::string_view consume_re2(INPUT_TEXT);
    bool consume_result_re2 = RE2::Consume(&consume_re2, *p->compiled_regex);
    std::string consume_remaining_re2(consume_re2.data(), consume_re2.size());

    // Test findAndConsume - finds match ANYWHERE
    std::string_view findconsume_re2(INPUT_TEXT);
    bool findconsume_result_re2 = RE2::FindAndConsume(&findconsume_re2, *p->compiled_regex);
    std::string findconsume_remaining_re2(findconsume_re2.data(), findconsume_re2.size());
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    // Test consume - must match at START
    const char* consume_wrapper = INPUT_TEXT.data();
    int consume_len = INPUT_TEXT.size();
    bool consume_result_wrapper = consume(p, &consume_wrapper, &consume_len);
    std::string consume_remaining_wrapper(consume_wrapper, consume_len);

    // Test findAndConsume - finds match ANYWHERE
    const char* findconsume_wrapper = INPUT_TEXT.data();
    int findconsume_len = INPUT_TEXT.size();
    bool findconsume_result_wrapper = findAndConsume(p, &findconsume_wrapper, &findconsume_len);
    std::string findconsume_remaining_wrapper(findconsume_wrapper, findconsume_len);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(consume_result_re2, consume_result_wrapper) << "Consume result must match";
    EXPECT_EQ(consume_remaining_re2, consume_remaining_wrapper) << "Consume remaining must match";

    EXPECT_EQ(findconsume_result_re2, findconsume_result_wrapper) << "FindAndConsume result must match";
    EXPECT_EQ(findconsume_remaining_re2, findconsume_remaining_wrapper) << "FindAndConsume remaining must match";
    // ===========================================================

    releasePattern(p);
}

// Consume - null pattern safety
TEST_F(Libre2APITest, Consume_NullPattern) {
    const char* text = "test";
    int len = 4;

    EXPECT_FALSE(consume(nullptr, &text, &len));
    EXPECT_FALSE(findAndConsume(nullptr, &text, &len));
}

// Consume - null input safety
TEST_F(Libre2APITest, Consume_NullInput) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("\\d+", true, error);
    ASSERT_NE(p, nullptr);

    EXPECT_FALSE(consume(p, nullptr, nullptr));
    EXPECT_FALSE(findAndConsume(p, nullptr, nullptr));

    releasePattern(p);
}

// Consume - empty input
TEST_F(Libre2APITest, Consume_EmptyInput) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("\\d*", true, error);  // Matches zero or more
    ASSERT_NE(p, nullptr);

    const char* text = "";
    int len = 0;

    // Matches empty string
    EXPECT_TRUE(consume(p, &text, &len));
    EXPECT_EQ(len, 0);

    releasePattern(p);
}

// Consume - large text performance
TEST_F(Libre2APITest, Consume_LargeText) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("\\w+\\s*", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    std::string large_text;
    for (int i = 0; i < 10000; i++) {
        large_text += "word ";
    }
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string_view input_re2(large_text);
    int count_re2 = 0;
    std::string word_re2;
    while (count_re2 < 100 && RE2::Consume(&input_re2, *p->compiled_regex, &word_re2)) {
        count_re2++;
    }
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    const char* input_wrapper = large_text.c_str();
    int len_wrapper = large_text.size();
    int count_wrapper = 0;
    std::string word_wrapper;
    while (count_wrapper < 100 && consume(p, &input_wrapper, &len_wrapper, &word_wrapper)) {
        count_wrapper++;
    }
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(count_re2, count_wrapper)
        << "Consume count must match - RE2: " << count_re2 << " vs Wrapper: " << count_wrapper;
    // ===========================================================

    releasePattern(p);
}

// Consume - Unicode text
TEST_F(Libre2APITest, Consume_Unicode) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("(\\w+)", true, error);
    ASSERT_NE(p, nullptr);

    const char* text = "hello 世界 test";
    int len = strlen(text);
    std::string word;

    // Consume "hello"
    EXPECT_TRUE(consume(p, &text, &len, &word));
    EXPECT_EQ(word, "hello");

    releasePattern(p);
}

// FindAndConsume - no match
TEST_F(Libre2APITest, FindAndConsume_NoMatch) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("\\d+", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "abcdef";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string_view input_re2(INPUT_TEXT);
    bool result_re2 = RE2::FindAndConsume(&input_re2, *p->compiled_regex);
    std::string remaining_re2(input_re2.data(), input_re2.size());
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    const char* input_wrapper = INPUT_TEXT.data();
    int len_wrapper = INPUT_TEXT.size();
    bool result_wrapper = findAndConsume(p, &input_wrapper, &len_wrapper);
    std::string remaining_wrapper(input_wrapper, len_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper)
        << "Return value must match - RE2: " << result_re2 << " vs Wrapper: " << result_wrapper;
    EXPECT_EQ(remaining_re2, remaining_wrapper)
        << "Remaining text must match - RE2: '" << remaining_re2 << "' vs Wrapper: '" << remaining_wrapper << "'";
    // ===========================================================

    releasePattern(p);
}
