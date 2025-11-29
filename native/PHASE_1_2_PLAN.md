# Phase 1.2 Implementation Plan: Complete RE2 API Coverage

**Date:** 2025-11-29
**Status:** PLANNING
**Goal:** Complete, production-ready C++ wrapper API with full RE2 coverage

---

## EXECUTIVE SUMMARY

Phase 1.2 will deliver **complete RE2 API coverage** in the `libre2::api` layer with:
- ✅ All RE2 matching functions (FullMatch, PartialMatch, Consume, FindAndConsume)
- ✅ All RE2 replacement functions (Replace, GlobalReplace, Extract)
- ✅ All RE2 utility functions (QuoteMeta, pattern info, etc.)
- ✅ Bulk operations (multi-text matching/replacement)
- ✅ Off-heap direct memory API (for Java DirectByteBuffer)
- ✅ Pattern options support (case_sensitive, etc.)
- ✅ Variadic capture groups (via RE2::Arg array)
- ✅ 160+ comprehensive tests
- ✅ Production-ready quality (thread-safe, memory-safe, leak-free)

**NO deferred work. Phase 1.2 is API-complete.**

---

## RE2 API SURFACE ANALYSIS

### Category 1: Matching Functions (MUST EXPOSE)

#### From re2.h analysis:

```cpp
// Basic matching
static bool FullMatch(string_view text, const RE2& re, Args... args);
static bool PartialMatch(string_view text, const RE2& re, Args... args);

// Consume/scan functions
static bool Consume(string_view* input, const RE2& re, Args... args);
static bool FindAndConsume(string_view* input, const RE2& re, Args... args);

// Variable argument versions
static bool FullMatchN(string_view text, const RE2& re, const Arg* args[], int n);
static bool PartialMatchN(string_view text, const RE2& re, const Arg* args[], int n);
static bool ConsumeN(string_view* input, const RE2& re, const Arg* args[], int n);
static bool FindAndConsumeN(string_view* input, const RE2& re, const Arg* args[], int n);
```

**Wrapper Must Provide:**

**Single-Text Variants:**
- `fullMatch` - 0, 1, 2, N captures (via array)
- `partialMatch` - 0, 1, 2, N captures
- `consume` - consume from start, advance input
- `findAndConsume` - find anywhere, advance input

**Bulk Variants (NEW - for ALL matching functions):**
- `fullMatchBulk` - match multiple texts against single pattern
- `partialMatchBulk` - partial match multiple texts
- `consumeBulk` - consume from multiple input streams
- `findAndConsumeBulk` - find-and-consume from multiple inputs

**Off-Heap Variants (for ALL single + bulk):**
- All functions above with `Direct` suffix (e.g., `fullMatchDirect`)
- All functions use jlong addresses (DirectByteBuffer compatible)
- Example: `fullMatchDirectBulk(jlong pattern, jlongArray text_addrs, ...)`

### Category 2: Replacement Functions (MUST EXPOSE)

```cpp
// Replace first match
static bool Replace(string* str, const RE2& re, string_view rewrite);

// Replace all matches
static int GlobalReplace(string* str, const RE2& re, string_view rewrite);

// Extract with rewrite
static bool Extract(string_view text, const RE2& re, string_view rewrite, string* out);
```

**Wrapper Must Provide:**

**Single-Text Variants:**
- `replace` - replace first occurrence
- `replaceAll` - replace all occurrences (GlobalReplace)
- `extract` - extract with rewrite template

**Bulk Variants:**
- `replaceBulk` - replace first in multiple texts
- `replaceAllBulk` - replace all in multiple texts
- `extractBulk` - extract from multiple texts

**Off-Heap Variants:**
- `replaceDirect` - single text, DirectByteBuffer
- `replaceAllDirect` - single text, DirectByteBuffer
- `extractDirect` - single text, DirectByteBuffer
- `replaceDirectBulk` - multiple texts, all DirectByteBuffer
- `replaceAllDirectBulk` - multiple texts, all DirectByteBuffer
- `extractDirectBulk` - multiple texts, all DirectByteBuffer

### Category 3: Utility Functions (MUST EXPOSE)

```cpp
// Pattern utilities
static string QuoteMeta(string_view unquoted);

// Pattern info (member functions)
int NumberOfCapturingGroups() const;
const map<string, int>& NamedCapturingGroups() const;
const map<int, string>& CapturingGroupNames() const;
bool ok() const;
string error() const;
string pattern() const;
int ProgramSize() const;
bool PossibleMatchRange(string* min, string* max, int maxlen) const;
```

