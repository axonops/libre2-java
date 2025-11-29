# Pattern Options Architecture Design

**Date:** 2025-11-29
**Sub-Phase:** 1.2.3b
**Status:** DESIGN DOCUMENT

---

## PROBLEM STATEMENT

Currently, our cache key is: `hash(pattern_string + case_sensitive)`

This means:
- ❌ Same pattern with different options returns same cached pattern (BUG)
- ❌ Can't support UTF-8 vs Latin1 encoding
- ❌ Can't support POSIX syntax mode
- ❌ Can't support other RE2::Options flags

**Example bug:**
```cpp
// These should be different cache entries but currently aren't:
compile("test", {case_sensitive: true});   // Entry 1
compile("test", {case_sensitive: false});  // Returns Entry 1 (WRONG!)
```

**Solution:** Cache key must include ALL options.

---

## RE2::OPTIONS COMPLETE LIST

From re2/re2.h (lines 620-692):

### Boolean Options (11 flags)
```cpp
bool posix_syntax;      // default: false
bool longest_match;     // default: false
bool log_errors;        // default: true (we override to false)
bool literal;           // default: false
bool never_nl;          // default: false
bool dot_nl;            // default: false
bool never_capture;     // default: false
bool case_sensitive;    // default: true (ALREADY SUPPORTED)
bool perl_classes;      // default: false (POSIX mode only)
bool word_boundary;     // default: false (POSIX mode only)
bool one_line;          // default: false (POSIX mode only)
```

### Encoding Option (enum)
```cpp
enum Encoding {
    EncodingUTF8 = 1,     // default
    EncodingLatin1 = 2
};
```

### Memory Limit
```cpp
int64_t max_mem;        // default: 8<<20 (8MB)
```

**Total:** 11 booleans + 1 enum + 1 int64 = **13 options**

---

## CACHE KEY STRATEGY

### Current Cache Key
```cpp
uint64_t key = MurmurHash3(pattern_string + case_sensitive_flag);
```

### New Cache Key (with options)
```cpp
struct CacheKey {
    std::string pattern;
    uint64_t options_hash;  // Hash of ALL 13 options
};

uint64_t final_key = MurmurHash3(pattern + std::to_string(options_hash));
```

### Options Hash Calculation
```cpp
uint64_t hashOptions(const RE2::Options& opts) {
    // Hash all 13 options into single uint64_t
    uint64_t hash = 0;

    // Boolean flags (11 bits)
    hash |= (opts.posix_syntax()     ? (1ULL << 0) : 0);
    hash |= (opts.longest_match()    ? (1ULL << 1) : 0);
    hash |= (opts.log_errors()       ? (1ULL << 2) : 0);
    hash |= (opts.literal()          ? (1ULL << 3) : 0);
    hash |= (opts.never_nl()         ? (1ULL << 4) : 0);
    hash |= (opts.dot_nl()           ? (1ULL << 5) : 0);
    hash |= (opts.never_capture()    ? (1ULL << 6) : 0);
    hash |= (opts.case_sensitive()   ? (1ULL << 7) : 0);
    hash |= (opts.perl_classes()     ? (1ULL << 8) : 0);
    hash |= (opts.word_boundary()    ? (1ULL << 9) : 0);
    hash |= (opts.one_line()         ? (1ULL << 10) : 0);

    // Encoding (2 bits)
    hash |= ((uint64_t)opts.encoding() << 11);

    // max_mem (upper 51 bits) - use lower 32 bits of max_mem
    hash |= ((uint64_t)(opts.max_mem() & 0xFFFFFFFF) << 13);

    return hash;
}
```

**Benefits:**
- Fast (bitwise operations)
- Compact (single uint64_t)
- Deterministic (same options = same hash)
- Collision-resistant (13 independent fields)

---

## PATTERN OPTIONS STRUCT

### JSON Format (What Java Will Send)
```json
{
  "case_sensitive": true,
  "encoding": "UTF8",
  "posix_syntax": false,
  "longest_match": false,
  "literal": false,
  "never_nl": false,
  "dot_nl": false,
  "never_capture": false,
  "perl_classes": false,
  "word_boundary": false,
  "one_line": false,
  "max_mem": 8388608
}
```

All fields optional - missing fields use defaults.

### C++ Struct
```cpp
namespace libre2 {
namespace api {

struct PatternOptions {
    // Boolean flags (11)
    bool posix_syntax = false;
    bool longest_match = false;
    bool log_errors = false;  // We always disable logging
    bool literal = false;
    bool never_nl = false;
    bool dot_nl = false;
    bool never_capture = false;
    bool case_sensitive = true;
    bool perl_classes = false;
    bool word_boundary = false;
    bool one_line = false;

    // Encoding
    bool utf8 = true;  // true = UTF8, false = Latin1

    // Memory limit
    int64_t max_mem = 8388608;  // 8MB default

    // Convert to RE2::Options
    RE2::Options toRE2Options() const {
        RE2::Options opts;
        opts.set_posix_syntax(posix_syntax);
        opts.set_longest_match(longest_match);
        opts.set_log_errors(log_errors);  // Always false for us
        opts.set_literal(literal);
        opts.set_never_nl(never_nl);
        opts.set_dot_nl(dot_nl);
        opts.set_never_capture(never_capture);
        opts.set_case_sensitive(case_sensitive);
        opts.set_perl_classes(perl_classes);
        opts.set_word_boundary(word_boundary);
        opts.set_one_line(one_line);
        opts.set_encoding(utf8 ? RE2::Options::EncodingUTF8 : RE2::Options::EncodingLatin1);
        opts.set_max_mem(max_mem);
        return opts;
    }

    // Compute hash for cache key
    uint64_t hash() const;

    // Parse from JSON
    static PatternOptions fromJson(const std::string& json);

    // Equality (for testing)
    bool operator==(const PatternOptions& other) const = default;
};

}  // namespace api
}  // namespace libre2
```

