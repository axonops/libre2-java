# libre2-java Native Testing Guidelines

**Version:** 1.0
**Date:** 2025-11-29
**Status:** MANDATORY - All tests must follow these patterns

---

## CRITICAL PRINCIPLE: RE2 Comparison Testing

**Every functional test MUST compare RE2 directly vs our wrapper.**

### Why This Matters

Our wrapper is a **pure delegation layer** - we expose RE2's functionality without changing behavior. Any difference between RE2 and our wrapper is a BUG.

**Testing approach:**
1. Call RE2 directly → capture results
2. Call our wrapper → capture results
3. Compare results → must be identical

**This validates:**
- ✅ Wrapper delegates correctly (no behavioral drift)
- ✅ No bugs in our abstraction layer
- ✅ RE2 is source of truth (documented in tests)
- ✅ Catches regressions immediately

---

## MANDATORY TEST PATTERN

### Template for ALL Functional Tests

```cpp
TEST_F(TestSuiteName, TestName) {
    // ========== SETUP ==========
    initCache();  // If testing with cache

    std::string error;
    RE2Pattern* p = compilePattern("pattern", true, error);
    ASSERT_NE(p, nullptr);
    // ===========================

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "test data here";
    const std::string EXPECTED_RESULT = "expected";
    // Any other test constants
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    // Call RE2 static methods directly
    // Capture ALL results (return value, captures, modified input, etc.)

    bool result_re2 = RE2::FullMatch(INPUT_TEXT, *p->compiled_regex);
    // OR: RE2::PartialMatch, RE2::Consume, RE2::FindAndConsume, etc.

    std::string capture_re2;  // If function has captures
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    // Call our wrapper functions
    // Capture ALL results (return value, captures, modified input, etc.)

    bool result_wrapper = fullMatch(p, INPUT_TEXT);
    // OR: partialMatch, consume, findAndConsume, etc.

    std::string capture_wrapper;  // If function has captures
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper)
        << "Return value must match - RE2: " << result_re2
        << " vs Wrapper: " << result_wrapper;

    EXPECT_EQ(capture_re2, capture_wrapper)
        << "Captured value must match - RE2: '" << capture_re2
        << "' vs Wrapper: '" << capture_wrapper << "'";

    // Compare ALL outputs from the functions
    // ===========================================================

    // ========== CLEANUP ==========
    releasePattern(p);
    // =============================
}
```

---

## PATTERN RULES

### 1. Test Data Definition

**✅ CORRECT:**
```cpp
// ========== TEST DATA (defined ONCE) ==========
const std::string INPUT_TEXT = "hello world";
const std::string EXPECTED_CAPTURE = "world";
// ==============================================
```

**❌ WRONG:**
```cpp
// Defining same data multiple times (error-prone)
const char* text1 = "hello world";  // RE2 version
const char* text2 = "hello world";  // Wrapper version - might typo!
```

**Rule:** Test data defined ONCE at the top. No duplication.

---

### 2. RE2 Execution

**✅ CORRECT:**
```cpp
// ========== EXECUTE RE2 (capture results) ==========
std::string_view input_re2(INPUT_TEXT);
std::string capture_re2;
bool result_re2 = RE2::Consume(&input_re2, *p->compiled_regex, &capture_re2);
std::string remaining_re2(input_re2.data(), input_re2.size());
// ===================================================
```