**Wrapper Must Provide:**
- `quoteMeta` - escape special characters
- `getPatternInfo` - capturing groups, named groups, error, etc.
- `isPatternValid` - check compilation success

### Category 4: Options (MUST SUPPORT)

```cpp
class RE2::Options {
  bool utf8;                // default true
  bool posix_syntax;        // default false
  bool longest_match;       // default false
  bool log_errors;          // default true
  int64_t max_mem;          // default 8MB
  bool literal;             // default false
  bool never_nl;            // default false
  bool dot_nl;              // default false
  bool never_capture;       // default false
  bool case_sensitive;      // default true
  bool perl_classes;        // default false (posix mode only)
  bool word_boundary;       // default false (posix mode only)
  bool one_line;            // default false (posix mode only)
};
```

**Wrapper Must Provide:**
- Options struct/JSON configuration
- Cache key includes pattern + options hash
- Different options = different cache entry

---

## PHASE 1.2 API DESIGN

### Design Principles

1. **Pointer-Based Primary API** - All functions operate on compiled pattern pointers
2. **Off-Heap Variants** - Separate functions for direct memory (Java DirectByteBuffer)
3. **Bulk Variants** - Multi-text operations for performance
4. **Cache Transparency** - Caching is invisible to caller (same API cached/uncached)
5. **RE2 Compatibility** - Match RE2 semantics exactly (not invented abstractions)
6. **Memory Safety** - Caller manages pattern lifecycle via refcount
7. **Thread Safety** - All functions safe for concurrent access

### API Structure

