# Phase 4 Implementation Plan Summary

**Date:** 2025-11-20
**Status:** Planning Complete ✅ (Revised for Generic Integration)
**Ready to Implement:** Yes

---

## What We're Building

Phase 4 adds production-grade observability to libre2-java as a **100% generic, framework-agnostic library**:

### 1. Metrics Integration (Optional Dropwizard Metrics)
- **21 comprehensive metrics** across 5 categories
- **Zero-dependency design** - works with or without Dropwizard
- **Framework-agnostic** - no hardcoded framework references
- **Configurable prefix** - users specify metric namespace
- **JMX-compatible** - standard Dropwizard JMX exposure
- **Zero overhead** when metrics disabled (NoOp default)

### 2. Enhanced Logging (SLF4J)
- **"RE2:" prefix** on all messages for easy filtering
- **Pattern hashing** for privacy (no sensitive regex in logs)
- **Structured messages** - grep-able, consistent format
- **Appropriate log levels** - TRACE/DEBUG/INFO/WARN/ERROR

---

## Key Architectural Decisions

### Decision: Abstract Metrics Interface
**Why:** Dropwizard Metrics is optional (provided scope)
- If user provides it → full metrics support
- If user doesn't → library still works, just no metrics

**Implementation:**
```
RE2MetricsRegistry (interface)
    ↓ implements
    ├── NoOpMetricsRegistry (default, zero overhead)
    └── DropwizardMetricsAdapter (optional, wraps Cassandra's MetricRegistry)
```

