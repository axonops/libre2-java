# Phase 4 Implementation Plan: Logging and Metrics Integration

**Date:** 2025-11-20
**Phase:** Logging and Metrics for Cassandra 5 Integration
**Status:** Planning Complete - Ready for Implementation

---

## Executive Summary

Phase 4 implements production-grade observability through SLF4J logging and optional Dropwizard Metrics integration. The library is **completely generic and framework-agnostic**, usable in any Java application.

**Key Goal:** Provide flexible metrics integration that works with any monitoring system (Cassandra, Spring Boot, standalone apps, etc.) without coupling the library to any specific framework.

---

## Current State Analysis

### What We Have
1. **Existing SLF4J Logging:**
   - Logger instances in: PatternCache, Pattern, IdleEvictionTask, RE2LibraryLoader, ResourceTracker
   - Basic TRACE/DEBUG logging for operations
   - No standardized message format
   - No consistent "RE2:" prefixing
   - No pattern hashing for privacy/readability

2. **Statistics Infrastructure:**
   - CacheStatistics record with comprehensive metrics
   - AtomicLong counters throughout PatternCache
   - Native memory tracking
   - No external metrics integration

3. **Dependencies (already in pom.xml):**
   - SLF4J 2.0.9 (provided scope) ✅
   - Dropwizard Metrics 4.2.19 (provided scope, optional) ✅

### What We Need
1. **Standardized Logging:**
   - All messages prefixed with "RE2:"
   - Pattern hashing: `Integer.toHexString(pattern.hashCode())`
   - Consistent log levels (TRACE/DEBUG/INFO/ERROR)
   - Structured, grep-able messages

