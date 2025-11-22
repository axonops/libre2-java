# libre2-core

Core RE2 regex library for Java 17+ with automatic pattern caching and native memory management.

## Overview

`libre2-core` provides high-performance, ReDoS-safe regex matching through JNI bindings to Google's RE2 library. Patterns are automatically cached and executed in off-heap native memory.

### Key Features

- **Linear time guarantee** - RE2's automata-based matching prevents catastrophic backtracking
- **Automatic caching** - LRU cache with dual eviction (size limit + idle timeout)
- **Off-heap execution** - Compiled patterns stored in native memory (not JVM heap)
- **Thread-safe** - Lock-free cache, safe concurrent access
- **Resource limits** - Configurable limits prevent unbounded memory growth
- **Framework-agnostic** - No dependencies on Cassandra or other frameworks

### Requirements

- Java 17 or later
- JNA 5.13.0 (provided scope - expects from host application)
- SLF4J 1.7+ for logging
- Dropwizard Metrics 4.2+ (optional, for metrics)

### Supported Platforms

- Linux x86_64, aarch64 (ARM64)
- macOS x86_64 (Intel), aarch64 (Apple Silicon)

## Quick Start

### Basic Usage

```java
import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.api.Matcher;

// Compile pattern (automatically cached)
Pattern emailPattern = Pattern.compile("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");

// Match against input
try (Matcher matcher = emailPattern.matcher("user@example.com")) {
    boolean matches = matcher.matches(); // true
}
```

### With Configuration

```java
import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.cache.PatternCache;
import com.axonops.libre2.cache.RE2Config;

// Configure cache
RE2Config config = RE2Config.builder()
    .maxCacheSize(100_000)       // 100K patterns
    .idleTimeoutSeconds(600)      // 10 minute timeout
    .build();

// Set global cache
Pattern.setGlobalCache(new PatternCache(config));

// Now all Pattern.compile() calls use this configuration
Pattern pattern = Pattern.compile("regex.*");
```

### With Metrics

```java
import com.codahale.metrics.MetricRegistry;
import com.axonops.libre2.metrics.DropwizardMetricsAdapter;

// Create metrics registry
MetricRegistry registry = new MetricRegistry();

// Configure with metrics
RE2Config config = RE2Config.builder()
    .metricsRegistry(new DropwizardMetricsAdapter(registry, "myapp.re2"))
    .build();

Pattern.setGlobalCache(new PatternCache(config));

// All operations now tracked in metrics
// Access via: registry.counter("myapp.re2.patterns.compiled.total.count")
```

## Architecture

### Pattern Caching

RE2 automatically caches compiled patterns using a **dual eviction strategy**:

1. **LRU Eviction** - When cache exceeds `maxCacheSize`, least-recently-used patterns evicted
2. **Idle Eviction** - Background thread evicts patterns idle beyond `idleTimeoutSeconds`

This provides:
- Short-term performance (LRU keeps hot patterns)
- Long-term memory hygiene (idle eviction cleans abandoned patterns)

### Deferred Cleanup

Patterns cannot be immediately freed if in use by active matchers. When evicted:
1. Pattern removed from cache (no longer available for new compilations)
2. Pattern moved to deferred cleanup queue (awaiting matcher closure)
3. Background task frees pattern once all matchers close

Prevents use-after-free crashes while allowing safe concurrent eviction.

### Native Memory

Compiled patterns stored in off-heap native memory to:
- Avoid Java GC pressure (regex automata can be 100s of KB)
- Leverage RE2's optimized C++ memory layout
- Prevent OutOfMemoryError in high-throughput scenarios

Memory is **exactly measured** (not estimated) via native library accounting.

## Configuration

See `RE2Config` Javadoc for comprehensive tuning guide. Key parameters:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `maxCacheSize` | 50,000 | Max patterns before LRU eviction |
| `idleTimeoutSeconds` | 300 (5min) | Evict patterns idle beyond this |
| `evictionScanIntervalSeconds` | 60 | How often idle eviction runs |
| `deferredCleanupIntervalSeconds` | 5 | How often deferred cleanup runs |
| `evictionProtectionMs` | 1000 | Protect new patterns from immediate eviction |
| `maxSimultaneousCompiledPatterns` | 100,000 | Safety limit (ACTIVE not cumulative) |

See also: [CONFIGURATION.md](../CONFIGURATION.md) in repo root

## Metrics

25 metrics available via Dropwizard Metrics integration:

- **Compilation** - patterns compiled, cache hits/misses, compilation latency
- **Cache State** - current patterns, memory usage (exact bytes)
- **Evictions** - LRU, idle, and deferred eviction counts
- **Performance** - match/find operation latencies
- **Errors** - compilation failures, resource exhaustion

See `MetricNames` Javadoc for complete metric catalog and monitoring recommendations.

## Testing

### Run Tests

```bash
mvn test
```

### Test Utilities

Use `TestUtils` class for common test setup patterns:

```java
import com.axonops.libre2.test.TestUtils;

private PatternCache originalCache;
private MetricRegistry registry;

@BeforeEach
void setup() {
    registry = new MetricRegistry();
    originalCache = TestUtils.replaceGlobalCacheWithMetrics(registry, "test");
}

@AfterEach
void cleanup() {
    TestUtils.restoreGlobalCache(originalCache);
}
```

## Module Structure

```
libre2-core/
├── src/main/java/
│   └── com/axonops/libre2/
│       ├── api/           # Public API (Pattern, Matcher, exceptions)
│       ├── cache/         # Pattern caching (PatternCache, RE2Config)
│       ├── jni/           # JNI bindings to native library
│       ├── metrics/       # Metrics interfaces and adapters
│       └── util/          # Utilities (ResourceTracker, etc.)
├── src/test/java/         # Comprehensive test suite (240+ tests)
└── src/main/resources/
    └── native/            # Platform-specific native libraries
```

## License

Apache License 2.0

See [THIRD_PARTY_LICENSES.md](../THIRD_PARTY_LICENSES.md) for RE2 library (BSD-3-Clause).
