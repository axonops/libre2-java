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

//=============================================================================
// PHASE 1.2.2: REPLACEMENT FUNCTIONS TESTS
//=============================================================================

// Replace - basic (first occurrence)
TEST_F(Libre2APITest, Replace_Basic) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("world", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "hello world";
    const std::string REWRITE = "RE2";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string text_re2(INPUT_TEXT);
    bool result_re2 = RE2::Replace(&text_re2, *p->compiled_regex, REWRITE);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string text_wrapper;
    bool result_wrapper = replace(p, INPUT_TEXT, REWRITE, &text_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper) << "Return value must match";
    EXPECT_EQ(text_re2, text_wrapper) 
        << "Result text must match - RE2: '" << text_re2 
        << "' vs Wrapper: '" << text_wrapper << "'";
    // ===========================================================

    releasePattern(p);
}

// Replace - with capture group references
TEST_F(Libre2APITest, Replace_CaptureGroups) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("(\\w+):(\\d+)", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "server:8080";
    const std::string REWRITE = "\\1 on port \\2";  // host on port 8080
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string text_re2(INPUT_TEXT);
    bool result_re2 = RE2::Replace(&text_re2, *p->compiled_regex, REWRITE);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string text_wrapper;
    bool result_wrapper = replace(p, INPUT_TEXT, REWRITE, &text_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper) << "Return value must match";
    EXPECT_EQ(text_re2, text_wrapper)
        << "Result text must match - RE2: '" << text_re2
        << "' vs Wrapper: '" << text_wrapper << "'";
    // ===========================================================

    releasePattern(p);
}

// Replace - no match
TEST_F(Libre2APITest, Replace_NoMatch) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("xyz", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "hello world";
    const std::string REWRITE = "REPLACED";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string text_re2(INPUT_TEXT);
    bool result_re2 = RE2::Replace(&text_re2, *p->compiled_regex, REWRITE);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string text_wrapper;
    bool result_wrapper = replace(p, INPUT_TEXT, REWRITE, &text_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper) << "Return value must match (false)";
    EXPECT_EQ(text_re2, text_wrapper)
        << "Text must be unchanged - RE2: '" << text_re2
        << "' vs Wrapper: '" << text_wrapper << "'";
    // ===========================================================

    releasePattern(p);
}

// ReplaceAll - single occurrence
TEST_F(Libre2APITest, ReplaceAll_Single) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("world", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "hello world";
    const std::string REWRITE = "RE2";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string text_re2(INPUT_TEXT);
    int count_re2 = RE2::GlobalReplace(&text_re2, *p->compiled_regex, REWRITE);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string text_wrapper;
    int count_wrapper = replaceAll(p, INPUT_TEXT, REWRITE, &text_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(count_re2, count_wrapper)
        << "Replacement count must match - RE2: " << count_re2
        << " vs Wrapper: " << count_wrapper;
    EXPECT_EQ(text_re2, text_wrapper)
        << "Result text must match - RE2: '" << text_re2
        << "' vs Wrapper: '" << text_wrapper << "'";
    // ===========================================================

    releasePattern(p);
}

// ReplaceAll - multiple occurrences
TEST_F(Libre2APITest, ReplaceAll_Multiple) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("a", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "aaa";
    const std::string REWRITE = "b";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string text_re2(INPUT_TEXT);
    int count_re2 = RE2::GlobalReplace(&text_re2, *p->compiled_regex, REWRITE);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string text_wrapper;
    int count_wrapper = replaceAll(p, INPUT_TEXT, REWRITE, &text_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(count_re2, count_wrapper)
        << "Replacement count must match - RE2: " << count_re2
        << " vs Wrapper: " << count_wrapper;
    EXPECT_EQ(text_re2, text_wrapper)
        << "Result text must match - RE2: '" << text_re2
        << "' vs Wrapper: '" << text_wrapper << "'";
    // ===========================================================

    releasePattern(p);
}

// ReplaceAll - non-overlapping (banana example)
TEST_F(Libre2APITest, ReplaceAll_NonOverlapping) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("ana", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "banana";
    const std::string REWRITE = "XXX";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string text_re2(INPUT_TEXT);
    int count_re2 = RE2::GlobalReplace(&text_re2, *p->compiled_regex, REWRITE);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string text_wrapper;
    int count_wrapper = replaceAll(p, INPUT_TEXT, REWRITE, &text_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(count_re2, count_wrapper)
        << "Replacement count must match - RE2: " << count_re2
        << " vs Wrapper: " << count_wrapper;
    EXPECT_EQ(text_re2, text_wrapper)
        << "Result text must match - RE2: '" << text_re2
        << "' vs Wrapper: '" << text_wrapper << "'";
    // ===========================================================

    releasePattern(p);
}

// ReplaceAll - with capture references
TEST_F(Libre2APITest, ReplaceAll_CaptureReferences) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("(\\d+)", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "a1b22c333";
    const std::string REWRITE = "[\\1]";  // Wrap numbers in brackets
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string text_re2(INPUT_TEXT);
    int count_re2 = RE2::GlobalReplace(&text_re2, *p->compiled_regex, REWRITE);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string text_wrapper;
    int count_wrapper = replaceAll(p, INPUT_TEXT, REWRITE, &text_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(count_re2, count_wrapper)
        << "Replacement count must match - RE2: " << count_re2
        << " vs Wrapper: " << count_wrapper;
    EXPECT_EQ(text_re2, text_wrapper)
        << "Result text must match - RE2: '" << text_re2
        << "' vs Wrapper: '" << text_wrapper << "'";
    // ===========================================================

    releasePattern(p);
}

// Extract - basic
TEST_F(Libre2APITest, Extract_Basic) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("(\\w+)@(\\w+)", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "email: user@example.com";
    const std::string REWRITE = "\\1 at \\2";  // user at example
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string result_re2;
    bool extracted_re2 = RE2::Extract(INPUT_TEXT, *p->compiled_regex, REWRITE, &result_re2);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string result_wrapper;
    bool extracted_wrapper = extract(p, INPUT_TEXT, REWRITE, &result_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(extracted_re2, extracted_wrapper) << "Return value must match";
    EXPECT_EQ(result_re2, result_wrapper)
        << "Extracted text must match - RE2: '" << result_re2
        << "' vs Wrapper: '" << result_wrapper << "'";
    // ===========================================================

    releasePattern(p);
}

// Extract - no match
TEST_F(Libre2APITest, Extract_NoMatch) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("xyz", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "hello world";
    const std::string REWRITE = "REPLACED";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string result_re2;
    bool extracted_re2 = RE2::Extract(INPUT_TEXT, *p->compiled_regex, REWRITE, &result_re2);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string result_wrapper;
    bool extracted_wrapper = extract(p, INPUT_TEXT, REWRITE, &result_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(extracted_re2, extracted_wrapper) << "Return value must match (false)";
    EXPECT_EQ(result_re2, result_wrapper)
        << "Result must match - RE2: '" << result_re2
        << "' vs Wrapper: '" << result_wrapper << "'";
    // ===========================================================

    releasePattern(p);
}

// Replace - empty text
TEST_F(Libre2APITest, Replace_EmptyText) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("test", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "";
    const std::string REWRITE = "REPLACED";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string text_re2(INPUT_TEXT);
    bool result_re2 = RE2::Replace(&text_re2, *p->compiled_regex, REWRITE);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string text_wrapper;
    bool result_wrapper = replace(p, INPUT_TEXT, REWRITE, &text_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper) << "Return value must match";
    EXPECT_EQ(text_re2, text_wrapper) << "Result must match";
    // ===========================================================

    releasePattern(p);
}

// ReplaceAll - whole match reference (\\0)
TEST_F(Libre2APITest, ReplaceAll_WholeMatchReference) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("\\w+", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "hello world";
    const std::string REWRITE = "[\\0]";  // Wrap each word in brackets
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string text_re2(INPUT_TEXT);
    int count_re2 = RE2::GlobalReplace(&text_re2, *p->compiled_regex, REWRITE);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string text_wrapper;
    int count_wrapper = replaceAll(p, INPUT_TEXT, REWRITE, &text_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(count_re2, count_wrapper) << "Count must match";
    EXPECT_EQ(text_re2, text_wrapper)
        << "Result must match - RE2: '" << text_re2
        << "' vs Wrapper: '" << text_wrapper << "'";
    // ===========================================================

    releasePattern(p);
}

// Replace - large text
TEST_F(Libre2APITest, Replace_LargeText) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("word", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    std::string large_text;
    for (int i = 0; i < 10000; i++) {
        large_text += "word ";
    }
    const std::string REWRITE = "REPLACED";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string text_re2(large_text);
    bool result_re2 = RE2::Replace(&text_re2, *p->compiled_regex, REWRITE);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string text_wrapper;
    bool result_wrapper = replace(p, large_text, REWRITE, &text_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper) << "Return value must match";
    EXPECT_EQ(text_re2, text_wrapper) << "Result must match";
    // ===========================================================

    releasePattern(p);
}

// ReplaceAll - large text (performance)
TEST_F(Libre2APITest, ReplaceAll_LargeText) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("word", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    std::string large_text;
    for (int i = 0; i < 10000; i++) {
        large_text += "word ";
    }
    const std::string REWRITE = "X";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string text_re2(large_text);
    int count_re2 = RE2::GlobalReplace(&text_re2, *p->compiled_regex, REWRITE);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string text_wrapper;
    int count_wrapper = replaceAll(p, large_text, REWRITE, &text_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(count_re2, count_wrapper)
        << "Count must match - RE2: " << count_re2
        << " vs Wrapper: " << count_wrapper;
    EXPECT_EQ(text_re2, text_wrapper) << "Result must match";
    // ===========================================================

    releasePattern(p);
}

// Replace - Unicode
TEST_F(Libre2APITest, Replace_Unicode) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("世界", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "hello 世界 test";
    const std::string REWRITE = "world";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string text_re2(INPUT_TEXT);
    bool result_re2 = RE2::Replace(&text_re2, *p->compiled_regex, REWRITE);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string text_wrapper;
    bool result_wrapper = replace(p, INPUT_TEXT, REWRITE, &text_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper) << "Return value must match";
    EXPECT_EQ(text_re2, text_wrapper)
        << "Result must match - RE2: '" << text_re2
        << "' vs Wrapper: '" << text_wrapper << "'";
    // ===========================================================

    releasePattern(p);
}

// Replace - null pattern safety
TEST_F(Libre2APITest, Replace_NullPattern) {
    std::string result;
    EXPECT_FALSE(replace(nullptr, "test", "repl", &result));
    EXPECT_FALSE(extract(nullptr, "test", "repl", &result));
}

// ReplaceAll - null pattern safety
TEST_F(Libre2APITest, ReplaceAll_NullPattern) {
    std::string result;
    EXPECT_EQ(replaceAll(nullptr, "test", "repl", &result), -1);
}

// Replace - thread safety
TEST_F(Libre2APITest, Replace_ThreadSafe) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("test", true, error);
    ASSERT_NE(p, nullptr);

    const int num_threads = 10;
    const int iterations = 100;
    std::atomic<int> errors{0};

    std::vector<std::thread> threads;
    for (int t = 0; t < num_threads; t++) {
        threads.emplace_back([&]() {
            for (int i = 0; i < iterations; i++) {
                std::string result;
                if (!replace(p, "test123", "X", &result)) {
                    errors.fetch_add(1);
                }
            }
        });
    }

    for (auto& th : threads) {
        th.join();
    }

    EXPECT_EQ(errors.load(), 0);

    releasePattern(p);
}

//=============================================================================
// PHASE 1.2.3: UTILITY FUNCTIONS TESTS
//=============================================================================

// QuoteMeta - basic special characters
TEST_F(Libre2APITest, QuoteMeta_Basic) {
    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "1.5-2.0?";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string result_re2 = RE2::QuoteMeta(INPUT_TEXT);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string result_wrapper = quoteMeta(INPUT_TEXT);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper)
        << "Quoted string must match - RE2: '" << result_re2
        << "' vs Wrapper: '" << result_wrapper << "'";
    // ===========================================================
}

// QuoteMeta - all special characters
TEST_F(Libre2APITest, QuoteMeta_AllSpecialChars) {
    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = ".*+?^$[]{}()|\\";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string result_re2 = RE2::QuoteMeta(INPUT_TEXT);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string result_wrapper = quoteMeta(INPUT_TEXT);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper)
        << "Quoted string must match - RE2: '" << result_re2
        << "' vs Wrapper: '" << result_wrapper << "'";
    // ===========================================================
}

// QuoteMeta - empty string
TEST_F(Libre2APITest, QuoteMeta_Empty) {
    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string result_re2 = RE2::QuoteMeta(INPUT_TEXT);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string result_wrapper = quoteMeta(INPUT_TEXT);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper) << "Results must match";
    // ===========================================================
}

// QuoteMeta - Unicode
TEST_F(Libre2APITest, QuoteMeta_Unicode) {
    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "Price: $5.99 (世界)";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string result_re2 = RE2::QuoteMeta(INPUT_TEXT);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string result_wrapper = quoteMeta(INPUT_TEXT);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper)
        << "Quoted string must match - RE2: '" << result_re2
        << "' vs Wrapper: '" << result_wrapper << "'";
    // ===========================================================
}

// GetPatternInfo - valid pattern
TEST_F(Libre2APITest, GetPatternInfo_Valid) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("(\\w+):(\\d+)", true, error);
    ASSERT_NE(p, nullptr);

    std::string info = getPatternInfo(p);

    // Verify JSON contains expected fields
    EXPECT_NE(info.find("\"valid\":true"), std::string::npos);
    EXPECT_NE(info.find("\"capturing_groups\":2"), std::string::npos);
    EXPECT_NE(info.find("\"pattern\":"), std::string::npos);
    EXPECT_NE(info.find("\"program_size\":"), std::string::npos);

    releasePattern(p);
}

// GetPatternInfo - invalid pattern
TEST_F(Libre2APITest, GetPatternInfo_Invalid) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("[invalid", true, error);
    EXPECT_EQ(p, nullptr) << "Invalid pattern should return nullptr";

    // getPatternInfo with null should return error JSON
    std::string info = getPatternInfo(nullptr);
    EXPECT_NE(info.find("\"valid\":false"), std::string::npos);
}

// GetPatternInfo - named groups
TEST_F(Libre2APITest, GetPatternInfo_NamedGroups) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("(?P<name>\\w+):(?P<port>\\d+)", true, error);
    ASSERT_NE(p, nullptr);

    std::string info = getPatternInfo(p);

    // Verify named groups present
    EXPECT_NE(info.find("\"named_groups\""), std::string::npos);
    EXPECT_NE(info.find("\"name\""), std::string::npos);
    EXPECT_NE(info.find("\"port\""), std::string::npos);

    releasePattern(p);
}