2. **Metrics Integration:**
   - Abstract metrics interface (framework-agnostic)
   - Optional Dropwizard Metrics adapter (if user provides Dropwizard)
   - JMX-compatible metric naming (via Dropwizard's standard behavior)
   - Zero overhead when metrics disabled (NoOp default)

---

## Dropwizard Metrics Integration (Generic, Framework-Agnostic)

### How Dropwizard Metrics Works (from official documentation)

1. **MetricRegistry:**
   - Central registry for all metrics (thread-safe)
   - Applications typically maintain one or more registries
   - Can be shared across components using `SharedMetricRegistries`

2. **Metric Types:**
   - **Gauge**: Instantaneous values (e.g., cache size, memory usage)
   - **Counter**: Atomic long counters (e.g., operations count)
   - **Histogram**: Statistical distributions with percentiles
   - **Timer**: Frequency + duration histograms in nanoseconds
   - **Meter**: Throughput with exponentially-weighted moving averages (1/5/15-min)

3. **Metric Naming (dotted notation):**
   ```
   com.example.Queue.requests.size
   com.mycompany.myapp.cache.hits
   ```
   - Hierarchical dotted names within MetricRegistry
   - Helper: `MetricRegistry.name(Class, String...)` for consistent naming
   - JMX ObjectName automatically derived from dotted name

4. **JMX Exposure:**
   - Dropwizard provides `JmxReporter` to expose metrics via JMX
   - ObjectName pattern: derived from metric's dotted name
   - Users configure JMX reporter separately (not our responsibility)

5. **Our Integration Strategy (100% Generic):**
   - Accept optional `RE2MetricsRegistry` at initialization
   - If provided: use that registry (could be Dropwizard, or custom implementation)
   - If not provided: use NoOp (zero overhead)
   - **Metric naming prefix:** Configurable via adapter constructor (default: `com.axonops.libre2`)
   - Support any Dropwizard metric types: Counter, Timer, Gauge

### Example Integration Patterns:

**Pattern 1: Standalone Application**
```java
// User creates their own MetricRegistry
MetricRegistry registry = new MetricRegistry();
RE2Config config = new RE2Config.Builder()
    .metricsRegistry(new DropwizardMetricsAdapter(registry, "com.myapp.regex"))
    .build();
```

**Pattern 2: Framework Integration (e.g., Cassandra)**
```java
// Framework provides its singleton registry
MetricRegistry frameworkRegistry = getFrameworkMetricRegistry();
RE2Config config = new RE2Config.Builder()
    .metricsRegistry(new DropwizardMetricsAdapter(frameworkRegistry, "org.framework.module.RE2"))
    .build();
```

**Pattern 3: No Metrics (Default)**
```java
// Just use defaults - NoOp metrics, zero overhead
RE2Config config = RE2Config.DEFAULT;
```

---

## Architecture Design

### Design Principle: Zero-Dependency Abstraction

**Problem:** Dropwizard Metrics is `provided` scope (optional dependency)
- If user provides it: full metrics support
- If user doesn't: library still works, just no metrics

**Solution:** Abstract interface + optional adapter

```
┌─────────────────────────────────────────────────────────────┐
│                         Pattern.java                        │
│                     PatternCache.java                       │
│                    (business logic)                         │
└──────────────────────┬──────────────────────────────────────┘
                       │
                       │ calls
                       ▼
           ┌───────────────────────┐
           │  RE2MetricsRegistry   │  ◄─── Abstract interface
           │     (interface)       │       (always available)
           └───────────────────────┘
                       ▲
                       │ implements
          ┌────────────┴────────────┐
          │                         │
┌─────────┴──────────┐   ┌──────────┴─────────────┐
│  NoOpMetrics       │   │  DropwizardMetrics     │
│  (default)         │   │  (if dependency        │
│                    │   │   available)           │
└────────────────────┘   └────────────────────────┘
                                    │
                                    │ wraps
                                    ▼
                         ┌──────────────────────┐
                         │ MetricRegistry       │
                         │ (Dropwizard/         │
                         │  Cassandra)          │
                         └──────────────────────┘
```

### Components to Implement

#### 1. `RE2MetricsRegistry` (Interface)
```java
package com.axonops.libre2.metrics;

import java.util.function.Supplier;

/**
 * Abstract metrics registry interface.
 * Allows libre2-java to work with or without Dropwizard Metrics.
 *
 * Follows Dropwizard Metrics patterns:
 * - Counter: Atomic long counters
 * - Timer: Measures duration in nanoseconds
 * - Gauge: Instantaneous value from supplier
 */
public interface RE2MetricsRegistry {
    /**
     * Increment a counter by 1.
     * Maps to Dropwizard Counter.inc()
     */
    void incrementCounter(String name);

    /**
     * Increment a counter by delta.
     * Maps to Dropwizard Counter.inc(delta)
     */
    void incrementCounter(String name, long delta);

    /**
     * Record a timer measurement in nanoseconds.
     * Maps to Dropwizard Timer.update(duration, TimeUnit.NANOSECONDS)
     */
    void recordTimer(String name, long durationNanos);

    /**
     * Register a gauge that computes value on-demand.
     * Maps to Dropwizard Gauge registration.
     */
    void registerGauge(String name, Supplier<Number> valueSupplier);

    /**
     * Remove a gauge (for cleanup).
     */
    void removeGauge(String name);
}
```

#### 2. `NoOpMetricsRegistry` (Default)
```java
package com.axonops.libre2.metrics;

import java.util.function.Supplier;

/**
 * No-op implementation when metrics are disabled.
 * Zero overhead - all methods are empty and will be inlined by JIT.
 */
public final class NoOpMetricsRegistry implements RE2MetricsRegistry {
    public static final NoOpMetricsRegistry INSTANCE = new NoOpMetricsRegistry();

    private NoOpMetricsRegistry() { }

    @Override public void incrementCounter(String name) { }
    @Override public void incrementCounter(String name, long delta) { }
    @Override public void recordTimer(String name, long durationNanos) { }
    @Override public void registerGauge(String name, Supplier<Number> valueSupplier) { }
    @Override public void removeGauge(String name) { }
}
```

#### 3. `DropwizardMetricsAdapter` (Optional)
```java
package com.axonops.libre2.metrics;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Generic Dropwizard Metrics adapter.
 * Only compiled if Dropwizard dependency is present (provided scope).
 *
 * Thread-safe: MetricRegistry and all Dropwizard metric types are thread-safe.
 *
 * Usage examples:
 * - Standalone: new DropwizardMetricsAdapter(myRegistry, "com.myapp.regex")
 * - Framework: new DropwizardMetricsAdapter(frameworkRegistry, "org.framework.component")
 */
public final class DropwizardMetricsAdapter implements RE2MetricsRegistry {
    private final MetricRegistry registry;
    private final String prefix;

    /**
     * Create adapter with default prefix: com.axonops.libre2
     *
     * @param registry the Dropwizard MetricRegistry to register metrics with
     */
    public DropwizardMetricsAdapter(MetricRegistry registry) {
        this(registry, "com.axonops.libre2");
    }

    /**
     * Create adapter with custom metric prefix.
     *
     * @param registry the Dropwizard MetricRegistry to register metrics with
     * @param prefix   the metric name prefix (e.g., "com.myapp.regex")
     */
    public DropwizardMetricsAdapter(MetricRegistry registry, String prefix) {
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.prefix = Objects.requireNonNull(prefix, "prefix cannot be null");
    }

    @Override
    public void incrementCounter(String name) {
        registry.counter(metricName(name)).inc();
    }

    @Override
    public void incrementCounter(String name, long delta) {
        registry.counter(metricName(name)).inc(delta);
    }

    @Override
    public void recordTimer(String name, long durationNanos) {
        registry.timer(metricName(name)).update(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void registerGauge(String name, Supplier<Number> valueSupplier) {
        String fullName = metricName(name);
        // Remove existing gauge if present (idempotent registration)
        registry.remove(fullName);
        // Register new gauge
        registry.register(fullName, (Gauge<Number>) valueSupplier::get);
    }

    @Override
    public void removeGauge(String name) {
        registry.remove(metricName(name));
    }

    private String metricName(String name) {
        return MetricRegistry.name(prefix, name);
    }
}
```

#### 4. `RE2Config` Enhancement
```java
// Add to RE2Config.java:
public record RE2Config(
    // ... existing 8 fields ...
    RE2MetricsRegistry metricsRegistry  // NEW - 9th field
) {
    public static final RE2Config DEFAULT = new RE2Config(
        true,                              // cacheEnabled
        50000,                             // maxCacheSize
        300,                               // idleTimeoutSeconds
        60,                                // evictionScanIntervalSeconds
        5,                                 // deferredCleanupIntervalSeconds
        100000,                            // maxSimultaneousCompiledPatterns
        1000,                              // maxMatchersPerPattern
        true,                              // validateCachedPatterns
        NoOpMetricsRegistry.INSTANCE       // metricsRegistry - disabled by default
    );

    // Builder class gets metricsRegistry setter
    public static class Builder {
        private RE2MetricsRegistry metricsRegistry = NoOpMetricsRegistry.INSTANCE;
        // ... existing fields ...

        public Builder metricsRegistry(RE2MetricsRegistry metricsRegistry) {
            this.metricsRegistry = Objects.requireNonNull(metricsRegistry);
            return this;
        }

        // ... existing builder methods ...
    }
}
```

**Note:** No framework-specific methods. Users configure via Builder:

```java
// Example 1: Standalone app with Dropwizard
MetricRegistry myRegistry = new MetricRegistry();
RE2Config config = new RE2Config.Builder()
    .metricsRegistry(new DropwizardMetricsAdapter(myRegistry, "com.myapp.regex"))
    .build();

// Example 2: Framework integration
RE2Config config = new RE2Config.Builder()
    .metricsRegistry(new DropwizardMetricsAdapter(frameworkRegistry, "org.framework.RE2"))
    .build();

// Example 3: No metrics (default)
RE2Config config = RE2Config.DEFAULT;
```

---

## Metrics to Expose

### Category 1: Pattern Compilation Metrics

| Metric Name (dotted) | Dropwizard Type | JMX Type | Description | Cassandra Benefit |
|-------------|------|------|-------------|-------------------|
| `patterns.compiled` | Counter | counter | Total patterns compiled since start | Track SAI pattern usage |
| `patterns.cache_hits` | Counter | counter | Cache hit count | Measure cache effectiveness |
| `patterns.cache_misses` | Counter | counter | Cache miss count | Identify cache sizing issues |
| `patterns.compilation_time` | Timer | timer | Pattern compilation latency (ns) | Detect slow patterns |
| `patterns.invalid_recompiled` | Counter | counter | Invalid patterns auto-recompiled | Monitor corruption |

### Category 2: Cache Eviction Metrics

| Metric Name (dotted) | Dropwizard Type | JMX Type | Description |
|-------------|------|------|-------------|
| `cache.evictions_lru` | Counter | counter | LRU evictions triggered |
| `cache.evictions_idle` | Counter | counter | Idle-time evictions |
| `cache.evictions_deferred` | Counter | counter | Deferred cleanup evictions |
| `cache.size` | Gauge | gauge | Current cache size (pattern count) |
| `cache.native_memory_bytes` | Gauge | gauge | Native memory used (off-heap) |
| `cache.native_memory_peak_bytes` | Gauge | gauge | Peak native memory since start |

### Category 3: Resource Management Metrics

| Metric Name (dotted) | Dropwizard Type | JMX Type | Description |
|-------------|------|------|-------------|
| `resources.patterns_active` | Gauge | gauge | Active pattern count (non-closed) |
| `resources.matchers_active` | Gauge | gauge | Active matcher count (non-closed) |
| `resources.patterns_freed` | Counter | counter | Patterns freed (closed) since start |
| `resources.matchers_freed` | Counter | counter | Matchers freed (closed) since start |

### Category 4: Performance Metrics

| Metric Name (dotted) | Dropwizard Type | JMX Type | Description |
|-------------|------|------|-------------|
| `matching.full_match` | Timer | timer | Full match operation latency (ns) |
| `matching.partial_match` | Timer | timer | Partial match operation latency (ns) |
| `matching.operations` | Counter | counter | Total match operations |

### Category 5: Error Metrics

| Metric Name (dotted) | Dropwizard Type | JMX Type | Description |
|-------------|------|------|-------------|
| `errors.compilation_failed` | Counter | counter | Pattern compilation failures |
| `errors.native_library` | Counter | counter | Native library errors |
| `errors.resource_exhausted` | Counter | counter | Resource limit hit |

**Total: 21 metrics** (16+ as specified in CLAUDE.md)

**JMX ObjectName Examples (prefix depends on user configuration):**

With default prefix `com.axonops.libre2`:
```
com.axonops.libre2:type=patterns,name=compiled
com.axonops.libre2:type=patterns,name=compilation_time
com.axonops.libre2:type=cache,name=size
```

With custom prefix `com.myapp.regex`:
```
com.myapp.regex:type=patterns,name=compiled
com.myapp.regex:type=patterns,name=compilation_time
com.myapp.regex:type=cache,name=size
```

*Note: Actual JMX ObjectName format depends on Dropwizard's JmxReporter configuration, which users configure separately.*

---

## Logging Strategy

### Log Levels

**TRACE (high-frequency, low-value):**
- Cache hit/miss per operation
- Pattern created/freed
- Matcher created/freed
- Eviction scan details

**DEBUG (moderate-frequency, operational):**
- Pattern compilation success with details
- Cache eviction triggered (LRU/idle)
- Native memory tracking updates

**INFO (low-frequency, lifecycle):**
- Library initialization
- Cache initialization
- Background thread start/stop
- Configuration applied

**WARN (unexpected but recoverable):**
- Invalid cached pattern detected and recompiled
- Resource limit approaching
- Cached pattern close() attempt (user mistake)

**ERROR (failures):**
- Pattern compilation failure
- Native library error
- Resource leak detected
- Fatal initialization error

### Message Format

**Pattern:**
```
RE2: <context> - <message> [details]
```

**Examples:**
```
INFO  RE2: Library loaded - version: 1.0.0, platform: darwin-aarch64
DEBUG RE2: Pattern compiled - hash: 7a3f2b1c, length: 25, caseSensitive: true, fromCache: false, nativeBytes: 1024
DEBUG RE2: Cache hit - hash: 7a3f2b1c, hitRate: 87.3%
TRACE RE2: Matcher created - pattern: 7a3f2b1c, refCount: 3
WARN  RE2: Invalid cached pattern detected - hash: 9c4e1f2a, recompiling
ERROR RE2: Pattern compilation failed - hash: 5d6a8b9c, error: invalid character class
```

### Pattern Hashing

```java
private static String hashPattern(String pattern) {
    return Integer.toHexString(pattern.hashCode());
}
```

**Rationale:**
- Privacy: Don't log potentially sensitive regex patterns
- Readability: Logs aren't cluttered with 200-char patterns
- Debuggable: Hash is consistent across logs, easy to grep

---

## Implementation Tasks

### Task 1: Create Metrics Infrastructure
**Files to create:**
- `src/main/java/com/axonops/libre2/metrics/RE2MetricsRegistry.java` (interface)
- `src/main/java/com/axonops/libre2/metrics/NoOpMetricsRegistry.java` (default)
- `src/main/java/com/axonops/libre2/metrics/DropwizardMetricsAdapter.java` (optional)

**Files to modify:**
- `src/main/java/com/axonops/libre2/cache/RE2Config.java` (add metricsRegistry field)

**Testing:**
- Verify NoOp has zero overhead (benchmark)
- Verify Dropwizard adapter delegates correctly
- Verify optional dependency handling

---

### Task 2: Enhance Logging
**Files to modify:**
- `src/main/java/com/axonops/libre2/api/Pattern.java`
- `src/main/java/com/axonops/libre2/cache/PatternCache.java`
- `src/main/java/com/axonops/libre2/cache/IdleEvictionTask.java`
- `src/main/java/com/axonops/libre2/jni/RE2LibraryLoader.java`
- `src/main/java/com/axonops/libre2/util/ResourceTracker.java`

**Changes:**
- Add "RE2:" prefix to all log messages
- Implement pattern hashing utility
- Adjust log levels (move high-freq to TRACE)
- Standardize message format

---

### Task 3: Integrate Metrics Collection Points
**Locations to add metrics calls:**

**PatternCache.java:**
- `getOrCompile()`: increment cache hit/miss counters, record compilation timer
- `evictLRU()`: increment LRU eviction counter
- `evictIdle()`: increment idle eviction counter
- `performDeferredCleanup()`: increment deferred eviction counter
- Cache size: register gauge
- Native memory: register gauge

**Pattern.java:**
- `compileUncached()`: record compilation timer, increment counter
- `compileUncached()` error: increment error counter
- `close()`: increment patterns freed counter

**Matcher.java:**
- Constructor: increment active matchers gauge
- `matches()` / `find()`: record matching timer
- `close()`: increment matchers freed counter

**RE2LibraryLoader.java:**
- Load failure: increment error counter

---

### Task 4: Write Tests
**Test files to create:**
- `src/test/java/com/axonops/libre2/metrics/MetricsIntegrationTest.java`
- `src/test/java/com/axonops/libre2/metrics/DropwizardAdapterTest.java`
- `src/test/java/com/axonops/libre2/metrics/CassandraIntegrationTest.java`

**Test scenarios:**
1. NoOp metrics (default): verify zero overhead
2. Dropwizard adapter: verify all 21 metrics tracked
3. Cassandra integration: simulate Cassandra passing its MetricRegistry
4. JMX exposure: verify metrics appear in JMX
5. Concurrent metrics: 100 threads, verify no lost updates
6. Metrics accuracy: compile 1000 patterns, verify counters match
7. Error metrics: trigger errors, verify error counters increment

---

### Task 5: Generic Framework Integration Validation
**Goal:** Prove library integrates seamlessly with external MetricRegistry

**Test approach:**
```java
@Test
void testDropwizardMetricsIntegration() {
    // Create a MetricRegistry (simulating any framework: Cassandra, Spring, etc.)
    MetricRegistry externalRegistry = new MetricRegistry();

    // Create libre2-java config with external registry and custom prefix
    RE2Config config = new RE2Config.Builder()
        .metricsRegistry(new DropwizardMetricsAdapter(externalRegistry, "com.example.myapp.regex"))
        .build();

    // Use libre2-java with this config
    PatternCache cache = new PatternCache(config);
    Pattern.setGlobalCache(cache);

    // Perform operations
    Pattern pattern = Pattern.compile("test.*pattern");
    Matcher matcher = pattern.matcher("test some pattern");
    matcher.matches();

    // Verify metrics appear in external registry with correct naming
    assertThat(externalRegistry.getCounters())
        .containsKey("com.example.myapp.regex.patterns.compiled");

    assertThat(externalRegistry.getTimers())
        .containsKey("com.example.myapp.regex.patterns.compilation_time");

    // Verify metrics are actually updating
    long compiledCount = externalRegistry.counter("com.example.myapp.regex.patterns.compiled").getCount();
    assertThat(compiledCount).isGreaterThan(0);

    // Verify gauges are registered
    assertThat(externalRegistry.getGauges())
        .containsKey("com.example.myapp.regex.cache.size");
}

@Test
void testJmxExposure() {
    // Setup JmxReporter (user responsibility, but we test it works)
    MetricRegistry registry = new MetricRegistry();
    JmxReporter jmxReporter = JmxReporter.forRegistry(registry).build();
    jmxReporter.start();

    // Configure libre2-java
    RE2Config config = new RE2Config.Builder()
        .metricsRegistry(new DropwizardMetricsAdapter(registry, "com.test.libre2"))
        .build();

    PatternCache cache = new PatternCache(config);
    Pattern.setGlobalCache(cache);
    Pattern.compile("test");

    // Verify JMX exposure (ObjectName format depends on JmxReporter config)
    MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
    Set<ObjectName> mbeans = mBeanServer.queryNames(
        new ObjectName("com.test.libre2:*"), null
    );
    assertThat(mbeans).isNotEmpty();

    jmxReporter.stop();
}
```

**Success criteria:**
- Metrics appear in external MetricRegistry with custom prefix
- JMX exposure works when user configures JmxReporter
- No exceptions during integration
- Performance overhead < 1%
- 100% framework-agnostic (no hardcoded framework references)

---

## Testing Strategy

### Unit Tests
- Each metrics adapter: verify delegation
- Pattern hashing: verify consistency
- Metrics accuracy: verify counters/timers
- Optional dependency: verify graceful degradation

### Integration Tests
- End-to-end metrics flow
- Cassandra MetricRegistry integration
- JMX exposure verification

### Performance Tests
- Metrics overhead: measure impact on compilation/matching
- NoOp metrics: verify zero overhead
- Concurrent metrics: verify thread-safety

### Acceptance Test
**"Drop-in" Test:**
1. Create MetricRegistry (simulating Cassandra)
2. Create RE2Config with that registry
3. Compile 10,000 patterns
4. Perform 100,000 matches
5. Verify all 21 metrics present in registry
6. Verify metrics accessible via JMX
7. Verify zero errors
8. Verify performance overhead < 1%

---

## Success Criteria

### Phase 4 Complete When:

1. **Logging:**
   - [ ] All log messages prefixed with "RE2:"
   - [ ] Pattern hashing implemented and used consistently
   - [ ] Log levels appropriate (TRACE/DEBUG/INFO/WARN/ERROR)
   - [ ] Messages grep-able and structured

2. **Metrics:**
   - [ ] 21+ metrics implemented
   - [ ] RE2MetricsRegistry interface defined
   - [ ] NoOpMetricsRegistry (default) working
   - [ ] DropwizardMetricsAdapter working
   - [ ] RE2Config accepts metricsRegistry parameter

3. **Cassandra Integration:**
   - [ ] Can accept Cassandra's singleton MetricRegistry
   - [ ] Metrics appear in that registry with correct naming
   - [ ] JMX exposure working (ObjectName pattern correct)
   - [ ] Zero integration errors
   - [ ] Performance overhead < 1%

4. **Testing:**
   - [ ] All metrics tests passing
   - [ ] Cassandra integration test passing
   - [ ] JMX exposure test passing
   - [ ] Performance overhead validated
   - [ ] Total test count: 204 + new metrics tests

5. **Documentation:**
   - [ ] PHASE_4_COMPLETE.md created with verification
   - [ ] DEVELOPMENT_STATUS.md updated
   - [ ] DECISION_LOG.md updated with metrics decisions

---

## Risks and Mitigations

### Risk 1: Dropwizard Optional Dependency
**Risk:** Compilation fails if Dropwizard not on classpath
**Mitigation:** Use Maven profiles or separate module for Dropwizard adapter

### Risk 2: Metrics Overhead
**Risk:** Metrics slow down critical path
**Mitigation:**
- Use NoOp by default
- Keep metrics collection lightweight (increment counters only)
- Benchmark to verify overhead < 1%

### Risk 3: Cassandra Compatibility
**Risk:** Metrics don't integrate with Cassandra as expected
**Mitigation:**
- Research Cassandra's exact integration pattern
- Test with actual Cassandra 5 MetricRegistry
- Validate JMX ObjectName patterns match Cassandra's

### Risk 4: Thread Safety
**Risk:** Concurrent metrics updates lose data
**Mitigation:**
- Use Dropwizard's thread-safe Counter/Timer/Histogram
- Test with 100+ concurrent threads
- Verify no lost updates

---

## Timeline Estimate

**Estimated effort:** 1 session (4-6 hours)

**Breakdown:**
- Task 1 (Metrics infrastructure): 1 hour
- Task 2 (Logging enhancement): 1 hour
- Task 3 (Metrics integration): 1.5 hours
- Task 4 (Tests): 1.5 hours
- Task 5 (Cassandra validation): 1 hour

---

## Next Steps

1. Create metrics package and interfaces
2. Implement NoOp and Dropwizard adapters
3. Enhance RE2Config to accept metrics registry
4. Enhance logging with RE2: prefix and pattern hashing
5. Integrate metrics collection points
6. Write comprehensive tests
7. Validate Cassandra integration
8. Update documentation

---

## Questions for User

1. **Metric Prefix:** Should we use `com.axonops.libre2` or allow Cassandra to specify custom prefix like `org.apache.cassandra.metrics.RE2Index`?
   - **Recommendation:** Accept prefix as parameter in adapter constructor

2. **Metrics Granularity:** Should we track per-pattern metrics (e.g., "pattern X was matched 1000 times") or just global aggregates?
   - **Recommendation:** Global aggregates (per-pattern would be unbounded)

3. **JMX Reporter:** Should we automatically register JmxReporter, or leave that to Cassandra?
   - **Recommendation:** Leave to Cassandra (they already have JmxReporter configured)

4. **Cassandra Version Testing:** Do you have a Cassandra 5.0 environment for integration testing?
   - **Fallback:** Simulate with standalone MetricRegistry + JmxReporter

---

## Summary

Phase 4 implements production-grade observability specifically designed for Cassandra 5 integration:

**What we're building:**
- Abstract metrics interface (works with or without Dropwizard)
- Dropwizard Metrics adapter for Cassandra integration
- 21+ comprehensive metrics (counters, timers, gauges, histograms)
- Standardized "RE2:" logging with pattern hashing
- Zero-overhead default (NoOp metrics)
- Seamless Cassandra MetricRegistry integration
- JMX-compatible naming for monitoring tools

**Why this matters:**
- Cassandra operators can monitor RE2 pattern compilation/matching
- Metrics appear in existing Cassandra monitoring (JMX, nodetool, dashboards)
- Zero configuration required (unless user wants metrics)
- Performance overhead < 1%
- "Drop-in" experience for Cassandra integration

**Success criteria:**
- Library integrates with Cassandra's singleton MetricRegistry
- All 21+ metrics accessible via JMX
- Logging consistent and grep-able
- Performance overhead negligible
- All tests passing (204 + new metrics tests)

Ready to implement! 🚀