```cpp
namespace libre2 {
namespace api {

//=============================================================================
// INITIALIZATION (from Phase 1.1 - no changes)
//=============================================================================

void initCache(const std::string& json_config = "");
void shutdownCache();
bool isCacheInitialized();
std::string getMetricsJSON();

//=============================================================================
// PATTERN COMPILATION (from Phase 1.1 - ENHANCED with options)
//=============================================================================

/**
 * Compile pattern with options.
 *
 * Options affect cache key: different options = different cache entry.
 *
 * @param pattern regex pattern string
 * @param options pattern options (JSON or struct)
 * @param error_out compilation error message (empty if success)
 * @return compiled pattern pointer, or nullptr on error
 */
cache::RE2Pattern* compilePattern(
    const char* pattern,
    const char* options_json,  // JSON options
    char** error_out);

// Overload with default options (case_sensitive=true, utf8=true)
cache::RE2Pattern* compilePattern(
    const char* pattern,
    bool case_sensitive,
    char** error_out);

void releasePattern(cache::RE2Pattern* pattern);

//=============================================================================
// MATCHING FUNCTIONS (from Phase 1.1 - ENHANCED)
//=============================================================================

// Full match - 0 captures
bool fullMatch(cache::RE2Pattern* pattern, const char* text, int text_len);

// Full match - 1 capture
bool fullMatch(
    cache::RE2Pattern* pattern,
    const char* text, int text_len,
    char* capture1_out, int capture1_max_len);

// Full match - 2 captures
bool fullMatch(
    cache::RE2Pattern* pattern,
    const char* text, int text_len,
    char* capture1_out, int capture1_max_len,
    char* capture2_out, int capture2_max_len);

// Full match - N captures (via output array)
bool fullMatchN(
    cache::RE2Pattern* pattern,
    const char* text, int text_len,
    char** captures_out,    // Array of capture buffers
    int* capture_lens,      // Array of buffer lengths
    int num_captures);

// Partial match - same overloads as fullMatch
bool partialMatch(cache::RE2Pattern* pattern, const char* text, int text_len);
bool partialMatch(cache::RE2Pattern* pattern, const char* text, int text_len,
                  char* capture1_out, int capture1_max_len);
bool partialMatch(cache::RE2Pattern* pattern, const char* text, int text_len,
                  char* capture1_out, int capture1_max_len,
                  char* capture2_out, int capture2_max_len);
bool partialMatchN(cache::RE2Pattern* pattern, const char* text, int text_len,
                   char** captures_out, int* capture_lens, int num_captures);

//=============================================================================
// CONSUME/SCAN FUNCTIONS (NEW in Phase 1.2)
//=============================================================================

/**
 * Consume from start of input, advance input pointer on match.
 *
 * Like Perl's m//gc operator.
 *
 * @param input_inout pointer to input text pointer (advanced on match)
 * @param input_len_inout pointer to input length (reduced on match)
 * @param pattern compiled pattern
 * @return true if match at start, false otherwise
 */
bool consume(
    const char** input_inout,
    int* input_len_inout,
    cache::RE2Pattern* pattern);

// Consume with 1 capture
bool consume(
    const char** input_inout, int* input_len_inout,
    cache::RE2Pattern* pattern,
    char* capture1_out, int capture1_max_len);

// Consume with N captures
bool consumeN(
    const char** input_inout, int* input_len_inout,
    cache::RE2Pattern* pattern,
    char** captures_out, int* capture_lens, int num_captures);

/**
 * Find pattern anywhere in input, advance past match.
 *
 * Like Perl's m//g operator.
 *
 * @param input_inout pointer to input text pointer (advanced past match)
 * @param input_len_inout pointer to input length (reduced by consumed amount)
 * @param pattern compiled pattern
 * @return true if match found, false otherwise
 */
bool findAndConsume(
    const char** input_inout,
    int* input_len_inout,
    cache::RE2Pattern* pattern);

// FindAndConsume with captures
bool findAndConsume(
    const char** input_inout, int* input_len_inout,
    cache::RE2Pattern* pattern,
    char* capture1_out, int capture1_max_len);

bool findAndConsumeN(
    const char** input_inout, int* input_len_inout,
    cache::RE2Pattern* pattern,
    char** captures_out, int* capture_lens, int num_captures);

//=============================================================================
// REPLACEMENT FUNCTIONS (NEW in Phase 1.2)
//=============================================================================

/**
 * Replace first occurrence of pattern with rewrite string.
 *
 * Modifies text in-place. Returns new text in result_out.
 *
 * @param text input text
 * @param text_len input length
 * @param pattern compiled pattern
 * @param rewrite rewrite template (supports \\0, \\1, \\2, etc.)
 * @param result_out output buffer for result
 * @param result_max_len maximum result length
 * @return true if replacement occurred, false otherwise
 */
bool replace(
    const char* text, int text_len,
    cache::RE2Pattern* pattern,
    const char* rewrite,
    char* result_out, int result_max_len);

/**
 * Replace all occurrences of pattern with rewrite string.
 *
 * @param text input text
 * @param text_len input length
 * @param pattern compiled pattern
 * @param rewrite rewrite template
 * @param result_out output buffer
 * @param result_max_len max output length
 * @return number of replacements made
 */
int replaceAll(
    const char* text, int text_len,
    cache::RE2Pattern* pattern,
    const char* rewrite,
    char* result_out, int result_max_len);

/**
 * Extract with rewrite template.
 *
 * Like replace but only outputs rewritten match, not entire text.
 *
 * @param text input text
 * @param text_len input length
 * @param pattern compiled pattern
 * @param rewrite rewrite template
 * @param result_out output buffer
 * @param result_max_len max output length
 * @return true if extraction occurred, false otherwise
 */
bool extract(
    const char* text, int text_len,
    cache::RE2Pattern* pattern,
    const char* rewrite,
    char* result_out, int result_max_len);

//=============================================================================
// UTILITY FUNCTIONS (NEW in Phase 1.2)
//=============================================================================

/**
 * Quote/escape special regex characters.
 *
 * Thread-safe, stateless (no caching).
 *
 * @param text input text
 * @param text_len input length
 * @param result_out output buffer
 * @param result_max_len max output length
 * @return length of quoted string, or -1 if buffer too small
 */
int quoteMeta(
    const char* text, int text_len,
    char* result_out, int result_max_len);

/**
 * Get pattern information.
 *
 * Returns JSON with:
 * {
 *   "valid": true,
 *   "capturing_groups": 2,
 *   "named_groups": {"name": 1, "value": 2},
 *   "group_names": {1: "name", 2: "value"},
 *   "program_size": 512,
 *   "error": "",
 *   "pattern": "(\\w+):(\\d+)"
 * }
 *
 * @param pattern compiled pattern
 * @param json_out output buffer for JSON
 * @param json_max_len max JSON length
 * @return length of JSON, or -1 if buffer too small
 */
int getPatternInfo(
    cache::RE2Pattern* pattern,
    char* json_out, int json_max_len);

/**
 * Check if pattern is valid.
 *
 * @param pattern compiled pattern
 * @return true if valid, false if compilation failed
 */
bool isPatternValid(cache::RE2Pattern* pattern);

//=============================================================================
// BULK OPERATIONS (NEW in Phase 1.2)
//=============================================================================

/**
 * Bulk partial match - match multiple texts against single pattern.
 *
 * Performance optimization: pattern compiled once, matched N times.
 *
 * @param pattern compiled pattern
 * @param texts array of text pointers
 * @param text_lens array of text lengths
 * @param num_texts number of texts
 * @param results_out output array (true/false for each text)
 * @return number of matches
 */
int partialMatchBulk(
    cache::RE2Pattern* pattern,
    const char** texts,
    const int* text_lens,
    int num_texts,
    bool* results_out);

/**
 * Bulk replace - replace in multiple texts.
 *
 * @param pattern compiled pattern
 * @param texts array of text pointers
 * @param text_lens array of text lengths
 * @param num_texts number of texts
 * @param rewrite rewrite template
 * @param results_out output buffers (array of char*)
 * @param result_max_lens max lengths for each result
 * @return number of replacements made across all texts
 */
int replaceBulk(
    cache::RE2Pattern* pattern,
    const char** texts,
    const int* text_lens,
    int num_texts,
    const char* rewrite,
    char** results_out,
    const int* result_max_lens);

/**
 * Bulk replace all - replace all in multiple texts.
 *
 * @return total number of replacements across all texts
 */
int replaceAllBulk(
    cache::RE2Pattern* pattern,
    const char** texts,
    const int* text_lens,
    int num_texts,
    const char* rewrite,
    char** results_out,
    const int* result_max_lens);

//=============================================================================
// OFF-HEAP DIRECT MEMORY API (NEW in Phase 1.2)
//=============================================================================

/**
 * Full match with direct memory access (for Java DirectByteBuffer).
 *
 * NO string copies - works directly on memory address.
 *
 * @param pattern compiled pattern
 * @param text_ptr memory address of text buffer
 * @param text_len length of text
 * @return true if full match, false otherwise
 */
bool fullMatchDirect(
    cache::RE2Pattern* pattern,
    const void* text_ptr,
    int text_len);

// Direct with 1 capture
bool fullMatchDirect(
    cache::RE2Pattern* pattern,
    const void* text_ptr, int text_len,
    void* capture1_ptr, int capture1_max_len, int* capture1_actual_len);

// Direct with N captures
bool fullMatchDirectN(
    cache::RE2Pattern* pattern,
    const void* text_ptr, int text_len,
    void** capture_ptrs, const int* capture_max_lens, int* capture_actual_lens, int num_captures);

// Partial match direct variants (same signatures as fullMatchDirect)
bool partialMatchDirect(cache::RE2Pattern* pattern, const void* text_ptr, int text_len);
bool partialMatchDirect(cache::RE2Pattern* pattern, const void* text_ptr, int text_len,
                        void* capture1_ptr, int capture1_max_len, int* capture1_actual_len);
bool partialMatchDirectN(cache::RE2Pattern* pattern, const void* text_ptr, int text_len,
                         void** capture_ptrs, const int* capture_max_lens, int* capture_actual_lens, int num_captures);

/**
 * Bulk direct memory operations.
 *
 * @param pattern compiled pattern
 * @param text_ptrs array of memory addresses
 * @param text_lens array of lengths
 * @param num_texts number of texts
 * @param results_out match results
 * @return number of matches
 */
int partialMatchDirectBulk(
    cache::RE2Pattern* pattern,
    const void** text_ptrs,
    const int* text_lens,
    int num_texts,
    bool* results_out);

}  // namespace api
}  // namespace libre2
```

