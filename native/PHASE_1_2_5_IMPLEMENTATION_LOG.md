# Phase 1.2.5 Implementation Log

**Started:** 2025-11-29 15:45 UTC
**Goal:** Add missing RE2 API functions with bulk/direct variants
**Status:** 🔄 IN PROGRESS

---

## CRITICAL REMINDERS

### Caching & Pointers
- ✅ **Pattern cache ONLY** - compile operations only
- ✅ **Return raw pointers** - clients manage refcount decrement
- ⚠️ **NO new caches yet** - track opportunities for Phase 1.2.6
- ✅ **No convenience functions** - would cause memory leaks (refcount never reaches zero)

### Design Principles
- ✅ All functions delegate to RE2 (pure wrapper)
- ✅ All tests use RE2 comparison pattern
- ✅ Bulk/Direct variants for matching/consume functions
- ✅ Follow existing patterns from Phase 1.2.4

---

## IMPLEMENTATION PROGRESS

### Phase 1.2.5a - N-Variant Matching (12 functions)

**Goal:** Support unlimited capture groups via array-based API

#### Standard Variants (4 functions)

**1. fullMatchN()**
- **Status:** ✅ COMPLETE
- **Signature:**
  ```cpp
  bool fullMatchN(cache::RE2Pattern* pattern, std::string_view text,
                  std::string* captures[], int n_captures);
  ```
- **Implementation Plan:**
  - Validate pattern pointer (nullptr check)
  - Call RE2::FullMatchN() with pattern->compiled_regex
  - Pass captures array directly (no allocation)
  - Return bool result
- **RE2 Delegation:** `RE2::FullMatchN(text, *pattern->compiled_regex, args, n)`
- **Caching Notes:** No caching (match operation, not compilation)
- **Issues:** None yet

**2. partialMatchN()**
- **Status:** ⏳ Pending
- **Signature:** Similar to fullMatchN but uses RE2::PartialMatchN

**3. consumeN()**
- **Status:** ⏳ Pending
- **Signature:**
  ```cpp
  bool consumeN(cache::RE2Pattern* pattern, const char** input_text, int* input_len,
                std::string* captures[], int n_captures);
  ```
- **Special Handling:** Modifies input_text and input_len on success

**4. findAndConsumeN()**
- **Status:** ⏳ Pending
- **Signature:** Similar to consumeN but uses RE2::FindAndConsumeN

#### Direct Variants (4 functions)

**5. fullMatchNDirect()**
- **Status:** ⏳ Pending
- **Signature:**
  ```cpp
  bool fullMatchNDirect(cache::RE2Pattern* pattern, int64_t text_address, int text_len,
                        std::string* captures[], int n_captures);
  ```
- **Implementation Plan:**
  - Cast int64_t → const char*
  - Wrap in re2::StringPiece (zero-copy)
  - Call fullMatchN internally (reuse logic)
- **Design Decision:** Reuse standard variant to avoid duplication

**6-8. partialMatchNDirect, consumeNDirect, findAndConsumeNDirect**
- **Status:** ⏳ Pending
- **Pattern:** Same as fullMatchNDirect

#### Bulk Variants (2 functions)

**9. fullMatchNBulk()**
- **Status:** ⏳ Pending
- **Signature:**
  ```cpp
  void fullMatchNBulk(cache::RE2Pattern* pattern, const char** texts, const int* text_lens,
                      int num_texts, std::string** captures_array[], int n_captures,
                      bool* results_out);
  ```
- **Complexity:** 2D capture array (num_texts × n_captures)
- **Partial Success:** Handle null texts (mark false, continue)
- **Design Decision:** Loop internally, call fullMatchN for each text

**10. partialMatchNBulk()**
- **Status:** ⏳ Pending
- **Pattern:** Same as fullMatchNBulk but uses partialMatchN

#### Bulk+Direct Variants (2 functions)

