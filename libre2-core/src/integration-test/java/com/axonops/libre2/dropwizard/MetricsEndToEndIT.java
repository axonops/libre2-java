package com.axonops.libre2.dropwizard;

import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.cache.PatternCache;
import com.axonops.libre2.cache.RE2Config;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end tests for Dropwizard Metrics integration.
 *
 * These tests verify that RE2MetricsConfig correctly sets up metrics
 * and that gauges are registered properly.
 */
class MetricsEndToEndIT {

    @Test
    void testGaugesRegisteredOnCacheCreation() {
        MetricRegistry registry = new MetricRegistry();
        RE2Config config = RE2MetricsConfig.withMetrics(registry, "e2e.test", false); // JMX disabled

        // Create cache - should register all gauges
        PatternCache cache = new PatternCache(config);

        // Verify gauges registered (11 total: cache, resources active, deferred)
        assertThat(registry.getGauges()).containsKeys(
            "e2e.test.cache.patterns.current.count",
            "e2e.test.cache.native_memory.current.bytes",
            "e2e.test.cache.native_memory.peak.bytes",
            "e2e.test.resources.patterns.active.current.count",
            "e2e.test.resources.matchers.active.current.count",
            "e2e.test.cache.deferred.patterns.current.count",
            "e2e.test.cache.deferred.patterns.peak.count",
            "e2e.test.cache.deferred.native_memory.current.bytes",
            "e2e.test.cache.deferred.native_memory.peak.bytes"
        );

        // Verify freed counts are Counters (not in getGauges())
        // Note: These may not exist yet if nothing has been freed
        // assertThat(registry.getCounters()).containsKeys(...) would fail if counters not created yet

        cache.reset();
    }

    @Test
    void testCassandraPrefixConvention() {
        MetricRegistry registry = new MetricRegistry();
        RE2Config config = RE2MetricsConfig.forCassandra(registry, false); // JMX disabled

        PatternCache cache = new PatternCache(config);

        // Verify Cassandra-standard prefix is used for gauges
        assertThat(registry.getGauges().keySet())
            .anyMatch(key -> key.startsWith("org.apache.cassandra.metrics.RE2."));

        // Verify specific Cassandra-prefixed gauge exists
        assertThat(registry.getGauges())
            .containsKey("org.apache.cassandra.metrics.RE2.cache.patterns.current.count");

        cache.reset();
    }

    @Test
    void testCustomPrefixWorks() {
        MetricRegistry registry = new MetricRegistry();
        RE2Config config = RE2MetricsConfig.withMetrics(registry, "com.mycompany.myapp.regex", false);

        PatternCache cache = new PatternCache(config);

        // Verify custom prefix used
        assertThat(registry.getGauges())
            .containsKey("com.mycompany.myapp.regex.cache.patterns.current.count");

        cache.reset();
    }

    @Test
    void testGaugeValuesReflectCacheState() {
        MetricRegistry registry = new MetricRegistry();
        RE2Config config = RE2MetricsConfig.withMetrics(registry, "gauge.test", false);
        PatternCache cache = new PatternCache(config);

        Gauge<Integer> cacheSize = (Gauge<Integer>) registry.getGauges().get("gauge.test.cache.patterns.current.count");

        // Initially empty
        assertThat(cacheSize.getValue()).isEqualTo(0);

        // Add pattern to cache
        cache.getOrCompile("pattern1", true, () -> Pattern.compileWithoutCache("pattern1"));

        // Gauge should update
        assertThat(cacheSize.getValue()).isEqualTo(1);

        // Add another
        cache.getOrCompile("pattern2", true, () -> Pattern.compileWithoutCache("pattern2"));

        assertThat(cacheSize.getValue()).isEqualTo(2);

        cache.reset();
    }

    @Test
    void testNativeMemoryGaugesNonZero() {
        MetricRegistry registry = new MetricRegistry();
        RE2Config config = RE2MetricsConfig.withMetrics(registry, "memory.test", false);
        PatternCache cache = new PatternCache(config);

        // Add pattern
        cache.getOrCompile("test.*", true, () -> Pattern.compileWithoutCache("test.*"));

        // Memory gauges should show non-zero values
        Gauge<Long> nativeMemory = (Gauge<Long>) registry.getGauges().get("memory.test.cache.native_memory.current.bytes");
        assertThat(nativeMemory.getValue()).isGreaterThan(0L);

        Gauge<Long> peakMemory = (Gauge<Long>) registry.getGauges().get("memory.test.cache.native_memory.peak.bytes");
        assertThat(peakMemory.getValue()).isGreaterThan(0L);

        cache.reset();
    }

    @Test
    void testResourceGaugesExist() {
        MetricRegistry registry = new MetricRegistry();
        RE2Config config = RE2MetricsConfig.withMetrics(registry, "resource.test", false);
        PatternCache cache = new PatternCache(config);

        // Verify resource gauges registered (active counts only)
        assertThat(registry.getGauges()).containsKeys(
            "resource.test.resources.patterns.active.current.count",
            "resource.test.resources.matchers.active.current.count"
        );

        // Gauges should return non-null values
        Gauge<Integer> patternsActive = (Gauge<Integer>) registry.getGauges().get("resource.test.resources.patterns.active.current.count");
        assertThat(patternsActive.getValue()).isNotNull();

        // Note: resources.patterns.freed and resources.matchers.freed are now Counters
        // They increment when patterns/matchers are freed (not registered as Gauges)

        cache.reset();
    }
}