---

## IMPLEMENTATION SUB-PHASES

To avoid overwhelming complexity, we'll implement Phase 1.2 in **4 sub-phases**:

### Sub-Phase 1.2.1: Consume/Scan Functions
**Duration:** 1-2 days
**Files:** `libre2_api.h/cpp`, `libre2_api_test.cpp`

**Deliverables:**
- `consume` (0, 1, N captures)
- `findAndConsume` (0, 1, N captures)
- 20+ tests for consume/scan behavior

**Why First:** Builds on Phase 1.1 matching foundation

---

### Sub-Phase 1.2.2: Replacement Functions
**Duration:** 1-2 days
**Files:** `libre2_api.h/cpp`, `libre2_api_test.cpp`

**Deliverables:**
- `replace` (first occurrence)
- `replaceAll` (all occurrences)
- `extract` (rewrite template)
- 20+ tests for replacement

**Why Second:** Independent of consume, extends API surface

---

### Sub-Phase 1.2.3: Utility & Options
**Duration:** 1-2 days
**Files:** `libre2_api.h/cpp`, `libre2_api_test.cpp`, `cache/pattern_cache.h/cpp`

**Deliverables:**
- `quoteMeta` utility
- `getPatternInfo` (capturing groups, named groups, etc.)
- Pattern options support (case_sensitive, posix_syntax, etc.)
- Cache keying updated (pattern + options hash)
- 20+ tests for utilities and options

