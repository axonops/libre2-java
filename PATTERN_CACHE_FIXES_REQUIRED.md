# Pattern Cache Required Fixes - Technical Debt

**Date:** 2025-11-28
**Status:** IDENTIFIED - IMPLEMENTATION PENDING
**Priority:** HIGH (Correctness + Performance)

---

## Summary

Current Pattern Cache implementation is **95% correct** with strict refcount invariant enforcement. However, 4 issues need fixing before production use:

1. ✅ **CRITICAL:** releasePattern() API design (key lookup can fail)
2. ✅ **IMPORTANT:** Explicit memory ordering (ARM64 correctness)
3. ✅ **NICE:** LRU consistency (std::min_element in both paths)
4. ✅ **PERFORMANCE:** Batch eviction (O(n²) → O(n + k log k))

All 114 tests currently passing. Fixes will maintain correctness while improving robustness and performance.

---

## Fix 1: releasePattern() API Redesign (CRITICAL)

### Current Problem

```cpp
void releasePattern(
    const std::string& pattern_string,  // ← Lookup by key
    bool case_sensitive,
    PatternCacheMetrics& metrics,       // ← Unused
    DeferredCache& deferred_cache) {    // ← Unused

    uint64_t key = makeKey(pattern_string, case_sensitive);

    // Dispatch to std or TBB
    if (using_tbb_) {
        // Find by key in TBB cache
        TBBMap::const_accessor acc;
        if (tbb_cache_.find(acc, key)) {  // ← CAN FAIL!
            acc->second.pattern->refcount.fetch_sub(1);
        }
    }
}
```

**Failure Scenario:**
1. Client gets pattern (refcount=1)
2. Client holds for 6+ minutes
3. Eviction: TTL expired, refcount=1 → moves to deferred cache
4. Client calls releasePattern("pattern") → **key not found in main cache**
5. Refcount never decremented → **MEMORY LEAK!**

### The Fix

```cpp
void releasePattern(
    std::shared_ptr<RE2Pattern>& pattern_ptr,  // ← Pass pointer
    PatternCacheMetrics& metrics) {

    if (!pattern_ptr) return;

    // Decrement refcount (works regardless of cache location)
    uint32_t prev = pattern_ptr->refcount.fetch_sub(1, std::memory_order_acq_rel);

    // Track metrics
    metrics.pattern_releases.fetch_add(1);
    if (prev == 1) {
        metrics.patterns_released_to_zero.fetch_add(1);
    }

    // Release shared_ptr
    pattern_ptr.reset();
}
```

**Why Better:**
- ✅ Caller already has pointer from getOrCompile()
- ✅ Works whether pattern in active cache or deferred cache
- ✅ No lookup needed (atomic operation only)
- ✅ Single implementation (no std/TBB dispatch)
- ✅ Clear semantics: "release this pattern pointer"

**Impact:**
- Remove releasePatternStd() and releasePatternTBB()
- Update all tests to pass pattern pointer
- Add metrics: pattern_releases, patterns_released_to_zero

---

## Fix 2: Explicit Memory Ordering (ARM64 CORRECTNESS)

### Current Problem

```cpp
// Implicit memory ordering (compiler-dependent)
pattern->refcount.store(1);                    // What order?
pattern->refcount.fetch_add(1);                // What order?
uint32_t rc = pattern->refcount.load();        // What order?
pattern->refcount.fetch_sub(1);                // What order?
```

**Why It's Wrong:**
- Works on x86 (strong memory model)
- **May fail on ARM64** (weak memory model)
- Code intent unclear
- Not following std::shared_ptr pattern

### The Fix

**Add explicit memory_order to ALL atomic operations:**

```cpp
// In getOrCompile() - cache hit (BOTH std and TBB):
pattern->refcount.fetch_add(1, std::memory_order_acq_rel);

// In getOrCompile() - cache miss (insert new pattern):
pattern->refcount.store(1, std::memory_order_release);

// In evict() - checking refcount (BOTH std and TBB):
uint32_t rc = pattern->refcount.load(std::memory_order_acquire);

// In releasePattern():
uint32_t prev = pattern->refcount.fetch_sub(1, std::memory_order_acq_rel);
```

