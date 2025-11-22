# Phase 4: Multi-Module Architecture

**Date:** 2025-11-20
**Revision:** Multi-module project structure

---

## Proposed Structure

```
libre2-java/
├── pom.xml (parent POM)
├── libre2-core/
│   ├── pom.xml
│   └── src/main/java/com/axonops/libre2/
│       ├── api/         (Pattern, Matcher, RE2, etc.)
│       ├── cache/       (PatternCache, CacheStatistics, etc.)
│       ├── jni/         (RE2NativeJNI, RE2LibraryLoader)
│       ├── metrics/     (RE2MetricsRegistry, NoOpMetricsRegistry, DropwizardMetricsAdapter)
│       └── util/        (ResourceTracker, etc.)
│
├── libre2-cassandra/
│   ├── pom.xml
│   └── src/
│       ├── main/java/com/axonops/libre2/cassandra/
│       │   ├── CassandraRE2Config.java        (Convenience factory methods)
│       │   ├── CassandraMetricsIntegration.java (Helper for Cassandra registry)
│       │   └── SAIPatternMatcher.java         (SAI-specific wrapper, if needed)
│       └── test/java/com/axonops/libre2/cassandra/
│           ├── Cassandra5IntegrationTest.java
│           └── SAIIndexTest.java
│
└── libre2-cassandra-4.x/  (optional - if Cassandra 4.x needs different integration)
    ├── pom.xml
    └── src/main/java/com/axonops/libre2/cassandra4/
        └── Cassandra4RE2Config.java
```

---

## Module Breakdown

### Module 1: `libre2-core` (Generic, Framework-Agnostic)

**Artifact:** `com.axonops:libre2-core:1.0.0`

**Purpose:** Core RE2 functionality, usable by any Java application

**Dependencies:**
- SLF4J API (provided)
- Dropwizard Metrics (provided, optional)
- JNA (provided) - wait, JNA is not in our pom.xml!

**Contains:**
- All existing code (Pattern, PatternCache, RE2MetricsRegistry, etc.)
- DropwizardMetricsAdapter (generic, accepts any MetricRegistry)
- NoOpMetricsRegistry (default)
- No framework-specific code

**Users:**
- Standalone Java applications
- Spring Boot applications
- Any framework wanting regex support

---

### Module 2: `libre2-cassandra` (Cassandra 5.x Integration)

**Artifact:** `com.axonops:libre2-cassandra:1.0.0`

**Purpose:** Convenience layer for Apache Cassandra 5.x integration

**Dependencies:**
- `libre2-core:1.0.0`
- Cassandra 5.x dependencies (provided) - for testing/development
- Dropwizard Metrics Core (provided) - Cassandra includes this
- Dropwizard Metrics JMX (compile) - needed for JmxReporter auto-registration

**Contains:**