**11. fullMatchNDirectBulk()**
- **Status:** ⏳ Pending
- **Signature:**
  ```cpp
  void fullMatchNDirectBulk(cache::RE2Pattern* pattern, const int64_t* addresses,
                            const int* lens, int num_texts,
                            std::string** captures_array[], int n_captures,
                            bool* results_out);
  ```
- **Design Decision:** Cast addresses, call fullMatchNBulk

**12. partialMatchNDirectBulk()**
- **Status:** ⏳ Pending
- **Pattern:** Same as fullMatchNDirectBulk

---

## DESIGN DECISIONS LOG

### Decision 1: N-Variant API Design
- **Date:** 2025-11-29
- **Issue:** How to support unlimited captures without C++ variadic templates in wrapper?
- **Options:**
  - A) Array-based: `std::string* captures[], int n_captures`
  - B) Vector-based: `std::vector<std::string*>& captures`
  - C) Callback-based: `void (*callback)(int idx, const char*, size_t)`
- **Chosen:** Option A (array-based)
- **Rationale:**
  - Matches RE2::FullMatchN() signature exactly
  - No std::vector allocation overhead
  - Caller controls memory (pre-allocates array)
  - Works naturally with JNI (Java can pass String[] → const char**)
  - Simple, predictable, fast
- **Impact:** User must pre-allocate capture array (know size in advance)
- **Alternative for dynamic:** User calls getNumberOfCapturingGroups() first

### Decision 2: Direct Variant Implementation Strategy
- **Date:** 2025-11-29
- **Issue:** Direct variants duplicate logic - how to reduce duplication?
- **Options:**
  - A) Duplicate implementation (copy/paste)
  - B) Direct variants call standard variants (wrap address → StringPiece)
  - C) Standard variants call Direct variants (everything goes through Direct)
- **Chosen:** Option B (Direct → Standard)
- **Rationale:**
  - Standard variant is canonical (matches RE2)
  - Direct variant is thin wrapper (just address casting + StringPiece)
  - Reduces duplication
  - Easier to maintain
  - Performance: Zero overhead (StringPiece is just pointer+length)
- **Code Pattern:**
  ```cpp
  bool fullMatchNDirect(RE2Pattern* p, int64_t addr, int len, string* caps[], int n) {
      if (addr == 0 || len < 0) return false;
      const char* text = reinterpret_cast<const char*>(addr);
      re2::StringPiece sp(text, len);
      return fullMatchN(p, sp, caps, n);  // Delegate to standard
  }
  ```

### Decision 3: Bulk Variant Capture Array Structure
- **Date:** 2025-11-29
- **Issue:** How to structure 2D capture array for bulk operations?
- **Options:**
  - A) `std::string** captures_array[]` - array of arrays
  - B) `std::string* captures[]` - flat array (num_texts * n_captures)
  - C) `std::vector<std::vector<std::string>>& results`
- **Chosen:** Option A (array of arrays)
- **Rationale:**
  - Natural structure: captures_array[text_idx][capture_idx]
  - Each text has its own capture array
  - Caller can use different arrays per text
  - JNI-friendly: Java String[][] → std::string**[]
  - Partial success: null text → skip its captures
- **Memory Management:** Caller allocates everything, wrapper just writes
- **Example Usage:**
  ```cpp
  // Java side allocates:
  String[][] captures = new String[10][3];  // 10 texts, 3 captures each
  // C++ receives:
  std::string** captures_array[10];  // Each element points to String[3]
  ```

### Decision 4: RE2::Arg Conversion - NOT IN PHASE 1.2.5
- **Date:** 2025-11-29
- **Issue:** RE2's N-variants use `const Arg* const args[]`, we use `std::string*[]`
- **Current Approach:** Only support string captures (no int, float, etc.)
- **Rationale:**
  - RE2::Arg is complex (template-based parsers)
  - String captures cover 90% of use cases
  - Type conversion (int, float) can be added in Phase 1.2.6+
  - Keep Phase 1.2.5 focused on API completeness
