# Complete RE2 API Inventory - From Actual Source Code

**Source:** reference-repos/re2 (tag 2025-11-05)
**Files:** re2/re2.h, re2/set.h
**Date:** 2025-11-29
**Purpose:** 100% accurate inventory of what RE2 provides vs what we have

---

## RE2 MAIN CLASS - COMPLETE METHOD LIST

### Constructors & Destructors (5 methods)

| Method | Have? | Notes |
|--------|-------|-------|
| `RE2(const char*)` | ✅ | Via compilePattern() |
| `RE2(const std::string&)` | ✅ | Via compilePattern() |
| `RE2(absl::string_view)` | ✅ | Via compilePattern() |
| `RE2(absl::string_view, const Options&)` | ✅ | Via compilePattern(pattern, options_json) |
| `~RE2()` | ✅ | Via releasePattern() |

**Status:** ✅ 5/5 COMPLETE

---

### Status/Validation Methods (5 methods)

| Method | Have? | Notes |
|--------|-------|-------|
| `bool ok() const` | ✅ | ok() - Phase 1.2.5c |
| `const std::string& pattern() const` | ✅ | getPattern() - Phase 1.2.5c |
| `const std::string& error() const` | ✅ | getError() - Phase 1.2.5c |
| `ErrorCode error_code() const` | ✅ | getErrorCode() - Phase 1.2.5c |
| `const std::string& error_arg() const` | ✅ | getErrorArg() - Phase 1.2.5c |

**Status:** ✅ 5/5 COMPLETE

---

### Pattern Analysis Methods (7 methods)

| Method | Have? | Notes |
|--------|-------|-------|
| `int NumberOfCapturingGroups() const` | ✅ | getNumberOfCapturingGroups() - 1.2.5b |
| `const map<string,int>& NamedCapturingGroups() const` | ✅ | getNamedCapturingGroupsJSON() - 1.2.5b |
| `const map<int,string>& CapturingGroupNames() const` | ✅ | getCapturingGroupNamesJSON() - 1.2.5b |
| `int ProgramSize() const` | ✅ | getProgramSize() - 1.2.5b |
| `int ReverseProgramSize() const` | ✅ | getReverseProgramSize() - 1.2.5b |
| `int ProgramFanout(vector<int>*) const` | ❌ | **MISSING** |
| `int ReverseProgramFanout(vector<int>*) const` | ❌ | **MISSING** |

**Status:** ⚠️ 5/7 (71%) - Missing ProgramFanout methods

---

### Matching Methods - N-Variants (4 methods)

| Method | Have? | Implementation |
|--------|-------|----------------|
| `static bool FullMatchN(string_view, const RE2&, const Arg* const[], int)` | ⚠️ | fullMatchN() but uses `string*[]` NOT `Arg*[]` |
| `static bool PartialMatchN(string_view, const RE2&, const Arg* const[], int)` | ⚠️ | partialMatchN() but uses `string*[]` NOT `Arg*[]` |
| `static bool ConsumeN(string_view*, const RE2&, const Arg* const[], int)` | ⚠️ | consumeN() but uses `string*[]` NOT `Arg*[]` |
| `static bool FindAndConsumeN(string_view*, const RE2&, const Arg* const[], int)` | ⚠️ | findAndConsumeN() but uses `string*[]` NOT `Arg*[]` |

**Status:** ⚠️ 4/4 IMPLEMENTED BUT **WRONG SIGNATURE** - Must accept `Arg*[]` not `string*[]`

---

### Matching Methods - Variadic Templates (4 methods)

