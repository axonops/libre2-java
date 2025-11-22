# RE2 Feature Gap Implementation Plan

## Executive Summary

This document outlines the implementation plan for adding missing RE2 features to libre2-java. The goal is to provide comprehensive regex functionality including bulk operations, capture groups, replace operations, and utilities - all with both single-string and batch APIs.

**Current State:** Basic Pattern/Matcher with fullMatch/partialMatch only
**Target State:** Full-featured RE2 wrapper with parity to java.util.regex
**Estimated Effort:** 8-10 days
**Risk Level:** Medium (native code changes, extensive testing required)

---

## Feature Gap Analysis

### What We Have (0.9.1)
- ✅ Pattern compilation (case-sensitive/insensitive)
- ✅ Basic matching (fullMatch, partialMatch)
- ✅ Pattern caching with dual eviction (LRU + idle)
- ✅ Metrics integration (25 metrics)
- ✅ Resource tracking and safety
- ✅ Thread-safe operations

### What's Missing

#### 1. Bulk Operations (High Priority)
**Gap:** Every match requires a JNI call (50ns overhead)
**Impact:** 10,000 matches = 500μs wasted in JNI overhead
**Solution:** Batch API that processes arrays in single JNI call

**Missing APIs:**
- `boolean[] matches(Collection<String>)` - bulk matching
- `List<String> filter(Collection<String>)` - bulk filtering
- Map variants for key/value filtering
- In-place filtering (retainMatches/removeMatches)

#### 2. Capture Groups (High Priority)
**Gap:** Cannot extract parts of matched text
**Impact:** Users cannot parse structured data (emails, phone numbers, etc.)
**Solution:** MatchResult class with group extraction

**Missing APIs:**
- `MatchResult match(String)` - single match with groups
- `List<MatchResult> findAll(String)` - all matches with groups
- Named group support
- Batch capture group extraction

#### 3. Replace Operations (Medium Priority)
**Gap:** Cannot perform find/replace with regex
**Impact:** Users must use java.util.regex for data cleaning
**Solution:** Replace methods with backreference support

**Missing APIs:**
- `String replaceFirst(String, String)` - replace first match
- `String replaceAll(String, String)` - replace all matches
- Backreference support ($1, $2, etc.)
- Batch replace operations

#### 4. Utilities (Low Priority)
**Gap:** Missing helper functions available in RE2
**Impact:** Users manually escape regex characters
**Solution:** Static utility methods

**Missing APIs:**
- `Pattern.quoteMeta(String)` - escape special characters
- `programSize()` - pattern complexity measurement
- `programFanout()` - DFA analysis

---

## Implementation Phases

### Phase 0: Native Foundation (2 days)
**Goal:** Add all required JNI methods before touching Java layer

**Deliverables:**
1. C++ implementation of bulk matching
2. C++ implementation of capture group extraction
3. C++ implementation of replace operations
4. C++ implementation of utility functions
5. JNI method signatures in RE2NativeJNI.java
6. Header file generation (`com_axonops_libre2_jni_RE2NativeJNI.h`)
7. Build verification on all platforms

**Branch:** `feature/re2-native-extensions`
**Merge into:** `development` (after all native methods work)

**Risk:** Native compilation failures on ARM64, cross-platform compatibility

---

### Phase 1: Bulk Matching API (1.5 days)
**Goal:** Minimize JNI overhead for high-throughput use cases

**Deliverables:**
1. `boolean[] matches(Collection<String>)` and array variant
2. `List<String> filter(Collection<String>)`
3. `List<String> filterNot(Collection<String>)`
4. Map filtering: `filterByKey`, `filterByValue`, `filterNotByKey`, `filterNotByValue`
5. In-place filtering: `retainMatches`, `removeMatches` (Collection and Map variants)
6. Comprehensive tests (correctness + performance benchmarks)
7. Metrics integration (bulk operation counters)
8. Documentation in Pattern.java Javadoc

**Branch:** `feature/bulk-matching`
**Merge into:** `development`

**Dependencies:** Phase 0 complete

---

