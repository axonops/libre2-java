# Phase 0: Native Cache Migration - Detailed Analysis

**Date:** 2025-11-28
**Status:** ANALYSIS COMPLETE - AWAITING USER DECISIONS

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Current Java Cache Architecture](#current-java-cache-architecture)
3. [Design Document Requirements](#design-document-requirements)
4. [Gap Analysis](#gap-analysis)
5. [Implementation Complexity Assessment](#implementation-complexity-assessment)
6. [Critical Questions for User](#critical-questions-for-user)
7. [Recommended Phased Approach](#recommended-phased-approach)

---

## Executive Summary

**Current State:**
- Java-based pattern caching with single `PatternCache` class (708 LOC)
- Dual eviction strategy: LRU (async) + TTL (background thread)
- Deferred cleanup queue for patterns still in use (refcount > 0)
- Configuration via `RE2Config` record (572 LOC)
- Metrics tracked: hits, misses, evictions, memory usage
- Native layer (re2_jni.cpp): 29 JNI functions, NO caching logic

**Design Document Requirements:**
- THREE separate C++ caches: Pattern Result Cache (optional), Pattern Compilation Cache, Deferred Cache
- Background eviction thread running every 100ms (vs. current 60s)
- Soft capacity limits with utilization ratio tracking
- Comprehensive metrics (~20+ metrics vs. current ~8)
- MurmurHash3 hashing (vs. current Java hashCode)
- JSON configuration and metrics export
- New Pattern Result Cache (not in current implementation)

**Key Discrepancies:**
1. **Cache Count:** Current = 1 cache, Design = 3 caches
2. **Pattern Result Cache:** NEW feature not in current Java implementation
3. **Eviction Frequency:** Current = 60s, Design = 100ms (600x faster!)
4. **Metrics Granularity:** Design has 2-3x more metrics than current
5. **Configuration Format:** Current = Java record, Design = JSON
6. **Hashing:** Current = Java hashCode(), Design = MurmurHash3

**Complexity Assessment:**
- **If implementing design as-is:** ~2,500-3,500 lines of C++ code
- **If migrating current functionality only:** ~800-1,200 lines of C++ code
- **Estimated effort:** 2-4 weeks full implementation + testing

---

## Current Java Cache Architecture

### 1. PatternCache.java (708 lines)

**Purpose:** Cache compiled RE2 patterns to avoid recompilation

**Data Structures:**
```java
ConcurrentHashMap<CacheKey, CachedPattern> cache;  // Main cache
CopyOnWriteArrayList<CachedPattern> deferredCleanup;  // Patterns evicted but refcount > 0
```

**Key Methods:**
- `getOrCompile(pattern, caseSensitive, compiler)` - Get cached or compile new pattern
- `evictIdlePatterns()` - Called by background thread, evicts patterns idle > 5min
- `evictLRUBatch(toEvict)` - Sample-based LRU eviction (async)
- `cleanupDeferredPatterns()` - Frees patterns with refcount == 0

**Eviction Strategy:**
1. **LRU Eviction (Soft Limit):**
   - Triggered when cache.size() > maxCacheSize (default 50,000)
   - Async execution via single-thread executor
   - Sample-based (scans 500 entries, not entire cache)
   - Eviction protection: 1 second minimum age before eviction

2. **Idle Eviction (Background Thread):**
   - Runs every 60 seconds (configurable via `evictionScanIntervalSeconds`)
   - Evicts patterns idle > 5 minutes (configurable via `idleTimeoutSeconds`)
   - Concurrent iteration (non-blocking)

3. **Deferred Cleanup:**
   - Runs every 5 seconds (configurable via `deferredCleanupIntervalSeconds`)
   - Frees patterns moved to deferred list when refcount → 0

**Metrics Tracked:**
- hits, misses
- evictionsLRU, evictionsIdle, evictionsDeferred
- currentSize, maxSize
- totalNativeMemoryBytes, peakNativeMemoryBytes
- deferredNativeMemoryBytes, peakDeferredNativeMemoryBytes
- invalidPatternRecompilations

**Thread Safety:**
- ConcurrentHashMap for lock-free reads/writes
- AtomicLong for all counters
- Sample-based eviction to minimize contention

### 2. IdleEvictionTask.java (130 lines)

**Purpose:** Background thread for idle eviction and deferred cleanup

**Execution Model:**
- Daemon thread at MIN_PRIORITY
- Wakes every 5 seconds for deferred cleanup
- Full idle scan every 60 seconds (configurable)
- Graceful shutdown with 5-second timeout

### 3. RE2Config.java (572 lines)

**Purpose:** Immutable configuration for cache behavior

**Key Parameters:**
```java
boolean cacheEnabled                          // Default: true
int maxCacheSize                              // Default: 50,000
long idleTimeoutSeconds                       // Default: 300 (5 min)
long evictionScanIntervalSeconds              // Default: 60
long deferredCleanupIntervalSeconds           // Default: 5
long evictionProtectionMs                     // Default: 1,000
int maxSimultaneousCompiledPatterns           // Default: 100,000
int maxMatchersPerPattern                     // Default: 10,000
boolean validateCachedPatterns                // Default: true
RE2MetricsRegistry metricsRegistry            // Default: NoOp
```

**Validation:**
- All positive value checks
- Scan interval ≤ idle timeout
- Deferred cleanup ≤ scan interval
- Cache size ≤ simultaneous limit

### 4. CacheStatistics.java (83 lines)

**Purpose:** Immutable snapshot of cache metrics

**Fields:**
```java
long hits, misses
long evictionsLRU, evictionsIdle, evictionsDeferred
int currentSize, maxSize
int deferredCleanupSize
long nativeMemoryBytes, peakNativeMemoryBytes
long invalidPatternRecompilations
```

**Derived Metrics:**
- hitRate(), missRate()
- totalEvictions()
- utilization()

---

## Design Document Requirements

### Three-Cache Architecture

#### 1. Pattern Result Cache (OPTIONAL - NEW FEATURE)

**Purpose:** Cache `(pattern_hash, input_string_hash) → boolean/match_result`

**Why:** Avoid re-evaluating same pattern on same input string

**Configuration:**
```cpp
bool pattern_result_cache_enabled              // Default: true
size_t pattern_result_cache_target_capacity_bytes  // Default: 100MB
size_t pattern_result_cache_string_threshold_bytes  // Default: 10KB (only cache small strings)
std::chrono::milliseconds pattern_result_cache_ttl_ms  // Default: 5 minutes
```

**Metrics (11 metrics):**
- hits, misses, hit_rate
- get_errors, put_errors
- ttl_evictions, lru_evictions, lru_evictions_bytes_freed
- current_entry_count, target_capacity_bytes, actual_size_bytes, utilization_ratio
- **PLUS from pseudocode:** total_bytes_freed, total_evictions

**Key Differences from Current:**
- This cache does NOT exist in current Java implementation
- Caches match RESULTS, not compiled patterns
- String threshold prevents caching large inputs
- Non-fatal errors (log metric, skip)

#### 2. Pattern Compilation Cache (REFERENCE-COUNTED)

**Purpose:** Cache compiled RE2 patterns (equivalent to current Java PatternCache)

**Configuration:**
```cpp
size_t pattern_cache_target_capacity_bytes     // Default: 100MB
std::chrono::milliseconds pattern_cache_ttl_ms  // Default: 5 minutes
```

**Metrics (13 metrics):**
- hits, misses, hit_rate
- compilation_errors
- ttl_evictions, lru_evictions, lru_evictions_bytes_freed
- entries_moved_to_deferred (NEW - split from current Java evictionsDeferred)
- current_entry_count, target_capacity_bytes, actual_size_bytes, utilization_ratio
- **PLUS from pseudocode:** total_bytes_freed, total_evictions, ttl_entries_moved_to_deferred, lru_entries_moved_to_deferred

**Key Differences from Current:**
- Capacity in BYTES (vs. current: entry count)
- Target capacity (soft limit) with utilization_ratio
- TTL per last access (current has idle timeout + LRU separately)
- Separate metrics for TTL vs. LRU movements to deferred

#### 3. Deferred Cache (LEAK PROTECTION)

**Purpose:** Hold patterns with refcount > 0 after eviction

**Configuration:**
```cpp
std::chrono::milliseconds deferred_cache_ttl_ms  // Default: 10 minutes
```

**Metrics (6 metrics):**
- immediate_evictions (refcount → 0)
- forced_evictions (TTL expired - LEAK!)
- forced_evictions_bytes_freed
- current_entry_count, actual_size_bytes
- **PLUS from pseudocode:** total_bytes_freed, total_evictions, immediate_evictions_bytes_freed

**Key Differences from Current:**
- Forced eviction after TTL (current Java doesn't have this)
- Separate cache structure (current uses CopyOnWriteArrayList)
- Leak detection logging on forced eviction

### Background Eviction Thread

**Design Requirements:**
```cpp
bool auto_start_eviction_thread                // Default: true
std::chrono::milliseconds eviction_check_interval_ms  // Default: 100ms
```

**Key Differences from Current:**
- **100ms interval** (vs. current Java: 60s for idle, 5s for deferred)
- **600x faster eviction frequency!**
- Single thread handles all three caches

### Configuration

**Design Requirements:**
- All parameters configurable (no hardcoded values)
- JSON configuration parsing (via folly::parseJson or similar)
- Validation in constructor

**Key Differences from Current:**
- JSON format (vs. current Java record/builder)
- Global `cache_enabled` flag to disable ALL caching
- Capacity in BYTES not entry count

### Metrics

**Design Requirements:**
- JSON export via `getMetricsJSON()`
- Atomic counters for lock-free updates
- Snapshot metrics (entry counts, sizes) under brief locks
- **~30+ metrics total** (vs. current ~8)

**Key Differences from Current:**
- 3-4x more metrics
- JSON serialization required
- Separate tracking for TTL vs. LRU evictions (both bytes and counts)

### Thread Safety

**Design Requirements:**
```cpp
// Lock Strategy
std::shared_mutex pattern_cache_mutex_;      // RwLock
std::shared_mutex result_cache_mutex_;       // RwLock
std::shared_mutex deferred_cache_mutex_;     // RwLock

// Lock Acquisition Order (Deadlock Prevention)
// 1. Pattern Cache
// 2. Result Cache
// 3. Deferred Cache
```

**Key Differences from Current:**
- Explicit read/write locks (vs. current ConcurrentHashMap)
- Defined lock acquisition order
- Pattern cache may have higher contention

### Hashing

**Design Requirement:** MurmurHash3 for pattern_string and input_string

**Key Difference from Current:**
- Current uses Java `hashCode()` (probably FNV-1a or similar)
- Need to implement or integrate MurmurHash3 in C++

---

## Gap Analysis

### Feature Gaps

| Feature | Current Java | Design Doc | Gap |
|---------|--------------|------------|-----|
| **Cache Count** | 1 (Pattern) | 3 (Result + Pattern + Deferred) | **HIGH** - Need 2 new caches |
| **Pattern Result Cache** | Not present | Optional but specified | **MEDIUM** - New feature |
| **Capacity Model** | Entry count | Bytes | **MEDIUM** - Need size estimation |
| **Eviction Interval** | 60s idle, 5s deferred | 100ms combined | **HIGH** - 600x faster! |
| **Metrics Count** | ~8 metrics | ~30 metrics | **MEDIUM** - 3-4x increase |
| **Deferred TTL** | No forced eviction | 10min forced eviction | **LOW** - Add timer |
| **Configuration Format** | Java record | JSON | **LOW** - JSON parsing |
| **Metrics Export** | Java objects | JSON | **LOW** - JSON serialization |
| **Hashing** | Java hashCode() | MurmurHash3 | **LOW** - Integrate library |

### Metric Gaps (Current → Design)

**Current Java Metrics (8):**
1. hits
2. misses
3. evictionsLRU
4. evictionsIdle
5. evictionsDeferred
6. currentSize
7. nativeMemoryBytes
8. peakNativeMemoryBytes

**Design Doc Metrics (~30+):**

**Pattern Result Cache (11):**
1. hits, 2. misses, 3. hit_rate
4. get_errors, 5. put_errors
6. ttl_evictions, 7. lru_evictions, 8. lru_evictions_bytes_freed
9. current_entry_count, 10. target_capacity_bytes, 11. actual_size_bytes, 12. utilization_ratio
13. total_bytes_freed (from pseudocode)
14. total_evictions (from pseudocode)

**Pattern Compilation Cache (13):**
1. hits, 2. misses, 3. hit_rate
4. compilation_errors
5. ttl_evictions, 6. lru_evictions, 7. lru_evictions_bytes_freed
8. entries_moved_to_deferred
9. current_entry_count, 10. target_capacity_bytes, 11. actual_size_bytes, 12. utilization_ratio
13. total_bytes_freed (from pseudocode)
14. total_evictions (from pseudocode)
15. ttl_entries_moved_to_deferred (from pseudocode)
16. lru_entries_moved_to_deferred (from pseudocode)

**Deferred Cache (6):**
1. immediate_evictions, 2. forced_evictions, 3. forced_evictions_bytes_freed
4. current_entry_count, 5. actual_size_bytes
6. total_bytes_freed (from pseudocode)
7. total_evictions (from pseudocode)
8. immediate_evictions_bytes_freed (from pseudocode)

**Missing from Design:**
- peakNativeMemoryBytes (current has this)
- invalidPatternRecompilations (current has this)

### Code Complexity Estimate

**If Implementing Full Design (3 caches):**

| Component | Estimated LOC | Complexity |
|-----------|---------------|------------|
| Configuration structs + JSON parsing | 200-300 | Medium |
| Metrics structs + JSON export | 300-400 | Medium |
| Pattern Result Cache implementation | 400-600 | Medium |
| Pattern Compilation Cache implementation | 600-800 | High |
| Deferred Cache implementation | 300-400 | Medium |
| Background eviction thread | 300-400 | High |
| MurmurHash3 integration | 100-150 | Low |
| Cache manager/orchestrator | 200-300 | Medium |
| JNI bindings (new functions) | 400-600 | High |
| **TOTAL** | **2,800-3,950 LOC** | **High** |

**If Migrating Current Functionality Only (1 cache + deferred):**

| Component | Estimated LOC | Complexity |
|-----------|---------------|------------|
| Configuration struct | 100-150 | Low |
| Metrics struct | 150-200 | Low |
| Pattern Compilation Cache | 400-600 | High |
| Deferred Cache | 200-300 | Medium |
| Background eviction thread | 250-350 | High |
| JNI bindings | 200-300 | Medium |
| **TOTAL** | **1,300-1,900 LOC** | **Medium** |

**Testing Estimate:**

| Test Type | Estimated LOC | Effort |
|-----------|---------------|--------|
| C++ unit tests (cache operations) | 800-1,200 | High |
| C++ integration tests (eviction, threading) | 400-600 | High |
| Memory leak tests (valgrind/asan) | 200-300 | Medium |
| Java integration tests (JNI layer) | 600-800 | High |
| Performance tests | 300-400 | Medium |
| **TOTAL** | **2,300-3,300 LOC** | **High** |

---

## Implementation Complexity Assessment

### High Complexity Areas

1. **Background Eviction Thread (100ms interval)**
   - **Challenge:** 600x faster than current (60s → 100ms)
   - **Risk:** High CPU overhead if not optimized
   - **Mitigation:** Need efficient data structures (no full cache scans)
   - **Question:** Is 100ms interval necessary? Current 60s works well.

2. **Capacity in Bytes (vs. Entry Count)**
   - **Challenge:** Need to estimate memory size of each entry
   - **Pattern size:** RE2::ProgramSize() or approximation
   - **Result size:** sizeof(MatchResult) + captured groups
   - **Risk:** Inaccurate estimates → incorrect eviction
   - **Mitigation:** Test with various pattern complexities

3. **Three Separate Caches (vs. One)**
   - **Challenge:** More complex orchestration
   - **Risk:** Lock ordering bugs → deadlocks
   - **Mitigation:** Strict lock acquisition order, thorough testing

4. **Pattern Result Cache (NEW Feature)**
   - **Challenge:** Not in current implementation → new behavior
   - **Risk:** Unknown performance impact
   - **Question:** Is this feature needed for Phase 1, or can it be Phase 2?

5. **MurmurHash3 Integration**
   - **Challenge:** Need to integrate or implement
   - **Options:** SMHasher library, inline implementation
   - **Risk:** Incorrect implementation → cache misses

### Medium Complexity Areas

1. **JSON Configuration & Metrics**
   - **Challenge:** Need JSON library (folly? nlohmann/json?)
   - **Risk:** Dependency management, build complexity
   - **Mitigation:** Use header-only library if possible

2. **JNI Integration**
   - **Challenge:** ~6-10 new JNI functions
   - **Risk:** Type conversions, exception handling
   - **Mitigation:** Follow existing re2_jni.cpp patterns

3. **Metrics Granularity (30+ metrics)**
   - **Challenge:** 3-4x more metrics than current
   - **Risk:** Code bloat, maintenance burden
   - **Mitigation:** Generate metrics code programmatically

### Low Complexity Areas

1. **Deferred Cache Forced Eviction**
   - **Challenge:** Add TTL check to deferred eviction
   - **Risk:** Low - straightforward timer logic

2. **Lock Strategy (shared_mutex)**
   - **Challenge:** Replace ConcurrentHashMap with RwLock
   - **Risk:** Low - standard pattern

---

## Critical Questions for User

### **Q1: Scope - Three Caches or One?**

**Design doc specifies THREE caches:**
1. Pattern Result Cache (optional, NEW feature)
2. Pattern Compilation Cache (equivalent to current PatternCache)
3. Deferred Cache (separate cache with forced eviction)

**Current Java has ONE cache:**
- PatternCache with deferred cleanup list

**Question:** Should I implement:
- **Option A:** All three caches as per design doc (full scope)
- **Option B:** Just Pattern Compilation Cache + Deferred Cache (migrate current functionality)
- **Option C:** Pattern Compilation Cache only, defer others to Phase 2

**Recommendation:** Start with Option B (current functionality), add Pattern Result Cache in Phase 2 if needed.

---

### **Q2: Pattern Result Cache Priority**

The Pattern Result Cache is a NEW feature not in current implementation.

**Purpose:** Cache `(pattern_hash, input_string_hash) → boolean/match_result` to avoid re-evaluation

**Question:** Should this be:
- **Option A:** Implemented in Phase 1 (full design implementation)
- **Option B:** Deferred to Phase 2 (after Pattern Cache working)
- **Option C:** Skipped entirely (out of scope)

**Trade-offs:**
- **Pros:** Performance boost for repeated pattern+input combinations
- **Cons:** Additional complexity, unclear if workload benefits from this

**Recommendation:** Option B (Phase 2) - validate Pattern Cache migration first, then add Result Cache.

---

### **Q3: Eviction Interval - 100ms vs. 60s**

**Design doc:** 100ms eviction interval (default)
**Current Java:** 60s idle scan, 5s deferred cleanup

**Question:** Confirm eviction interval requirements:
- **Option A:** 100ms as per design (600x faster than current)
- **Option B:** Keep current intervals (60s idle, 5s deferred) initially
- **Option C:** Make it configurable, start with current defaults

**Trade-offs:**
- **100ms pros:** Faster cleanup, tighter capacity control
- **100ms cons:** Higher CPU overhead, more thread contention
- **60s pros:** Proven to work well in current implementation

**Recommendation:** Option C - make it configurable, default to current values (60s/5s) initially. User can tune to 100ms if needed.

---

### **Q4: Capacity Model - Bytes vs. Entry Count**

**Design doc:** Capacity in bytes (e.g., 100MB)
**Current Java:** Entry count (e.g., 50,000 patterns)

**Question:** Confirm capacity model:
- **Option A:** Capacity in bytes as per design (need size estimation)
- **Option B:** Entry count (simpler, proven)
- **Option C:** Support both (configurable)

**Trade-offs:**
- **Bytes pros:** More accurate memory control
- **Bytes cons:** Size estimation can be inaccurate
- **Entry count pros:** Simpler, no estimation needed
- **Entry count cons:** Memory usage varies by pattern complexity

**Recommendation:** Option C - support both. Default to entry count for simplicity, add byte-based capacity later.

---

### **Q5: Java API Changes**

Moving caching to C++ changes the Java API significantly.

**Current Flow:**
```java
// Java manages cache
Pattern p = Pattern.compile(regex);  // → PatternCache.getOrCompile() → compiler.get()
```

**Proposed Flow (C++ caching):**
```java
// C++ manages cache
Pattern p = Pattern.compile(regex);  // → JNI call → C++ getOrCompile() → return handle
```

**Question:** Java API approach:
- **Option A:** Keep `PatternCache` class as thin JNI wrapper (preserve API)
- **Option B:** Remove `PatternCache`, call C++ directly from `Pattern.compile()`
- **Option C:** Hybrid - keep PatternCache for config management, delegate caching to C++

**Recommendation:** Option C - keep PatternCache for configuration and metrics integration, but move caching logic to C++.

---

### **Q6: Configuration Format**

**Design doc:** JSON configuration
**Current Java:** RE2Config record with builder

**Question:** Configuration approach:
- **Option A:** Java builds JSON string, passes to C++ (C++ parses JSON)
- **Option B:** Java passes individual parameters via JNI (no JSON in C++)
- **Option C:** JSON config file loaded by both Java and C++

**Recommendation:** Option A - Java serializes RE2Config to JSON, passes to C++ init function. Simplest integration.

---

### **Q7: Metrics Integration**

**Current:** RE2MetricsRegistry interface, Dropwizard adapter

**Question:** How should C++ metrics integrate with Java metrics?
- **Option A:** C++ exports JSON, Java parses and updates Dropwizard counters
- **Option B:** Each metric as separate JNI call (e.g., `getCacheHits()`)
- **Option C:** Bulk metrics struct via JNI, Java converts to Dropwizard

**Recommendation:** Option A - C++ has `getMetricsJSON()` JNI function, Java parses and updates metrics. Clean separation.

---

### **Q8: Testing Infrastructure**

**Design doc mentions:** valgrind/asan for memory leak testing

**Question:** Testing requirements:
- **Q8a:** Do we have CI infrastructure for valgrind/asan?
- **Q8b:** Which platforms support it? (macOS has different tools - leaks, MallocDebug)
- **Q8c:** Should memory leak tests run on every CI build or manually?

**Recommendation:** Start with manual leak testing during development, add to CI later if infrastructure exists.

---

### **Q9: Backward Compatibility**

**Context:** Project is pre-release (no published versions)

**Question:** Any backward compatibility requirements?
- **Option A:** No compatibility needed (can break Java API freely)
- **Option B:** Preserve PatternCache API for internal users
- **Option C:** Deprecate old API, provide migration path

**Recommendation:** Clarify if any internal users depend on current API.

---

### **Q10: Deferred Cache Design**

**Design doc:** Separate cache with immediate + forced eviction
**Current Java:** CopyOnWriteArrayList for deferred patterns

**Question:** Confirm deferred cache should be:
- **Option A:** Separate C++ cache (unordered_map) as per design
- **Option B:** Keep Java-managed deferred list (simpler)
- **Option C:** Hybrid - C++ tracks refcounts, Java manages deferred list

**Recommendation:** Option A - full migration to C++ for consistency.

---

## Recommended Phased Approach

Based on analysis, I recommend this phased approach:

### **Phase 1: Core Pattern Compilation Cache (C++) - 2 weeks**

**Scope:**
- Pattern Compilation Cache implementation (C++)
- Deferred Cache implementation (C++)
- Background eviction thread (60s/5s intervals initially)
- Configuration struct (entry-count capacity model)
- Basic metrics (hits, misses, evictions)
- JNI bindings for cache operations
- C++ unit tests + integration tests

**Deliverables:**
- `native/wrapper/pattern_cache.h/.cpp` (~800 LOC)
- `native/wrapper/deferred_cache.h/.cpp` (~300 LOC)
- `native/wrapper/cache_config.h/.cpp` (~200 LOC)
- `native/wrapper/cache_metrics.h/.cpp` (~200 LOC)
- `native/wrapper/eviction_thread.h/.cpp` (~300 LOC)
- Updated `re2_jni.cpp` (+200 LOC for new JNI functions)
- C++ tests (~1,000 LOC)

**Branch:** `feature/native-pattern-cache`

**Success Criteria:**
- All C++ tests pass
- No memory leaks (manual valgrind/asan testing)
- Metrics accurate
- Build succeeds on all 4 platforms

---

### **Phase 2: Java Integration - 1 week**

**Scope:**
- Update Java `PatternCache` to delegate to C++
- Update `Pattern.compile()` flow
- Parse C++ metrics JSON in Java
- Update Dropwizard metrics integration
- Java unit tests + integration tests
- Update existing Java cache tests

**Deliverables:**
- Updated `PatternCache.java` (reduced from 708 → ~200 LOC)
- Updated `Pattern.java` (call C++ cache via JNI)
- Updated `RE2Config.java` (add JSON serialization)
- Metrics adapter for JSON → Dropwizard
- Java tests (~800 LOC)

**Branch:** `feature/native-cache-java-integration` (off `feature/native-pattern-cache`)

**Success Criteria:**
- All Java tests pass
- Existing integration tests pass
- Metrics visible in Dropwizard
- Coverage ≥ current level

---

### **Phase 3: Pattern Result Cache (Optional) - 1 week**

**Scope:**
- Pattern Result Cache implementation (C++)
- String threshold configuration
- Result caching JNI functions
- Tests for result cache

**Deliverables:**
- `native/wrapper/result_cache.h/.cpp` (~500 LOC)
- Updated eviction thread (handle result cache)
- JNI bindings for result cache
- C++ + Java tests (~600 LOC)

**Branch:** `feature/pattern-result-cache` (off `feature/native-cache-java-integration`)

**Success Criteria:**
- Result cache hit rate >50% for repeated inputs
- No performance regression
- Metrics accurate

---

### **Phase 4: Optimization & Tuning - 1 week**

**Scope:**
- Byte-based capacity model (optional)
- Eviction interval tuning (100ms if needed)
- MurmurHash3 integration (if needed)
- Performance benchmarking
- Memory leak testing in CI

**Deliverables:**
- Performance test suite
- CI integration for leak detection
- Tuning documentation

**Branch:** `feature/cache-optimization` (off previous phase)

**Success Criteria:**
- Performance ≥ current Java cache
- No leaks detected in 24hr stress test
- Documentation complete

---

### **Phase 5: Cleanup & Documentation - 3 days**

**Scope:**
- Remove unused Java cache code
- Update documentation
- Final code review
- Merge to main

**Deliverables:**
- Updated README
- Migration guide
- Performance comparison report

**Success Criteria:**
- All tests passing
- Code review approved
- Documentation complete

---

## Token Usage Summary

**Phase 0 Analysis:**
- File reads: ~10,000 tokens
- Analysis documents: ~5,000 tokens
- **Total:** ~15,000 tokens
- **Remaining:** ~985,000 tokens

---

## Next Steps

1. **User answers Q1-Q10** (critical decisions)
2. **Create detailed implementation plan** based on answers
3. **Create feature branch** for Phase 1
4. **Begin C++ implementation** with continuous testing
5. **Track progress** in session log

---

**Status:** ANALYSIS COMPLETE - AWAITING USER INPUT ON CRITICAL QUESTIONS