### Decision: Configurable Metric Prefix
**Why:** Different frameworks/apps want metrics in their namespace
**Default:** `com.axonops.libre2` (if user doesn't specify)
**Examples:**
- Cassandra: `org.apache.cassandra.metrics.RE2`
- Spring Boot app: `com.mycompany.myapp.regex`
- Standalone: `com.axonops.libre2` (default)

### Decision: Dropwizard Metric Types
Based on official Cassandra usage:
- **Counter** - atomic long counters (patterns compiled, cache hits/misses, errors)
- **Timer** - duration measurements in nanoseconds (compilation time, match time)
- **Gauge** - instantaneous values via supplier (cache size, memory usage, active resources)

---

## The 21 Metrics

### Pattern Compilation (5 metrics)
- `patterns.compiled` (Counter) - total patterns compiled
- `patterns.cache_hits` (Counter) - cache hit count
- `patterns.cache_misses` (Counter) - cache miss count
- `patterns.compilation_time` (Timer) - compilation latency
- `patterns.invalid_recompiled` (Counter) - corrupted patterns auto-fixed

### Cache Eviction (6 metrics)
- `cache.evictions_lru` (Counter) - LRU evictions
- `cache.evictions_idle` (Counter) - idle-time evictions
- `cache.evictions_deferred` (Counter) - deferred cleanup evictions
- `cache.size` (Gauge) - current cache size
- `cache.native_memory_bytes` (Gauge) - off-heap memory used
- `cache.native_memory_peak_bytes` (Gauge) - peak memory

### Resource Management (4 metrics)
- `resources.patterns_active` (Gauge) - active pattern count
- `resources.matchers_active` (Gauge) - active matcher count
- `resources.patterns_freed` (Counter) - patterns closed
- `resources.matchers_freed` (Counter) - matchers closed

### Performance (3 metrics)
- `matching.full_match` (Timer) - full match latency
- `matching.partial_match` (Timer) - partial match latency
- `matching.operations` (Counter) - total match operations

### Errors (3 metrics)
- `errors.compilation_failed` (Counter) - compilation failures
- `errors.native_library` (Counter) - native library errors
- `errors.resource_exhausted` (Counter) - resource limits hit

---

## Integration Examples

### Example 1: Standalone Application
```java
// Create your own MetricRegistry
MetricRegistry registry = new MetricRegistry();

// Configure JMX reporting (optional)
JmxReporter jmxReporter = JmxReporter.forRegistry(registry).build();
jmxReporter.start();

// Create RE2 config with metrics
RE2Config config = new RE2Config.Builder()
    .metricsRegistry(new DropwizardMetricsAdapter(registry, "com.myapp.regex"))
    .build();

// Use the config
PatternCache cache = new PatternCache(config);
Pattern.setGlobalCache(cache);

// Metrics now visible in JMX under com.myapp.regex.*
```

### Example 2: Framework Integration (e.g., Cassandra)
```java
// Framework code (e.g., SAI index initialization):
// Get framework's existing MetricRegistry
MetricRegistry frameworkRegistry = getFrameworkMetricRegistry();

// Create libre2-java config with framework's registry
RE2Config config = new RE2Config.Builder()
    .metricsRegistry(new DropwizardMetricsAdapter(
        frameworkRegistry,
        "org.apache.cassandra.metrics.SAI.RE2Index"
    ))
    .build();

// Set as global cache config
PatternCache cache = new PatternCache(config);
Pattern.setGlobalCache(cache);

// Now all libre2-java metrics appear in framework's MetricRegistry
// and are automatically exposed via framework's existing JMX reporter
```

### Example 3: No Metrics (Default)
```java
// Just use defaults - NoOp metrics, zero overhead
RE2Config config = RE2Config.DEFAULT;
PatternCache cache = new PatternCache(config);
// No metrics overhead, library works normally
```

**JMX ObjectNames (examples based on prefix):**
- With `com.myapp.regex` → `com.myapp.regex:type=patterns,name=compiled`
- With `org.apache.cassandra.metrics.RE2` → `org.apache.cassandra.metrics:type=RE2,name=patterns.compiled`
- Format depends on Dropwizard's JmxReporter configuration

---

## Implementation Tasks

### Task 1: Create Metrics Infrastructure ✅ (planned)
**New files:**
- `src/main/java/com/axonops/libre2/metrics/RE2MetricsRegistry.java`
- `src/main/java/com/axonops/libre2/metrics/NoOpMetricsRegistry.java`
- `src/main/java/com/axonops/libre2/metrics/DropwizardMetricsAdapter.java`

**Modified files:**
- `src/main/java/com/axonops/libre2/cache/RE2Config.java` (add metricsRegistry field)

### Task 2: Enhance Logging ⏳
**Modify:**
- Pattern.java, PatternCache.java, IdleEvictionTask.java, RE2LibraryLoader.java, ResourceTracker.java

**Changes:**
- Add "RE2:" prefix to all messages
- Implement `hashPattern(String)` utility
- Adjust log levels (move high-freq to TRACE)
- Standardize message format

### Task 3: Integrate Metrics Collection ⏳
**Add metrics calls to:**
- `PatternCache.getOrCompile()` - cache hits/misses, compilation timer
- `PatternCache.evictLRU/evictIdle()` - eviction counters
- `Pattern.compileUncached()` - compilation timer, error counter
- `Matcher.matches/find()` - match timer
- Register gauges for cache size, memory, active resources

### Task 4: Write Tests ⏳
**New test files:**
- `MetricsIntegrationTest.java` - end-to-end metrics flow
- `DropwizardAdapterTest.java` - adapter correctness
- `CassandraIntegrationTest.java` - simulate Cassandra registry
- `MetricsPerformanceTest.java` - verify overhead < 1%

### Task 5: Generic Framework Integration Validation ⏳
**Goal:** Prove metrics integrate with any external MetricRegistry
**Test:** Create MetricRegistry, perform operations, verify all 21 metrics present with custom prefix

---

## Success Criteria

Phase 4 complete when:

- ✅ All 21 metrics implemented
- ✅ RE2MetricsRegistry interface + NoOp + Dropwizard adapter working
- ✅ RE2Config accepts metricsRegistry parameter
- ✅ All log messages have "RE2:" prefix
- ✅ Pattern hashing implemented
- ✅ Generic framework integration test passing (no hardcoded framework refs)
- ✅ JMX exposure verified
- ✅ Performance overhead < 1%
- ✅ All tests passing (204 + new metrics tests)
- ✅ Documentation updated

---

## Performance Expectations

**Metrics overhead (with Dropwizard enabled):**
- Counter increment: ~10-20ns
- Timer recording: ~50-100ns
- Gauge registration: one-time cost, ~1μs

**Total expected overhead:** < 1% on compilation/matching operations

**NoOp metrics (default):**
- Zero overhead - methods are empty and JIT-inlined

---

## Next Steps

1. ✅ Implementation plan complete
2. ⏳ Create metrics package and interfaces
3. ⏳ Implement NoOp and Dropwizard adapters
4. ⏳ Update RE2Config
5. ⏳ Enhance logging
6. ⏳ Integrate metrics collection points
7. ⏳ Write comprehensive tests
8. ⏳ Validate Cassandra integration
9. ⏳ Update PHASE_4_COMPLETE.md

---

## Documentation References

**Cassandra Metrics:**
- https://cassandra.apache.org/doc/stable/cassandra/managing/operating/metrics.html

**Dropwizard Metrics:**
- https://metrics.dropwizard.io/4.2.0/manual/core.html

**Implementation Plan:**
- PHASE_4_IMPLEMENTATION_PLAN.md (comprehensive 500+ line plan)

---

## Questions Answered

**Q: Should metric prefix be configurable?**
A: Yes - users specify prefix in DropwizardMetricsAdapter constructor. Default: `com.axonops.libre2`

**Q: Should we have framework-specific code (e.g., forCassandra() methods)?**
A: NO - library must be 100% generic. Users configure via Builder with their own registry + prefix.

**Q: Should we track per-pattern metrics?**
A: No - only global aggregates (per-pattern would be unbounded and expensive)

**Q: Should we register JmxReporter?**
A: No - users configure JmxReporter themselves (or use framework's existing reporter)

**Q: How do gauges get updated?**
A: Via Supplier lambda - Dropwizard calls supplier on-demand when gauge is read

**Q: Multi-module project (core + cassandra modules)?**
A: Not needed - core is already generic. Users just pass their MetricRegistry + prefix to the adapter.

---

## Ready to Implement! 🚀

The plan is comprehensive, based on official documentation, and designed specifically for Cassandra 5 integration. All architectural decisions are documented, and the implementation path is clear.

**Estimated time:** 1 session (4-6 hours)
