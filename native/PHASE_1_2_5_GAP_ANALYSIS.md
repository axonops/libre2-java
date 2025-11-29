# Phase 1.2.5 - Wrapper API Gap Analysis

**Date:** 2025-11-29
**Purpose:** Compare libre2 wrapper API against RE2 public API
**Result:** CRITICAL GAPS IDENTIFIED - Wrapper is incomplete

---

## EXECUTIVE SUMMARY

**Status:** 🔴 **CRITICAL - Wrapper API is NOT like-for-like with RE2**

### Key Findings:

1. **CRITICAL:** Capture groups hardcoded to 0/1/2 - RE2 supports **unlimited** via variadic templates
2. **MISSING:** 30+ core RE2 methods not exposed in wrapper
3. **MISSING:** Pattern analysis methods (NumberOfCapturingGroups, NamedCapturingGroups, etc.)
4. **MISSING:** Low-level Match() method with full control
5. **MISSING:** Rewrite validation methods (CheckRewriteString, MaxSubmatch, Rewrite)
6. **MISSING:** Options class (13 properties, getters/setters)
7. **MISSING:** RE2::Arg class for type-safe argument conversion
8. **MISSING:** Error codes enumeration
9. **INCORRECT:** API design doesn't match RE2's variadic template approach

**Conclusion:** Current wrapper provides ~40% of RE2's functionality. Needs significant expansion.

---

## 1. RE2 ACTUAL API (from re2.h, tag 2025-11-05)

### Core Matching Methods

**RE2's ACTUAL signatures:**
```cpp
// VARIADIC TEMPLATES - accepts ANY number of captures!
template <typename... A>
static bool FullMatch(absl::string_view text, const RE2& re, A&&... a);

template <typename... A>
static bool PartialMatch(absl::string_view text, const RE2& re, A&&... a);

template <typename... A>
static bool Consume(absl::string_view* input, const RE2& re, A&&... a);

template <typename... A>
static bool FindAndConsume(absl::string_view* input, const RE2& re, A&&... a);
```

**What this means:**
- User can pass **0, 1, 2, 3, 4, ...**, unlimited captures
- RE2 example from header: `RE2::FullMatch("ruby:1234", "(\\w+):(\\d+)", &s, &i)`
- Another example: `RE2::PartialMatch("x*100 + 20", "(\\d+)", &number)`
- NOT limited to 0/1/2 like our wrapper!

**Low-level N-variants (backing implementation):**
```cpp
static bool FullMatchN(absl::string_view text, const RE2& re,
                       const Arg* const args[], int n);
static bool PartialMatchN(absl::string_view text, const RE2& re,
                          const Arg* const args[], int n);
static bool ConsumeN(absl::string_view* input, const RE2& re,
                     const Arg* const args[], int n);
static bool FindAndConsumeN(absl::string_view* input, const RE2& re,
                            const Arg* const args[], int n);
```

### Pattern Analysis Methods

```cpp
int NumberOfCapturingGroups() const;
const std::map<std::string, int>& NamedCapturingGroups() const;
const std::map<int, std::string>& CapturingGroupNames() const;
int ProgramSize() const;
int ReverseProgramSize() const;
int ProgramFanout(std::vector<int>* histogram) const;
int ReverseProgramFanout(std::vector<int>* histogram) const;
```

### Status/Validation Methods

```cpp
bool ok() const;
const std::string& pattern() const;
const std::string& error() const;
ErrorCode error_code() const;
const std::string& error_arg() const;
```

### Replacement Methods

```cpp
static bool Replace(std::string* str, const RE2& re, absl::string_view rewrite);
static int GlobalReplace(std::string* str, const RE2& re, absl::string_view rewrite);
static bool Extract(absl::string_view text, const RE2& re,
                    absl::string_view rewrite, std::string* out);
```

### Rewrite Validation

```cpp
bool CheckRewriteString(absl::string_view rewrite, std::string* error) const;
static int MaxSubmatch(absl::string_view rewrite);
bool Rewrite(std::string* out, absl::string_view rewrite,
             const absl::string_view* vec, int veclen) const;
```

### Utility Methods