// IsPatternValid - valid
TEST_F(Libre2APITest, IsPatternValid_Valid) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("test", true, error);
    ASSERT_NE(p, nullptr);

    EXPECT_TRUE(isPatternValid(p));

    releasePattern(p);
}

// IsPatternValid - null
TEST_F(Libre2APITest, IsPatternValid_Null) {
    EXPECT_FALSE(isPatternValid(nullptr));
}

//=============================================================================
// PHASE 1.2.4: BULK & OFF-HEAP OPERATIONS TESTS
//=============================================================================

// FullMatchBulk - basic
TEST_F(Libre2APITest, FullMatchBulk_Basic) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("\\d+", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::vector<std::string> TEXTS = {"123", "abc", "456", "xyz789"};
    // ==============================================

    // ========== EXECUTE RE2 (loop) ==========
    bool results_re2[4];
    for (size_t i = 0; i < TEXTS.size(); i++) {
        results_re2[i] = RE2::FullMatch(TEXTS[i], *p->compiled_regex);
    }
    // ========================================

    // ========== EXECUTE WRAPPER (bulk) ==========
    const char* texts[4];
    int lens[4];
    for (size_t i = 0; i < TEXTS.size(); i++) {
        texts[i] = TEXTS[i].data();
        lens[i] = TEXTS[i].size();
    }

    bool results_wrapper[4];
    fullMatchBulk(p, texts, lens, 4, results_wrapper);
    // ===========================================

    // ========== COMPARE (CRITICAL) ==========
    for (size_t i = 0; i < TEXTS.size(); i++) {
        EXPECT_EQ(results_re2[i], results_wrapper[i])
            << "Result " << i << " must match - Text: '" << TEXTS[i] << "'";
    }
    // ========================================

    releasePattern(p);
}

// FullMatchDirect - zero-copy
TEST_F(Libre2APITest, FullMatchDirect_ZeroCopy) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("test", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "test";
    // ==============================================

    // ========== EXECUTE RE2 (with StringPiece) ==========
    re2::StringPiece input_re2(INPUT_TEXT);
    bool result_re2 = RE2::FullMatch(input_re2, *p->compiled_regex);
    // ===================================================

    // ========== EXECUTE WRAPPER (direct memory) ==========
    int64_t address = reinterpret_cast<int64_t>(INPUT_TEXT.data());
    bool result_wrapper = fullMatchDirect(p, address, INPUT_TEXT.size());
    // =====================================================

    // ========== COMPARE (CRITICAL) ==========
    EXPECT_EQ(result_re2, result_wrapper) << "Results must match";
    // ========================================

    releasePattern(p);
}

// FullMatchDirectBulk - combined
TEST_F(Libre2APITest, FullMatchDirectBulk_Combined) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("\\d+", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::vector<std::string> TEXTS = {"123", "abc", "456"};
    // ==============================================

    // ========== EXECUTE RE2 (loop with StringPiece) ==========
    bool results_re2[3];
    for (size_t i = 0; i < TEXTS.size(); i++) {
        re2::StringPiece input(TEXTS[i]);
        results_re2[i] = RE2::FullMatch(input, *p->compiled_regex);
    }
    // =========================================================

    // ========== EXECUTE WRAPPER (bulk direct) ==========
    int64_t addresses[3];
    int lengths[3];
    for (size_t i = 0; i < TEXTS.size(); i++) {
        addresses[i] = reinterpret_cast<int64_t>(TEXTS[i].data());
        lengths[i] = TEXTS[i].size();
    }

    bool results_wrapper[3];
    fullMatchDirectBulk(p, addresses, lengths, 3, results_wrapper);
    // ===================================================

    // ========== COMPARE (CRITICAL) ==========
    for (size_t i = 0; i < TEXTS.size(); i++) {
        EXPECT_EQ(results_re2[i], results_wrapper[i])
            << "Result " << i << " must match";
    }
    // ========================================

    releasePattern(p);
}

//=============================================================================
// N-VARIANT MATCHING TESTS (Phase 1.2.5a - Unlimited Captures)
//=============================================================================

// fullMatchN - 0 captures
TEST_F(Libre2APITest, FullMatchN_ZeroCaptures) {
    initCache();

    // ========== TEST DATA (defined ONCE) ==========
    const std::string PATTERN = "\\d+";
    const std::string TEXT = "123";
    // ==============================================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    bool result_re2 = RE2::FullMatch(TEXT, re2_pattern);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    bool result_wrapper = fullMatchN(p, TEXT, nullptr, 0);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    // =============================

    releasePattern(p);
}

// fullMatchN - 1 capture
TEST_F(Libre2APITest, FullMatchN_OneCapture) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\d+)";
    const std::string TEXT = "123";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::string cap1_re2;
    const RE2::Arg arg1_re2(&cap1_re2);
    const RE2::Arg* args_re2[] = {&arg1_re2};
    bool result_re2 = RE2::FullMatchN(TEXT, re2_pattern, args_re2, 1);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string cap1_wrapper;
    const Arg arg1_wrapper(&cap1_wrapper);
    const Arg* args_wrapper[] = {&arg1_wrapper};
    bool result_wrapper = fullMatchN(p, TEXT, args_wrapper, 1);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_EQ(cap1_re2, cap1_wrapper);
    // =============================

    releasePattern(p);
}

// fullMatchN - 3 captures
TEST_F(Libre2APITest, FullMatchN_ThreeCaptures) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+):(\\d+):(\\w+)";
    const std::string TEXT = "foo:123:bar";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::string cap1_re2, cap2_re2, cap3_re2;
    const RE2::Arg arg1_re2(&cap1_re2);
    const RE2::Arg arg2_re2(&cap2_re2);
    const RE2::Arg arg3_re2(&cap3_re2);
    const RE2::Arg* args_re2[] = {&arg1_re2, &arg2_re2, &arg3_re2};
    bool result_re2 = RE2::FullMatchN(TEXT, re2_pattern, args_re2, 3);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string cap1_wrapper, cap2_wrapper, cap3_wrapper;
    const Arg arg1_wrapper(&cap1_wrapper), arg2_wrapper(&cap2_wrapper), arg3_wrapper(&cap3_wrapper);
    const Arg* args_wrapper[] = {&arg1_wrapper, &arg2_wrapper, &arg3_wrapper};
    bool result_wrapper = fullMatchN(p, TEXT, args_wrapper, 3);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_EQ(cap1_re2, cap1_wrapper) << "Capture 1";
    EXPECT_EQ(cap2_re2, cap2_wrapper) << "Capture 2";
    EXPECT_EQ(cap3_re2, cap3_wrapper) << "Capture 3";
    // =============================

    releasePattern(p);
}

