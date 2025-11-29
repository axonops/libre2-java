# Phase 1.2.5g Execution Plan - Test Reorganization & RE2 Test Porting

**Status:** Ready to execute
**Scope:** Reorganize 3851 lines + port 91 RE2 tests
**Token budget:** 320K used, 680K remaining (68% available)

---

## CURRENT STATE

**File:** tests/libre2_api_test.cpp
- Lines: 3851
- Tests: 120
- Status: TOO LARGE, needs reorganization

**Target:** 8 organized test files (~400-500 lines each)

---

## TEST CATEGORIZATION (from current file)

**Category 1: Cache Layer (Tests 1-12):**
- CompileWithoutCache, CompileWithCache
- ExplicitInit*, CacheReuse, ShutdownAndReinit
- ThreadSafeConcurrentCompile, DoubleInitThrows
- ~400 lines → libre2_cache_test.cpp

**Category 2: Basic Matching (Tests 13-16):**
- CaptureGroup_*, FullVsPartialMatch
- ~150 lines → libre2_matching_test.cpp

**Category 3: Consume/Scan (Tests 17-32):**
- Consume_*, FindAndConsume_*, ConsumeVsFindAndConsume
- ~500 lines → libre2_consume_test.cpp

**Category 4: Replacement (Tests 33-48):**
- Replace_*, ReplaceAll_*, Extract_*
- ~550 lines → libre2_replacement_test.cpp

**Category 5: Utility (Tests 49-51):**
- QuoteMeta_*, GetPatternInfo_*, IsPatternValid_*
- ~200 lines → keep in libre2_api_test.cpp

**Category 6: Bulk/Direct (Tests 52-54):**
- FullMatchBulk, FullMatchDirect, FullMatchDirectBulk
- ~200 lines → libre2_bulk_test.cpp

**Category 7: N-Variants (Tests 55-67):**
- FullMatchN_*, PartialMatchN_*, ConsumeN_*, etc.
- ~600 lines → add to libre2_matching_test.cpp

**Category 8: Analysis (Tests 68-82):**
- GetNumberOfCapturingGroups, GetProgramSize, etc.
- ~300 lines → libre2_analysis_test.cpp

**Category 9: Status/Validation (Tests 83-90):**
- Ok_*, GetPattern, GetError_*, etc.
- ~250 lines → add to libre2_analysis_test.cpp

**Category 10: Rewrite Validation (Tests 91-96):**
- CheckRewriteString_*, MaxSubmatch_*, Rewrite_*
- ~200 lines → libre2_replacement_test.cpp

**Category 11: Generic Match (Tests 97-102):**
- Match_Unanchored, Match_AnchorStart, etc.
- ~250 lines → libre2_matching_test.cpp

**Category 12: Advanced (Tests 103-105):**
- PossibleMatchRange_*
- ~100 lines → libre2_analysis_test.cpp

**Category 13: Typed Captures (Tests 106-112):**
- FullMatchN_IntCapture, MixedTypes, Hex/Octal, Optional
- ~350 lines → libre2_matching_test.cpp

**Category 14: Options/Set (Tests 113-120):**
- CompilePattern_WithOptions, Options_*, Set_*
- ~200 lines → libre2_api_test.cpp

---

## NEW FILE STRUCTURE

```
tests/
├── libre2_api_test.cpp          # Basic API, cache init, utilities (500 lines)
├── libre2_cache_test.cpp        # Cache layer tests (400 lines)
├── libre2_matching_test.cpp     # All matching variants (1200 lines)
├── libre2_consume_test.cpp      # Consume/scan operations (500 lines)
├── libre2_replacement_test.cpp  # Replace/extract + rewrite (750 lines)
├── libre2_analysis_test.cpp     # Analysis/status/validation (650 lines)
├── libre2_bulk_test.cpp         # Bulk/direct variants (300 lines)
└── libre2_re2_ported_test.cpp   # Tests ported from RE2 (500+ lines)
```

**Total: 8 files, ~4800 lines (with ported tests)**

---

## RE2 TESTS TO PORT (91 tests)

### From re2_test.cc (78 tests):

**Skip (Arg parsing - already have typed capture tests):**
- HexTests, OctalTests, DecimalTests (covered by our Arg tests)

**Port ALL remaining (75+ tests):**
1. Replace (all variants)
2. CheckRewriteString (all)
3. Extract (all variants)
4. MaxSubmatchTooLarge
5. Consume (all variants)
6. ConsumeN (all variants)
7. FindAndConsume (all variants)
8. FindAndConsumeN (all variants)
9. MatchNumberPeculiarity
10. Match (all variants)
11. QuoteMeta (Simple, SimpleNegative, Latin1, UTF8, HasNull)
12. ProgramSize BigProgram
13. ProgramFanout BigProgram
14. EmptyCharset (Fuzz, BitstateAssumptions)
15. Capture NamedGroups
16. CapturedGroupTest
17. FullMatch (all variants: NoArgs, ZeroArg, OneArg, IntegerArg, etc)
18. PartialMatch (all variants)
19. PartialMatchN (all)
20. All remaining matching tests (~40 more)

### From exhaustive tests (13 tests):

**Port ALL:**
- exhaustive_test.cc: 4 tests
- exhaustive1_test.cc: 2 tests
- exhaustive2_test.cc: 3 tests
- exhaustive3_test.cc: 4 tests

---

## EXECUTION STRATEGY

Given token constraints (680K remaining), use efficient approach:

**Strategy A: Port First, Then Split (RECOMMENDED)**
1. Port ALL 75+ RE2 tests to current file (append)
2. Verify all pass
3. Then split into organized files
4. Benefit: Tests validated before reorganization

**Strategy B: Split First, Then Port**
1. Split current file first
2. Port tests to appropriate new files
3. Risk: More complex, harder to track

**DECISION: Use Strategy A**

---

## NEXT STEPS

1. ✅ Create this execution plan
2. Start porting tests from re2_test.cc (batch by category)
3. Port exhaustive tests
4. Verify all pass
5. Split into organized files
6. Update CMakeLists.txt
7. Final verification

**Starting now with test porting!**