```cpp
static std::string QuoteMeta(absl::string_view unquoted);
bool PossibleMatchRange(std::string* min, std::string* max, int maxlen) const;
```

### Generic Match (Low-Level)

```cpp
enum Anchor { UNANCHORED, ANCHOR_START, ANCHOR_BOTH };

bool Match(absl::string_view text, size_t startpos, size_t endpos,
           Anchor re_anchor, absl::string_view* submatch, int nsubmatch) const;
```

### Options Class

```cpp
class Options {
  // 13 properties with getters/setters:
  int64_t max_mem() const;
  void set_max_mem(int64_t m);

  Encoding encoding() const;
  void set_encoding(Encoding enc);

  bool posix_syntax() const;
  void set_posix_syntax(bool b);

  bool longest_match() const;
  void set_longest_match(bool b);

  bool log_errors() const;
  void set_log_errors(bool b);

  bool literal() const;
  void set_literal(bool b);

  bool never_nl() const;
  void set_never_nl(bool b);

  bool dot_nl() const;
  void set_dot_nl(bool b);

  bool never_capture() const;
  void set_never_capture(bool b);

  bool case_sensitive() const;
  void set_case_sensitive(bool b);

  bool perl_classes() const;
  void set_perl_classes(bool b);

  bool word_boundary() const;
  void set_word_boundary(bool b);

  bool one_line() const;
  void set_one_line(bool b);
};
```

### Error Codes

```cpp
enum ErrorCode {
  NoError = 0,
  ErrorInternal,
  ErrorBadEscape,
  ErrorBadCharClass,
  ErrorBadCharRange,
  ErrorMissingBracket,
  ErrorMissingParen,
  ErrorUnexpectedParen,
  ErrorTrailingBackslash,
  ErrorRepeatArgument,
  ErrorRepeatSize,
  ErrorRepeatOp,
  ErrorBadPerlOp,
  ErrorBadUTF8,
  ErrorBadNamedCapture,
  ErrorPatternTooLarge
};
```

---

## 2. OUR WRAPPER API (current libre2_api.h)

### What We Have (33 functions):

**Compilation:**
- ✅ `compilePattern(pattern, case_sensitive, error)` - OK
- ✅ `compilePattern(pattern, options_json, error)` - OK but different from RE2::Options
- ✅ `releasePattern(pattern)` - OK

**Matching (0,1,2 captures ONLY - WRONG!):**
- ⚠️ `fullMatch(pattern, text)` - Limited to 0 captures
- ⚠️ `fullMatch(pattern, text, capture1)` - Limited to 1 capture
- ⚠️ `fullMatch(pattern, text, capture1, capture2)` - Limited to 2 captures
- ⚠️ `partialMatch(pattern, text)` - Limited to 0 captures
- ⚠️ `partialMatch(pattern, text, capture1)` - Limited to 1 capture
- ⚠️ `partialMatch(pattern, text, capture1, capture2)` - Limited to 2 captures

**Consume/Scan (0,1,2 captures ONLY - WRONG!):**
- ⚠️ `consume(pattern, input, len)` - Limited
- ⚠️ `consume(pattern, input, len, capture1)` - Limited
- ⚠️ `consume(pattern, input, len, capture1, capture2)` - Limited
- ⚠️ `findAndConsume(...)` - Same limitations

**Replacement:**
- ✅ `replace(pattern, text, rewrite, result)` - OK
- ✅ `replaceAll(pattern, text, rewrite, result)` - OK (GlobalReplace in RE2)
- ✅ `extract(pattern, text, rewrite, result)` - OK

**Utility:**
- ✅ `quoteMeta(text)` - OK (QuoteMeta in RE2)
- ⚠️ `getPatternInfo(pattern)` - Returns JSON, different from RE2
- ⚠️ `isPatternValid(pattern)` - Similar to ok() but different

**Bulk/Direct (OUR ADDITIONS - not in RE2):**
- ➕ `fullMatchBulk(...)` - Our optimization
- ➕ `partialMatchBulk(...)` - Our optimization
- ➕ `fullMatchDirect(...)` - Our zero-copy optimization
- ➕ `partialMatchDirect(...)` - Our zero-copy optimization
- ➕ `fullMatchDirectBulk(...)` - Combined optimization
- ➕ `partialMatchDirectBulk(...)` - Combined optimization