---

## CACHE LAYER MODIFICATIONS

### Change 1: RE2Pattern Stores Options

**Current:**
```cpp
struct RE2Pattern {
    std::shared_ptr<RE2> compiled_regex;
    std::string pattern_string;
    bool case_sensitive;
    std::atomic<uint32_t> refcount{0};
    // ...
};
```

**New:**
```cpp
struct RE2Pattern {
    std::shared_ptr<RE2> compiled_regex;
    std::string pattern_string;
    bool case_sensitive;  // DEPRECATED (kept for backward compat)
    api::PatternOptions options;  // NEW: Full options
    std::atomic<uint32_t> refcount{0};
    // ...
};
```

### Change 2: Cache Key Includes Options Hash

**Current (pattern_cache.cpp:191):**
```cpp
uint64_t PatternCache::makeKey(const std::string& pattern, bool case_sensitive) const {
    std::string key_string = pattern + (case_sensitive ? ":CS" : ":CI");
    return murmurHash3(key_string.c_str(), key_string.size());
}
```

**New:**
```cpp
uint64_t PatternCache::makeKey(
    const std::string& pattern,
    const api::PatternOptions& options) const {

    // Hash pattern string
    uint64_t pattern_hash = murmurHash3(pattern.c_str(), pattern.size());

    // Hash options
    uint64_t options_hash = options.hash();

    // Combine (XOR or concatenate)
    return pattern_hash ^ options_hash;
}
```

### Change 3: Update getOrCompile Signature

**Current:**
```cpp
std::shared_ptr<RE2Pattern> getOrCompile(
    const std::string& pattern_string,
    bool case_sensitive,  // DEPRECATED
    PatternCacheMetrics& metrics,
    std::string& error_msg);
```

**New:**
```cpp
std::shared_ptr<RE2Pattern> getOrCompile(
    const std::string& pattern_string,
    const api::PatternOptions& options,  // NEW
    PatternCacheMetrics& metrics,
    std::string& error_msg);
```

---

## API CHANGES

### New compilePattern Overload

**Keep existing for backward compat:**
```cpp
cache::RE2Pattern* compilePattern(
    const std::string& pattern,
    bool case_sensitive,  // Simple case
    std::string& error_out);
```

**Add new overload with full options:**
```cpp
cache::RE2Pattern* compilePattern(
    const std::string& pattern,
    const std::string& options_json,  // Full options as JSON
    std::string& error_out);
```

**Both map to:**
```cpp
cache::RE2Pattern* compilePatternInternal(
    const std::string& pattern,
    const PatternOptions& options,
    std::string& error_out);
```

---

## IMPLEMENTATION PLAN

### Step 1: Create PatternOptions Struct
**File:** `wrapper/pattern_options.h` (new)
**File:** `wrapper/pattern_options.cpp` (new)

- Define struct with all 13 options
- Implement `toRE2Options()`
- Implement `hash()` (bitwise packing)
- Implement `fromJson()` (parse JSON string)
- Implement `operator==` for testing

### Step 2: Modify RE2Pattern
**File:** `wrapper/cache/deferred_cache.h`

- Add `PatternOptions options` field
- Keep `bool case_sensitive` for backward compat (deprecated)

### Step 3: Modify PatternCache
**File:** `wrapper/cache/pattern_cache.h`
**File:** `wrapper/cache/pattern_cache.cpp`

- Change `makeKey` to accept PatternOptions
- Update `getOrCompile` to accept PatternOptions
- Update `compilePattern` helper to use options
- Store options in RE2Pattern

### Step 4: Update libre2_api
**File:** `wrapper/libre2_api.h`
**File:** `wrapper/libre2_api.cpp`

- Add `compilePattern` overload with JSON options
- Keep existing overload (maps to default options)
- Parse JSON → PatternOptions struct
- Pass options to cache layer

### Step 5: Comprehensive Tests
**File:** `tests/libre2_api_test.cpp`

30+ tests covering:
- Each of 11 boolean options (on vs off)
- UTF8 vs Latin1 encoding
- max_mem limits
- Option combinations
- Cache key uniqueness (different options = different cache entry)
- All with RE2 comparison pattern

---

## BACKWARD COMPATIBILITY

### Existing Code Continues to Work

**Current code:**
```cpp
RE2Pattern* p = compilePattern("test", true, error);  // case_sensitive=true
```

