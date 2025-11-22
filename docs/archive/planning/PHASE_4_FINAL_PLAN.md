# Phase 4: Final Implementation Plan

**Date:** 2025-11-20
**Status:** ✅ Planning Complete - Multi-Module Architecture Approved
**Ready to Implement:** Yes

---

## Architecture Decision: Multi-Module Project

### Structure
```
libre2-java/
├── pom.xml                    (parent POM)
├── libre2-core/               (generic, framework-agnostic)
└── libre2-cassandra/          (Cassandra-specific, drop-in JMX)
```

### Key Benefits
1. **Core stays generic** - usable by any Java application
2. **Cassandra module provides convenience** - 3-line drop-in with automatic JMX
3. **Version-specific modules possible** - can add libre2-cassandra-4.x if needed
4. **Better testing** - test against actual Cassandra in cassandra module
5. **Cleaner dependencies** - users choose what they need

---

## Implementation Phases

### Phase 4A: Refactor to Multi-Module (30 minutes)
**Goal:** Restructure project without breaking existing functionality

**Steps:**
1. Create parent POM at root
2. Move existing code to `libre2-core/` subdirectory
3. Create `libre2-cassandra/` module skeleton
4. Update CI/CD to build both modules
5. **Verify:** All 204 existing tests still pass

**Deliverables:**
- Parent POM with 2 modules
- `libre2-core` with all existing code
- `libre2-cassandra` with basic structure
- CI building both modules

---

### Phase 4B: Implement Metrics (4-6 hours)
**Goal:** Add metrics support to core + Cassandra convenience layer

#### Part 1: Core Module Metrics (Generic)

**Files to create:**
```
libre2-core/src/main/java/com/axonops/libre2/metrics/
├── RE2MetricsRegistry.java          (interface)
├── NoOpMetricsRegistry.java         (default, zero overhead)
└── DropwizardMetricsAdapter.java    (optional, generic)
```

**Files to modify:**
```
libre2-core/src/main/java/com/axonops/libre2/
├── cache/RE2Config.java             (add metricsRegistry field)
├── cache/PatternCache.java          (add metrics collection points)
├── api/Pattern.java                 (add metrics + logging enhancements)
├── api/Matcher.java                 (add metrics)
└── jni/RE2LibraryLoader.java        (logging enhancements)
```

**Metrics:** 21 metrics across 5 categories (Counter, Timer, Gauge)
**Logging:** All messages with "RE2:" prefix, pattern hashing

#### Part 2: Cassandra Module (Convenience + JMX)

**Files to create:**
```
libre2-cassandra/src/main/java/com/axonops/libre2/cassandra/
├── CassandraRE2Config.java              (factory methods, auto-JMX)
├── CassandraMetricsIntegration.java     (helper methods)
└── SAIPatternMatcher.java               (optional: SAI-specific wrapper)

libre2-cassandra/src/test/java/com/axonops/libre2/cassandra/
├── Cassandra5IntegrationTest.java       (integration tests)
└── JmxExposureTest.java                 (verify JMX registration)
```

**Key feature:** Automatic JMX exposure
```java
// User code - 3 lines:
MetricRegistry cassandraRegistry = CassandraMetrics.getRegistry();
RE2Config config = CassandraRE2Config.forCassandra(cassandraRegistry);
Pattern.setGlobalCache(new PatternCache(config));

// ✅ JMX automatically configured
// ✅ Metrics appear under: org.apache.cassandra.metrics:type=RE2,*
// ✅ Operators can monitor via nodetool, JConsole, Prometheus
```

---

## The 21 Metrics (All Modules)

### Pattern Compilation (5)
- `patterns.compiled` (Counter)
- `patterns.cache_hits` (Counter)
- `patterns.cache_misses` (Counter)
- `patterns.compilation_time` (Timer)
- `patterns.invalid_recompiled` (Counter)

### Cache Eviction (6)
- `cache.evictions_lru` (Counter)
- `cache.evictions_idle` (Counter)
- `cache.evictions_deferred` (Counter)
- `cache.size` (Gauge)
- `cache.native_memory_bytes` (Gauge)
- `cache.native_memory_peak_bytes` (Gauge)

### Resource Management (4)
- `resources.patterns_active` (Gauge)
- `resources.matchers_active` (Gauge)
- `resources.patterns_freed` (Counter)
- `resources.matchers_freed` (Counter)

### Performance (3)
- `matching.full_match` (Timer)
- `matching.partial_match` (Timer)
- `matching.operations` (Counter)

### Errors (3)
- `errors.compilation_failed` (Counter)
- `errors.native_library` (Counter)
- `errors.resource_exhausted` (Counter)

---

## Usage Examples

### Standalone Application (libre2-core only)
```xml
<dependency>
    <groupId>com.axonops</groupId>
    <artifactId>libre2-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

```java
// No metrics, just core functionality
Pattern pattern = Pattern.compile("test.*");
Matcher matcher = pattern.matcher("test123");
boolean matches = matcher.matches();
```

### Standalone with Metrics (libre2-core + manual Dropwizard)
```xml
<dependency>
    <groupId>com.axonops</groupId>
    <artifactId>libre2-core</artifactId>
    <version>1.0.0</version>