// fullMatchN - 5 captures (stress test)
TEST_F(Libre2APITest, FullMatchN_FiveCaptures) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+):(\\d+)";
    const std::string TEXT = "192.168.1.1:8080";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::string c1_re2, c2_re2, c3_re2, c4_re2, c5_re2;
    const RE2::Arg a1_re2(&c1_re2), a2_re2(&c2_re2), a3_re2(&c3_re2);
    const RE2::Arg a4_re2(&c4_re2), a5_re2(&c5_re2);
    const RE2::Arg* args_re2[] = {&a1_re2, &a2_re2, &a3_re2, &a4_re2, &a5_re2};
    bool result_re2 = RE2::FullMatchN(TEXT, re2_pattern, args_re2, 5);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string c1_w, c2_w, c3_w, c4_w, c5_w;
    const Arg a1_w(&c1_w), a2_w(&c2_w), a3_w(&c3_w), a4_w(&c4_w), a5_w(&c5_w);
    const Arg* args_wrapper[] = {&a1_w, &a2_w, &a3_w, &a4_w, &a5_w};
    bool result_wrapper = fullMatchN(p, TEXT, args_wrapper, 5);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_EQ(c1_re2, c1_w) << "Capture 1";
    EXPECT_EQ(c2_re2, c2_w) << "Capture 2";
    EXPECT_EQ(c3_re2, c3_w) << "Capture 3";
    EXPECT_EQ(c4_re2, c4_w) << "Capture 4";
    EXPECT_EQ(c5_re2, c5_w) << "Capture 5";
    // =============================

    releasePattern(p);
}

// fullMatchN - no match
TEST_F(Libre2APITest, FullMatchN_NoMatch) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\d+)";
    const std::string TEXT = "abc";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::string cap1_re2;
    const RE2::Arg arg1_re2(&cap1_re2);
    const RE2::Arg* args_re2[] = {&arg1_re2};
    bool result_re2 = RE2::FullMatchN(TEXT, re2_pattern, args_re2, 1);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string cap1_wrapper;
    const Arg arg1_wrapper(&cap1_wrapper);
    const Arg* args_wrapper[] = {&arg1_wrapper};
    bool result_wrapper = fullMatchN(p, TEXT, args_wrapper, 1);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_FALSE(result_re2);  // Both should be false
    // =============================

    releasePattern(p);
}

// partialMatchN - 2 captures
TEST_F(Libre2APITest, PartialMatchN_TwoCaptures) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\d+):(\\w+)";
    const std::string TEXT = "foo 123:bar baz";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::string cap1_re2, cap2_re2;
    const RE2::Arg arg1_re2(&cap1_re2), arg2_re2(&cap2_re2);
    const RE2::Arg* args_re2[] = {&arg1_re2, &arg2_re2};
    bool result_re2 = RE2::PartialMatchN(TEXT, re2_pattern, args_re2, 2);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string cap1_wrapper, cap2_wrapper;
    const Arg arg1_wrapper(&cap1_wrapper), arg2_wrapper(&cap2_wrapper);
    const Arg* args_wrapper[] = {&arg1_wrapper, &arg2_wrapper};
    bool result_wrapper = partialMatchN(p, TEXT, args_wrapper, 2);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_EQ(cap1_re2, cap1_wrapper) << "Capture 1";
    EXPECT_EQ(cap2_re2, cap2_wrapper) << "Capture 2";
    // =============================

    releasePattern(p);
}

// consumeN - 2 captures
TEST_F(Libre2APITest, ConsumeN_TwoCaptures) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+)=(\\d+)";
    const std::string INPUT_TEXT = "foo=123 bar=456";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::string_view input_re2(INPUT_TEXT);
    std::string cap1_re2, cap2_re2;
    const RE2::Arg arg1_re2(&cap1_re2), arg2_re2(&cap2_re2);
    const RE2::Arg* args_re2[] = {&arg1_re2, &arg2_re2};
    bool result_re2 = RE2::ConsumeN(&input_re2, re2_pattern, args_re2, 2);
    std::string remaining_re2(input_re2.data(), input_re2.size());
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    const char* input_wrapper = INPUT_TEXT.data();
    int len_wrapper = INPUT_TEXT.size();
    std::string cap1_wrapper, cap2_wrapper;
    const Arg arg1_wrapper(&cap1_wrapper), arg2_wrapper(&cap2_wrapper);
    const Arg* args_wrapper[] = {&arg1_wrapper, &arg2_wrapper};
    bool result_wrapper = consumeN(p, &input_wrapper, &len_wrapper, args_wrapper, 2);
    std::string remaining_wrapper(input_wrapper, len_wrapper);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_EQ(cap1_re2, cap1_wrapper) << "Capture 1";
    EXPECT_EQ(cap2_re2, cap2_wrapper) << "Capture 2";
    EXPECT_EQ(remaining_re2, remaining_wrapper) << "Remaining text";
    // =============================

    releasePattern(p);
}

// findAndConsumeN - 2 captures
TEST_F(Libre2APITest, FindAndConsumeN_TwoCaptures) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+):(\\d+)";
    const std::string INPUT_TEXT = "ignore foo:123 rest";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::string_view input_re2(INPUT_TEXT);
    std::string cap1_re2, cap2_re2;
    const RE2::Arg arg1_re2(&cap1_re2), arg2_re2(&cap2_re2);
    const RE2::Arg* args_re2[] = {&arg1_re2, &arg2_re2};
    bool result_re2 = RE2::FindAndConsumeN(&input_re2, re2_pattern, args_re2, 2);
    std::string remaining_re2(input_re2.data(), input_re2.size());
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    const char* input_wrapper = INPUT_TEXT.data();
    int len_wrapper = INPUT_TEXT.size();
    std::string cap1_wrapper, cap2_wrapper;
    const Arg arg1_wrapper(&cap1_wrapper), arg2_wrapper(&cap2_wrapper);
    const Arg* args_wrapper[] = {&arg1_wrapper, &arg2_wrapper};
    bool result_wrapper = findAndConsumeN(p, &input_wrapper, &len_wrapper, args_wrapper, 2);
    std::string remaining_wrapper(input_wrapper, len_wrapper);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_EQ(cap1_re2, cap1_wrapper) << "Capture 1";
    EXPECT_EQ(cap2_re2, cap2_wrapper) << "Capture 2";
    EXPECT_EQ(remaining_re2, remaining_wrapper) << "Remaining text";
    // =============================

    releasePattern(p);
}

// fullMatchNDirect - 2 captures (zero-copy)
TEST_F(Libre2APITest, FullMatchNDirect_TwoCaptures) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+):(\\d+)";
    const std::string TEXT = "foo:123";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::string cap1_re2, cap2_re2;
    const RE2::Arg arg1_re2(&cap1_re2), arg2_re2(&cap2_re2);
    const RE2::Arg* args_re2[] = {&arg1_re2, &arg2_re2};
    bool result_re2 = RE2::FullMatchN(TEXT, re2_pattern, args_re2, 2);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string cap1_wrapper, cap2_wrapper;
    const Arg arg1_wrapper(&cap1_wrapper), arg2_wrapper(&cap2_wrapper);
    const Arg* args_wrapper[] = {&arg1_wrapper, &arg2_wrapper};
    int64_t address = reinterpret_cast<int64_t>(TEXT.data());
    bool result_wrapper = fullMatchNDirect(p, address, TEXT.size(), args_wrapper, 2);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_EQ(cap1_re2, cap1_wrapper) << "Capture 1";
    EXPECT_EQ(cap2_re2, cap2_wrapper) << "Capture 2";
    // =============================

    releasePattern(p);
}

// fullMatchNBulk - 3 texts, 2 captures each
TEST_F(Libre2APITest, FullMatchNBulk_MultipleTexts) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+):(\\d+)";
    const std::vector<std::string> TEXTS = {"foo:1", "bar:2", "baz:3"};
    // ===============================

    // ========== EXECUTE RE2 (loop) ==========
    RE2 re2_pattern(PATTERN);
    bool results_re2[3];
    std::string caps_re2[3][2];  // 3 texts, 2 captures each

    for (size_t i = 0; i < TEXTS.size(); i++) {
        const RE2::Arg arg1_re2(&caps_re2[i][0]);
        const RE2::Arg arg2_re2(&caps_re2[i][1]);
        const RE2::Arg* args_re2[] = {&arg1_re2, &arg2_re2};
        results_re2[i] = RE2::FullMatchN(TEXTS[i], re2_pattern, args_re2, 2);
    }
    // ========================================

    // ========== EXECUTE WRAPPER (bulk) ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);

    const char* texts[3] = {TEXTS[0].data(), TEXTS[1].data(), TEXTS[2].data()};
    int lens[3] = {(int)TEXTS[0].size(), (int)TEXTS[1].size(), (int)TEXTS[2].size()};

    // Each text gets its own capture storage and Arg array
    std::string caps_wrapper[3][2];
    const Arg arg0_0(&caps_wrapper[0][0]), arg0_1(&caps_wrapper[0][1]);
    const Arg arg1_0(&caps_wrapper[1][0]), arg1_1(&caps_wrapper[1][1]);
    const Arg arg2_0(&caps_wrapper[2][0]), arg2_1(&caps_wrapper[2][1]);
    const Arg* args0[] = {&arg0_0, &arg0_1};
    const Arg* args1[] = {&arg1_0, &arg1_1};
    const Arg* args2[] = {&arg2_0, &arg2_1};
    const Arg** args_array[] = {args0, args1, args2};

    bool results_wrapper[3];
    fullMatchNBulk(p, texts, lens, 3, args_array, 2, results_wrapper);
    // ============================================

    // ========== COMPARE ==========
    for (size_t i = 0; i < 3; i++) {
        EXPECT_EQ(results_re2[i], results_wrapper[i]) << "Result " << i;
        if (results_re2[i]) {
            EXPECT_EQ(caps_re2[i][0], caps_wrapper[i][0]) << "Text " << i << " cap 1";
            EXPECT_EQ(caps_re2[i][1], caps_wrapper[i][1]) << "Text " << i << " cap 2";
        }
    }
    // =============================

    releasePattern(p);
}