- **Implementation:**
  ```cpp
  // Convert std::string*[] → RE2::Arg[]
  std::vector<RE2::Arg> args_vec;
  args_vec.reserve(n_captures);
  for (int i = 0; i < n_captures; i++) {
      args_vec.emplace_back(captures[i]);  // RE2::Arg(std::string*)
  }
  const RE2::Arg* args[n_captures];
  for (int i = 0; i < n_captures; i++) {
      args[i] = &args_vec[i];
  }
  return RE2::FullMatchN(text, *pattern->compiled_regex, args, n_captures);
  ```
- **Future:** Add typed capture support (int*, float*, etc.) in Phase 1.2.6+

---

## ISSUES & RESOLUTIONS

### Issue 1: RE2::Arg Allocation on Stack
- **Status:** 🔄 INVESTIGATING
- **Problem:** RE2::FullMatchN needs `const Arg* const args[]`
- **Current Code:** Allocates vector<Arg> + pointer array
- **Concern:** Performance overhead for every match call
- **Options:**
  - A) Keep vector allocation (simple, safe, small overhead)
  - B) Use alloca() for stack allocation (fast, risky)
  - C) Thread-local pool of Arg objects (complex)
- **Decision:** PENDING - start with Option A, profile later
- **Tracking:** Monitor performance in Phase 1.2.6

---

## CACHING OPPORTUNITIES (for Phase 1.2.6)

### Opportunity 1: Result Caching for Matching
- **Function:** fullMatch, partialMatch, consume, etc.
- **Key:** (pattern_ptr, text_hash, options)
- **Value:** (bool result, captures)
- **Benefit:** Repeated matches on same text (hot path in scans)
- **Trade-off:** Memory vs CPU (need hit rate analysis)
- **Decision:** DEFER to Phase 1.2.6
- **Rationale:**
  - Phase 1.2.5 = API completeness only
  - Need production data to tune cache size/eviction
  - Result cache already exists (from Phase 1.0) but not integrated

### Opportunity 2: Compiled Capture Group Metadata Cache
- **Function:** getNumberOfCapturingGroups, NamedCapturingGroups
- **Key:** pattern_ptr (already cached)
- **Value:** Metadata is already in RE2Pattern object
- **Benefit:** No cache needed - just return from pattern object
- **Decision:** NO separate cache needed
- **Implementation:** Access pattern->compiled_regex->NumberOfCapturingGroups()

### Opportunity 3: Rewrite String Validation Cache
- **Function:** checkRewriteString
- **Key:** (pattern_ptr, rewrite_string)
- **Value:** (bool valid, error_message)
- **Benefit:** Rewrite strings often reused (templates)
- **Trade-off:** Small cache (100s of entries max)
- **Decision:** DEFER to Phase 1.2.6
- **Priority:** LOW (validation is fast)

---

## TESTING STRATEGY

### Test Structure (RE2 Comparison Pattern)

```cpp
TEST_F(Libre2APITest, FullMatchN_MultipleCaptures) {
    // ========== TEST DATA (defined ONCE) ==========
    const std::string PATTERN = "(\\w+):(\\d+):(\\w+)";  // 3 captures
    const std::string TEXT = "foo:123:bar";

    // ========== EXECUTE RE2 (capture results) ==========
    RE2 re2_pattern(PATTERN);
    std::string cap1_re2, cap2_re2, cap3_re2;
    const RE2::Arg* args_re2[] = {
        &RE2::Arg(&cap1_re2),
        &RE2::Arg(&cap2_re2),
        &RE2::Arg(&cap3_re2)
    };
    bool result_re2 = RE2::FullMatchN(TEXT, re2_pattern, args_re2, 3);

    // ========== EXECUTE WRAPPER (capture results) ==========
    std::string error;
    RE2Pattern* p = compilePattern(PATTERN, true, error);
    std::string cap1_wrapper, cap2_wrapper, cap3_wrapper;
    std::string* caps[] = {&cap1_wrapper, &cap2_wrapper, &cap3_wrapper};
    bool result_wrapper = fullMatchN(p, TEXT, caps, 3);

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(result_re2, result_wrapper);
    EXPECT_EQ(cap1_re2, cap1_wrapper) << "Capture 1 mismatch";
    EXPECT_EQ(cap2_re2, cap2_wrapper) << "Capture 2 mismatch";
    EXPECT_EQ(cap3_re2, cap3_wrapper) << "Capture 3 mismatch";

    releasePattern(p);
}
```