**Why Third:** Requires cache layer modifications for options

---

### Sub-Phase 1.2.4: Bulk & Off-Heap
**Duration:** 2-3 days
**Files:** `libre2_api.h/cpp`, `libre2_api_test.cpp`

**Deliverables:**
- Bulk operations (matchBulk, replaceBulk, etc.)
- Off-heap direct memory API (fullMatchDirect, partialMatchDirect, etc.)
- Direct bulk operations
- 40+ tests for bulk and off-heap

**Why Last:** Most complex, requires all prior functions working

---

## TESTING STRATEGY

### Test Coverage Requirements

**Total Tests:** 320+ (breakdown by category)

1. **Basic Matching:** 40 tests (was 20)
   - FullMatch/PartialMatch edge cases
   - Empty strings, huge strings (1MB+)
   - Unicode handling (UTF-8, emoji, etc.)
   - Case sensitivity modes
   - Thread safety (concurrent matching)

2. **Consume/Scan:** 30 tests (was 20)
   - Consume from start (0, 1, 2, N captures)
   - FindAndConsume anywhere (0, 1, 2, N captures)
   - Input advancement verification
   - Empty match handling (zero-width matches)
   - Multiple consume loops (extract all)
   - Consume with no match (input unchanged)

3. **Replacement:** 30 tests (was 20)
   - Replace first (simple, with captures, named groups)
   - ReplaceAll (multiple occurrences, non-overlapping)
   - Extract with rewrite (capture references)
   - Rewrite template syntax (\\0, \\1, \\2, etc.)
   - Empty/no-match cases (no modification)
   - Large text replacement (performance)

4. **Utility:** 40 tests (was 20)
   - QuoteMeta special characters (all regex chars)
   - PatternInfo (groups, names, error, size)
   - Pattern validity checks (valid vs invalid)
   - Named capture groups (extraction, mapping)
   - Program size verification
   - Error messages (detailed, parseable)
   - Pattern string retrieval

5. **Options:** 40 tests (was 20)
   - Case-sensitive vs insensitive (10 tests)
   - UTF-8 vs Latin1 encoding (10 tests)
   - POSIX syntax mode vs default
   - Longest match vs first match
   - All 12 boolean options tested
   - max_mem limits (OOM scenarios)
   - Cache keying (different options = different cache entry)
   - Options combinations (case-insensitive + POSIX, etc.)

6. **Bulk Operations:** 50 tests (was 20)
   - Bulk match (small: 10 texts, large: 1000 texts)
   - Bulk replace (multiple texts, varying sizes)
   - Bulk performance (faster than loop)
   - Empty array handling (zero texts)
   - All-or-nothing error semantics
   - Memory limits (huge batches)
   - Concurrent bulk calls (thread safety)

7. **Off-Heap:** 60 tests (was 40)
   - Direct memory match (0, 1, 2, N captures)
   - Direct bulk operations (bulk + off-heap)
   - Memory safety (bounds checking, buffer overflow)
   - Capture overflow handling (buffer too small)
   - Java DirectByteBuffer simulation
   - Large buffers (1GB+, stress test)
   - Misaligned addresses (alignment safety)
   - Null pointer handling

8. **Property-Based:** 30 tests (NEW)
   - Random pattern generation (valid patterns only)
   - Random text generation (ASCII, UTF-8, binary)
   - QuickCheck-style: match(x) ⇒ match(x) (idempotent)
   - QuickCheck-style: replace(replace(x)) ≠ replace(x) for most cases
   - Fuzz testing (random bytes, invalid UTF-8)
   - Invariant testing (pattern cache hit ≡ pattern compile)