**Cache Management (OUR ADDITIONS):**
- ➕ `initCache(json_config)` - Our caching layer
- ➕ `shutdownCache()` - Our caching layer
- ➕ `isCacheInitialized()` - Our caching layer
- ➕ `getMetricsJSON()` - Our metrics

---

## 3. GAP ANALYSIS - What's Missing

### 🔴 CRITICAL GAPS (Must Implement)

1. **Variadic Capture Groups**
   - **Missing:** Template-based unlimited captures
   - **Currently:** Hardcoded 0/1/2 overloads
   - **Impact:** Cannot extract patterns with 3+ groups (e.g., IPv4 `(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)`)
   - **Fix Required:** Implement N-variants (FullMatchN, etc.) with array-based captures

2. **Pattern Analysis Methods**
   - **Missing:** `NumberOfCapturingGroups()` - How many capture groups?
   - **Missing:** `NamedCapturingGroups()` - Map of named groups
   - **Missing:** `CapturingGroupNames()` - Reverse map
   - **Impact:** Cannot query pattern structure, plan capture arrays

3. **Status/Validation Methods**
   - **Missing:** `ok()` - Pattern valid?
   - **Missing:** `pattern()` - Get original pattern string
   - **Missing:** `error()` - Get error message
   - **Missing:** `error_code()` - Get error code (enum)
   - **Missing:** `error_arg()` - Get offending part of pattern
   - **Impact:** Limited error diagnostics

4. **Rewrite Validation**
   - **Missing:** `CheckRewriteString()` - Validate rewrite template
   - **Missing:** `MaxSubmatch()` - Maximum capture referenced
   - **Missing:** `Rewrite()` - Apply rewrite template manually
   - **Impact:** Cannot validate rewrite strings before use

5. **Generic Match Method**
   - **Missing:** `Match(text, startpos, endpos, anchor, submatch[], n)`
   - **Impact:** No low-level control over matching (anchoring, position, submatch array)

6. **Advanced Analysis**
   - **Missing:** `ProgramSize()` - Regex complexity metric
   - **Missing:** `ReverseProgramSize()` - Reverse regex complexity
   - **Missing:** `ProgramFanout()` - Performance analysis
   - **Missing:** `ReverseProgramFanout()` - Reverse performance
   - **Missing:** `PossibleMatchRange()` - String range that could match

7. **Options Class**
   - **Missing:** Full `RE2::Options` class with 13 properties
   - **Currently:** JSON string (indirect, not like-for-like)
   - **Impact:** Cannot programmatically inspect/modify options

8. **Error Codes Enumeration**
   - **Missing:** `ErrorCode` enum (15 values)
   - **Impact:** Cannot handle specific error types

9. **RE2::Arg Class**
   - **Missing:** Type-safe argument conversion
   - **Impact:** No way to parse captures as int, float, hex, etc.

---

## 4. DESIGN MISMATCH ANALYSIS

### Issue 1: Capture Groups Hardcoded to 0/1/2

**RE2's Approach:**
```cpp
// Variadic templates - unlimited captures
template <typename... A>
static bool FullMatch(absl::string_view text, const RE2& re, A&&... a);

// Usage examples from RE2 header:
RE2::FullMatch("ruby:1234", "(\\w+):(\\d+)", &s, &i);  // 2 captures
RE2::FullMatch("foo", "f(o+)", &s);                     // 1 capture
RE2::FullMatch("hello", "h.*o");                        // 0 captures
```

**Our Wrapper's Approach:**
```cpp
// Fixed overloads - ONLY 0, 1, or 2!
bool fullMatch(RE2Pattern* pattern, std::string_view text);
bool fullMatch(RE2Pattern* pattern, std::string_view text, std::string* capture1);
bool fullMatch(RE2Pattern* pattern, std::string_view text, std::string* capture1, std::string* capture2);
```

**Problem:**
- User needs 3 captures? **IMPOSSIBLE**
- User needs 4 captures (IPv4)? **IMPOSSIBLE**
- User needs 10 captures (complex log parsing)? **IMPOSSIBLE**