#### `CassandraRE2Config.java`
```java
package com.axonops.libre2.cassandra;

import com.axonops.libre2.cache.RE2Config;
import com.axonops.libre2.metrics.DropwizardMetricsAdapter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.jmx.JmxReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cassandra-specific configuration factory.
 * Provides convenient drop-in methods for Cassandra integration.
 *
 * JMX EXPOSURE: This module automatically registers metrics with JMX
 * using Cassandra's standard ObjectName patterns.
 */
public final class CassandraRE2Config {
    private static final Logger logger = LoggerFactory.getLogger(CassandraRE2Config.class);
    private static volatile JmxReporter jmxReporter;

    /**
     * Create RE2Config for Cassandra 5.x Cassandra integration.
     *
     * AUTOMATIC JMX EXPOSURE:
     * - Metrics registered in cassandraMetricRegistry
     * - If registry not already exposed via JMX, we create JmxReporter
     * - Metrics appear in JMX under: org.apache.cassandra.metrics:type=RE2,name=*
     * - Operators can monitor via nodetool, JConsole, Prometheus JMX exporter, etc.
     *
     * @param cassandraMetricRegistry Cassandra's singleton MetricRegistry
     * @return configured RE2Config for Cassandra (JMX-enabled)
     */
    public static RE2Config forCassandra(MetricRegistry cassandraMetricRegistry) {
        return forCassandra(cassandraMetricRegistry, true);
    }

    /**
     * Create RE2Config for Cassandra 5.x Cassandra integration.
     *
     * @param cassandraMetricRegistry Cassandra's singleton MetricRegistry
     * @param enableJmx whether to automatically set up JMX exposure
     * @return configured RE2Config for Cassandra
     */
    public static RE2Config forCassandra(MetricRegistry cassandraMetricRegistry, boolean enableJmx) {
        if (enableJmx) {
            ensureJmxReporter(cassandraMetricRegistry);
        }

        return new RE2Config.Builder()
            .cacheEnabled(true)
            .maxCacheSize(50000)  // Suitable for Cassandra workloads
            .idleTimeoutSeconds(300)
            .evictionScanIntervalSeconds(60)
            .metricsRegistry(new DropwizardMetricsAdapter(
                cassandraMetricRegistry,
                "org.apache.cassandra.metrics.RE2"
            ))
            .build();
    }

    /**
     * Create RE2Config for Cassandra 5.x with custom metric prefix.
     * Automatically sets up JMX exposure.
     */
    public static RE2Config forCassandra(MetricRegistry cassandraMetricRegistry, String metricPrefix) {
        return forCassandra(cassandraMetricRegistry, metricPrefix, true);
    }

    /**
     * Create RE2Config for Cassandra 5.x with custom metric prefix.
     *
     * @param cassandraMetricRegistry Cassandra's singleton MetricRegistry
     * @param metricPrefix custom metric prefix (e.g., "org.apache.cassandra.metrics.MyIndex.RE2")
     * @param enableJmx whether to automatically set up JMX exposure
     * @return configured RE2Config
     */
    public static RE2Config forCassandra(MetricRegistry cassandraMetricRegistry, String metricPrefix, boolean enableJmx) {
        if (enableJmx) {
            ensureJmxReporter(cassandraMetricRegistry);
        }

        return new RE2Config.Builder()
            .cacheEnabled(true)
            .maxCacheSize(50000)
            .metricsRegistry(new DropwizardMetricsAdapter(cassandraMetricRegistry, metricPrefix))
            .build();
    }

    /**
     * Ensures JmxReporter is registered for the given MetricRegistry.
     * Idempotent: safe to call multiple times, only creates one reporter.
     *
     * NOTE: In practice, Cassandra already has a JmxReporter for its singleton registry.
     * This is a safety net in case it doesn't, ensuring metrics are always exposed.
     */
    private static synchronized void ensureJmxReporter(MetricRegistry registry) {
        if (jmxReporter == null) {
            try {
                logger.info("RE2: Registering JmxReporter for Cassandra metrics");
                jmxReporter = JmxReporter.forRegistry(registry).build();
                jmxReporter.start();
                logger.info("RE2: JmxReporter started - metrics available via JMX");
            } catch (Exception e) {
                logger.warn("RE2: Failed to start JmxReporter (may already be registered by Cassandra)", e);
                // Not fatal - Cassandra probably already has JmxReporter
            }
        }
    }

    /**
     * Shutdown hook for clean JMX reporter cleanup.
     * Called automatically by JVM shutdown, or manually if needed.
     */
    public static synchronized void shutdown() {
        if (jmxReporter != null) {
            logger.info("RE2: Stopping JmxReporter");
            jmxReporter.stop();
            jmxReporter = null;
        }
    }
}
```

#### `CassandraMetricsIntegration.java`
```java
package com.axonops.libre2.cassandra;

import com.axonops.libre2.cache.CacheStatistics;
import com.codahale.metrics.MetricRegistry;

/**
 * Helper for integrating RE2 metrics with Cassandra's monitoring.
 */
public final class CassandraMetricsIntegration {

    /**
     * Register RE2 cache statistics as Cassandra-compatible metrics.
     * This exposes RE2 stats in a way familiar to Cassandra operators.
     */
    public static void registerCassandraMetrics(
        MetricRegistry cassandraRegistry,
        CacheStatistics stats,
        String keyspace,
        String table
    ) {
        String prefix = String.format(
            "org.apache.cassandra.metrics.StorageAttachedIndex.%s.%s.RE2",
            keyspace, table
        );

        // Register gauges for Cassandra operators
        cassandraRegistry.register(prefix + ".CacheHitRate",
            () -> stats.hitRate());

        cassandraRegistry.register(prefix + ".CacheUtilization",
            () -> stats.utilization());

        cassandraRegistry.register(prefix + ".NativeMemoryMB",
            () -> stats.nativeMemoryBytes() / (1024.0 * 1024.0));

        // ... other Cassandra-style metrics
    }
}
```

#### Integration Tests
```java
package com.axonops.libre2.cassandra;

import org.junit.jupiter.api.Test;
import com.codahale.metrics.MetricRegistry;

/**
 * Integration tests with actual Cassandra 5.x.
 * Requires Cassandra dependencies on test classpath.
 */
public class Cassandra5IntegrationTest {

    @Test
    void testSAIIndexIntegration() {
        // Simulate Cassandra Cassandra integration using RE2
        MetricRegistry cassandraRegistry = new MetricRegistry();

        // Use convenience method
        RE2Config config = CassandraRE2Config.forCassandra(cassandraRegistry);
        PatternCache cache = new PatternCache(config);
        Pattern.setGlobalCache(cache);

        // Perform SAI-like operations
        Pattern pattern = Pattern.compile(".*ERROR.*", false);
        // ... test matching behavior

        // Verify metrics in Cassandra registry
        assertThat(cassandraRegistry.getCounters())
            .containsKey("org.apache.cassandra.metrics.RE2.patterns.compiled");
    }

    @Test
    void testCassandraQueryTimeout() {
        // Verify RE2 linear-time behavior under Cassandra query timeout
        // ...
    }
}
```

---

### Module 3: `libre2-cassandra-4.x` (Optional - If Needed)

**Only create if Cassandra 4.x requires different integration.**

**Artifact:** `com.axonops:libre2-cassandra-4:1.0.0`

**Purpose:** Cassandra 4.x specific integration (if metrics API differs)