**Key points:**
- Use RE2 static methods directly (RE2::FullMatch, RE2::PartialMatch, etc.)
- Access compiled pattern via `p->compiled_regex`
- Capture ALL results (return value, captures, modified input)
- Use `std::string_view` for consume/findAndConsume (RE2's API)

---

### 3. Wrapper Execution

**✅ CORRECT:**
```cpp
// ========== EXECUTE WRAPPER (capture results) ==========
const char* input_wrapper = INPUT_TEXT.data();
int len_wrapper = INPUT_TEXT.size();
std::string capture_wrapper;
bool result_wrapper = consume(p, &input_wrapper, &len_wrapper, &capture_wrapper);
std::string remaining_wrapper(input_wrapper, len_wrapper);
// ========================================================
```

**Key points:**
- Use our wrapper functions (fullMatch, consume, etc.)
- Pass pattern pointer (not compiled_regex)
- Capture ALL results (same as RE2 execution)
- Use `const char* + int` for our wrapper API

---

### 4. Comparison

**✅ CORRECT:**
```cpp
// ========== COMPARE (CRITICAL: must be identical) ==========
EXPECT_EQ(result_re2, result_wrapper) << "Return value must match";
EXPECT_EQ(capture_re2, capture_wrapper) << "Capture must match";
EXPECT_EQ(remaining_re2, remaining_wrapper) << "Remaining text must match";
// ===========================================================
```

**❌ WRONG:**
```cpp
// Re-executing functions in comparison (inefficient, unclear)
EXPECT_EQ(RE2::Consume(...), consume(...));  // NO!
```

**Rule:**
- Compare CAPTURED results only
- No function execution in comparison section
- Clear error messages showing RE2 vs wrapper values

---

## EXAMPLE TESTS

### Example 1: Simple Match

```cpp
TEST_F(Libre2APITest, FullMatch_Simple) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("\\d+", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "12345";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    bool result_re2 = RE2::FullMatch(INPUT_TEXT, *p->compiled_regex);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    bool result_wrapper = fullMatch(p, INPUT_TEXT);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper) << "Results must match";
    // ===========================================================

    releasePattern(p);
}
```

### Example 2: With Captures

```cpp
TEST_F(Libre2APITest, PartialMatch_Captures) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("(\\w+):(\\d+)", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "host:8080";
    // ==============================================

    // ========== EXECUTE RE2 (capture results) ==========
    std::string name_re2, port_re2;
    bool result_re2 = RE2::PartialMatch(INPUT_TEXT, *p->compiled_regex, &name_re2, &port_re2);
    // ===================================================

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string name_wrapper, port_wrapper;
    bool result_wrapper = partialMatch(p, INPUT_TEXT, &name_wrapper, &port_wrapper);
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper) << "Match result must match";
    EXPECT_EQ(name_re2, name_wrapper) << "Name capture must match";
    EXPECT_EQ(port_re2, port_wrapper) << "Port capture must match";
    // ===========================================================

    releasePattern(p);
}
```

### Example 3: Multiple Operations (Loop)

```cpp
TEST_F(Libre2APITest, FindAndConsume_ExtractAll) {
    initCache();

    std::string error;
    RE2Pattern* p = compilePattern("(\\d+)", true, error);
    ASSERT_NE(p, nullptr);

    // ========== TEST DATA (defined ONCE) ==========
    const std::string INPUT_TEXT = "a1b22c333d4444";
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
    const char* input_wrapper = INPUT_TEXT.data();
    int len_wrapper = INPUT_TEXT.size();
    std::vector<std::string> numbers_wrapper;
    std::string num_wrapper;
    while (findAndConsume(p, &input_wrapper, &len_wrapper, &num_wrapper)) {
        numbers_wrapper.push_back(num_wrapper);
    }
    // ========================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    ASSERT_EQ(numbers_re2.size(), numbers_wrapper.size())
        << "Number count must match";
    for (size_t i = 0; i < numbers_re2.size(); i++) {
        EXPECT_EQ(numbers_re2[i], numbers_wrapper[i])
            << "Number " << i << " must match";
    }
    // ===========================================================

    releasePattern(p);
}
```

---

## WHEN TO USE THIS PATTERN

### MUST Use RE2 Comparison Pattern:

✅ **ALL tests for:**
- fullMatch, partialMatch (Phase 1.1)
- consume, findAndConsume (Phase 1.2.1)
- replace, replaceAll, extract (Phase 1.2.2 - future)
- Any function that wraps RE2 static methods

### CAN Skip RE2 Comparison:

✅ **Tests for:**
- Cache behavior (hits, misses, eviction)
- Thread safety (concurrent access)
- Initialization (initCache, shutdown)
- Error handling (null pointers, invalid patterns)
- Metrics (getMetricsJSON)
- Resource management (refcounting)

**Rule:** If the test validates FUNCTIONAL BEHAVIOR (matching, replacing, etc.), use RE2 comparison. If testing infrastructure (cache, threads, etc.), can skip.

---

## TESTING CATEGORIES

### Category 1: Functional Tests (MUST use RE2 comparison)

Tests that validate regex operations:
- Matching (full, partial, consume, findAndConsume)
- Replacement (replace, replaceAll, extract)
- Capture groups (0, 1, 2, N captures)
- Edge cases (empty strings, unicode, large text)

**Pattern:** RE2 vs wrapper comparison (mandatory)

### Category 2: Infrastructure Tests (CAN skip RE2 comparison)

Tests that validate wrapper infrastructure:
- Cache hits/misses
- LRU eviction
- TTL eviction
- Refcount management
- Thread safety
- Initialization modes
- Metrics accuracy

**Pattern:** Standard GoogleTest assertions

### Category 3: Safety Tests (CAN skip RE2 comparison)

Tests that validate safety:
- Null pointer handling
- Invalid pattern handling
- Buffer overflows
- Memory leaks (ASan)
- Data races (TSan)
- Concurrent access

**Pattern:** Standard GoogleTest assertions

---

## BEST PRACTICES

### 1. Test Data Naming

Use descriptive ALL_CAPS names for test constants:

```cpp
const std::string INPUT_TEXT = "...";
const std::string EXPECTED_CAPTURE = "...";
const std::string EXPECTED_REMAINING = "...";
const int EXPECTED_COUNT = 5;
```

### 2. Variable Naming Convention

Use `_re2` and `_wrapper` suffixes consistently:

```cpp
bool result_re2 = RE2::FullMatch(...);
bool result_wrapper = fullMatch(...);

std::string capture_re2;
std::string capture_wrapper;
```

### 3. Comment Sections

Always use these exact comment blocks:

```cpp
// ========== TEST DATA (defined ONCE) ==========
// ==============================================

// ========== EXECUTE RE2 (capture results) ==========
// ===================================================

// ========== EXECUTE WRAPPER (capture results) ==========
// ========================================================

// ========== COMPARE (CRITICAL: must be identical) ==========
// ===========================================================
```

These make code reviews easy and ensure consistency.

### 4. Error Messages

Include both values in error messages:

```cpp
EXPECT_EQ(result_re2, result_wrapper)
    << "Results must match - RE2: " << result_re2
    << " vs Wrapper: " << result_wrapper;
```

This makes debugging failures trivial.

### 5. Cleanup

Always release patterns:

```cpp
releasePattern(p);  // End of test
```

---

## ANTI-PATTERNS (DO NOT DO THIS)

### ❌ Anti-Pattern 1: Duplicate Test Data

```cpp
// WRONG - data defined multiple times
const char* text1 = "hello";  // For RE2
const char* text2 = "hello";  // For wrapper - might typo!
```

**Why wrong:** Easy to make typos, hard to maintain.

**Fix:** Define once, use for both.

### ❌ Anti-Pattern 2: Re-Execute in Comparison

```cpp
// WRONG - executing functions in assertions
EXPECT_EQ(RE2::FullMatch(text, re), fullMatch(p, text));
```

**Why wrong:**
- Unclear which execution failed
- Can't see intermediate values
- Harder to debug

**Fix:** Execute once, capture results, then compare.

### ❌ Anti-Pattern 3: No RE2 Comparison

```cpp
// WRONG - only testing wrapper, not comparing to RE2
TEST_F(...) {
    bool result = fullMatch(p, "test");
    EXPECT_TRUE(result);  // How do we know this is correct?
}
```

**Why wrong:** We're guessing expected behavior. RE2 is source of truth.

**Fix:** Always compare against RE2 direct call.

### ❌ Anti-Pattern 4: Inconsistent Naming

```cpp
// WRONG - confusing variable names
bool r1 = RE2::FullMatch(...);
bool match_result_wrapper = fullMatch(...);
EXPECT_EQ(r1, match_result_wrapper);  // Unclear which is which
```

**Why wrong:** Hard to read, easy to swap.

**Fix:** Consistent `_re2` and `_wrapper` suffixes.

---

## TEST COVERAGE REQUIREMENTS

### Minimum Coverage for Each Function

Every function must have tests for:

1. **Happy path** - Basic functionality works
2. **Edge cases** - Empty strings, large strings, unicode
3. **Error cases** - No match, invalid input, null pointers
4. **RE2 comparison** - Wrapper behavior identical to RE2
5. **Thread safety** - Concurrent access (if function is thread-safe)

### Example: consume() Function Coverage

```
✅ Consume_Basic - Happy path (basic consumption)
✅ Consume_OneCapture - 1 capture group
✅ Consume_TwoCaptures - 2 capture groups
✅ Consume_NoMatch - No match at start (input unchanged)
✅ Consume_EmptyMatch - Zero-width match
✅ Consume_MultipleIterations - Loop extraction
✅ Consume_NullPattern - Null pattern safety
✅ Consume_NullInput - Null input safety
✅ Consume_EmptyInput - Empty string
✅ Consume_LargeText - Performance (10K iterations)
✅ Consume_Unicode - UTF-8 handling
✅ Consume_ThreadSafe - Concurrent access (10 threads × 100 iterations)
```

**Total:** 12 tests for 6 function overloads (2 tests per overload average)

---

## RUNNING TESTS

### Build and Run All Tests

```bash
cd native/
./scripts/build_tests.sh
```

**Expected output:**
```
100% tests passed, 0 tests failed out of N
✓ All tests passed!
```

### Run Specific Test

```bash
cd native/cmake-build
./tests/cache_tests --gtest_filter="Libre2APITest.Consume_Basic"
```

### Run with Sanitizers

```bash
# Memory leak detection
./scripts/build_tests.sh asan

# Thread safety / race detection
./scripts/build_tests.sh tsan
```

**Both must show ZERO errors.**

---

## CONTINUOUS IMPROVEMENT

### When Adding New Functions

1. Write RE2 comparison test FIRST
2. Implement wrapper function
3. Run test - should pass if wrapper delegates correctly
4. Add edge case tests (all with RE2 comparison)

### When Tests Fail

If RE2 comparison test fails:

```
Expected: result_re2 (true)
Actual:   result_wrapper (false)
```

**This means:** Wrapper has a bug (not delegating correctly to RE2).

**Fix:** Review wrapper implementation, ensure it calls RE2 exactly.

### Code Review Checklist

Before merging any PR with new tests:

- [ ] All functional tests use RE2 comparison pattern
- [ ] Test data defined ONCE (no duplication)
- [ ] Comment sections present and formatted correctly
- [ ] Error messages include both RE2 and wrapper values
- [ ] 100% tests passing
- [ ] ASan clean (zero leaks)
- [ ] TSan clean (zero races)

---

## SUMMARY

**Golden Rule:** Our wrapper is pure delegation. RE2 is source of truth.

**Testing ensures:**
1. Wrapper behavior = RE2 behavior (no drift)
2. Test data defined safely (no duplication errors)
3. Clear error messages (RE2 vs wrapper comparison)
4. Complete coverage (happy path + edges + errors)

**This pattern is MANDATORY for all functional tests going forward.**

---

**Questions?** See examples in `/Users/johnny/Development/libre2-java/native/tests/libre2_api_test.cpp`

**Last Updated:** 2025-11-29 (Sub-Phase 1.2.1)