// fullMatchNDirectBulk - combined (zero-copy + bulk + captures)
TEST_F(Libre2APITest, FullMatchNDirectBulk_Combined) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\d+)";
    const std::vector<std::string> TEXTS = {"123", "456", "789"};
    // ===============================

    // ========== EXECUTE RE2 (loop) ==========
    RE2 re2_pattern(PATTERN);
    bool results_re2[3];
    std::string caps_re2[3];

    for (size_t i = 0; i < TEXTS.size(); i++) {
        const RE2::Arg arg_re2(&caps_re2[i]);
        const RE2::Arg* args_re2[] = {&arg_re2};
        results_re2[i] = RE2::FullMatchN(TEXTS[i], re2_pattern, args_re2, 1);
    }
    // ========================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);

    int64_t addresses[3] = {
        reinterpret_cast<int64_t>(TEXTS[0].data()),
        reinterpret_cast<int64_t>(TEXTS[1].data()),
        reinterpret_cast<int64_t>(TEXTS[2].data())
    };
    int lens[3] = {(int)TEXTS[0].size(), (int)TEXTS[1].size(), (int)TEXTS[2].size()};

    std::string caps_wrapper[3];
    const Arg arg0(&caps_wrapper[0]);
    const Arg arg1(&caps_wrapper[1]);
    const Arg arg2(&caps_wrapper[2]);
    const Arg* args0[] = {&arg0};
    const Arg* args1[] = {&arg1};
    const Arg* args2[] = {&arg2};
    const Arg** args_array[] = {args0, args1, args2};

    bool results_wrapper[3];
    fullMatchNDirectBulk(p, addresses, lens, 3, args_array, 1, results_wrapper);
    // =====================================

    // ========== COMPARE ==========
    for (size_t i = 0; i < 3; i++) {
        EXPECT_EQ(results_re2[i], results_wrapper[i]) << "Result " << i;
        if (results_re2[i]) {
            EXPECT_EQ(caps_re2[i], caps_wrapper[i]) << "Text " << i << " capture";
        }
    }
    // =============================

    releasePattern(p);
}


//=============================================================================
// PATTERN ANALYSIS TESTS (Phase 1.2.5b)
//=============================================================================

// getNumberOfCapturingGroups - basic pattern
TEST_F(Libre2APITest, GetNumberOfCapturingGroups_Basic) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+):(\\d+):(\\w+)";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    int result_re2 = re2_pattern.NumberOfCapturingGroups();
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    int result_wrapper = getNumberOfCapturingGroups(p);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_EQ(3, result_wrapper);  // Pattern has 3 groups
    // =============================

    releasePattern(p);
}

// getNumberOfCapturingGroups - no groups
TEST_F(Libre2APITest, GetNumberOfCapturingGroups_NoGroups) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "\\d+";  // No capturing groups
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    int result_re2 = re2_pattern.NumberOfCapturingGroups();
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    int result_wrapper = getNumberOfCapturingGroups(p);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_EQ(0, result_wrapper);
    // =============================

    releasePattern(p);
}

// getProgramSize - complexity metric
TEST_F(Libre2APITest, GetProgramSize_ComplexPattern) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+):(\\d+)";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    int result_re2 = re2_pattern.ProgramSize();
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    int result_wrapper = getProgramSize(p);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_GT(result_wrapper, 0);  // Should have non-zero size
    // =============================

    releasePattern(p);
}

// getReverseProgramSize
TEST_F(Libre2APITest, GetReverseProgramSize_ComplexPattern) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+):(\\d+)";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    int result_re2 = re2_pattern.ReverseProgramSize();
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    int result_wrapper = getReverseProgramSize(p);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_GT(result_wrapper, 0);  // Should have non-zero size
    // =============================

    releasePattern(p);
}

// getNamedCapturingGroupsJSON - named groups
TEST_F(Libre2APITest, GetNamedCapturingGroups_WithNames) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(?P<word>\\w+):(?P<number>\\d+)";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    const auto& named_re2 = re2_pattern.NamedCapturingGroups();
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string json_wrapper = getNamedCapturingGroupsJSON(p);
    // =====================================

    // ========== COMPARE ==========
    // Wrapper returns JSON, RE2 returns map - verify content
    EXPECT_TRUE(json_wrapper.find("\"word\"") != std::string::npos);
    EXPECT_TRUE(json_wrapper.find("\"number\"") != std::string::npos);

    // Verify indices match
    for (const auto& [name, index] : named_re2) {
        std::string expected = "\"" + name + "\":" + std::to_string(index);
        EXPECT_TRUE(json_wrapper.find(expected) != std::string::npos)
            << "Missing or incorrect: " << name << " -> " << index;
    }
    // =============================

    releasePattern(p);
}


//=============================================================================
// STATUS/VALIDATION TESTS (Phase 1.2.5c)
//=============================================================================

// ok() - valid pattern
TEST_F(Libre2APITest, Ok_ValidPattern) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+):(\\d+)";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    bool result_re2 = re2_pattern.ok();
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    bool result_wrapper = ok(p);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);  // Should be valid
    // =============================

    releasePattern(p);
}

// ok() - invalid pattern
TEST_F(Libre2APITest, Ok_InvalidPattern) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(?P<incomplete";  // Missing )
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    bool result_re2 = re2_pattern.ok();
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    bool result_wrapper = ok(p);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_FALSE(result_wrapper);  // Both should be false
    // =============================

    if (p) releasePattern(p);
}

// getPattern() - retrieve original pattern
TEST_F(Libre2APITest, GetPattern_Original) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+):(\\d+)";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    const std::string& result_re2 = re2_pattern.pattern();
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string result_wrapper = getPattern(p);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_EQ(PATTERN, result_wrapper);
    // =============================

    releasePattern(p);
}

// getError() - valid pattern (no error)
TEST_F(Libre2APITest, GetError_NoError) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "\\d+";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    const std::string& result_re2 = re2_pattern.error();
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string result_wrapper = getError(p);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper.empty());  // No error
    // =============================

    releasePattern(p);
}

// getError() - invalid pattern (wrapper design difference)
TEST_F(Libre2APITest, GetError_WithError) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(?P<name>incomplete";  // Missing )
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    const std::string& result_re2 = re2_pattern.error();
    // =================================

    // ========== EXECUTE WRAPPER ==========
    // NOTE: compilePattern returns nullptr for invalid patterns (design difference)
    // The error message is populated in the error_out parameter
    std::string error_out;
    RE2Pattern* p = compilePattern(PATTERN, true, error_out);
    EXPECT_EQ(p, nullptr) << "Wrapper returns nullptr for invalid pattern";
    // =====================================

    // ========== COMPARE ==========
    // Compare error messages (compilePattern error_out vs RE2::error())
    EXPECT_EQ(result_re2, error_out) << "Error messages should match";
    EXPECT_FALSE(error_out.empty());  // Should have error message
    // =============================

    // No release needed - p is nullptr
}

// getErrorCode() - valid pattern
TEST_F(Libre2APITest, GetErrorCode_NoError) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "\\w+";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    int result_re2 = static_cast<int>(re2_pattern.error_code());
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    int result_wrapper = getErrorCode(p);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_EQ(0, result_wrapper);  // RE2::NoError
    // =============================

    releasePattern(p);
}

// getErrorCode() - invalid pattern (wrapper design difference)
TEST_F(Libre2APITest, GetErrorCode_WithError) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(?P<bad";  // Missing )
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    int result_re2 = static_cast<int>(re2_pattern.error_code());
    // =================================

    // ========== EXECUTE WRAPPER ==========
    // Wrapper returns nullptr for invalid patterns
    std::string error_out;
    RE2Pattern* p = compilePattern(PATTERN, true, error_out);
    EXPECT_EQ(p, nullptr) << "Wrapper returns nullptr for invalid pattern";
    EXPECT_FALSE(error_out.empty()) << "Error should be populated";

    // getErrorCode(nullptr) returns -1 (wrapper convention)
    int result_wrapper = getErrorCode(p);
    // =====================================

    // ========== COMPARE (accounting for design difference) ==========
    // RE2 returns specific error code (e.g., ErrorMissingParen = 6)
    // Wrapper returns -1 for nullptr (different design)
    EXPECT_NE(0, result_re2) << "RE2 should have error code";
    EXPECT_EQ(-1, result_wrapper) << "Wrapper returns -1 for nullptr";
    // ================================================================

    // No release needed - p is nullptr
}

// getErrorArg() - offending portion (wrapper design difference)
TEST_F(Libre2APITest, GetErrorArg_WithError) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(?P<name>bad";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    const std::string& result_re2 = re2_pattern.error_arg();
    // =================================

    // ========== EXECUTE WRAPPER ==========
    // Wrapper returns nullptr for invalid patterns (design difference)
    std::string error_out;
    RE2Pattern* p = compilePattern(PATTERN, true, error_out);
    EXPECT_EQ(p, nullptr) << "Wrapper returns nullptr for invalid pattern";

    // getErrorArg(nullptr) returns empty string
    std::string result_wrapper = getErrorArg(p);
    // =====================================

    // ========== COMPARE (accounting for design difference) ==========
    // RE2 has error_arg in pattern object
    // Wrapper returns "" for nullptr (cannot access error_arg)
    EXPECT_FALSE(result_re2.empty()) << "RE2 should have error_arg";
    EXPECT_TRUE(result_wrapper.empty()) << "Wrapper returns empty for nullptr";
    // ================================================================

    // No release needed - p is nullptr
}

//=============================================================================
// REWRITE VALIDATION TESTS (Phase 1.2.5d)
//=============================================================================

// checkRewriteString - valid rewrite
TEST_F(Libre2APITest, CheckRewriteString_Valid) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+):(\\d+)";
    const std::string REWRITE = "\\1=\\2";  // Valid: uses groups 1 and 2
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::string error_re2;
    bool result_re2 = re2_pattern.CheckRewriteString(REWRITE, &error_re2);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string compile_error;
    RE2Pattern* p = compilePattern(PATTERN, true, compile_error);
    ASSERT_NE(p, nullptr);
    std::string error_wrapper;
    bool result_wrapper = checkRewriteString(p, REWRITE, &error_wrapper);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);  // Should be valid
    EXPECT_EQ(error_re2, error_wrapper);  // Both should be empty
    // =============================

    releasePattern(p);
}

