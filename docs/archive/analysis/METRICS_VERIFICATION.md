# Metrics Verification and Issues Found

## Critical Review of All 21 Metrics

### ✅ Latency Metrics (VERIFIED CORRECT)

**patterns.compilation.latency (Timer)**
- **Tracks:** Time from start to completion of `RE2NativeJNI.compile()`
- **Location:** Pattern.doCompile() lines 143-160
- **Verified:** ✅ Correctly measures actual pattern compilation time
- **Unit:** Nanoseconds

**matching.full_match.latency (Timer)**
- **Tracks:** Time from start to completion of `RE2NativeJNI.fullMatch()`
- **Location:** Matcher.matches() lines 68-75
- **Verified:** ✅ Correctly measures full match operation time
- **Unit:** Nanoseconds

**matching.partial_match.latency (Timer)**
- **Tracks:** Time from start to completion of `RE2NativeJNI.partialMatch()`
- **Location:** Matcher.find() lines 83-90
- **Verified:** ✅ Correctly measures partial match operation time
- **Unit:** Nanoseconds

---

### ❌ ISSUE: Deferred Evictions Metric

**Current metric: `cache.evictions.deferred.count`**
- **What it actually tracks:** CUMULATIVE count of patterns that have been freed from deferred list
- **What's missing:** CURRENT count of patterns waiting in deferred list
- **Problem:** Can't see current deferred cleanup backlog!

**Code analysis:**
```java
// Line 267, 316: When pattern added to deferred list
evictionsDeferred.incrementAndGet();  // AtomicLong (cumulative)

// Line 357: When pattern actually freed
config.metricsRegistry().incrementCounter("cache.evictions_deferred");  // Metrics counter
```

**So the counter tracks patterns that were freed, NOT patterns currently deferred.**

---

### Missing Metrics for Deferred Cleanup

**Need to add:**
1. `cache.deferred.patterns.count` (Gauge) - CURRENT deferred count
2. `cache.deferred.native_memory.bytes` (Gauge) - CURRENT memory in deferred patterns
3. `cache.deferred.native_memory.peak.bytes` (Gauge) - PEAK deferred memory

**Why critical:**
- Large deferred backlog = memory leak risk
- Need to monitor if deferred cleanup keeping up
- Peak deferred memory = worst-case memory pressure

---

### ❌ ISSUE: Confusing Counter Names

**Current:** `cache.evictions.deferred.count`
- **Meaning:** Cumulative patterns freed from deferred list
- **Confusion:** Sounds like current deferred count
- **Fix:** Rename to `cache.evictions.deferred.total.count` (cumulative)

---

## Corrected Metric Plan (24 total, was 21)

### Pattern Compilation (5)
- `patterns.compiled.total.count` (Counter) - cumulative compiled
- `patterns.cache.hits.total.count` (Counter) - cumulative hits
- `patterns.cache.misses.total.count` (Counter) - cumulative misses
- `patterns.compilation.latency` (Timer) - compilation time ✅
- `patterns.invalid.recompiled.total.count` (Counter) - cumulative recompilations

### Cache State (6)
- `cache.patterns.current.count` (Gauge) - current cached patterns
- `cache.native_memory.current.bytes` (Gauge) - current memory
- `cache.native_memory.peak.bytes` (Gauge) - peak memory

### Cache Evictions (6)
- `cache.evictions.lru.total.count` (Counter) - cumulative LRU evictions
- `cache.evictions.idle.total.count` (Counter) - cumulative idle evictions
- `cache.evictions.deferred.total.count` (Counter) - cumulative deferred freed

### Deferred Cleanup (3) **NEW**
- `cache.deferred.patterns.current.count` (Gauge) - patterns waiting to be freed
- `cache.deferred.native_memory.current.bytes` (Gauge) - memory in deferred patterns
- `cache.deferred.native_memory.peak.bytes` (Gauge) - peak deferred memory

### Resource Management (4)
- `resources.patterns.active.count` (Gauge) - active patterns
- `resources.matchers.active.count` (Gauge) - active matchers
- `resources.patterns.freed.total.count` (Gauge→Counter) - cumulative freed
- `resources.matchers.freed.total.count` (Gauge→Counter) - cumulative freed

### Performance (3)
- `matching.full_match.latency` (Timer) - full match time ✅
- `matching.partial_match.latency` (Timer) - partial match time ✅
- `matching.operations.total.count` (Counter) - total operations

### Errors (3)
- `errors.compilation.failed.total.count` (Counter) - compilation failures
- `errors.native_library.total.count` (Counter) - library errors
- `errors.resource.exhausted.total.count` (Counter) - resource limit hits

**Total: 27 metrics** (was 21, added 6)

---

## Action Items

1. ✅ Verify latency metrics track correct operations (VERIFIED)
2. ❌ Add 3 deferred cleanup metrics (MISSING)
3. ❌ Rename all metrics with improved naming (TODO)
4. ❌ Change resources.patterns/matchers_freed from Gauge to Counter (WRONG TYPE)
5. ❌ Update all tests (TODO)