**Memory Order Justification:**
- `store(1, release)`: New pattern visible to other threads
- `fetch_add(1, acq_rel)`: Acquire previous stores, release for next ops
- `load(acquire)`: Synchronize with all previous stores
- `fetch_sub(1, acq_rel)`: Full synchronization on release

**Files to Update:**
- pattern_cache.cpp: 8 locations (4 in std path, 4 in TBB path)
- deferred_cache.cpp: 2 locations

---

## Fix 3: LRU Consistency (MAINTAINABILITY)

### Current Inconsistency

**std path:**
```cpp
auto lru_it = std::min_element(std_cache_.begin(), std_cache_.end(),
    [](const auto& a, const auto& b) {
        return a.second.last_access < b.second.last_access;
    });
```

**TBB path:**
```cpp
uint64_t lru_key = 0;
auto lru_time = std::chrono::steady_clock::time_point::max();
for (TBBMap::iterator it = tbb_cache_.begin(); it != tbb_cache_.end(); ++it) {
    if (it->second.last_access < lru_time) {
        lru_time = it->second.last_access;
        lru_key = it->first;
    }
}
```

### The Fix

**Use std::min_element in TBB path too:**

```cpp
// In evictTBB() LRU section:
auto lru_it = std::min_element(tbb_cache_.begin(), tbb_cache_.end(),
    [](const auto& a, const auto& b) {
        return a.second.last_access < b.second.last_access;
    });

if (lru_it == tbb_cache_.end()) break;
uint64_t lru_key = lru_it->first;
```

**Benefits:**
- Code consistency (DRY principle)
- Easier to maintain
- More idiomatic C++

---

## Fix 4: Batch Eviction for LRU (PERFORMANCE)

### Current Problem

```cpp
// Evict one at a time (O(n²))
while (size > target) {
    auto lru = std::min_element(...);  // O(n) scan
    evict(lru);                         // Evict 1
}  // Loop and scan again → O(n²)
```

**At 10,000 patterns evicting 1,000:**
- 10,000 × 1,000 = 10M operations
- At 100ms eviction cycle = **100M ops/sec**
- Result: Eviction thread CPU starvation

### The Fix: Batch Eviction

```cpp
size_t evictLRUBatch(
    PatternCacheMetrics& metrics,
    DeferredCache& deferred_cache) {

    // Step 1: Collect candidates (refcount == 0)
    std::vector<std::pair<uint64_t, PatternCacheEntry*>> candidates;

    for (auto& [key, entry] : cache_) {
        if (entry.pattern->refcount.load(std::memory_order_acquire) == 0) {
            candidates.push_back({key, &entry});
        }
    }

    if (candidates.empty()) return 0;

    // Step 2: Partial sort to find N oldest
    size_t batch_size = std::min(
        config_.pattern_cache_lru_batch_size,
        candidates.size());

    std::partial_sort(
        candidates.begin(),
        candidates.begin() + batch_size,
        candidates.end(),
        [](const auto& a, const auto& b) {
            return a.second->last_access < b.second->last_access;
        });

    // Step 3: Evict batch
    size_t evicted = 0;
    for (size_t i = 0; i < batch_size; i++) {
        evictEntry(candidates[i].first, metrics, deferred_cache);
        evicted++;

        // Stop if back under capacity
        if (getTotalSizeBytes() <= config_.pattern_cache_target_capacity_bytes) {
            break;
        }
    }

    return evicted;
}
```

**Complexity:**
- Collect candidates: O(n)
- Partial sort: O(n + k log k) where k = batch_size
- Total: **O(n + k log k) = O(10k + 100×7) = ~11k ops**
- **100x faster than O(n²)**

**Performance:**
- At 10k patterns: 1-2ms per cycle
- At 100ms interval: ~2% CPU (acceptable)
- No extra locks (TBB concurrency preserved)