### Test Coverage Plan

**N-Variant Matching (45 tests):**
- 0 captures (sanity check): 5 tests
- 1 capture: 5 tests
- 2 captures: 5 tests
- 3 captures: 5 tests
- 5 captures: 3 tests
- 10 captures: 2 tests
- 20 captures (stress): 2 tests
- Direct variants: 8 tests
- Bulk variants: 5 tests
- Bulk+Direct: 5 tests

**Edge Cases:**
- Empty text
- Empty captures (n_captures = 0)
- NULL capture pointers (should fail gracefully)
- Pattern with no groups (n_captures > 0 but pattern has 0 groups)
- Unicode text
- Large captures (1MB+ text)

---

## PERFORMANCE NOTES

### Baseline (Phase 1.2.4)
- fullMatch (0 captures): ~1μs per match
- Bulk operations: 100x throughput vs individual calls

### Expected (Phase 1.2.5)
- fullMatchN (3 captures): ~2-3μs per match (RE2 overhead)
- Capture extraction: ~500ns per capture (string copy)
- Bulk with captures: ~300K matches/sec (estimated)

### Profiling Plan (Phase 1.2.6)
- Measure RE2::Arg allocation overhead
- Measure capture array iteration cost
- Identify hot paths for result caching

---

## NEXT STEPS

1. ✅ Create implementation log (this file)
2. 🔄 Implement fullMatchN (standard variant)
3. ⏳ Test fullMatchN with RE2 comparison
4. ⏳ Implement partialMatchN, consumeN, findAndConsumeN
5. ⏳ Add Direct variants (4 functions)
6. ⏳ Add Bulk variants (2 functions)
7. ⏳ Add Bulk+Direct variants (2 functions)
8. ⏳ Write all 45 tests
9. ⏳ Build and verify 100% pass
10. ⏳ Move to Phase 1.2.5b (Pattern analysis)

---

**Tracking:** All design decisions, issues, and caching opportunities logged here.
**Commit Strategy:** Commit after each sub-phase (5a, 5b, 5c, 5d, 5e, 5f) with detailed messages.

---

## PHASE 1.2.5b COMPLETION

**Status:** ✅ COMPLETE
**Date:** 2025-11-29 16:15 UTC

**Functions Implemented (5):**
1. ✅ getNumberOfCapturingGroups() - RE2::NumberOfCapturingGroups()
2. ✅ getNamedCapturingGroupsJSON() - RE2::NamedCapturingGroups() as JSON
3. ✅ getCapturingGroupNamesJSON() - RE2::CapturingGroupNames() as JSON
4. ✅ getProgramSize() - RE2::ProgramSize()
5. ✅ getReverseProgramSize() - RE2::ReverseProgramSize()

**Tests Added (5, all with RE2 comparison):**
1. ✅ GetNumberOfCapturingGroups_Basic - 3 groups
2. ✅ GetNumberOfCapturingGroups_NoGroups - 0 groups
3. ✅ GetProgramSize_ComplexPattern - complexity metric
4. ✅ GetReverseProgramSize_ComplexPattern - reverse complexity
5. ✅ GetNamedCapturingGroups_WithNames - named groups

**Test Results:**
- Total: 237/237 passing (was 232, +5)
- Pattern analysis: 5/5 passing
- All comparisons: RE2 == Wrapper ✅

**Implementation Notes:**
- Direct delegation to RE2 methods (zero overhead)
- Returns -1 for invalid patterns
- JSON format for named groups (language-agnostic)
- No caching needed (metadata in pattern object)

**Caching Analysis:**
- NO separate cache required
- Metadata already stored in RE2Pattern object
- Functions are O(1) lookups
- No performance optimization opportunities

**Commit:** 595dd4a

**Next:** Phase 1.2.5c (Status/validation methods)