**Still works** - internally converted to:
```cpp
PatternOptions opts;
opts.case_sensitive = true;
// All other options use defaults
```

**No breaking changes to existing API.**

---

## CACHE KEY UNIQUENESS GUARANTEE

Different options configurations MUST produce different cache keys:

```cpp
// These must be DIFFERENT cache entries:
compile("test", {case_sensitive: true});   // Key: hash1
compile("test", {case_sensitive: false});  // Key: hash2  (hash1 ≠ hash2)

compile("test", {utf8: true});             // Key: hash3
compile("test", {utf8: false});            // Key: hash4  (hash3 ≠ hash4)

compile("test", {posix_syntax: true});     // Key: hash5
compile("test", {posix_syntax: false});    // Key: hash6  (hash5 ≠ hash6)
```

**Test:** Create pattern with every option different, verify cache misses.

---

## TESTING STRATEGY

### Test Categories

1. **Option Flag Tests** (11 tests - one per boolean)
   - Test each flag: true vs false
   - Verify RE2 behavior matches with that option
   - Example: case_sensitive true vs false

2. **Encoding Tests** (5 tests)
   - UTF8 vs Latin1
   - Unicode characters with both encodings
   - Verify RE2 comparison

3. **Memory Limit Tests** (3 tests)
   - Default max_mem
   - Large pattern with low max_mem (might fail to compile)
   - Verify RE2 behavior

4. **Combination Tests** (10 tests)
   - Multiple options together
   - Verify cache key uniqueness
   - Example: case_insensitive + POSIX + Latin1

5. **Cache Key Tests** (10 tests)
   - Different options = different cache entries
   - Same options = same cache entry (cache hit)
   - Options hash collision resistance

**Total:** ~40 tests for options

---

## RISKS & MITIGATION

### Risk 1: Options Hash Collisions
**Problem:** Two different option combinations produce same hash
**Mitigation:**
- Use all 64 bits of hash space
- Boolean flags in separate bits (no overlap)
- Test for collisions (try all 2^11 boolean combinations)

### Risk 2: Breaking Existing Code
**Problem:** Changing API signature breaks existing tests
**Mitigation:**
- Keep existing `compilePattern(pattern, case_sensitive, error)` signature
- Add new overload for JSON options
- Both map to internal function with PatternOptions

### Risk 3: JSON Parsing Complexity
**Problem:** Invalid JSON crashes or throws
**Mitigation:**
- Use existing nlohmann::json (already in project)
- Catch exceptions, return error
- Validate all fields before parsing

### Risk 4: Cache Performance
**Problem:** Options hash adds overhead to cache lookups
**Mitigation:**
- Hash computation is fast (bitwise ops)
- Cache key is still single uint64_t
- No performance impact

---

## IMPLEMENTATION ORDER

1. ✅ Create PatternOptions struct (pattern_options.h/cpp)
2. ✅ Implement hash(), toRE2Options(), fromJson()
3. ✅ Add PatternOptions field to RE2Pattern
4. ✅ Modify PatternCache::makeKey to use options
5. ✅ Modify PatternCache::getOrCompile to accept options
6. ✅ Update compilePattern helper in pattern_cache.cpp
7. ✅ Add compilePattern overload in libre2_api.h
8. ✅ Implement new overload in libre2_api.cpp
9. ✅ Add 40 tests (all with RE2 comparison)
10. ✅ Update SESSION_PROGRESS.md and commit

---

## ESTIMATED IMPACT

### Files to Create
- `wrapper/pattern_options.h` (new, ~150 lines)
- `wrapper/pattern_options.cpp` (new, ~200 lines)

### Files to Modify
- `wrapper/cache/deferred_cache.h` - Add options field (~5 lines)
- `wrapper/cache/pattern_cache.h` - Update signatures (~10 lines)
- `wrapper/cache/pattern_cache.cpp` - Implement new logic (~50 lines)
- `wrapper/libre2_api.h` - Add overload (~15 lines)
- `wrapper/libre2_api.cpp` - Implement overload (~30 lines)
- `tests/libre2_api_test.cpp` - Add 40 tests (~1000 lines)

### Total Changes
- New code: ~1,400 lines
- Modified code: ~100 lines
- Tests: 40 new tests (218 → 258)

**Estimated duration:** 2-3 hours (careful implementation)

---

## SUCCESS CRITERIA

Sub-Phase 1.2.3b complete when:

- ✅ PatternOptions struct working (fromJson, toRE2Options, hash)
- ✅ Cache key includes options hash
- ✅ Same pattern + different options = different cache entries (verified)
- ✅ All 13 RE2::Options supported
- ✅ Backward compatible (existing code works)
- ✅ 40+ tests passing (all with RE2 comparison)
- ✅ 100% tests passing overall
- ✅ Zero behavioral differences (wrapper = RE2)
- ✅ SESSION_PROGRESS.md updated
- ✅ Committed with clear message

---

## NEXT STEPS

1. Review this design document
2. Approve architecture
3. Implement in order (struct → cache → API → tests)
4. Commit when complete

**Ready to proceed?** 🚀
