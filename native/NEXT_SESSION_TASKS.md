# Next Session Tasks

## PHASE 1.2.5g - Test File Organization (INCOMPLETE)

**Status:** Functionality complete, file organization pending
**Current:** All 384 tests in single file (libre2_api_test.cpp - 5159 lines)
**Target:** Split into 8 organized files (~500-700 lines each)

### What Needs To Be Done:

**Task:** Split tests/libre2_api_test.cpp into organized files

**Files to create:**
1. libre2_cache_test.cpp - Cache layer tests (~12 tests)
2. libre2_consume_test.cpp - Consume/FindAndConsume tests (~22 tests)
3. libre2_replacement_test.cpp - Replace/Extract/Rewrite tests (~27 tests)
4. libre2_bulk_test.cpp - Bulk/Direct variant tests (~8 tests)
5. libre2_matching_test.cpp - FullMatch/PartialMatch/N-variants (~58 tests)
6. libre2_analysis_test.cpp - Analysis/Status/Validation (~24 tests)
7. libre2_re2_ported_test.cpp - All RE2 ported tests (~50 compact tests)
8. libre2_api_test.cpp - Basic API tests (remaining)

**Steps:**
1. Use automated script (Python/sed) with user approval
2. OR manually move test by test (time-consuming)
3. Update CMakeLists.txt with all new files
4. Build and verify all 384 tests still pass
5. Commit organized test structure

**Estimated Effort:** 1-2 hours in fresh session

---

## PHASE 2 - Java Layer (READY TO START)

**Status:** Native wrapper API 100% complete, ready for JNI
**Goal:** Implement Java bindings to native wrapper

### What Phase 2 Entails:

**JNI Layer:**
- Create JNI wrapper for all libre2 API functions
- Handle Java ↔ C++ type conversion
- DirectByteBuffer support (zero-copy)
- Bulk operation support (Java arrays)

**Java API Classes:**
- Pattern class (wraps RE2Pattern*)
- Matcher class (for match operations)
- Options class (Java version of RE2::Options)
- Set class (multi-pattern matching)
- Exception hierarchy

**Testing:**
- Java unit tests for all APIs
- Integration tests
- Performance benchmarks

**Estimated Effort:** 2-3 weeks

---

## CURRENT STATE SUMMARY

**Phase 1:** ✅ COMPLETE
- 100% RE2 public API coverage
- 384 tests passing (100%)
- 104 RE2 tests ported
- Ready for Java layer

**Phase 1.2.5g:** ⚠️ INCOMPLETE (file organization only)
- Tests exist and pass
- Need reorganization for maintainability
- Can be done anytime

**Recommended Next:** Start Phase 2 (Java layer implementation)