// checkRewriteString - invalid rewrite (too many groups)
TEST_F(Libre2APITest, CheckRewriteString_TooManyGroups) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+)";  // Only 1 group
    const std::string REWRITE = "\\1 \\2";  // Invalid: references \2 but pattern has only 1 group
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::string error_re2;
    bool result_re2 = re2_pattern.CheckRewriteString(REWRITE, &error_re2);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string compile_error;
    RE2Pattern* p = compilePattern(PATTERN, true, compile_error);
    ASSERT_NE(p, nullptr);
    std::string error_wrapper;
    bool result_wrapper = checkRewriteString(p, REWRITE, &error_wrapper);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_FALSE(result_wrapper);  // Should be invalid
    EXPECT_EQ(error_re2, error_wrapper);  // Both should have same error
    EXPECT_FALSE(error_wrapper.empty());  // Should have error message
    // =============================

    releasePattern(p);
}

// maxSubmatch - static method (no pattern needed)
TEST_F(Libre2APITest, MaxSubmatch_MultipleGroups) {
    // ========== TEST DATA ==========
    const std::string REWRITE = "foo \\2,\\1,\\3";  // References up to \3
    // ===============================

    // ========== EXECUTE RE2 ==========
    int result_re2 = RE2::MaxSubmatch(REWRITE);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    int result_wrapper = maxSubmatch(REWRITE);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_EQ(3, result_wrapper);  // Highest reference is \3
    // =============================
}

// maxSubmatch - no captures
TEST_F(Libre2APITest, MaxSubmatch_NoCaptures) {
    // ========== TEST DATA ==========
    const std::string REWRITE = "no captures here";
    // ===============================

    // ========== EXECUTE RE2 ==========
    int result_re2 = RE2::MaxSubmatch(REWRITE);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    int result_wrapper = maxSubmatch(REWRITE);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_EQ(0, result_wrapper);  // No capture references
    // =============================
}

// rewrite - apply template manually
TEST_F(Libre2APITest, Rewrite_ManualSubstitution) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+):(\\d+)";
    const std::string REWRITE_TEMPLATE = "name=\\1, value=\\2";
    // NOTE: vec[0] = \0 (entire match), vec[1] = \1 (group 1), vec[2] = \2 (group 2)
    const std::string ENTIRE_MATCH = "foo:123";  // \0
    const std::string CAP1 = "foo";               // \1
    const std::string CAP2 = "123";               // \2
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    absl::string_view vec_re2[] = {ENTIRE_MATCH, CAP1, CAP2};  // [0]=\0, [1]=\1, [2]=\2
    std::string out_re2;
    bool result_re2 = re2_pattern.Rewrite(&out_re2, REWRITE_TEMPLATE, vec_re2, 3);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string compile_error;
    RE2Pattern* p = compilePattern(PATTERN, true, compile_error);
    ASSERT_NE(p, nullptr);
    const std::string* caps[] = {&ENTIRE_MATCH, &CAP1, &CAP2};  // [0]=\0, [1]=\1, [2]=\2
    std::string out_wrapper;
    bool result_wrapper = rewrite(p, &out_wrapper, REWRITE_TEMPLATE, caps, 3);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);  // Should succeed
    EXPECT_EQ(out_re2, out_wrapper);  // Output should match
    EXPECT_EQ("name=foo, value=123", out_wrapper);
    // =============================

    releasePattern(p);
}

// rewrite - with \0 (entire match)
TEST_F(Libre2APITest, Rewrite_WithEntireMatch) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+)";
    const std::string REWRITE_TEMPLATE = "matched: \\0, group: \\1";
    const std::string ENTIRE_MATCH = "hello";  // \0
    const std::string CAP1 = "hello";           // \1
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    absl::string_view vec_re2[] = {ENTIRE_MATCH, CAP1};  // vec[0] = \0, vec[1] = \1
    std::string out_re2;
    bool result_re2 = re2_pattern.Rewrite(&out_re2, REWRITE_TEMPLATE, vec_re2, 2);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string compile_error;
    RE2Pattern* p = compilePattern(PATTERN, true, compile_error);
    ASSERT_NE(p, nullptr);
    const std::string* caps[] = {&ENTIRE_MATCH, &CAP1};
    std::string out_wrapper;
    bool result_wrapper = rewrite(p, &out_wrapper, REWRITE_TEMPLATE, caps, 2);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);
    EXPECT_EQ(out_re2, out_wrapper);
    EXPECT_EQ("matched: hello, group: hello", out_wrapper);
    // =============================

    releasePattern(p);
}

//=============================================================================
// GENERIC MATCH TESTS (Phase 1.2.5e - Low-Level Control)
//=============================================================================

// match() - UNANCHORED (find anywhere)
TEST_F(Libre2APITest, Match_Unanchored) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\d+)";
    const std::string TEXT = "foo 123 bar";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    absl::string_view submatch_re2[2];  // [0]=entire, [1]=group1
    bool result_re2 = re2_pattern.Match(TEXT, 0, TEXT.size(),
                                        RE2::UNANCHORED, submatch_re2, 2);
    std::string entire_re2(submatch_re2[0].data(), submatch_re2[0].size());
    std::string cap1_re2(submatch_re2[1].data(), submatch_re2[1].size());
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string entire_wrapper, cap1_wrapper;
    std::string* subs[] = {&entire_wrapper, &cap1_wrapper};
    bool result_wrapper = match(p, TEXT, 0, TEXT.size(),
                                UNANCHORED, subs, 2);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);
    EXPECT_EQ(entire_re2, entire_wrapper);
    EXPECT_EQ(cap1_re2, cap1_wrapper);
    EXPECT_EQ("123", cap1_wrapper);
    // =============================

    releasePattern(p);
}

// match() - ANCHOR_START
TEST_F(Libre2APITest, Match_AnchorStart) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+)";
    const std::string TEXT = "hello world";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    absl::string_view submatch_re2[2];
    bool result_re2 = re2_pattern.Match(TEXT, 0, TEXT.size(),
                                        RE2::ANCHOR_START, submatch_re2, 2);
    std::string cap1_re2 = (submatch_re2[1].data() != nullptr) ?
        std::string(submatch_re2[1]) : "";
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string entire_wrapper, cap1_wrapper;
    std::string* subs[] = {&entire_wrapper, &cap1_wrapper};
    bool result_wrapper = match(p, TEXT, 0, TEXT.size(),
                                ANCHOR_START, subs, 2);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);
    EXPECT_EQ(cap1_re2, cap1_wrapper);
    EXPECT_EQ("hello", cap1_wrapper);
    // =============================

    releasePattern(p);
}

// match() - ANCHOR_BOTH (like FullMatch)
TEST_F(Libre2APITest, Match_AnchorBoth) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "\\w+";
    const std::string TEXT = "hello";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    absl::string_view submatch_re2[1];
    bool result_re2 = re2_pattern.Match(TEXT, 0, TEXT.size(),
                                        RE2::ANCHOR_BOTH, submatch_re2, 1);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string entire_wrapper;
    std::string* subs[] = {&entire_wrapper};
    bool result_wrapper = match(p, TEXT, 0, TEXT.size(),
                                ANCHOR_BOTH, subs, 1);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);
    EXPECT_EQ("hello", entire_wrapper);
    // =============================

    releasePattern(p);
}

// match() - substring (startpos, endpos)
TEST_F(Libre2APITest, Match_Substring) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\d+)";
    const std::string TEXT = "abc 123 def 456 ghi";
    size_t START = 12;  // Start at "456"
    size_t END = 15;    // End after "456"
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    absl::string_view submatch_re2[2];
    bool result_re2 = re2_pattern.Match(TEXT, START, END,
                                        RE2::UNANCHORED, submatch_re2, 2);
    std::string cap1_re2 = (submatch_re2[1].data() != nullptr) ?
        std::string(submatch_re2[1]) : "";
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string entire_wrapper, cap1_wrapper;
    std::string* subs[] = {&entire_wrapper, &cap1_wrapper};
    bool result_wrapper = match(p, TEXT, START, END,
                                UNANCHORED, subs, 2);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);
    EXPECT_EQ(cap1_re2, cap1_wrapper);
    EXPECT_EQ("456", cap1_wrapper);
    // =============================

    releasePattern(p);
}

// matchDirect() - zero-copy with anchor
TEST_F(Libre2APITest, MatchDirect_WithCaptures) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+):(\\d+)";
    const std::string TEXT = "foo:123";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    absl::string_view submatch_re2[3];
    bool result_re2 = re2_pattern.Match(TEXT, 0, TEXT.size(),
                                        RE2::ANCHOR_BOTH, submatch_re2, 3);
    std::string cap1_re2 = std::string(submatch_re2[1]);
    std::string cap2_re2 = std::string(submatch_re2[2]);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string entire_wrapper, cap1_wrapper, cap2_wrapper;
    std::string* subs[] = {&entire_wrapper, &cap1_wrapper, &cap2_wrapper};
    int64_t address = reinterpret_cast<int64_t>(TEXT.data());
    bool result_wrapper = matchDirect(p, address, TEXT.size(),
                                      0, TEXT.size(), ANCHOR_BOTH, subs, 3);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);
    EXPECT_EQ(cap1_re2, cap1_wrapper);
    EXPECT_EQ(cap2_re2, cap2_wrapper);
    // =============================

    releasePattern(p);
}