</dependency>
<dependency>
    <groupId>io.dropwizard.metrics</groupId>
    <artifactId>metrics-core</artifactId>
    <version>4.2.19</version>
</dependency>
```

```java
// Manual metrics setup
MetricRegistry registry = new MetricRegistry();
JmxReporter.forRegistry(registry).build().start();

RE2Config config = new RE2Config.Builder()
    .metricsRegistry(new DropwizardMetricsAdapter(registry, "com.myapp.regex"))
    .build();

Pattern.setGlobalCache(new PatternCache(config));
```

### Cassandra Integration (libre2-cassandra - EASIEST)
```xml
<dependency>
    <groupId>com.axonops</groupId>
    <artifactId>libre2-cassandra</artifactId>
    <version>1.0.0</version>
</dependency>
<!-- Transitively includes libre2-core -->
```

```java
// Drop-in, 3 lines:
MetricRegistry cassandraRegistry = CassandraMetrics.getRegistry();
RE2Config config = CassandraRE2Config.forCassandra(cassandraRegistry);
Pattern.setGlobalCache(new PatternCache(config));

// ✅ All 21 metrics in JMX automatically
// ✅ No JmxReporter setup needed
// ✅ Standard Cassandra ObjectName patterns
```

---

## Testing Strategy

### Core Module Tests
- `MetricsIntegrationTest.java` - verify all 21 metrics work
- `DropwizardAdapterTest.java` - verify adapter correctness
- `NoOpMetricsTest.java` - verify zero overhead
- `LoggingTest.java` - verify RE2: prefix and pattern hashing

### Cassandra Module Tests
- `Cassandra5IntegrationTest.java` - test against Cassandra 5.x classes
- `JmxExposureTest.java` - verify automatic JMX registration
- `SAIIndexSimulationTest.java` - simulate Cassandra workload

**Total Expected Tests:** 204 (existing) + ~15 (new metrics tests) = ~220 tests

---

## Success Criteria

### Phase 4A Complete When:
- ✅ Parent POM created
- ✅ Code moved to `libre2-core/`
- ✅ `libre2-cassandra/` skeleton exists
- ✅ All 204 existing tests pass
- ✅ CI builds both modules

### Phase 4B Complete When:
- ✅ All 21 metrics implemented in core
- ✅ RE2MetricsRegistry interface + NoOp + Dropwizard adapter working
- ✅ All logging has "RE2:" prefix
- ✅ Pattern hashing implemented
- ✅ CassandraRE2Config with automatic JMX implemented
- ✅ Integration tests passing
- ✅ JMX exposure verified
- ✅ Performance overhead < 1%
- ✅ ~220 total tests passing
- ✅ Documentation complete

---

## Timeline

**Phase 4A:** 30 minutes
**Phase 4B:** 4-6 hours
**Total:** 4.5-6.5 hours (single session)

---

## Dependencies to Add

### libre2-core module
```xml
<!-- Already have SLF4J and Dropwizard Metrics in provided scope ✅ -->
```

### libre2-cassandra module
```xml
<dependencies>
    <!-- Core library -->
    <dependency>
        <groupId>com.axonops</groupId>
        <artifactId>libre2-core</artifactId>
        <version>${project.version}</version>
    </dependency>

    <!-- Dropwizard Metrics JMX (for automatic JmxReporter) -->
    <dependency>
        <groupId>io.dropwizard.metrics</groupId>
        <artifactId>metrics-jmx</artifactId>
        <version>4.2.19</version>
    </dependency>

    <!-- Cassandra 5.x (test scope only) -->
    <dependency>
        <groupId>org.apache.cassandra</groupId>
        <artifactId>cassandra-all</artifactId>
        <version>5.0.0</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## Documentation to Update

After Phase 4B complete:
- `PHASE_4_COMPLETE.md` - verification checklist
- `DEVELOPMENT_STATUS.md` - update progress
- `DECISION_LOG.md` - record multi-module decision and SLF4J logging decision
- `README.md` - update with usage examples for both modules
- `libre2-cassandra/README.md` - Cassandra-specific documentation

**Already Created:**
- ✅ `LOGGING_GUIDE.md` - comprehensive SLF4J logging guide
- ✅ `libre2-core/src/test/resources/logback-test.xml` - example test configuration
- ✅ `libre2-cassandra/CASSANDRA_LOGGING.md` - Cassandra-specific logging guide

---

## Questions Answered

**Q: Should we have framework-specific code?**
A: Yes, but in a separate module (`libre2-cassandra`). Core remains generic.

**Q: Should we handle JMX setup for Cassandra?**
A: Yes, `libre2-cassandra` automatically sets up JmxReporter for drop-in experience.

**Q: Multi-module project?**
A: Yes - `libre2-core` (generic) + `libre2-cassandra` (convenience + auto-JMX).

**Q: Can we test against actual Cassandra?**
A: Yes - `libre2-cassandra` module can have Cassandra dependencies in test scope.

**Q: Can we support multiple Cassandra versions?**
A: Yes - can add `libre2-cassandra-4.x`, `libre2-cassandra-5.x` if needed.

---

## Ready to Implement! 🚀

**Phase 4A (refactor) is low-risk and takes 30 minutes.**
**Phase 4B (metrics) builds on solid foundation.**

**Proceed with Phase 4A first?**