### Phase 2: Capture Groups (2 days)
**Goal:** Enable structured data extraction from matches

**Deliverables:**
1. `MatchResult` class with group access
2. `MatchResult match(String)` - single match
3. `MatchResult find(String)` - find first
4. `List<MatchResult> findAll(String)` - find all
5. Named group support (`group(String name)`)
6. Batch variants: `matchWithGroups`, `findInEach`, `findAllInEach`
7. Position tracking (start/end indices)
8. Comprehensive tests (all group extraction scenarios)
9. Documentation and usage examples

**Branch:** `feature/capture-groups`
**Merge into:** `development`

**Dependencies:** Phase 0 complete

---

### Phase 3: Replace Operations (1.5 days)
**Goal:** Enable regex-based find/replace

**Deliverables:**
1. `String replaceFirst(String, String)` - replace first match
2. `String replaceAll(String, String)` - replace all matches
3. Backreference support ($1, $2, etc.) via RE2::Rewrite
4. `replaceAll(String, Function<MatchResult, String>)` - custom replacer
5. Batch variants: `replaceFirstInEach`, `replaceAllInEach`
6. Comprehensive tests (literal replacement, backreferences, edge cases)
7. Documentation and usage examples

**Branch:** `feature/replace-operations`
**Merge into:** `development`

**Dependencies:** Phase 0 complete, Phase 2 helpful (for custom replacer)

---

### Phase 4: Utilities (0.5 days)
**Goal:** Add helper functions for common operations

**Deliverables:**
1. `static String quoteMeta(String)` - escape special chars
2. `static String[] quoteMeta(Collection<String>)` - batch escape
3. `long programSize()` - pattern complexity
4. `Map<Integer, Integer> programFanout()` - DFA analysis
5. Tests and documentation

**Branch:** `feature/utilities`
**Merge into:** `development`

**Dependencies:** Phase 0 complete

---

### Phase 5: Integration & Polish (1 day)
**Goal:** Ensure all features work together, comprehensive testing

**Deliverables:**
1. Integration tests (combining multiple features)
2. Performance benchmarks (bulk vs single-string)
3. Update QUICKSTART.md with all new features
4. Update libre2-core/README.md
5. Verify all 187+ tests still pass
6. Update metrics documentation (if new metrics added)
7. Final code review and cleanup

**Branch:** `feature/integration-polish`
**Merge into:** `development`

**Dependencies:** Phases 0-4 complete

---

### Phase 6: Documentation & Release (0.5 days)
**Goal:** Prepare for 1.0.0 release

**Deliverables:**
1. CHANGELOG.md update
2. Version bump to 1.0.0
3. Comprehensive Javadoc review
4. Migration guide (0.9.x → 1.0.0)
5. Tag release
6. Prepare for Maven Central deployment

**Branch:** `release/1.0.0`
**Merge into:** `main` and `development`

**Dependencies:** Phase 5 complete

---

## Implementation Order Summary

```
Phase 0: Native Foundation (2 days)
  ├─ Bulk matching JNI (fullMatchBulk, partialMatchBulk)
  ├─ Capture groups JNI (extractGroups, extractGroupsBulk)
  ├─ Replace JNI (replace, replaceAll, replaceAllBulk)
  └─ Utilities JNI (quoteMeta, programSize, programFanout)

Phase 1: Bulk Matching (1.5 days)
  ├─ Collection matching/filtering
  └─ Map filtering variants

Phase 2: Capture Groups (2 days)
  ├─ MatchResult class
  ├─ Single-string APIs
  └─ Batch APIs

Phase 3: Replace Operations (1.5 days)
  ├─ Single-string replace
  └─ Batch replace

Phase 4: Utilities (0.5 days)
  └─ Static helper methods

Phase 5: Integration & Polish (1 day)
  └─ Testing, docs, benchmarks

Phase 6: Documentation & Release (0.5 days)
  └─ Release prep for 1.0.0
```

**Total Estimated Time:** 9 days

---

## Risk Assessment