**Configuration:**
- `pattern_cache_lru_batch_size` (default: 100)
- Larger = fewer cycles to reach target (but more work per cycle)
- Smaller = more cycles (but less work per cycle)
- Tunable per workload

---

## Implementation Checklist

### Code Changes

**cache_config.h:**
- [x] Add `pattern_cache_lru_batch_size` field
- [x] Add to JSON parsing (default: 100)
- [x] Add validation (must be > 0)

**cache_config.cpp:**
- [x] Parse `pattern_cache_lru_batch_size` from JSON
- [x] Serialize to JSON
- [x] Validate > 0

**pattern_cache.h:**
- [ ] Change `releasePattern()` signature:
  - From: `(string, bool, metrics, deferred)`
  - To: `(shared_ptr<RE2Pattern>&, metrics)`
- [ ] Remove: `releasePatternStd()`, `releasePatternTBB()` declarations
- [ ] Add: Helper method `evictLRUBatch()` (both std and TBB)

**pattern_cache.cpp:**
- [ ] Implement new `releasePattern()` (single version)
- [ ] Remove old `releasePatternStd()` and `releasePatternTBB()`
- [ ] Add explicit memory ordering to ALL atomic ops:
  - [ ] `getOrCompileStd()`: 3 locations
  - [ ] `getOrCompileTBB()`: 3 locations
  - [ ] `evictStd()`: 1 location
  - [ ] `evictTBB()`: 1 location
- [ ] Replace O(n²) LRU eviction with batch eviction:
  - [ ] `evictStd()`: Implement batch collection + partial_sort
  - [ ] `evictTBB()`: Implement batch collection + partial_sort
- [ ] Use `std::min_element` in TBB path (consistency)

**pattern_cache_test.cpp:**
- [ ] Update all `releasePattern()` calls to pass pattern pointer
- [ ] Add test: `releasePattern()` decrements refcount correctly
- [ ] Add test: `releasePattern()` works after pattern evicted to deferred
- [ ] Add test: Batch eviction performance (verify < 5ms for 10k patterns)

**deferred_cache.h/.cpp:**
- [ ] Add explicit memory ordering to refcount.load() calls

**cache_metrics.h:**
- [ ] Add metrics: `pattern_releases`, `patterns_released_to_zero`

---

## Testing Plan

### Correctness Tests

1. **releasePattern() with pointer:**
   - Pattern in active cache → refcount decrements
   - Pattern in deferred cache → refcount decrements
   - Null pointer → no crash

2. **Memory ordering (run on ARM64):**
   - All tests pass on ARM64 Linux
   - valgrind/helgrind shows no data races

3. **Batch eviction:**
   - Correctly identifies oldest N patterns
   - Evicts batch in one pass
   - Stops when under capacity

### Performance Tests

1. **Batch eviction benchmark:**
   - 10,000 patterns
   - Force batch eviction
   - Measure time < 5ms

2. **Stress test:**
   - Continuous get/release for 10 seconds
   - Eviction running every 100ms
   - No CPU starvation

---

## Expected Results After Fixes

**Correctness:**
- ✅ No memory leaks (releasePattern always works)
- ✅ ARM64 safe (explicit memory ordering)
- ✅ Code consistency (both paths similar)

**Performance:**
- ✅ Eviction: 1-2ms per cycle (was ~100ms)
- ✅ 100x faster LRU eviction
- ✅ No CPU starvation

**Code Quality:**
- ✅ Simpler API (pointer-based release)
- ✅ Explicit synchronization intent
- ✅ Maintainable (consistent std/TBB logic)

---

## Implementation Order

1. Fix releasePattern() API (ripples to tests)
2. Add explicit memory ordering (8 locations)
3. Implement batch eviction (both std and TBB)
4. Fix LRU consistency (std::min_element in TBB)
5. Test all changes
6. Commit with detailed message
7. Tag checkpoint

**Estimated Time:** 2-3 hours
**Risk:** Medium (API change affects tests)
**Benefit:** High (correctness + 100x perf improvement)

---

**Status:** READY FOR IMPLEMENTATION
**Next Step:** Implement fixes in order above
