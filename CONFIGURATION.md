# Configuration Guide

Complete guide to configuring libre2-java for your production environment.

---

## Default Configuration

**The defaults work for most production deployments** (optimized for servers with 128GB+ RAM).

```java
RE2Config.DEFAULT:
- cacheEnabled: true
- maxCacheSize: 50,000 patterns (~50-200MB memory)
- idleTimeoutSeconds: 300 (5 minutes)
- evictionScanIntervalSeconds: 60 (1 minute)
- deferredCleanupIntervalSeconds: 5 (5 seconds)
- maxSimultaneousCompiledPatterns: 100,000 (ACTIVE, not cumulative)
- maxMatchersPerPattern: 10,000
```

**Estimated memory:** 50-200 MB (negligible in 128GB+ clusters)

---

## Configuration Parameters

### 1. cacheEnabled

**Type:** boolean
**Default:** `true`
**Purpose:** Enable/disable automatic pattern caching

**When `true`:**
- Patterns automatically cached (up to maxCacheSize)
- Huge performance improvement for repeated patterns
- Automatic resource management (no manual close() needed)
- LRU + idle eviction manages memory

**When `false`:**
- No caching (every compile() creates new pattern)
- Users must manually close() patterns
- Lower memory usage
- Slower for repeated patterns
- Use only if memory extremely constrained

**Recommendation:** Keep `true` for production

---

### 2. maxCacheSize

**Type:** int
**Default:** `50,000`
**Range:** 1 to maxSimultaneousCompiledPatterns
**Purpose:** Maximum patterns to keep in cache

**Memory Impact:**
- ~1-4 KB per simple pattern
- ~10-50 KB per complex pattern
- 50K patterns ≈ 50-200 MB

**When to increase:**
- High pattern diversity (many unique patterns)
- Lots of RAM available
- Want maximum cache hit rate

**When to decrease:**
- Memory constrained environment
- Low pattern diversity (few unique patterns)
- Smaller deployments

**Examples:**
```java
.maxCacheSize(10000)   // Small: 10K patterns (~10-40MB)
.maxCacheSize(100000)  // Large: 100K patterns (~100-400MB)
```

---

### 3. idleTimeoutSeconds

**Type:** long
**Default:** `300` (5 minutes)
**Range:** > 0
**Purpose:** Evict patterns unused for this many seconds

**How it works:**
- Pattern last used at 10:00 AM
- Idle timeout = 300s (5 min)
- Background scan at 10:05 AM: Pattern evicted (idle for 5 min)

**When to increase:**
- Patterns reused infrequently but regularly
- Want to keep patterns cached longer
- Lots of RAM available

**When to decrease:**
- Want aggressive memory reclamation
- Patterns used in bursts then never again
- Memory constrained

**Examples:**
```java
.idleTimeoutSeconds(60)    // Aggressive: 1 minute
.idleTimeoutSeconds(600)   // Relaxed: 10 minutes
.idleTimeoutSeconds(3600)  // Very relaxed: 1 hour
```

---

### 4. evictionScanIntervalSeconds

**Type:** long
**Default:** `60` (1 minute)
**Range:** > 0, should be ≤ idleTimeoutSeconds
**Purpose:** How often background thread scans for idle patterns

**Trade-off:**
- **More frequent:** Faster idle detection, more CPU overhead
- **Less frequent:** Slower idle detection, less CPU overhead

**Relationship with idleTimeout:**
- Pattern idle for idleTimeoutSeconds
- But only evicted when next scan runs
- Actual eviction time: idleTimeout + (up to scanInterval)

**Examples:**
```java
.evictionScanIntervalSeconds(30)   // Frequent scans
.evictionScanIntervalSeconds(300)  // Infrequent scans (5 min)
```

**Recommendation:** Keep at 60s unless specific need

---

### 5. deferredCleanupIntervalSeconds

**Type:** long
**Default:** `5` (5 seconds)
**Range:** > 0, must be ≤ evictionScanIntervalSeconds
**Purpose:** How often to cleanup patterns that were evicted while still in use - prevents memory leaks

#### What This Protects Against

1. **Memory Leaks:** Ensures patterns that were evicted while in use are eventually freed
2. **Native Memory Retention:** Minimizes time that evicted patterns remain in memory
3. **Use-After-Free Crashes:** Reference counting prevents crashes, but cleanup ensures memory is freed

