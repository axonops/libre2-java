# Phase 1.2.5g - Test Reorganization & RE2 Test Porting

**Started:** 2025-11-29 16:45 UTC
**Status:** 🔄 IN PROGRESS

---

## PROBLEM STATEMENT

**Current State:**
- `libre2_api_test.cpp`: 3179 lines (WAY too large!)
- Growing fast (added 800+ lines in Phase 1.2.5a-f)
- Hard to navigate, find specific tests
- RE2 has 15+ years of edge cases we should leverage

**Target State:**
- Multiple organized test files (~300-400 lines each)
- Clear categorization by functionality
- RE2 edge cases ported to wrapper
- Faster parallel test compilation

---

## CURRENT TEST FILE ANALYSIS

**File:** libre2_api_test.cpp (3179 lines)

**Breakdown (estimated):**
- Cache layer tests: ~1400 lines (Phase 1.0)
- Facade layer tests: ~200 lines (Phase 1.1)
- Consume/scan tests: ~250 lines (Phase 1.2.1)
- Replacement tests: ~250 lines (Phase 1.2.2)
- Utility tests: ~150 lines (Phase 1.2.3)
- Bulk/Direct tests: ~200 lines (Phase 1.2.4)
- N-variant tests: ~450 lines (Phase 1.2.5a)
- Analysis tests: ~120 lines (Phase 1.2.5b)
- Status tests: ~180 lines (Phase 1.2.5c)
- Rewrite validation: ~150 lines (Phase 1.2.5d)
- Generic Match: ~180 lines (Phase 1.2.5e)
- Advanced analysis: ~80 lines (Phase 1.2.5f)

---

## RE2 TEST SUITE REVIEW

**File:** reference-repos/re2/re2/testing/re2_test.cc (54K = ~1800 lines)

**Tests Found (30 test cases):**
- HexTests, OctalTests, DecimalTests - Arg parsing (skip - we use strings only)
- Replace, Extract - Already have
- MaxSubmatchTooLarge - Edge case to port
- Consume, ConsumeN - Already have
- FindAndConsume, FindAndConsumeN - Already have
- MatchNumberPeculiarity - Edge case to port
- Match - Already have
- QuoteMeta (Simple, SimpleNegative, Latin1, UTF8, HasNull) - Port these!
- ProgramSize, ProgramFanout - Already have
- EmptyCharset tests - Edge case to port
- Capture NamedGroups - Port
- FullMatchWithNoArgs, FullMatchZeroArg, FullMatchOneArg - Already covered
- PartialMatch, PartialMatchN - Already have

**Tests to Port (ALL appropriate functional tests):**

**CRITICAL:** Port ALL functional tests from RE2, not just edge cases.
If RE2 tests a behavior, we should test it too (with RE2 comparison).

**From re2_test.cc:**
1. ✅ Replace - all test cases
2. ✅ Extract - all test cases
3. ✅ Consume - all test cases
4. ✅ ConsumeN - all test cases
5. ✅ FindAndConsume - all test cases
6. ✅ FindAndConsumeN - all test cases
7. ✅ Match - all test cases
8. ✅ QuoteMeta (Simple, SimpleNegative, Latin1, UTF8, HasNull) - ALL
9. ✅ FullMatch variations (NoArgs, ZeroArg, OneArg, etc) - ALL
10. ✅ PartialMatch, PartialMatchN - ALL
11. ✅ NamedGroups - ALL
12. ✅ CheckRewriteString - ALL
13. ✅ MaxSubmatch - ALL
14. ✅ ProgramSize, ProgramFanout - ALL applicable

**Skip ONLY:**
- Internal implementation tests (parse_test.cc, dfa_test.cc)
- Exhaustive tests (too slow, exhaustive_test.cc)
- Hex/Octal/Decimal Arg parsing (we use strings only for now)

**Expected:** 50-100+ ported tests (not 15-20)

---

## TEST FILE SPLIT PLAN

**New Structure:**

```
tests/
├── libre2_cache_test.cpp           # Cache layer (Phase 1.0) - 1400 lines
├── libre2_matching_test.cpp        # fullMatch, partialMatch, N-variants - 600 lines
├── libre2_consume_test.cpp         # consume, findAndConsume variants - 400 lines
├── libre2_replacement_test.cpp     # replace, replaceAll, extract - 300 lines
├── libre2_analysis_test.cpp        # Pattern analysis, status, validation - 400 lines
├── libre2_bulk_test.cpp            # All bulk/direct variants - 400 lines
├── libre2_generic_match_test.cpp   # Generic Match() with anchors - 300 lines
└── libre2_re2_ported_test.cpp      # Tests ported from RE2 suite - 300+ lines
```

**Total:** 8 files, ~4100 lines (with new RE2 tests)

---

## IMPLEMENTATION STEPS

### Step 1: Review RE2 Tests ✅
- [x] Located re2_test.cc (54K, ~1800 lines)
- [x] Identified 30+ test cases
- [x] Selected 15-20 edge cases to port

### Step 2: Identify Edge Cases to Port
- [ ] QuoteMeta edge cases (5 tests)
- [ ] Empty pattern/text tests
- [ ] Unicode edge cases
- [ ] Very long patterns (1000+ chars)
- [ ] NamedGroups tests
- [ ] Null handling

### Step 3: Create Test Organization Plan
- [ ] Document which tests go in which file
- [ ] Create file split checklist

### Step 4: Split Test File
- [ ] Extract cache tests → libre2_cache_test.cpp
- [ ] Extract matching tests → libre2_matching_test.cpp
- [ ] Extract consume tests → libre2_consume_test.cpp
- [ ] Extract replacement tests → libre2_replacement_test.cpp
- [ ] Extract analysis tests → libre2_analysis_test.cpp
- [ ] Extract bulk tests → libre2_bulk_test.cpp
- [ ] Extract generic match → libre2_generic_match_test.cpp
- [ ] Create new file for ported tests

### Step 5: Port RE2 Tests
- [ ] Port QuoteMeta edge cases
- [ ] Port named groups tests
- [ ] Port Unicode tests
- [ ] Port empty pattern/text tests
- [ ] All with RE2 comparison pattern

### Step 6: Update Build System
- [ ] Update tests/CMakeLists.txt with new test files
- [ ] Verify all tests compile
- [ ] Verify all tests run

### Step 7: Validate
- [ ] All 260+ tests still passing
- [ ] New RE2 tests passing
- [ ] No regressions
- [ ] Build time improved (parallel compilation)

---

## PROGRESS TRACKING

**Current:** Reviewing RE2 test suite
**Next:** Identify specific edge cases to port
**Status:** 0/7 steps complete

---

**Token Usage:** 196K/1M (19.6%)
**Tests:** 260/260 passing (before reorganization)
