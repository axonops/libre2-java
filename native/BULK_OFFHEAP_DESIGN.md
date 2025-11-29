# Bulk & Off-Heap Operations Design

**Sub-Phase:** 1.2.4 (FINAL)
**Date:** 2025-11-29
**Status:** DESIGN → IMPLEMENT

---

## SCOPE

Sub-Phase 1.2.4 completes Phase 1.2 with:
1. **Bulk operations** - Process multiple texts with single pattern
2. **Off-heap (direct memory)** - Java DirectByteBuffer support
3. **Combined** - Bulk + off-heap together

**This completes the entire wrapper API** - no deferred work.

---

## BULK OPERATIONS DESIGN

### Concept

**Problem:** Calling match() in a loop is inefficient (function call overhead × N)

**Solution:** Bulk API - pass array of texts, get array of results

**Benefits:**
- Amortize function call overhead
- Single refcount increment (not N increments)
- Potential for vectorization/SIMD
- Better cache locality

### API Signature Pattern

```cpp
// Single text (existing)
bool fullMatch(RE2Pattern* pattern, string_view text);

// Bulk variant (NEW)
int fullMatchBulk(
    RE2Pattern* pattern,
    const char** texts,      // Array of text pointers
    const int* text_lens,    // Array of lengths
    int num_texts,           // Number of texts
    bool* results_out);      // Pre-allocated output array
// Returns: number of matches (or -1 on error)
```

### Functions Needing Bulk Variants

**Matching (4 functions × bulk = 4 new):**
- fullMatchBulk
- partialMatchBulk
- consumeBulk (array of inputs, each consumed independently)
- findAndConsumeBulk

**Replacement (3 functions × bulk = 3 new):**
- replaceBulk (replace first in each text)
- replaceAllBulk (replace all in each text)
- extractBulk

**Total:** 7 bulk functions

---

## OFF-HEAP (DIRECT MEMORY) DESIGN

### Concept

**Problem:** Java string → C++ requires copy (expensive for large strings)

**Solution:** DirectByteBuffer - Java allocates off-heap, pass address to C++

**Benefits:**
- Zero copy (no Java heap → C++ copy)
- Works with large data (multi-MB texts)
- Lower GC pressure (data off-heap)

### API Signature Pattern

```cpp
// Heap variant (existing)
bool fullMatch(RE2Pattern* pattern, string_view text);

// Direct memory variant (NEW)
bool fullMatchDirect(
    RE2Pattern* pattern,
    const void* text_ptr,    // Memory address (from DirectByteBuffer)
    int text_len);           // Length in bytes
```

### jlong vs void*

**Decision:** Use `const void*` in C++ layer, JNI layer casts jlong → void*

**Rationale:**
- C++ API stays clean (standard pointer type)
- JNI layer handles jlong ↔ void* casting
- Type-safe within C++

### Functions Needing Direct Variants

**All matching/replacement functions:**
- fullMatchDirect, partialMatchDirect
- consumeDirect, findAndConsumeDirect
- replaceDirect, replaceAllDirect, extractDirect

**Total:** 7 direct functions

---

## COMBINED: BULK + DIRECT

Most powerful: Process multiple off-heap buffers in one call.

```cpp
int fullMatchDirectBulk(
    RE2Pattern* pattern,
    const void** text_ptrs,   // Array of DirectByteBuffer addresses
    const int* text_lens,     // Array of lengths
    int num_texts,
    bool* results_out);
```

**Total:** 7 bulk+direct combined functions

---

## FINAL FUNCTION COUNT

```
Existing (Phase 1.1 + 1.2.1-1.2.3):  27 functions
Bulk variants:                        7 functions
Direct variants:                      7 functions
Bulk+Direct combined:                 7 functions
────────────────────────────────────────────────
TOTAL:                               48 functions
```

**Close to original estimate of 50-60 functions** ✅

---

## IMPLEMENTATION STRATEGY

### Approach: Incremental

**Step 1:** Bulk operations (7 functions, ~30 tests)
**Step 2:** Direct memory (7 functions, ~30 tests)
**Step 3:** Bulk+Direct (7 functions, ~30 tests)
**Step 4:** Integration tests (~20 tests)

**Total:** 21 functions, ~110 tests

---

## ERROR HANDLING

### Bulk Operations: All-or-Nothing

```cpp
int partialMatchBulk(...) {
    // Validate all inputs first
    if (!pattern || !texts || !text_lens || !results_out) {
        return -1;  // Error - no results written
    }

    // Process all texts
    int match_count = 0;
    for (int i = 0; i < num_texts; i++) {
        results_out[i] = partialMatch(pattern, texts[i], text_lens[i]);
        if (results_out[i]) match_count++;
    }

    return match_count;  // Success - all processed
}
```

**If ANY validation fails:** return -1, no partial results
**If validation passes:** process all, return count

---

## TESTING PATTERN

**Every bulk/direct test MUST:**
1. Test with RE2 directly (loop calling RE2)
2. Test with our wrapper (bulk call)
3. Compare results (must be identical)

**Example:**
```cpp
TEST_F(Libre2APITest, PartialMatchBulk_Basic) {
    // ========== TEST DATA (defined ONCE) ==========
    const std::vector<std::string> TEXTS = {"test1", "no", "test2"};
    // ==============================================

    // ========== EXECUTE RE2 (loop, capture results) ==========
    std::vector<bool> results_re2;
    for (const auto& text : TEXTS) {
        results_re2.push_back(RE2::PartialMatch(text, *p->compiled_regex));
    }
    // =========================================================

    // ========== EXECUTE WRAPPER (bulk, capture results) ==========
    std::vector<const char*> text_ptrs;
    std::vector<int> text_lens;
    for (const auto& t : TEXTS) {
        text_ptrs.push_back(t.data());
        text_lens.push_back(t.size());
    }

    std::vector<bool> results_wrapper(TEXTS.size());
    int count = partialMatchBulk(p, text_ptrs.data(), text_lens.data(),
                                  TEXTS.size(), results_wrapper.data());
    // =============================================================

    // ========== COMPARE (CRITICAL: must be identical) ==========
    EXPECT_EQ(results_re2.size(), results_wrapper.size());
    for (size_t i = 0; i < results_re2.size(); i++) {
        EXPECT_EQ(results_re2[i], results_wrapper[i])
            << "Result " << i << " must match";
    }
    // ===========================================================
}
```

---

## NEXT: IMPLEMENT

Ready to implement Sub-Phase 1.2.4 (final sub-phase of Phase 1.2).

**Estimated:** 3-5 hours with current momentum
**Tokens Available:** 773K (77%)

Should we continue? 🚀