#### How Deferred Cleanup Works

When a pattern is evicted but still has active Matchers (refCount > 0):
1. Pattern is removed from cache (can't be looked up anymore)
2. But native resources CAN'T be freed yet (would crash active Matchers)
3. Pattern is added to "deferred cleanup" list
4. Background thread checks list every N seconds
5. When refCount reaches 0 (all Matchers closed), native resources are freed

**Without deferred cleanup:** Memory leak! Pattern removed from cache, never freed.

**Example scenario:**
```
Time 0:00 - Pattern P in cache, being used by 10 Matchers (refCount=10)
Time 0:01 - Cache eviction removes P (need space)
          - P can't be freed (refCount=10), added to deferred list
Time 0:02 - 8 Matchers close (refCount=2)
Time 0:03 - Last 2 Matchers close (refCount=0)
Time 0:05 - Deferred cleanup runs, sees refCount=0, frees P ✓
```

**Why 5 seconds default:**
- Typical Matcher lifetime is milliseconds to seconds
- 5s gives plenty of time for normal operations to complete
- Frequent enough to keep memory retention low
- Not so frequent that it wastes CPU

**When to tune:**
- **High load, memory critical:** Decrease to 1-2s for faster cleanup
- **Low load, CPU constrained:** Increase to 10-15s
- **Debugging leaks:** Decrease to 1s to identify issues faster

**Examples:**
```java
.deferredCleanupIntervalSeconds(1)   // Very frequent (memory-critical systems)
.deferredCleanupIntervalSeconds(15)  // Less frequent (CPU-constrained)
```

**Validation:** Must be ≤ evictionScanIntervalSeconds (cleanup at least as often as idle scan)

---

### 6. maxSimultaneousCompiledPatterns

**Type:** int
**Default:** `100,000`
**Range:** > 0, must be ≥ maxCacheSize
**Purpose:** Limit on ACTIVE (simultaneous) patterns to prevent memory exhaustion and DoS attacks

**CRITICAL:** This is NOT a cumulative limit!

#### What This Protects Against

1. **Memory Exhaustion:** Prevents runaway pattern allocation from consuming all available memory
2. **ReDoS Amplification:** Limits damage from malicious or buggy code that compiles thousands of patterns
3. **Resource Starvation:** Ensures one component can't monopolize native memory
4. **Denial of Service:** Prevents attackers from overwhelming the system with pattern compilations

#### How It Works

- Counts patterns **currently in memory** (allocated but not yet freed)
- Includes: cached patterns + uncached patterns currently in use
- When limit reached, new compile() calls throw `ResourceException`
- Patterns that are freed (via eviction or close) reduce the count

#### What it does NOT limit

- Total patterns compiled over library lifetime (unlimited)
- Patterns that have been freed can be recompiled
- This is intentional: long-running servers can compile millions over time

**Example:**
```
maxSimultaneousCompiledPatterns = 100,000

Day 1: Compile 100,000 patterns → OK
       Close all 100,000 patterns → count back to 0

Day 2: Compile 100,000 NEW patterns → OK (previous freed)

After 1 month: 10,000,000 patterns compiled (cumulative)
               But only 100,000 simultaneous at any point

This is OK! Limit is on ACTIVE count, not cumulative.
```

#### Important: This is a Safety Limit

If you hit this limit in production, it indicates one of:
1. **Configuration too low:** Increase the limit for your workload
2. **Resource leak:** Code is compiling patterns but not allowing them to be evicted
3. **Attack:** Malicious input attempting to exhaust resources
4. **Bug:** Application bug creating excessive patterns

Monitor `ResourceTracker.getActivePatternCount()` to understand normal usage.

**When to increase:**
- Very high concurrency (1000s of concurrent operations)
- Each operation uses unique patterns
- Lots of RAM available

**When to decrease:**
- Memory constrained environment
- Stricter DoS protection needed
- Running untrusted user-supplied patterns

**Examples:**
```java
.maxSimultaneousCompiledPatterns(50000)   // Stricter limit (tighter security)
.maxSimultaneousCompiledPatterns(500000)  // Higher limit (more capacity)
```

---

### 7. maxMatchersPerPattern

**Type:** int
**Default:** `10,000`
**Range:** > 0
**Purpose:** Limit concurrent Matchers per Pattern to prevent per-pattern resource exhaustion

#### What This Protects Against

1. **Per-Pattern Resource Exhaustion:** Prevents a single "hot" pattern from consuming all resources
2. **Runaway Matcher Creation:** Catches bugs where code creates matchers in a loop without closing them
3. **Memory Leak Detection:** If limit is hit, likely indicates Matchers not being closed properly
4. **Use-After-Free Prevention:** Reference counting tracks active Matchers to prevent patterns from being freed while in use

#### How It Works

- Each Pattern has a reference count (refCount)
- Creating a Matcher increments refCount
- Closing a Matcher decrements refCount
- When refCount would exceed limit, throws `ResourceException`

**When exceeded:**
- `pattern.matcher()` throws `ResourceException`
- Prevents new matchers on that specific pattern
- Other patterns are unaffected

#### Important: This Catches Bugs

If you hit this limit, it almost always indicates a bug:
- **Matcher not closed:** Code calling `pattern.matcher()` without `try-with-resources`
- **Infinite loop:** Creating matchers in a loop that never terminates
- **Async issue:** Matchers created but close() never called

**Example of common bug:**
```java
// BAD - Matcher never closed, will eventually hit limit!
for (String line : millionLines) {
    Matcher m = pattern.matcher(line);  // Creates matcher
    if (m.find()) { ... }
    // m.close() never called - LEAK!
}

// GOOD - Always use try-with-resources
for (String line : millionLines) {
    try (Matcher m = pattern.matcher(line)) {
        if (m.find()) { ... }
    }  // Automatically closed
}
```

**Typical workload:** 10-100 concurrent matchers per pattern
**Default 10K:** Provides comfortable safety margin for legitimate use

**When to increase:**
- Very high concurrency on popular patterns (1000s of threads)
- Each operation creates multiple matchers legitimately

**When to decrease:**
- Stricter resource control needed
- Want earlier detection of matcher leaks

**Examples:**
```java
.maxMatchersPerPattern(1000)   // Stricter (catch leaks faster)
.maxMatchersPerPattern(50000)  // More permissive (very high concurrency)
```

---

## Custom Configuration Example

```java
// High-throughput, lots of RAM
RE2Config highThroughput = RE2Config.builder()
    .cacheEnabled(true)
    .maxCacheSize(100000)                      // 100K patterns
    .idleTimeoutSeconds(600)                   // 10 min idle
    .evictionScanIntervalSeconds(120)          // Scan every 2 min
    .deferredCleanupIntervalSeconds(2)         // Cleanup every 2s (fast)
    .maxSimultaneousCompiledPatterns(500000)   // 500K simultaneous
    .maxMatchersPerPattern(20000)              // 20K matchers per pattern
    .build();

// Memory-constrained environment
RE2Config memoryConstrained = RE2Config.builder()
    .cacheEnabled(true)
    .maxCacheSize(5000)                        // 5K patterns only
    .idleTimeoutSeconds(60)                    // 1 min idle (aggressive)
    .evictionScanIntervalSeconds(30)           // Scan every 30s
    .deferredCleanupIntervalSeconds(5)         // Cleanup every 5s
    .maxSimultaneousCompiledPatterns(10000)    // 10K simultaneous
    .maxMatchersPerPattern(1000)               // 1K matchers per pattern
    .build();

// Cache disabled (manual management)
RE2Config noCache = RE2Config.NO_CACHE;
// Users must close() patterns manually
```

---

## Configuration Validation

**The following are validated and will throw IllegalArgumentException:**

1. ✅ maxCacheSize > 0 (when cache enabled)
2. ✅ idleTimeoutSeconds > 0 (when cache enabled)
3. ✅ evictionScanIntervalSeconds > 0 (when cache enabled)
4. ✅ deferredCleanupIntervalSeconds > 0 (when cache enabled)
5. ✅ deferredCleanupIntervalSeconds ≤ evictionScanIntervalSeconds
6. ✅ maxSimultaneousCompiledPatterns > 0 (always)
7. ✅ maxMatchersPerPattern > 0 (always)
8. ✅ maxCacheSize ≤ maxSimultaneousCompiledPatterns

**Warnings (still valid, but suboptimal):**
- ⚠️ evictionScanIntervalSeconds > idleTimeoutSeconds
- ⚠️ deferredCleanupIntervalSeconds > 30s (memory retention risk)

---

## Monitoring Configuration

**Check current configuration:**
```java
CacheStatistics stats = Pattern.getCacheStatistics();

stats.maxSize();              // Current maxCacheSize
stats.currentSize();          // How many patterns in cache now
stats.deferredCleanupPending();  // Patterns awaiting cleanup
stats.hits() / stats.misses();   // Hit rate
```

**Resource usage:**
```java
ResourceTracker.ResourceStatistics res = ResourceTracker.getStatistics();

res.activePatterns();         // Current simultaneous patterns
res.totalCompiled();          // Cumulative over lifetime
res.totalClosed();            // Cumulative closed
res.hasPotentialLeaks();      // true if leaking
```

---

## Tuning Recommendations

### For High-Throughput Servers

**Default config is optimal for most high-memory servers.**

If you see ResourceExceptions or memory issues:
- Check cache hit rate via `Pattern.getCacheStatistics()` (should be > 80%)
- Increase maxCacheSize if low hit rate
- Check deferredCleanupPending (should be low, < 100)

### For Log Processing / ETL Pipelines

**Consider larger cache, faster cleanup:**
```java
.maxCacheSize(100000)              // More patterns for diverse log formats
.deferredCleanupIntervalSeconds(2) // Faster cleanup between batches
```

### For Memory-Constrained Environments

**Smaller cache, more aggressive eviction:**
```java
.maxCacheSize(10000)        // Smaller cache (~10-40MB)
.idleTimeoutSeconds(60)     // Aggressive eviction (1 min idle)
.evictionScanIntervalSeconds(30)  // Frequent scans
```

### For Extreme Concurrency (1000s of threads)

**Higher limits, frequent cleanup:**
```java
.maxSimultaneousCompiledPatterns(500000)  // More simultaneous patterns
.maxMatchersPerPattern(50000)             // More matchers per pattern
.deferredCleanupIntervalSeconds(1)        // Very frequent cleanup
```

### For Untrusted User Input (Security-Critical)

**Stricter limits to prevent abuse:**
```java
.maxSimultaneousCompiledPatterns(10000)  // Strict limit
.maxMatchersPerPattern(1000)             // Strict per-pattern limit
.maxCacheSize(5000)                      // Smaller cache
```

---

## Common Questions

**Q: Why is maxSimultaneousCompiledPatterns NOT cumulative?**

A: Allows unlimited compilations over library lifetime. Patterns can be freed and recompiled. Only limits SIMULTANEOUS active patterns at any point in time. Critical for long-running Cassandra instances.

**Q: What happens when limits exceeded?**

A: `ResourceException` thrown. Query fails. Metric incremented for monitoring.

**Q: What is deferred cleanup?**

A: When pattern evicted but still in use by Matchers, can't free immediately. Added to "deferred" list, freed when safe (every 5s by default). Prevents memory leaks.

**Q: Do I need to close() patterns?**

A: **No** (for cached patterns from `compile()`). Cache manages lifecycle. Only close() patterns from `compileWithoutCache()` (testing only).

---

## Configuration in Practice

**Current limitation:** Configuration uses static cache (single global config).

**Workaround for now:** Adjust defaults before first Pattern.compile() call.

**Coming in Phase 3:** Dynamic configuration per-instance.

---

## Performance Impact of Configuration

| Setting | Performance Impact | Memory Impact |
|---------|-------------------|---------------|
| Larger maxCacheSize | ✅ Higher hit rate | ⚠️ More memory |
| Smaller maxCacheSize | ⚠️ Lower hit rate | ✅ Less memory |
| Shorter idleTimeout | ⚠️ More evictions | ✅ Less memory |
| Longer idleTimeout | ✅ Fewer evictions | ⚠️ More memory |
| More frequent deferred cleanup | ✅ Less memory retention | ⚠️ Slightly more CPU |
| Less frequent deferred cleanup | ⚠️ More memory retention | ✅ Less CPU |

**For production:** Use defaults unless profiling shows need to tune.