// matchBulk() - multiple texts with anchor
TEST_F(Libre2APITest, MatchBulk_MultipleTexts) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\d+)";
    const std::vector<std::string> TEXTS = {"123", "abc", "456"};
    // ===============================

    // ========== EXECUTE RE2 (loop) ==========
    RE2 re2_pattern(PATTERN);
    bool results_re2[3];
    std::string caps_re2[3][2];  // [text][submatch]

    for (size_t i = 0; i < TEXTS.size(); i++) {
        absl::string_view submatch_re2[2];
        results_re2[i] = re2_pattern.Match(TEXTS[i], 0, TEXTS[i].size(),
                                           RE2::ANCHOR_BOTH, submatch_re2, 2);
        if (results_re2[i]) {
            caps_re2[i][0] = submatch_re2[0].data() ? std::string(submatch_re2[0]) : "";
            caps_re2[i][1] = submatch_re2[1].data() ? std::string(submatch_re2[1]) : "";
        }
    }
    // ========================================

    // ========== EXECUTE WRAPPER (bulk) ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);

    const char* texts[3] = {TEXTS[0].data(), TEXTS[1].data(), TEXTS[2].data()};
    int lens[3] = {(int)TEXTS[0].size(), (int)TEXTS[1].size(), (int)TEXTS[2].size()};

    std::string caps_wrapper[3][2];
    std::string* caps0[] = {&caps_wrapper[0][0], &caps_wrapper[0][1]};
    std::string* caps1[] = {&caps_wrapper[1][0], &caps_wrapper[1][1]};
    std::string* caps2[] = {&caps_wrapper[2][0], &caps_wrapper[2][1]};
    std::string** caps_array[] = {caps0, caps1, caps2};

    bool results_wrapper[3];
    // Note: Using largest text size as endpos (works for all since ANCHOR_BOTH checks full text)
    size_t max_len = std::max({TEXTS[0].size(), TEXTS[1].size(), TEXTS[2].size()});
    matchBulk(p, texts, lens, 3, 0, max_len, ANCHOR_BOTH, caps_array, 2, results_wrapper);
    // ============================================

    // ========== COMPARE ==========
    for (size_t i = 0; i < 3; i++) {
        EXPECT_EQ(results_re2[i], results_wrapper[i]) << "Result " << i;
        if (results_re2[i]) {
            EXPECT_EQ(caps_re2[i][0], caps_wrapper[i][0]) << "Text " << i << " entire";
            EXPECT_EQ(caps_re2[i][1], caps_wrapper[i][1]) << "Text " << i << " cap1";
        }
    }
    // =============================

    releasePattern(p);
}

//=============================================================================
// ADVANCED ANALYSIS TESTS (Phase 1.2.5f)
//=============================================================================

// possibleMatchRange - simple digit pattern
TEST_F(Libre2APITest, PossibleMatchRange_Digits) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "[0-9]{3}";  // 3 digits
    const int MAXLEN = 10;
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::string min_re2, max_re2;
    bool result_re2 = re2_pattern.PossibleMatchRange(&min_re2, &max_re2, MAXLEN);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string min_wrapper, max_wrapper;
    bool result_wrapper = possibleMatchRange(p, &min_wrapper, &max_wrapper, MAXLEN);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);
    EXPECT_EQ(min_re2, min_wrapper);
    EXPECT_EQ(max_re2, max_wrapper);
    // Range should be 000 to 999
    EXPECT_EQ("000", min_wrapper);
    EXPECT_EQ("999", max_wrapper);
    // =============================

    releasePattern(p);
}

// possibleMatchRange - word pattern
TEST_F(Libre2APITest, PossibleMatchRange_Word) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "\\w+";  // One or more word chars
    const int MAXLEN = 5;
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::string min_re2, max_re2;
    bool result_re2 = re2_pattern.PossibleMatchRange(&min_re2, &max_re2, MAXLEN);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string min_wrapper, max_wrapper;
    bool result_wrapper = possibleMatchRange(p, &min_wrapper, &max_wrapper, MAXLEN);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_EQ(min_re2, min_wrapper);
    EXPECT_EQ(max_re2, max_wrapper);
    // =============================

    releasePattern(p);
}

// possibleMatchRange - complex pattern
TEST_F(Libre2APITest, PossibleMatchRange_Complex) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "a[0-9]+b";
    const int MAXLEN = 20;
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::string min_re2, max_re2;
    bool result_re2 = re2_pattern.PossibleMatchRange(&min_re2, &max_re2, MAXLEN);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string min_wrapper, max_wrapper;
    bool result_wrapper = possibleMatchRange(p, &min_wrapper, &max_wrapper, MAXLEN);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_EQ(min_re2, min_wrapper);
    EXPECT_EQ(max_re2, max_wrapper);
    // Should start with 'a' and end with 'b'
    EXPECT_FALSE(min_wrapper.empty());
    EXPECT_FALSE(max_wrapper.empty());
    EXPECT_EQ('a', min_wrapper[0]);
    EXPECT_EQ('a', max_wrapper[0]);
    // =============================

    releasePattern(p);
}

//=============================================================================
// TYPED CAPTURE TESTS (Phase 1.2.5h - RE2::Arg Support)
// Ported from re2/re2/testing/re2_arg_test.cc
//=============================================================================

// fullMatchN - int capture
TEST_F(Libre2APITest, FullMatchN_IntCapture) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\d+)";
    const std::string TEXT = "12345";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    int value_re2;
    const RE2::Arg arg_re2(&value_re2);
    const RE2::Arg* args_re2[] = {&arg_re2};
    bool result_re2 = RE2::FullMatchN(TEXT, re2_pattern, args_re2, 1);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    int value_wrapper;
    const Arg arg_wrapper(&value_wrapper);
    const Arg* args_wrapper[] = {&arg_wrapper};
    bool result_wrapper = fullMatchN(p, TEXT, args_wrapper, 1);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);
    EXPECT_EQ(value_re2, value_wrapper);
    EXPECT_EQ(12345, value_wrapper);
    // =============================

    releasePattern(p);
}

// fullMatchN - mixed types (string + int + float)
TEST_F(Libre2APITest, FullMatchN_MixedTypes) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+):(\\d+):([0-9.]+)";
    const std::string TEXT = "foo:123:3.14";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::string word_re2;
    int number_re2;
    double value_re2;
    const RE2::Arg arg1_re2(&word_re2);
    const RE2::Arg arg2_re2(&number_re2);
    const RE2::Arg arg3_re2(&value_re2);
    const RE2::Arg* args_re2[] = {&arg1_re2, &arg2_re2, &arg3_re2};
    bool result_re2 = RE2::FullMatchN(TEXT, re2_pattern, args_re2, 3);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string word_wrapper;
    int number_wrapper;
    double value_wrapper;
    const Arg arg1_wrapper(&word_wrapper);
    const Arg arg2_wrapper(&number_wrapper);
    const Arg arg3_wrapper(&value_wrapper);
    const Arg* args_wrapper[] = {&arg1_wrapper, &arg2_wrapper, &arg3_wrapper};
    bool result_wrapper = fullMatchN(p, TEXT, args_wrapper, 3);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);
    EXPECT_EQ(word_re2, word_wrapper);
    EXPECT_EQ(number_re2, number_wrapper);
    EXPECT_DOUBLE_EQ(value_re2, value_wrapper);
    EXPECT_EQ("foo", word_wrapper);
    EXPECT_EQ(123, number_wrapper);
    EXPECT_DOUBLE_EQ(3.14, value_wrapper);
    // =============================

    releasePattern(p);
}

// fullMatchN - Hex parsing
TEST_F(Libre2APITest, FullMatchN_HexParsing) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "0x([0-9A-Fa-f]+)";
    const std::string TEXT = "0xFF";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    int value_re2;
    const RE2::Arg arg_re2 = RE2::Hex(&value_re2);
    const RE2::Arg* args_re2[] = {&arg_re2};
    bool result_re2 = RE2::FullMatchN(TEXT, re2_pattern, args_re2, 1);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    int value_wrapper;
    const Arg arg_wrapper = Hex(&value_wrapper);
    const Arg* args_wrapper[] = {&arg_wrapper};
    bool result_wrapper = fullMatchN(p, TEXT, args_wrapper, 1);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);
    EXPECT_EQ(value_re2, value_wrapper);
    EXPECT_EQ(255, value_wrapper);
    // =============================

    releasePattern(p);
}

// fullMatchN - Octal parsing
TEST_F(Libre2APITest, FullMatchN_OctalParsing) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "0([0-7]+)";
    const std::string TEXT = "077";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    int value_re2;
    const RE2::Arg arg_re2 = RE2::Octal(&value_re2);
    const RE2::Arg* args_re2[] = {&arg_re2};
    bool result_re2 = RE2::FullMatchN(TEXT, re2_pattern, args_re2, 1);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    int value_wrapper;
    const Arg arg_wrapper = Octal(&value_wrapper);
    const Arg* args_wrapper[] = {&arg_wrapper};
    bool result_wrapper = fullMatchN(p, TEXT, args_wrapper, 1);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);
    EXPECT_EQ(value_re2, value_wrapper);
    EXPECT_EQ(63, value_wrapper);  // 077 octal = 63 decimal
    // =============================

    releasePattern(p);
}

// fullMatchN - CRadix (auto-detect 0x, 0, decimal)
TEST_F(Libre2APITest, FullMatchN_CRadixHex) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(.+)";
    const std::string TEXT = "0x2830";  // Should parse as hex
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    int value_re2;
    const RE2::Arg arg_re2 = RE2::CRadix(&value_re2);
    const RE2::Arg* args_re2[] = {&arg_re2};
    bool result_re2 = RE2::FullMatchN(TEXT, re2_pattern, args_re2, 1);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    int value_wrapper;
    const Arg arg_wrapper = CRadix(&value_wrapper);
    const Arg* args_wrapper[] = {&arg_wrapper};
    bool result_wrapper = fullMatchN(p, TEXT, args_wrapper, 1);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);
    EXPECT_EQ(value_re2, value_wrapper);
    EXPECT_EQ(10288, value_wrapper);  // 0x2830 hex = 10288 decimal
    // =============================

    releasePattern(p);
}

// fullMatchN - std::optional (missing capture)
TEST_F(Libre2APITest, FullMatchN_OptionalMissing) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+)(?::(\\d+))?";  // Second group optional
    const std::string TEXT = "foo";  // No number
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::string word_re2;
    std::optional<int> number_re2;
    const RE2::Arg arg1_re2(&word_re2);
    const RE2::Arg arg2_re2(&number_re2);
    const RE2::Arg* args_re2[] = {&arg1_re2, &arg2_re2};
    bool result_re2 = RE2::FullMatchN(TEXT, re2_pattern, args_re2, 2);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string word_wrapper;
    std::optional<int> number_wrapper;
    const Arg arg1_wrapper(&word_wrapper);
    const Arg arg2_wrapper(&number_wrapper);
    const Arg* args_wrapper[] = {&arg1_wrapper, &arg2_wrapper};
    bool result_wrapper = fullMatchN(p, TEXT, args_wrapper, 2);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);
    EXPECT_EQ(word_re2, word_wrapper);
    EXPECT_EQ(number_re2.has_value(), number_wrapper.has_value());
    EXPECT_FALSE(number_wrapper.has_value());  // Should be empty
    // =============================

    releasePattern(p);
}