### High Risk Items
1. **Native Code Compatibility**
   - Risk: Compilation failures on ARM64, different platforms
   - Mitigation: Test on all CI platforms after each native change

2. **JNI Memory Management**
   - Risk: Memory leaks in bulk operations (large arrays)
   - Mitigation: Careful use of JStringGuard, local ref cleanup

3. **Capture Group Complexity**
   - Risk: RE2's C++ API for groups is complex (submatch handling)
   - Mitigation: Start with simple cases, extensive testing

### Medium Risk Items
4. **Performance Regressions**
   - Risk: New code slows down existing paths
   - Mitigation: Benchmark suite, compare before/after

5. **API Design Changes**
   - Risk: Discover better API during implementation
   - Mitigation: Review design before coding each phase

### Low Risk Items
6. **Documentation Lag**
   - Risk: Code complete but docs missing
   - Mitigation: Write docs as features are implemented

---

## Success Criteria

### Functional Requirements
- ✅ All 31 new methods implemented and tested
- ✅ MatchResult class fully functional
- ✅ All tests pass (target: 240+ tests)
- ✅ No memory leaks (verified with long-running tests)
- ✅ Thread-safe (verified with concurrency tests)

### Performance Requirements
- ✅ Bulk matching 10-20x faster than individual calls
- ✅ Capture groups <10% overhead vs basic matching
- ✅ Replace operations comparable to java.util.regex

### Quality Requirements
- ✅ Comprehensive Javadoc for all new APIs
- ✅ Usage examples in QUICKSTART.md
- ✅ Clean build on all platforms
- ✅ No new compiler warnings
- ✅ Metrics properly tracking new operations

---

## Branch Strategy

```
main (stable releases)
  └─ development (active development)
      ├─ feature/re2-native-extensions (Phase 0)
      ├─ feature/bulk-matching (Phase 1)
      ├─ feature/capture-groups (Phase 2)
      ├─ feature/replace-operations (Phase 3)
      ├─ feature/utilities (Phase 4)
      ├─ feature/integration-polish (Phase 5)
      └─ release/1.0.0 (Phase 6)
```

**Merge Strategy:**
- Each feature branch merges to `development` after completion
- All tests must pass before merge
- Code review required for native code changes
- `development` merges to `main` for releases only

---

## Testing Strategy

### Unit Tests (Per Phase)
- Correctness tests for each new method
- Edge cases (empty input, null handling, invalid patterns)
- Error conditions (compilation failures, invalid groups)

### Integration Tests (Phase 5)
- Combining bulk + capture groups
- Combining replace + capture groups
- End-to-end workflows

### Performance Tests (Phase 5)
- Bulk matching vs single-string (expect 10-20x improvement)
- Memory usage under load
- Concurrency stress tests

### Platform Tests (Continuous)
- Linux x86_64, ARM64
- macOS x86_64, Apple Silicon
- Run on every commit via GitHub Actions

---

## Rollback Plan

If a phase fails or introduces critical bugs:

1. **Immediate:** Revert merge commit from `development`
2. **Analysis:** Identify root cause on feature branch
3. **Fix:** Implement fix on feature branch
4. **Re-test:** Full test suite passes
5. **Re-merge:** Merge corrected branch to `development`

**Critical Path:** Native code changes (Phase 0) are highest risk. If Phase 0 fails, all subsequent phases blocked.

---

## Dependencies

### External Dependencies (No Changes)
- JNA 5.13.0 (provided by host application)
- SLF4J 1.7+ (logging)
- Dropwizard Metrics 4.2.19 (optional, metrics)
- JUnit 5 (testing)

### Internal Dependencies
- RE2 native library (no version change, using existing API)
- Existing Pattern/Matcher classes (extend, don't break)
- Existing metrics infrastructure (add new metrics)

### Build Dependencies
- Maven 3.8+
- Java 17
- Native compilation toolchain (gcc/clang)

---

## Notes

- All implementations must maintain backward compatibility with 0.9.x API
- Existing tests (187 tests) must continue passing
- New features are additive only - no breaking changes
- Focus on correctness first, optimization second
- Extensive Javadoc required for all public APIs