9. **Integration:** 40 tests (was 20)
   - Cache + options + bulk (combined scenarios)
   - Concurrent access (50 threads, 1000 iterations each)
   - Memory leak verification (ASan, 10K iteration cycles)
   - Thread safety (TSan, stress test)
   - Error propagation end-to-end (all error paths)
   - Performance regression (benchmarks as tests)
   - Resource cleanup (patterns, buffers, handles)
   - Cross-feature interaction (consume + replace + bulk)

### Test Quality Requirements

- ✅ All tests must pass (100%)
- ✅ Zero memory leaks (AddressSanitizer)
- ✅ Zero data races (ThreadSanitizer)
- ✅ Thread-safe concurrent access verified
- ✅ Performance benchmarks (bulk vs single)

---

## CACHE LAYER MODIFICATIONS

### Pattern + Options Cache Key

**Current:** Cache key = `hash(pattern_string + case_sensitive)`
**Phase 1.2:** Cache key = `hash(pattern_string + options_hash)`

```cpp
// PatternCache modification
struct PatternCacheKey {
    std::string pattern;
    uint64_t options_hash;  // Hash of all RE2::Options

    uint64_t hash() const {
        return MurmurHash3(pattern + std::to_string(options_hash));
    }
};
```

### Options Struct

```cpp
struct PatternOptions {
    bool utf8 = true;
    bool posix_syntax = false;
    bool longest_match = false;
    bool case_sensitive = true;
    bool literal = false;
    bool never_nl = false;
    bool dot_nl = false;
    bool never_capture = false;
    bool perl_classes = false;
    bool word_boundary = false;
    bool one_line = false;
    int64_t max_mem = 8388608;  // 8MB default

    uint64_t hash() const;  // Hash all fields
    RE2::Options toRE2Options() const;  // Convert to RE2::Options
    static PatternOptions fromJson(const std::string& json);
};
```

---

## MEMORY MANAGEMENT STRATEGY

### Pointer-Based API

- ✅ All functions operate on `cache::RE2Pattern*`
- ✅ Caller MUST call `releasePattern()` when done
- ✅ Refcount decremented atomically
- ✅ Pattern eligible for eviction when refcount=0

### Off-Heap API

- ✅ NO string copies (zero-copy)
- ✅ Direct memory address + length
- ✅ Works with Java `DirectByteBuffer`
- ✅ Caller ensures memory validity during call
- ✅ Results written to pre-allocated buffers

### Bulk API

- ✅ Single pattern pointer shared across all texts
- ✅ Refcount incremented ONCE (not per text)
- ✅ Results written to pre-allocated arrays
- ✅ Caller manages result buffer sizes

---

## FILES TO CREATE/MODIFY

### New Files
```
native/docs/
├── PHASE_1_2_API_REFERENCE.md    (NEW: Complete API documentation)
└── PHASE_1_2_EXAMPLES.md         (NEW: Usage examples)
```

### Modified Files
```
native/wrapper/
├── libre2_api.h                  (MODIFY: Add ~30 new functions)
├── libre2_api.cpp                (MODIFY: Implement ~30 functions)

native/wrapper/cache/
├── pattern_cache.h               (MODIFY: Support options in cache key)
├── pattern_cache.cpp             (MODIFY: Hash options, store in key)
├── deferred_cache.h              (MODIFY: RE2Pattern stores options)

native/tests/
├── libre2_api_test.cpp           (MODIFY: Add 140+ new tests)
└── test_fixtures/                (NEW: Test data for bulk operations)
```

---

## SUCCESS CRITERIA

Phase 1.2 is complete when:

### Functional
- ✅ All RE2 static functions exposed (FullMatch, PartialMatch, Consume, Replace, etc.)
- ✅ All RE2 options supported (12 boolean flags + max_mem)
- ✅ All capture group scenarios work (0, 1, 2, N captures)
- ✅ Bulk operations implemented and tested
- ✅ Off-heap direct memory API working
- ✅ Pattern options cached correctly (different options = different cache entry)