// fullMatchN - std::optional (present capture)
TEST_F(Libre2APITest, FullMatchN_OptionalPresent) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+)(?::(\\d+))?";
    const std::string TEXT = "foo:123";  // Has number
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::string word_re2;
    std::optional<int> number_re2;
    const RE2::Arg arg1_re2(&word_re2);
    const RE2::Arg arg2_re2(&number_re2);
    const RE2::Arg* args_re2[] = {&arg1_re2, &arg2_re2};
    bool result_re2 = RE2::FullMatchN(TEXT, re2_pattern, args_re2, 2);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string word_wrapper;
    std::optional<int> number_wrapper;
    const Arg arg1_wrapper(&word_wrapper);
    const Arg arg2_wrapper(&number_wrapper);
    const Arg* args_wrapper[] = {&arg1_wrapper, &arg2_wrapper};
    bool result_wrapper = fullMatchN(p, TEXT, args_wrapper, 2);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);
    EXPECT_EQ(word_re2, word_wrapper);
    EXPECT_EQ(number_re2.has_value(), number_wrapper.has_value());
    EXPECT_TRUE(number_wrapper.has_value());
    EXPECT_EQ(*number_re2, *number_wrapper);
    EXPECT_EQ(123, *number_wrapper);
    // =============================

    releasePattern(p);
}

//=============================================================================
// PROGRAM FANOUT & ENUM TESTS (Phase 1.2.5i)
//=============================================================================

// getProgramFanoutJSON - histogram analysis
TEST_F(Libre2APITest, GetProgramFanoutJSON_Complex) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+|\\d+)*";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::vector<int> histogram_re2;
    int max_bucket_re2 = re2_pattern.ProgramFanout(&histogram_re2);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string json_wrapper = getProgramFanoutJSON(p);
    // =====================================

    // ========== COMPARE ==========
    // Verify JSON contains histogram values
    EXPECT_FALSE(json_wrapper.empty());
    EXPECT_NE("[]", json_wrapper);
    // Should be JSON array format
    EXPECT_EQ('[', json_wrapper[0]);
    EXPECT_EQ(']', json_wrapper[json_wrapper.size()-1]);
    // =============================

    releasePattern(p);
}

// getReverseProgramFanoutJSON
TEST_F(Libre2APITest, GetReverseProgramFanoutJSON_Complex) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "(\\w+):(\\d+)";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::vector<int> histogram_re2;
    int max_bucket_re2 = re2_pattern.ReverseProgramFanout(&histogram_re2);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string json_wrapper = getReverseProgramFanoutJSON(p);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_FALSE(json_wrapper.empty());
    EXPECT_EQ('[', json_wrapper[0]);
    EXPECT_EQ(']', json_wrapper[json_wrapper.size()-1]);
    // =============================

    releasePattern(p);
}

// ErrorCode enum - verify values match
TEST_F(Libre2APITest, ErrorCode_EnumValues) {
    // Verify our re-exported ErrorCode matches RE2::ErrorCode
    EXPECT_EQ(static_cast<int>(ErrorCode::NoError), 
              static_cast<int>(RE2::NoError));
    EXPECT_EQ(static_cast<int>(ErrorCode::ErrorBadEscape), 
              static_cast<int>(RE2::ErrorBadEscape));
    EXPECT_EQ(static_cast<int>(ErrorCode::ErrorMissingParen), 
              static_cast<int>(RE2::ErrorMissingParen));
}

// CannedOptions enum - verify values
TEST_F(Libre2APITest, CannedOptions_EnumValues) {
    EXPECT_EQ(static_cast<int>(CannedOptions::DefaultOptions), 
              static_cast<int>(RE2::DefaultOptions));
    EXPECT_EQ(static_cast<int>(CannedOptions::Latin1), 
              static_cast<int>(RE2::Latin1));
    EXPECT_EQ(static_cast<int>(CannedOptions::POSIX), 
              static_cast<int>(RE2::POSIX));
    EXPECT_EQ(static_cast<int>(CannedOptions::Quiet), 
              static_cast<int>(RE2::Quiet));
}

// Encoding enum - verify values
TEST_F(Libre2APITest, Encoding_EnumValues) {
    EXPECT_EQ(static_cast<int>(Encoding::EncodingUTF8), 
              static_cast<int>(RE2::Options::EncodingUTF8));
    EXPECT_EQ(static_cast<int>(Encoding::EncodingLatin1), 
              static_cast<int>(RE2::Options::EncodingLatin1));
}

//=============================================================================
// RE2::OPTIONS API TESTS (Phase 1.2.5j)
//=============================================================================

// compilePattern with Options object
TEST_F(Libre2APITest, CompilePattern_WithOptions) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "HELLO";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2::Options opts_re2;
    opts_re2.set_case_sensitive(false);
    RE2 re2_pattern(PATTERN, opts_re2);
    bool result_re2 = RE2::FullMatch("hello", re2_pattern);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    Options opts_wrapper;
    opts_wrapper.set_case_sensitive(false);
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, opts_wrapper, error);
    ASSERT_NE(p, nullptr);
    bool result_wrapper = fullMatch(p, "hello");
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);  // Case-insensitive match
    // =============================

    releasePattern(p);
}

// Options - CannedOptions (Latin1)
TEST_F(Libre2APITest, Options_CannedLatin1) {
    initCache();

    // ========== TEST DATA ==========
    const std::string PATTERN = "test";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN, RE2::Latin1);
    bool is_latin1_re2 = (re2_pattern.options().encoding() == RE2::Options::EncodingLatin1);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    Options opts_wrapper(CannedOptions::Latin1);
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, opts_wrapper, error);
    ASSERT_NE(p, nullptr);
    const PatternOptions& pattern_opts = getOptions(p);
    bool is_latin1_wrapper = !pattern_opts.utf8;  // Latin1 = utf8 is false
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(is_latin1_re2, is_latin1_wrapper);
    EXPECT_TRUE(is_latin1_wrapper);
    // =============================

    releasePattern(p);
}

// Options - getters/setters
TEST_F(Libre2APITest, Options_GettersSetters) {
    // ========== TEST DATA ==========
    // Test all 13 options
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2::Options opts_re2;
    opts_re2.set_max_mem(2 * 1024 * 1024);
    opts_re2.set_case_sensitive(false);
    opts_re2.set_literal(true);
    opts_re2.set_dot_nl(true);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    Options opts_wrapper;
    opts_wrapper.set_max_mem(2 * 1024 * 1024);
    opts_wrapper.set_case_sensitive(false);
    opts_wrapper.set_literal(true);
    opts_wrapper.set_dot_nl(true);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(opts_re2.max_mem(), opts_wrapper.max_mem());
    EXPECT_EQ(opts_re2.case_sensitive(), opts_wrapper.case_sensitive());
    EXPECT_EQ(opts_re2.literal(), opts_wrapper.literal());
    EXPECT_EQ(opts_re2.dot_nl(), opts_wrapper.dot_nl());
    EXPECT_EQ(2 * 1024 * 1024, opts_wrapper.max_mem());
    EXPECT_FALSE(opts_wrapper.case_sensitive());
    EXPECT_TRUE(opts_wrapper.literal());
    EXPECT_TRUE(opts_wrapper.dot_nl());
    // =============================
}

// Options - Copy() method
TEST_F(Libre2APITest, Options_Copy) {
    // ========== EXECUTE RE2 ==========
    RE2::Options opts1_re2;
    opts1_re2.set_case_sensitive(false);
    opts1_re2.set_max_mem(1024);
    RE2::Options opts2_re2;
    opts2_re2.Copy(opts1_re2);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    Options opts1_wrapper;
    opts1_wrapper.set_case_sensitive(false);
    opts1_wrapper.set_max_mem(1024);
    Options opts2_wrapper;
    opts2_wrapper.Copy(opts1_wrapper);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(opts2_re2.case_sensitive(), opts2_wrapper.case_sensitive());
    EXPECT_EQ(opts2_re2.max_mem(), opts2_wrapper.max_mem());
    EXPECT_FALSE(opts2_wrapper.case_sensitive());
    EXPECT_EQ(1024, opts2_wrapper.max_mem());
    // =============================
}

//=============================================================================
// RE2::SET TESTS (Phase 1.2.6 - Multi-Pattern Matching)
//=============================================================================

// Set - basic multi-pattern matching
TEST_F(Libre2APITest, Set_BasicMatching) {
    // ========== TEST DATA ==========
    const std::vector<std::string> PATTERNS = {"foo", "bar", "baz"};
    const std::string TEXT = "bar";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2::Set set_re2(RE2::Options(), RE2::UNANCHORED);
    std::string error_re2;
    for (const auto& pat : PATTERNS) {
        ASSERT_GE(set_re2.Add(pat, &error_re2), 0) << "Failed to add: " << error_re2;
    }
    ASSERT_TRUE(set_re2.Compile());
    std::vector<int> matches_re2;
    bool result_re2 = set_re2.Match(TEXT, &matches_re2);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    Set set_wrapper(Options(), RE2::UNANCHORED);
    std::string error_wrapper;
    for (const auto& pat : PATTERNS) {
        ASSERT_GE(set_wrapper.Add(pat, &error_wrapper), 0) << "Failed to add: " << error_wrapper;
    }
    ASSERT_TRUE(set_wrapper.Compile());
    std::vector<int> matches_wrapper;
    bool result_wrapper = set_wrapper.Match(TEXT, &matches_wrapper);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);
    EXPECT_EQ(matches_re2.size(), matches_wrapper.size());
    EXPECT_EQ(1u, matches_wrapper.size());  // Only "bar" matches
    EXPECT_EQ(1, matches_wrapper[0]);  // Index 1 (second pattern)
    // =============================
}