| Method | Have? | Notes |
|--------|-------|-------|
| `template<typename...A> static bool FullMatch(...)` | ❌ | Cannot implement (C++ templates don't cross language boundaries) |
| `template<typename...A> static bool PartialMatch(...)` | ❌ | N/A for wrapper |
| `template<typename...A> static bool Consume(...)` | ❌ | N/A for wrapper |
| `template<typename...A> static bool FindAndConsume(...)` | ❌ | N/A for wrapper |

**Status:** ✅ N/A - Variadic templates cannot be exposed in wrapper. N-variants are the equivalent.

---

### Generic Match Method (1 method)

| Method | Have? | Notes |
|--------|-------|-------|
| `bool Match(string_view, size_t start, size_t end, Anchor, string_view* submatch, int n) const` | ✅ | match() - Phase 1.2.5e |

**Status:** ✅ 1/1 COMPLETE

---

### Replacement Methods (3 methods)

| Method | Have? | Notes |
|--------|-------|-------|
| `static bool Replace(string*, const RE2&, string_view)` | ✅ | replace() - Phase 1.2.2 |
| `static int GlobalReplace(string*, const RE2&, string_view)` | ✅ | replaceAll() - Phase 1.2.2 |
| `static bool Extract(string_view, const RE2&, string_view, string*)` | ✅ | extract() - Phase 1.2.2 |

**Status:** ✅ 3/3 COMPLETE

---

### Rewrite Validation Methods (3 methods)

| Method | Have? | Notes |
|--------|-------|-------|
| `bool CheckRewriteString(string_view, string*) const` | ✅ | checkRewriteString() - Phase 1.2.5d |
| `static int MaxSubmatch(string_view)` | ✅ | maxSubmatch() - Phase 1.2.5d |
| `bool Rewrite(string*, string_view, const string_view*, int) const` | ✅ | rewrite() - Phase 1.2.5d |

**Status:** ✅ 3/3 COMPLETE

---

### Utility Methods (4 methods)

| Method | Have? | Notes |
|--------|-------|-------|
| `static string QuoteMeta(string_view)` | ✅ | quoteMeta() - Phase 1.2.3 |
| `bool PossibleMatchRange(string*, string*, int) const` | ✅ | possibleMatchRange() - Phase 1.2.5f |
| `const Options& options() const` | ❌ | **MISSING** - returns Options reference |
| `template<T> static Arg CRadix(T*)` | ❌ | **MISSING** |
| `template<T> static Arg Hex(T*)` | ❌ | **MISSING** |
| `template<T> static Arg Octal(T*)` | ❌ | **MISSING** |

**Status:** ⚠️ 2/6 (33%) - Missing options() getter and Arg helpers

---

## RE2::OPTIONS CLASS (28 methods)

| Method | Have? | Notes |
|--------|-------|-------|
| `Options()` | ✅ | PatternOptions() constructor |
| `Options(CannedOptions)` | ❌ | **MISSING** - Latin1, POSIX, Quiet |
| `int64_t max_mem() const` | ❌ | **MISSING** |
| `void set_max_mem(int64_t)` | ❌ | **MISSING** |
| `Encoding encoding() const` | ❌ | **MISSING** |
| `void set_encoding(Encoding)` | ❌ | **MISSING** |
| `bool posix_syntax() const` | ❌ | **MISSING** |
| `void set_posix_syntax(bool)` | ❌ | **MISSING** |
| `bool longest_match() const` | ❌ | **MISSING** |
| `void set_longest_match(bool)` | ❌ | **MISSING** |
| `bool log_errors() const` | ❌ | **MISSING** |
| `void set_log_errors(bool)` | ❌ | **MISSING** |
| `bool literal() const` | ❌ | **MISSING** |
| `void set_literal(bool)` | ❌ | **MISSING** |
| `bool never_nl() const` | ❌ | **MISSING** |
| `void set_never_nl(bool)` | ❌ | **MISSING** |
| `bool dot_nl() const` | ❌ | **MISSING** |
| `void set_dot_nl(bool)` | ❌ | **MISSING** |
| `bool never_capture() const` | ❌ | **MISSING** |
| `void set_never_capture(bool)` | ❌ | **MISSING** |
| `bool case_sensitive() const` | ❌ | **MISSING** |
| `void set_case_sensitive(bool)` | ❌ | **MISSING** |
| `bool perl_classes() const` | ❌ | **MISSING** |
| `void set_perl_classes(bool)` | ❌ | **MISSING** |
| `bool word_boundary() const` | ❌ | **MISSING** |
| `void set_word_boundary(bool)` | ❌ | **MISSING** |
| `bool one_line() const` | ❌ | **MISSING** |
| `void set_one_line(bool)` | ❌ | **MISSING** |
| `void Copy(const Options&)` | ❌ | **MISSING** |
| `int ParseFlags() const` | ❌ | **MISSING** |

**Status:** ❌ 1/28 (4%) - Only have struct, missing ALL getters/setters

---

## RE2::ARG CLASS (5+ methods)

| Method | Have? | Notes |
|--------|-------|-------|
| `Arg()` | ❌ | **MISSING** |
| `Arg(std::nullptr_t)` | ❌ | **MISSING** |
| `template<T> Arg(T*)` | ❌ | **MISSING** |
| `template<T> Arg(T*, Parser)` | ❌ | **MISSING** |
| `bool Parse(const char*, size_t) const` | ❌ | **MISSING** |

**Status:** ❌ 0/5 (0%) - COMPLETELY MISSING

---

## RE2::SET CLASS (7+ methods)

| Method | Have? | Notes |
|--------|-------|-------|
| `Set(const Options&, Anchor)` | ❌ | **MISSING** |
| `~Set()` | ❌ | **MISSING** |
| `Set(Set&&)` | ❌ | **MISSING** |
| `Set& operator=(Set&&)` | ❌ | **MISSING** |
| `int Add(string_view, string*)` | ❌ | **MISSING** |
| `int Size() const` | ❌ | **MISSING** |
| `bool Compile()` | ❌ | **MISSING** |
| `bool Match(string_view, vector<int>*) const` | ❌ | **MISSING** |
| `bool Match(string_view, vector<int>*, ErrorInfo*) const` | ❌ | **MISSING** |

**Status:** ❌ 0/9 (0%) - COMPLETELY MISSING

---

## ENUMERATIONS

| Enum | Have? | Notes |
|------|-------|-------|
| `ErrorCode` (15 values) | ❌ | **MISSING** - only return int from getErrorCode() |
| `CannedOptions` (4 values) | ❌ | **MISSING** |
| `Anchor` (3 values) | ✅ | Phase 1.2.5e |
| `Encoding` (2 values) | ❌ | **MISSING** - have in PatternOptions but not exposed |

**Status:** ⚠️ 1/4 (25%)

---

## COMPLETE SUMMARY

### What We HAVE (30 wrapper functions):

**✅ Core Matching:**
- compilePattern, releasePattern
- fullMatch, partialMatch (0,1,2 capture overloads) - **BUT should use Arg**
- fullMatchN, partialMatchN, consumeN, findAndConsumeN - **BUT wrong signature (string* not Arg*)**
- match (generic with anchors)

**✅ Analysis:**
- getNumberOfCapturingGroups, getNamedCapturingGroupsJSON
- getCapturingGroupNamesJSON, getProgramSize, getReverseProgramSize
- ok, getPattern, getError, getErrorCode, getErrorArg
- possibleMatchRange

**✅ Replacement:**
- replace, replaceAll, extract
- checkRewriteString, maxSubmatch, rewrite

**✅ Utility:**
- quoteMeta

**✅ Our Additions (not in RE2):**
- Direct memory variants (12 functions)
- Bulk variants (6 functions)
- initCache, shutdownCache, getMetricsJSON

---

### What We're MISSING:

**❌ CRITICAL - Phase 1.2.5h (MUST DO):**
1. RE2::Arg class (5 methods) - COMPLETELY MISSING
2. Hex(), Octal(), CRadix() helpers (3 methods) - MISSING
3. Fix N-variant signatures to accept `Arg*[]` not `string*[]` - WRONG
4. **Impact:** Cannot do typed captures (int, float, etc)

**❌ IMPORTANT - Phase 1.2.5i:**
5. options() const - Returns Options& reference - MISSING
6. ProgramFanout() - Analysis method - MISSING
7. ReverseProgramFanout() - Analysis method - MISSING

**❌ IMPORTANT - Phase 1.2.5j (Options API):**
8. Options getters (13 methods) - MISSING
9. Options setters (13 methods) - MISSING  
10. Options::Copy() - MISSING
11. Options::ParseFlags() - MISSING
12. CannedOptions enum - MISSING
13. Encoding enum (exposed) - MISSING

**❌ MAJOR FEATURE - Phase 1.2.6 (Set class):**
14. RE2::Set class (9 methods) - COMPLETELY MISSING
15. Set::Add(), Compile(), Match() - MISSING
16. Multi-pattern matching - MISSING

---

## CORRECTED API COVERAGE

### By Category:

| Category | Have | Total | % | Status |
|----------|------|-------|---|--------|
| Constructors/Destructors | 5 | 5 | 100% | ✅ |
| Status/Validation | 5 | 5 | 100% | ✅ |
| Pattern Analysis | 5 | 7 | 71% | ⚠️ Missing Fanout |
| Matching (N-variants) | 4 | 4 | 100%* | ⚠️ *Wrong signature (string* not Arg*) |
| Matching (Generic) | 1 | 1 | 100% | ✅ |
| Replacement | 3 | 3 | 100% | ✅ |
| Rewrite | 3 | 3 | 100% | ✅ |
| Utility | 2 | 6 | 33% | ❌ Missing options(), Arg helpers |
| RE2::Arg class | 0 | 5 | 0% | ❌ COMPLETELY MISSING |
| RE2::Options API | 1 | 28 | 4% | ❌ MOSTLY MISSING |
| RE2::Set class | 0 | 9 | 0% | ❌ COMPLETELY MISSING |
| Enums | 1 | 4 | 25% | ❌ MOSTLY MISSING |

### Overall:

**Core RE2 Class:** ~70% (missing Arg support, options(), Fanout)
**RE2::Arg Class:** 0%
**RE2::Options API:** 4%
**RE2::Set Class:** 0%
**Enums:** 25%

**TOTAL HONEST COVERAGE:** ~50-60% of complete RE2 public API

---

## REQUIRED WORK TO REACH 100%

### Phase 1.2.5h - RE2::Arg Support (CRITICAL)
**Functions:** 8
- Re-export RE2::Arg (typedef)
- Update 4 N-variant signatures (string*[] → Arg*[])
- Add Hex(), Octal(), CRadix() helpers
- Add options() getter

**Tests:** 50+ (all of re2_arg_test.cc)
**Effort:** 6-8 hours
**Priority:** 🔴 CRITICAL - blocks like-for-like

### Phase 1.2.5i - ProgramFanout + Enums
**Functions:** 4
- programFanout(), reverseProgramFanout()
- Expose ErrorCode enum
- Expose Encoding enum properly

**Tests:** 10+
**Effort:** 2-3 hours
**Priority:** 🟡 MEDIUM

### Phase 1.2.5j - RE2::Options Getters/Setters
**Functions:** 28
- 13 getters
- 13 setters
- Copy(), ParseFlags()
- CannedOptions enum

**Tests:** 30+
**Effort:** 6-8 hours
**Priority:** 🟡 MEDIUM - improves API usability

### Phase 1.2.6 - RE2::Set Class
**Functions:** 9
- Constructor, destructor, move
- Add(), Size(), Compile()
- Match() (2 overloads)

**Tests:** 40+
**Effort:** 12-16 hours
**Priority:** 🟢 NICE TO HAVE - important for multi-pattern use cases

---

## TOTAL REMAINING WORK

**Functions to add:** ~49
**Tests to add:** ~130+
**Effort:** ~26-35 hours
**Timeline:** 4-5 days

---

**CRITICAL NEXT STEP:** Phase 1.2.5h (RE2::Arg) - This is non-negotiable for RE2 compatibility.
