# libre2-dropwizard

**Dropwizard Metrics integration with automatic JMX**

---

## Overview

The `libre2-dropwizard` module provides convenient integration with Dropwizard Metrics, including automatic JMX exposure. Use this module if your application already uses Dropwizard Metrics (Cassandra, Spring Boot, Dropwizard apps, etc.).

---

## Installation

```xml
<dependency>
    <groupId>com.axonops</groupId>
    <artifactId>libre2-dropwizard</artifactId>
    <version>0.9.1</version>
</dependency>
<!-- Transitively includes libre2-core -->
```

---

## Quick Start

### Basic Usage

```java
import com.axonops.libre2.dropwizard.RE2MetricsConfig;
import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.cache.PatternCache;
import com.codahale.metrics.MetricRegistry;

// Get your application's MetricRegistry
MetricRegistry registry = getYourMetricRegistry();

// Create RE2 config with metrics (choose your prefix)
RE2Config config = RE2MetricsConfig.withMetrics(registry, "com.myapp.regex");

// Set as global cache
Pattern.setGlobalCache(new PatternCache(config));

// Use normally
Pattern pattern = Pattern.compile("test.*");
Matcher matcher = pattern.matcher("test123");
boolean matches = matcher.matches();

// All 21 metrics now in your MetricRegistry + JMX automatically
```

---

## Integration Examples

### Cassandra Integration

```java
import com.axonops.libre2.dropwizard.RE2MetricsConfig;

// Get Cassandra's MetricRegistry
MetricRegistry cassandraRegistry = getCassandraMetricRegistry();

// Use convenience method (sets standard Cassandra prefix)
RE2Config config = RE2MetricsConfig.forCassandra(cassandraRegistry);
Pattern.setGlobalCache(new PatternCache(config));

// Metrics appear under: org.apache.cassandra.metrics.RE2.*
// Visible via: nodetool, JConsole, Prometheus JMX exporter
```

### Spring Boot Integration

```java
import com.axonops.libre2.dropwizard.RE2MetricsConfig;
import org.springframework.beans.factory.annotation.Autowired;

@Autowired
private MeterRegistry meterRegistry;  // Spring's registry

// Convert Spring's MeterRegistry to Dropwizard (if needed)
// Or if Spring Boot uses Dropwizard metrics directly:
MetricRegistry dropwizardRegistry = getDropwizardRegistry();

RE2Config config = RE2MetricsConfig.withMetrics(dropwizardRegistry, "com.mycompany.myapp.regex");
Pattern.setGlobalCache(new PatternCache(config));
```

### Standalone Application

```java
import com.axonops.libre2.dropwizard.RE2MetricsConfig;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.jmx.JmxReporter;

// Create your own registry
MetricRegistry registry = new MetricRegistry();

// Configure RE2
RE2Config config = RE2MetricsConfig.withMetrics(registry, "com.mycompany.appname.re2");
Pattern.setGlobalCache(new PatternCache(config));

// Metrics automatically exposed via JMX (RE2MetricsConfig handles this)
// Or manually configure JmxReporter:
JmxReporter jmxReporter = JmxReporter.forRegistry(registry).build();
jmxReporter.start();
```

---

## Metric Prefix Configuration

The metric prefix determines where your metrics appear in JMX and monitoring systems:

| Framework | Recommended Prefix | JMX ObjectName Example |
|-----------|-------------------|------------------------|
| Cassandra | `org.apache.cassandra.metrics.RE2` | `org.apache.cassandra.metrics:type=RE2,name=patterns.compiled` |
| Spring Boot | `com.mycompany.myapp.regex` | `com.mycompany.myapp.regex:type=patterns,name=compiled` |
| Standalone | `com.axonops.libre2` | `com.axonops.libre2:type=patterns,name=compiled` |
| Custom | Whatever you want | Depends on your prefix |

---

## The 21 Metrics

All metrics are automatically registered in your MetricRegistry:

### Pattern Compilation (5 metrics)
- `patterns.compiled` (Counter) - Total patterns compiled
- `patterns.cache_hits` (Counter) - Cache hit count
- `patterns.cache_misses` (Counter) - Cache miss count
- `patterns.compilation_time` (Timer) - Compilation latency
- `patterns.invalid_recompiled` (Counter) - Auto-recompiled patterns

### Cache Eviction (6 metrics)
- `cache.evictions_lru` (Counter) - LRU evictions
- `cache.evictions_idle` (Counter) - Idle evictions
- `cache.evictions_deferred` (Counter) - Deferred evictions
- `cache.size` (Gauge) - Current cache size
- `cache.native_memory_bytes` (Gauge) - Off-heap memory used
- `cache.native_memory_peak_bytes` (Gauge) - Peak memory

### Resource Management (4 metrics)
- `resources.patterns_active` (Gauge) - Active patterns
- `resources.matchers_active` (Gauge) - Active matchers
- `resources.patterns_freed` (Counter) - Patterns freed
- `resources.matchers_freed` (Counter) - Matchers freed

### Performance (3 metrics)
- `matching.full_match` (Timer) - Full match latency
- `matching.partial_match` (Timer) - Partial match latency
- `matching.operations` (Counter) - Total matches

### Errors (3 metrics)
- `errors.compilation_failed` (Counter) - Compilation failures
- `errors.native_library` (Counter) - Native library errors
- `errors.resource_exhausted` (Counter) - Resource limits hit

---

## JMX Monitoring

Once configured, metrics are accessible via JMX:

### Using JConsole

1. Connect to your application's JMX port
2. Navigate to MBeans tab
3. Find your metric prefix (e.g., `com.myapp.regex` or `org.apache.cassandra.metrics.RE2`)
4. Expand to see all 21 metrics

### Using Command Line (jmxterm)

```bash
# Install jmxterm
brew install jmxterm

# Connect and query
java -jar jmxterm.jar -l localhost:7199
> domains
> domain com.axonops.libre2
> beans
> get -b com.axonops.libre2:type=patterns,name=compiled
```

### Cassandra-Specific Monitoring

```bash
# Using nodetool
nodetool sjk mx -q 'org.apache.cassandra.metrics:type=RE2,*'

# View all RE2 metrics
nodetool sjk mx -b org.apache.cassandra.metrics:type=RE2,name=patterns.compiled

# Watch cache size
watch -n 5 "nodetool sjk mx -b org.apache.cassandra.metrics:type=RE2,name=cache.size"
```

---

## Logging Configuration

All logs are produced via SLF4J. See [LOGGING_GUIDE.md](../LOGGING_GUIDE.md) for comprehensive documentation.

### Quick Configuration

**Logback (Cassandra, Spring Boot):**
```xml
<configuration>
    <logger name="com.axonops.libre2" level="INFO"/>
</configuration>
```

**Log4j2:**
```xml
<Configuration>
    <Loggers>
        <Logger name="com.axonops.libre2" level="info"/>
    </Loggers>
</Configuration>
```

All logs are prefixed with "RE2:" for easy filtering.

---

## API

### RE2MetricsConfig

**Main factory class for creating RE2Config with metrics:**

```java
// Generic (specify your prefix):
RE2Config withMetrics(MetricRegistry registry, String metricPrefix)
RE2Config withMetrics(MetricRegistry registry, String metricPrefix, boolean enableJmx)

// Default prefix (com.axonops.libre2):
RE2Config withMetrics(MetricRegistry registry)

// Cassandra convenience (uses org.apache.cassandra.metrics.RE2):
RE2Config forCassandra(MetricRegistry cassandraRegistry)
```

---

## Examples

See [examples/](../examples/) directory for complete examples:
- Standalone application
- Cassandra integration
- Spring Boot integration
- Custom monitoring setup

---

## Requirements

- Java 17+
- Dropwizard Metrics 4.2.x (provided by your application)
- libre2-core 0.9.1

---

## Performance

**Metrics overhead:** < 1%
- Counter increment: ~10-20ns
- Timer recording: ~50-100ns
- Gauge registration: one-time cost

**Zero overhead option:** Don't use this module, just use `libre2-core` with default NoOp metrics.