**Dependencies:**
- `libre2-core:1.0.0`
- Cassandra 4.x dependencies (provided)

---

## Benefits of Multi-Module Architecture

### 1. Core Remains Generic
- `libre2-core` is 100% framework-agnostic
- Usable by anyone (Spring, Micronaut, standalone, etc.)
- No Cassandra dependencies polluting core

### 2. Cassandra Module Provides Convenience
- Factory methods: `CassandraRE2Config.forCassandra(registry)`
- Cassandra-specific metric naming
- Integration tests with actual Cassandra
- Documentation specific to Cassandra operators

### 3. Version-Specific Modules
- Can publish `libre2-cassandra-4` and `libre2-cassandra-5` separately
- Handle API differences between Cassandra versions
- Users depend on the version they need

### 4. Easier Testing
- Test `libre2-core` without Cassandra dependencies
- Test `libre2-cassandra` with actual Cassandra 5.x on classpath
- CI can run Cassandra-specific tests separately

### 5. Cleaner Dependency Management
```xml
<!-- User depending on core only -->
<dependency>
    <groupId>com.axonops</groupId>
    <artifactId>libre2-core</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- Cassandra 5 user -->
<dependency>
    <groupId>com.axonops</groupId>
    <artifactId>libre2-cassandra</artifactId>
    <version>1.0.0</version>
</dependency>
<!-- This transitively brings in libre2-core -->
```

---

## Parent POM Structure

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.axonops</groupId>
    <artifactId>libre2-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>libre2-core</module>
        <module>libre2-cassandra</module>
    </modules>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
        <slf4j.version>2.0.9</slf4j.version>
        <dropwizard-metrics.version>4.2.19</dropwizard-metrics.version>
        <cassandra.version>5.0.0</cassandra.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <!-- Shared dependency versions -->
            <dependency>
                <groupId>org.slf4j</groupId>
                <artifactId>slf4j-api</artifactId>
                <version>${slf4j.version}</version>
                <scope>provided</scope>
            </dependency>
            <dependency>
                <groupId>io.dropwizard.metrics</groupId>
                <artifactId>metrics-core</artifactId>
                <version>${dropwizard-metrics.version}</version>
                <scope>provided</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

---

## Migration Plan

### Phase 4A: Refactor to Multi-Module (Before Metrics Implementation)

**Steps:**
1. Create parent POM
2. Move existing code to `libre2-core/`
3. Create `libre2-cassandra/` module
4. Update build scripts to handle multi-module build
5. Verify all 204 tests still pass

**Estimated Time:** 30 minutes

### Phase 4B: Implement Metrics (As Planned)

**Steps:**
1. Implement metrics in `libre2-core` (generic)
2. Implement Cassandra convenience layer in `libre2-cassandra`
3. Write tests for both modules
4. Validate Cassandra integration

**Estimated Time:** 4-6 hours

---

## Usage Examples

### Standalone Application
```java
// Depend on libre2-core only
import com.axonops.libre2.api.Pattern;

Pattern pattern = Pattern.compile("test.*");
// No metrics, just core functionality
```

### Cassandra Integration (Drop-In, JMX Automatic)
```java
// Depend on libre2-cassandra (transitively includes libre2-core)
import com.axonops.libre2.cassandra.CassandraRE2Config;
import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.cache.PatternCache;

// In Cassandra integration initialization - THAT'S IT, 3 LINES:
MetricRegistry cassandraRegistry = CassandraMetrics.getRegistry();
RE2Config config = CassandraRE2Config.forCassandra(cassandraRegistry);  // JMX automatic!
Pattern.setGlobalCache(new PatternCache(config));

// ✅ Metrics automatically appear in Cassandra JMX under:
//    org.apache.cassandra.metrics:type=RE2,name=patterns.compiled
//    org.apache.cassandra.metrics:type=RE2,name=cache.size
//    ... (all 21 metrics)

// ✅ Operators can immediately monitor via:
//    - nodetool sjk mx -q 'org.apache.cassandra.metrics:type=RE2,*'
//    - JConsole: connect to Cassandra JMX port
//    - Prometheus JMX exporter (if configured)
//    - Standard Cassandra monitoring dashboards

// ✅ No JmxReporter setup needed - handled automatically
// ✅ No additional configuration - just works
```

---

## Decision

**Question:** Should we refactor to multi-module now (Phase 4A) or later?

**Option 1:** Refactor now (before implementing metrics)
- Pro: Cleaner architecture from the start
- Pro: Easier to test Cassandra integration
- Con: Adds 30 minutes before we start metrics work

**Option 2:** Refactor later (after Phase 4 complete)
- Pro: Faster to start metrics implementation
- Con: More disruptive to refactor later
- Con: Harder to test Cassandra integration without module

**Recommendation:** Refactor now (Option 1) - it's only 30 minutes and provides much better testing/development experience.

---

## Next Steps

1. **User Decision:** Approve multi-module architecture?
2. **If approved:** Phase 4A (refactor to multi-module)
3. **Then:** Phase 4B (implement metrics as planned)

**Ready to proceed?**