**Why This Happened:**
- We focused on simple cases first
- Didn't realize RE2 uses variadic templates
- Didn't study RE2's actual implementation strategy

**Fix Strategy:**
- Implement N-variants: `fullMatchN(pattern, text, string*, n_captures)`
- Accept array of string pointers
- User pre-allocates capture array
- Call RE2::FullMatchN() internally

---

### Issue 2: Options as JSON String vs RE2::Options Class

**RE2's Approach:**
```cpp
RE2::Options opts;
opts.set_case_sensitive(false);
opts.set_max_mem(1024*1024);
RE2 pattern("hello", opts);

// Or use canned options:
RE2 pattern("hello", RE2::Latin1);
```

**Our Wrapper's Approach:**
```cpp
std::string options_json = R"({
  "case_sensitive": false,
  "max_mem": 1048576
})";
RE2Pattern* p = compilePattern("hello", options_json, error);
```

**Trade-offs:**
- ✅ **Pro:** JSON is language-agnostic (good for JNI/Python/Go)
- ✅ **Pro:** Simpler for high-level bindings
- ❌ **Con:** Not like-for-like with RE2
- ❌ **Con:** Cannot inspect options after creation
- ❌ **Con:** Extra parsing overhead

**Decision:** KEEP JSON approach for now, but ADD methods to query options:
```cpp
const PatternOptions& getOptions(RE2Pattern* pattern);  // Return struct
std::string getOptionsJSON(RE2Pattern* pattern);        // Return JSON
```

---

### Issue 3: Pattern Analysis Missing

**RE2 Provides:**
```cpp
RE2 re("(\\w+):(\\d+)");
int num_groups = re.NumberOfCapturingGroups();  // Returns 2
auto named = re.NamedCapturingGroups();         // Map of names → indices
auto names = re.CapturingGroupNames();          // Map of indices → names
```

**Our Wrapper:**
- `getPatternInfo()` returns JSON blob
- No direct access to capturing group count
- No access to named groups

**Impact:**
- User cannot determine how many captures to allocate
- User cannot use named groups effectively

**Fix:**
```cpp
int getNumberOfCapturingGroups(RE2Pattern* pattern);
std::string getNamedCapturingGroupsJSON(RE2Pattern* pattern);  // Map as JSON
std::string getCapturingGroupNamesJSON(RE2Pattern* pattern);   // Map as JSON
```

---

## 5. REMEDIATION PLAN

### Phase 1.2.5 - Add Missing Core Methods (Priority 1)

**Goal:** Expose all 40 core RE2 methods identified in user's checklist

#### 1.2.5a - N-Variant Matching (CRITICAL)

```cpp
// Add array-based matching with unlimited captures
bool fullMatchN(RE2Pattern* pattern, std::string_view text,
                std::string* captures[], int n_captures);

bool partialMatchN(RE2Pattern* pattern, std::string_view text,
                   std::string* captures[], int n_captures);

bool consumeN(RE2Pattern* pattern, const char** input_text, int* input_len,
              std::string* captures[], int n_captures);

bool findAndConsumeN(RE2Pattern* pattern, const char** input_text, int* input_len,
                     std::string* captures[], int n_captures);
```

**Tests:** 20+ tests with 0, 1, 2, 3, 5, 10, 20 captures

#### 1.2.5b - Pattern Analysis

```cpp
int getNumberOfCapturingGroups(RE2Pattern* pattern);
std::string getNamedCapturingGroupsJSON(RE2Pattern* pattern);
std::string getCapturingGroupNamesJSON(RE2Pattern* pattern);
int getProgramSize(RE2Pattern* pattern);
int getReverseProgramSize(RE2Pattern* pattern);
```

**Tests:** 10+ tests

#### 1.2.5c - Status/Validation

```cpp
bool isOk(RE2Pattern* pattern);  // Alias for isPatternValid
std::string getPattern(RE2Pattern* pattern);
std::string getError(RE2Pattern* pattern);
int getErrorCode(RE2Pattern* pattern);
std::string getErrorArg(RE2Pattern* pattern);
```

