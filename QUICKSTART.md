# Quick Start Guide

Get started with libre2-java in 5 minutes.

## Installation

### Maven

```xml
<dependency>
    <groupId>com.axonops</groupId>
    <artifactId>libre2-core</artifactId>
    <version>0.9.1</version>
</dependency>
```

### Gradle

```gradle
implementation 'com.axonops:libre2-core:0.9.1'
```

## Basic Usage

### 1. Simple Pattern Matching

```java
import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.api.Matcher;

// Compile pattern (automatically cached)
Pattern pattern = Pattern.compile("\\d{3}-\\d{4}");

// Match against input
try (Matcher matcher = pattern.matcher("123-4567")) {
    boolean matches = matcher.matches(); // true
}

// Find substring
try (Matcher matcher = pattern.matcher("Call 123-4567 now!")) {
    boolean found = matcher.find(); // true
}
```

### 2. Case-Insensitive Matching

```java
Pattern pattern = Pattern.compile("hello", false); // caseSensitive = false

try (Matcher matcher = pattern.matcher("HELLO")) {
    boolean matches = matcher.matches(); // true
}
```

### 3. Reusing Patterns

```java
// Pattern is cached - subsequent compiles return cached instance
Pattern p1 = Pattern.compile("test.*");
Pattern p2 = Pattern.compile("test.*"); // Cache hit - instant return

// Create multiple matchers from same pattern (thread-safe)
try (Matcher m1 = p1.matcher("test123");
     Matcher m2 = p1.matcher("test456")) {
    // Matchers are NOT thread-safe - each thread needs own matcher
    boolean match1 = m1.matches();
    boolean match2 = m2.matches();
}
```

## Configuration

### Custom Cache Size

```java
import com.axonops.libre2.cache.PatternCache;
import com.axonops.libre2.cache.RE2Config;

// Configure before using patterns
RE2Config config = RE2Config.builder()
    .maxCacheSize(100_000)           // 100K patterns (default: 50K)
    .idleTimeoutSeconds(600)         // 10 min timeout (default: 5min)
    .build();

Pattern.setGlobalCache(new PatternCache(config));

// Now all Pattern.compile() calls use this config
Pattern pattern = Pattern.compile("regex.*");
```

### Memory-Constrained Environment

```java
RE2Config config = RE2Config.builder()
    .maxCacheSize(5_000)             // Smaller cache
    .idleTimeoutSeconds(60)          // Aggressive cleanup (1 min)
    .evictionScanIntervalSeconds(15) // Scan every 15s
    .build();

Pattern.setGlobalCache(new PatternCache(config));
```

### Disable Caching (Manual Management)

```java
// Use NO_CACHE for full manual control
Pattern.setGlobalCache(new PatternCache(RE2Config.NO_CACHE));

// Now you must manually close patterns
Pattern pattern = Pattern.compileWithoutCache("test.*");
try {
    // Use pattern...
} finally {
    pattern.close(); // Must close manually
}
```

## Metrics Integration

### With Dropwizard Metrics

```java
import com.codahale.metrics.MetricRegistry;
import com.axonops.libre2.metrics.DropwizardMetricsAdapter;

// Create Dropwizard registry
MetricRegistry registry = new MetricRegistry();

// Configure with metrics
RE2Config config = RE2Config.builder()
    .metricsRegistry(new DropwizardMetricsAdapter(registry, "myapp.re2"))
    .build();

Pattern.setGlobalCache(new PatternCache(config));

// All operations now tracked:
// - myapp.re2.patterns.compiled.total.count
// - myapp.re2.patterns.cache.hits.total.count
// - myapp.re2.patterns.compilation.latency
// - myapp.re2.cache.patterns.current.count
// - And 21 more metrics...
```

### For Apache Cassandra

```java
import com.axonops.libre2.dropwizard.RE2MetricsConfig;
import com.codahale.metrics.MetricRegistry;

// Get Cassandra's metric registry
MetricRegistry cassandraRegistry = ...; // From Cassandra

// Use Cassandra-specific configuration
RE2Config config = RE2MetricsConfig.forCassandra(cassandraRegistry);
Pattern.setGlobalCache(new PatternCache(config));

// Metrics appear in JMX:
// - org.apache.cassandra.metrics.RE2.patterns.compiled.total.count
// - org.apache.cassandra.metrics.RE2.cache.patterns.current.count
```

## Common Patterns

### Email Validation

```java
Pattern emailPattern = Pattern.compile(
    "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"
);

boolean isValid = emailPattern.matches("user@example.com");
```

### URL Matching

```java
Pattern urlPattern = Pattern.compile(
    "https?://[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/.*)?",
    false // case-insensitive
);

boolean isUrl = urlPattern.matches("https://example.com/path");
```

### IPv4 Address

```java
Pattern ipPattern = Pattern.compile(
    "\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b"
);

try (Matcher matcher = ipPattern.matcher("Server IP: 192.168.1.1")) {
    boolean found = matcher.find(); // true
}
```

## Error Handling

### Invalid Regex

```java
import com.axonops.libre2.api.PatternCompilationException;

try {
    Pattern pattern = Pattern.compile("[invalid(");
} catch (PatternCompilationException e) {
    // Handle invalid regex syntax
    System.err.println("Invalid regex: " + e.getMessage());
}
```

### Resource Limits

```java
import com.axonops.libre2.api.ResourceException;

try {
    // If too many patterns compiled simultaneously
    Pattern pattern = Pattern.compile(".*");
} catch (ResourceException e) {
    // Hit maxSimultaneousCompiledPatterns limit
    System.err.println("Resource limit exceeded: " + e.getMessage());
}
```

## Thread Safety

### Thread-Safe Pattern Sharing

```java
// Pattern can be safely shared across threads
Pattern sharedPattern = Pattern.compile("\\d+");

// Thread 1
try (Matcher m1 = sharedPattern.matcher("123")) {
    m1.matches();
}

// Thread 2 (concurrent)
try (Matcher m2 = sharedPattern.matcher("456")) {
    m2.matches();
}
```

### Matcher Thread Confinement

```java
// NEVER share Matcher between threads
Pattern pattern = Pattern.compile("test.*");
Matcher matcher = pattern.matcher("input");

// WRONG - will crash or corrupt
new Thread(() -> matcher.matches()).start(); // DON'T DO THIS

// CORRECT - each thread gets own matcher
new Thread(() -> {
    try (Matcher m = pattern.matcher("input")) {
        m.matches();
    }
}).start();
```

## Performance Tips

1. **Reuse Patterns** - Pattern compilation is expensive (~100μs), caching is cheap (~50ns)
2. **Close Matchers** - Use try-with-resources to ensure prompt cleanup
3. **Monitor Cache Hit Rate** - Should be >90% for steady-state workloads
4. **Watch Deferred Cleanup** - Should stay near zero (indicates matcher leaks if high)

## Next Steps

- [Architecture Guide](ARCHITECTURE.md) - Deep dive into caching and memory management
- [Configuration Guide](CONFIGURATION.md) - Comprehensive tuning recommendations
- [Metrics Guide](libre2-core/src/main/java/com/axonops/libre2/metrics/MetricNames.java) - All 25 metrics documented
- [API Javadoc](libre2-core/src/main/java/com/axonops/libre2/api/Pattern.java) - Complete API reference

## Support

- GitHub Issues: https://github.com/axonops/libre2-java/issues
- License: Apache 2.0