// Set - multiple matches
TEST_F(Libre2APITest, Set_MultipleMatches) {
    // ========== TEST DATA ==========
    const std::vector<std::string> PATTERNS = {"\\d+", "\\w+", "[a-z]+"};
    const std::string TEXT = "abc123";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2::Set set_re2(RE2::Options(), RE2::UNANCHORED);
    std::string error_re2;
    for (const auto& pat : PATTERNS) {
        set_re2.Add(pat, &error_re2);
    }
    set_re2.Compile();
    std::vector<int> matches_re2;
    bool result_re2 = set_re2.Match(TEXT, &matches_re2);
    std::sort(matches_re2.begin(), matches_re2.end());
    // =================================

    // ========== EXECUTE WRAPPER ==========
    Set set_wrapper(Options(), RE2::UNANCHORED);
    std::string error_wrapper;
    for (const auto& pat : PATTERNS) {
        set_wrapper.Add(pat, &error_wrapper);
    }
    set_wrapper.Compile();
    std::vector<int> matches_wrapper;
    bool result_wrapper = set_wrapper.Match(TEXT, &matches_wrapper);
    std::sort(matches_wrapper.begin(), matches_wrapper.end());
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_EQ(matches_re2.size(), matches_wrapper.size());
    EXPECT_EQ(matches_re2, matches_wrapper);
    // All 3 patterns match "abc123"
    EXPECT_EQ(3u, matches_wrapper.size());
    // =============================
}

// Set - no matches
TEST_F(Libre2APITest, Set_NoMatches) {
    // ========== TEST DATA ==========
    const std::vector<std::string> PATTERNS = {"foo", "bar"};
    const std::string TEXT = "baz";
    // ===============================

    // ========== EXECUTE RE2 ==========
    RE2::Set set_re2(RE2::Options(), RE2::UNANCHORED);
    std::string error_re2;
    for (const auto& pat : PATTERNS) {
        set_re2.Add(pat, &error_re2);
    }
    set_re2.Compile();
    std::vector<int> matches_re2;
    bool result_re2 = set_re2.Match(TEXT, &matches_re2);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    Set set_wrapper(Options(), RE2::UNANCHORED);
    std::string error_wrapper;
    for (const auto& pat : PATTERNS) {
        set_wrapper.Add(pat, &error_wrapper);
    }
    set_wrapper.Compile();
    std::vector<int> matches_wrapper;
    bool result_wrapper = set_wrapper.Match(TEXT, &matches_wrapper);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_FALSE(result_wrapper);
    EXPECT_EQ(matches_re2.size(), matches_wrapper.size());
    EXPECT_TRUE(matches_wrapper.empty());
    // =============================
}

// Set - Size() method
TEST_F(Libre2APITest, Set_Size) {
    // ========== EXECUTE RE2 ==========
    RE2::Set set_re2(RE2::Options(), RE2::UNANCHORED);
    std::string error_re2;
    set_re2.Add("pattern1", &error_re2);
    set_re2.Add("pattern2", &error_re2);
    set_re2.Add("pattern3", &error_re2);
    int size_re2 = set_re2.Size();
    // =================================

    // ========== EXECUTE WRAPPER ==========
    Set set_wrapper(Options(), RE2::UNANCHORED);
    std::string error_wrapper;
    set_wrapper.Add("pattern1", &error_wrapper);
    set_wrapper.Add("pattern2", &error_wrapper);
    set_wrapper.Add("pattern3", &error_wrapper);
    int size_wrapper = set_wrapper.Size();
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(size_re2, size_wrapper);
    EXPECT_EQ(3, size_wrapper);
    // =============================
}

//=============================================================================
// RE2 PORTED TESTS - QuoteMeta (from re2_test.cc)
//=============================================================================

// QuoteMeta - comprehensive test cases
TEST_F(Libre2APITest, QuoteMeta_Comprehensive) {
    // Test data from RE2's QuoteMeta Simple test
    const std::vector<std::string> TEST_CASES = {
        "foo",
        "foo.bar",
        "foo\\.bar",
        "[1-9]",
        "1.5-2.0?",
        "\\d",
        "Who doesn't like ice cream?",
        "((a|b)c?d*e+[f-h]i)",
        "((?!)xxx).*yyy",
        "(["
    };

    for (const auto& unquoted : TEST_CASES) {
        // ========== EXECUTE RE2 ==========
        std::string quoted_re2 = RE2::QuoteMeta(unquoted);
        RE2 re2_pattern(quoted_re2);
        bool result_re2 = RE2::FullMatch(unquoted, re2_pattern);
        // =================================

        // ========== EXECUTE WRAPPER ==========
        std::string quoted_wrapper = quoteMeta(unquoted);
        std::string error;
        RE2Pattern* p = compilePattern(quoted_wrapper, true, error);
        ASSERT_NE(p, nullptr) << "Failed to compile quoted: " << quoted_wrapper;
        bool result_wrapper = fullMatch(p, unquoted);
        // =====================================

        // ========== COMPARE ==========
        EXPECT_EQ(quoted_re2, quoted_wrapper) << "Quoted strings differ for: " << unquoted;
        EXPECT_EQ(result_re2, result_wrapper) << "Match results differ for: " << unquoted;
        EXPECT_TRUE(result_wrapper) << "Quoted pattern should match original: " << unquoted;
        // =============================

        releasePattern(p);
    }
}

// QuoteMeta - UTF8 handling
TEST_F(Libre2APITest, QuoteMeta_UTF8) {
    initCache();

    const std::vector<std::string> UTF8_CASES = {
        "Plácido Domingo",
        "xyz",
        "\xc2\xb0",  // 2-byte UTF8 (degree symbol)
        "27\xc2\xb0 degrees",
        "\xe2\x80\xb3",  // 3-byte UTF8 (double prime)
        "\xf0\x9d\x85\x9f"  // 4-byte UTF8 (music note)
    };

    for (const auto& text : UTF8_CASES) {
        // ========== EXECUTE RE2 ==========
        std::string quoted_re2 = RE2::QuoteMeta(text);
        RE2 re2_pattern(quoted_re2);
        bool result_re2 = RE2::FullMatch(text, re2_pattern);
        // =================================

        // ========== EXECUTE WRAPPER ==========
        std::string quoted_wrapper = quoteMeta(text);
        std::string error;
        RE2Pattern* p = compilePattern(quoted_wrapper, true, error);
        ASSERT_NE(p, nullptr);
        bool result_wrapper = fullMatch(p, text);
        // =====================================

        // ========== COMPARE ==========
        EXPECT_EQ(quoted_re2, quoted_wrapper);
        EXPECT_EQ(result_re2, result_wrapper);
        EXPECT_TRUE(result_wrapper);
        // =============================

        releasePattern(p);
    }
}

// QuoteMeta - with null byte
TEST_F(Libre2APITest, QuoteMeta_HasNull) {
    initCache();

    // ========== TEST DATA ==========
    std::string text_with_null;
    text_with_null += '\0';
    text_with_null += "abc";
    // ===============================

    // ========== EXECUTE RE2 ==========
    std::string quoted_re2 = RE2::QuoteMeta(text_with_null);
    RE2 re2_pattern(quoted_re2);
    bool result_re2 = RE2::FullMatch(text_with_null, re2_pattern);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string quoted_wrapper = quoteMeta(text_with_null);
    std::string error;
    RE2Pattern* p = compilePattern(quoted_wrapper, true, error);
    ASSERT_NE(p, nullptr);
    bool result_wrapper = fullMatch(p, text_with_null);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(quoted_re2, quoted_wrapper);
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);
    // =============================

    releasePattern(p);
}

//=============================================================================
// RE2 PORTED TESTS - Replace/Extract (from re2_test.cc)
//=============================================================================

// Replace - with capture groups (from RE2 test data)
TEST_F(Libre2APITest, RE2Ported_Replace_CaptureRewrite) {
    initCache();

    // ========== TEST DATA (from RE2) ==========
    const std::string PATTERN = "(qu|[b-df-hj-np-tv-z]*)([a-z]+)";
    const std::string REWRITE = "\\2\\1ay";
    const std::string ORIGINAL = "the quick brown fox";
    const std::string EXPECTED = "ethay quick brown fox";
    // ==========================================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::string str_re2 = ORIGINAL;
    bool result_re2 = RE2::Replace(&str_re2, re2_pattern, REWRITE);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string result_wrapper;
    bool replaced_wrapper = replace(p, ORIGINAL, REWRITE, &result_wrapper);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, replaced_wrapper);
    EXPECT_TRUE(replaced_wrapper);
    EXPECT_EQ(str_re2, result_wrapper);
    EXPECT_EQ(EXPECTED, result_wrapper);
    // =============================

    releasePattern(p);
}

// GlobalReplace - multiple replacements (from RE2)
TEST_F(Libre2APITest, RE2Ported_GlobalReplace_Multiple) {
    initCache();

    // ========== TEST DATA (from RE2) ==========
    const std::string PATTERN = "\\w+";
    const std::string REWRITE = "\\0-NOSPAM";
    const std::string ORIGINAL = "abcd.efghi@google.com";
    const std::string EXPECTED = "abcd-NOSPAM.efghi-NOSPAM@google-NOSPAM.com-NOSPAM";
    const int EXPECTED_COUNT = 4;
    // ==========================================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::string str_re2 = ORIGINAL;
    int count_re2 = RE2::GlobalReplace(&str_re2, re2_pattern, REWRITE);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string result_wrapper;
    int count_wrapper = replaceAll(p, ORIGINAL, REWRITE, &result_wrapper);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(count_re2, count_wrapper);
    EXPECT_EQ(str_re2, result_wrapper);
    EXPECT_EQ(EXPECTED_COUNT, count_wrapper);
    EXPECT_EQ(EXPECTED, result_wrapper);
    // =============================

    releasePattern(p);
}

// Extract - basic extraction (from RE2)
TEST_F(Libre2APITest, RE2Ported_Extract_Basic) {
    initCache();

    // ========== TEST DATA (from RE2) ==========
    const std::string PATTERN = "(.*)@([^.]*)";
    const std::string REWRITE = "\\2!\\1";
    const std::string TEXT = "boris@kremvax.ru";
    const std::string EXPECTED = "kremvax!boris";
    // ==========================================

    // ========== EXECUTE RE2 ==========
    RE2 re2_pattern(PATTERN);
    std::string out_re2;
    bool result_re2 = RE2::Extract(TEXT, re2_pattern, REWRITE, &out_re2);
    // =================================

    // ========== EXECUTE WRAPPER ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    ASSERT_NE(p, nullptr);
    std::string out_wrapper;
    bool result_wrapper = extract(p, TEXT, REWRITE, &out_wrapper);
    // =====================================

    // ========== COMPARE ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_TRUE(result_wrapper);
    EXPECT_EQ(out_re2, out_wrapper);
    EXPECT_EQ(EXPECTED, out_wrapper);
    // =============================

    releasePattern(p);
}