**Tests:** 5+ tests

#### 1.2.5d - Rewrite Validation

```cpp
bool checkRewriteString(RE2Pattern* pattern, std::string_view rewrite,
                        std::string* error_out);
int maxSubmatch(std::string_view rewrite);  // Static method
bool rewrite(RE2Pattern* pattern, std::string* out,
             std::string_view rewrite, const std::string* captures[], int n);
```

**Tests:** 10+ tests

#### 1.2.5e - Generic Match

```cpp
enum Anchor { UNANCHORED, ANCHOR_START, ANCHOR_BOTH };

bool match(RE2Pattern* pattern, std::string_view text,
           size_t startpos, size_t endpos, Anchor anchor,
           std::string* submatches[], int n_submatches);
```

**Tests:** 15+ tests

#### 1.2.5f - Advanced Analysis

```cpp
bool possibleMatchRange(RE2Pattern* pattern, std::string* min_out,
                        std::string* max_out, int maxlen);
```

**Tests:** 5+ tests

---

### Phase 1.2.6 - Options Inspection (Priority 2)

```cpp
// Return internal options struct (read-only access)
const PatternOptions& getOptions(RE2Pattern* pattern);

// Return options as JSON (for bindings)
std::string getOptionsJSON(RE2Pattern* pattern);
```

---

### Phase 1.2.7 - Error Code Enumeration (Priority 2)

```cpp
// Expose RE2::ErrorCode as libre2::ErrorCode
enum ErrorCode {
  NoError = 0,
  ErrorInternal,
  ErrorBadEscape,
  // ... (all 15 error codes)
};

// Add to API:
ErrorCode getErrorCode(RE2Pattern* pattern);  // Returns enum, not int
```

---

## 6. WHAT TO KEEP (Our Additions)

These are **NOT in RE2** but provide value for performance:

✅ **Keep:**
- Bulk operations (fullMatchBulk, etc.)
- Direct memory operations (fullMatchDirect, etc.)
- Cache management (initCache, shutdownCache)
- Metrics (getMetricsJSON)
- JSON-based options (in addition to struct-based)

**Rationale:** These address specific performance needs (JNI overhead, caching) that RE2 doesn't provide.

---

## 7. ESTIMATED EFFORT

| Sub-Phase | Functions | Tests | Effort (hours) |
|-----------|-----------|-------|----------------|
| 1.2.5a - N-variants | 4 | 20 | 4-6 |
| 1.2.5b - Analysis | 5 | 10 | 2-3 |
| 1.2.5c - Status | 5 | 5 | 1-2 |
| 1.2.5d - Rewrite | 3 | 10 | 2-3 |
| 1.2.5e - Generic Match | 1 | 15 | 3-4 |
| 1.2.5f - Advanced | 1 | 5 | 1-2 |
| **Total** | **19** | **65** | **13-20 hours** |

**Timeline:** 2-3 days for implementation + testing

---

## 8. SUCCESS CRITERIA

Phase 1.2.5 complete when:

- ✅ All 19 missing core methods implemented
- ✅ N-variant matching supports unlimited captures (tested up to 20)
- ✅ All pattern analysis methods working
- ✅ All status/validation methods working
- ✅ All rewrite validation methods working
- ✅ Generic Match() method working with all anchor modes
- ✅ 65+ new tests passing (all with RE2 comparison)
- ✅ Wrapper API covers 90%+ of RE2 core functionality
- ✅ Documentation updated with API coverage table

---

## 9. CONCLUSION

**Current State:**
- Wrapper API provides ~40% of RE2's core functionality
- Critical gaps in capture groups (limited to 0/1/2)
- Missing pattern analysis, rewrite validation, generic match

**Required Action:**
- Implement Phase 1.2.5 (19 functions, 65+ tests)
- Maintain RE2 comparison testing pattern for ALL new tests
- Document API coverage and design decisions

**After Phase 1.2.5:**
- Wrapper will cover 90%+ of RE2 core API
- Ready for Java layer integration
- Production-ready for Cassandra use case

---

**Next Step:** Review this analysis with user, get approval, start Phase 1.2.5a (N-variant matching)