### Quality
- ✅ 160+ tests passing (100%)
- ✅ Zero memory leaks (AddressSanitizer clean)
- ✅ Zero data races (ThreadSanitizer clean)
- ✅ Thread-safe concurrent access (multi-threaded tests pass)
- ✅ Performance validated (bulk faster than loop)

### Documentation
- ✅ All functions documented (Doxygen comments)
- ✅ API reference complete
- ✅ Usage examples for each function category
- ✅ Migration guide from re2java

### Production-Ready
- ✅ Error handling comprehensive
- ✅ Null-safe (all pointer parameters checked)
- ✅ Buffer overflow protection
- ✅ Build warnings zero
- ✅ Code coverage >90%

---

## ESTIMATED TIMELINE

| Sub-Phase | Duration | Tests | Complexity |
|-----------|----------|-------|------------|
| 1.2.1 Consume/Scan | 2-3 days | 30 | Medium |
| 1.2.2 Replacement | 2-3 days | 30 | Medium |
| 1.2.3 Utility/Options | 2-3 days | 80 | High (cache changes) |
| 1.2.4 Bulk/Off-Heap | 3-5 days | 110 | Very High (complexity) |
| Property/Fuzz Testing | 2 days | 30 | Medium |
| Integration Testing | 2 days | 40 | High |
| Documentation | 1 day | - | Low |
| **TOTAL** | **14-20 days** | **320+** | - |

---

## RISKS & MITIGATION

### Risk 1: Options Cache Key Explosion
**Problem:** Too many option combinations = cache ineffective
**Mitigation:** Monitor cache hit rate, consider default options fast-path

### Risk 2: Off-Heap Buffer Overflows
**Problem:** Direct memory writes could overflow buffers
**Mitigation:** Strict bounds checking, return actual lengths, comprehensive tests

### Risk 3: Bulk Operations Complexity
**Problem:** N texts × M operations = difficult to test all scenarios
**Mitigation:** Property-based testing, fuzz testing, ASan/TSan validation

### Risk 4: Thread Safety in Bulk
**Problem:** Concurrent bulk calls on same pattern
**Mitigation:** Refcount increment BEFORE bulk loop, pattern immutable during operation

---

## NEXT STEPS

1. **Review this plan** - Your approval before coding
2. **Start Sub-Phase 1.2.1** - Consume/scan functions
3. **Iterative development** - Complete one sub-phase, test, commit before next
4. **Continuous testing** - Run full test suite after each function
5. **Documentation as we go** - Document each function as implemented

---

## DESIGN DECISIONS (APPROVED)

1. **API Design:**
   - ✅ Use jlong for pointers (matches existing JNI pattern)
   - ✅ Use jlong for DirectByteBuffer addresses (standard practice)
   - ✅ JSON for options configuration (Java will serialize to JSON)
   - ✅ C-compatible signatures (no std::string in public API)

2. **Capture Groups:**
   - ✅ Support BOTH fixed overloads (0,1,2) AND array for N captures
   - ✅ No artificial limit on N captures (match RE2's unlimited support)
   - ✅ Common case: 0-2 captures (fast path), rare case: N captures (array)

3. **Off-Heap Memory:**
   - ✅ Use jlong everywhere (not void*)
   - ✅ Simple returns on-heap (bool, int) - cheap to return
   - ✅ Complex returns off-heap (captures, replace results) - write to DirectByteBuffer
   - ✅ Rule: If result >1KB potential, use off-heap output buffer

4. **Bulk Operations:**
   - ✅ NO hardcoded limits (caller responsible for batch size)
   - ✅ Document memory implications (warn about huge batches)
   - ✅ All-or-nothing semantics (no partial results on error)
   - ✅ Return -1 on error, >=0 on success (with count)

5. **Testing:**
   - ✅ 320+ comprehensive tests (expanded from 160)
   - ✅ Property-based testing (QuickCheck-style)
   - ✅ Fuzz testing (random inputs)
   - ✅ Performance regression tests
   - ✅ "Test the hell out of this" - exhaustive coverage

---

📊 **PLANNING SESSION METRICS**
```
Tokens Used:        93,437 / 1,000,000 (9.3%)
Cost Estimate:      ~$0.28 USD
Phase:              1.2 Planning
Status:             AWAITING APPROVAL
Document:           /Users/johnny/Development/libre2-java/native/PHASE_1_2_PLAN.md
Next Action:        Your review and approval
```

---

**Ready for your review!** 🚀
