package com.axonops.libre2.dropwizard;

import com.axonops.libre2.api.Matcher;
import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.cache.PatternCache;
import com.axonops.libre2.cache.RE2Config;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.jmx.JmxReporter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

/**
 * JMX integration tests.
 *
 * Verifies that metrics are actually exposed via JMX and accessible
 * through the platform MBean server.
 */
class JmxIntegrationTest {

    private JmxReporter jmxReporter;
    private MetricRegistry registry;
    private PatternCache originalCache;

    @BeforeEach
    void setup() {
        originalCache = Pattern.getGlobalCache();
        registry = new MetricRegistry();

        // Start JMX reporter
        jmxReporter = JmxReporter.forRegistry(registry).build();
        jmxReporter.start();
    }

    @AfterEach
    void cleanup() {
        if (jmxReporter != null) {
            jmxReporter.stop();
        }
        Pattern.setGlobalCache(originalCache);
    }

    @Test
    void testMetricsExposedViaJmx() throws Exception {
        // Create config with metrics
        RE2Config config = RE2MetricsConfig.withMetrics(registry, "com.test.jmx", false);
        Pattern.setGlobalCache(new PatternCache(config));

        // Compile a pattern to generate metrics
        Pattern.compile("test.*");

        // Get MBean server
        MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

        // Query for our metrics (Dropwizard uses "metrics" domain with type classification)
        Set<ObjectName> mbeans = mBeanServer.queryNames(
            new ObjectName("metrics:name=com.test.jmx.*,type=*"), null
        );

        // Verify MBeans are registered
        assertThat(mbeans)
            .as("JMX MBeans should be registered for RE2 metrics")
            .hasSizeGreaterThan(5); // Should have multiple metrics

        // Verify specific metrics exist
        boolean foundCacheSizeGauge = mbeans.stream()
            .anyMatch(name -> name.toString().contains("cache.patterns.current.count") && name.toString().contains("type=gauges"));

        boolean foundCompiledCounter = mbeans.stream()
            .anyMatch(name -> name.toString().contains("patterns.compiled.total.count") && name.toString().contains("type=counters"));

        boolean foundCompilationTimer = mbeans.stream()
            .anyMatch(name -> name.toString().contains("patterns.compilation.latency") && name.toString().contains("type=timers"));

        assertThat(foundCacheSizeGauge)
            .as("cache.patterns.current.count gauge should be in JMX")
            .isTrue();

        assertThat(foundCompiledCounter)
            .as("patterns.compiled.total.count counter should be in JMX")
            .isTrue();

        assertThat(foundCompilationTimer)
            .as("patterns.compilation.latency timer should be in JMX")
            .isTrue();
    }

    @Test
    void testCassandraJmxNaming() throws Exception {
        // Use Cassandra prefix
        RE2Config config = RE2MetricsConfig.forCassandra(registry);
        Pattern.setGlobalCache(new PatternCache(config));

        // Compile pattern
        Pattern.compile("test.*");

        // Get MBean server
        MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

        // Query for Cassandra-prefixed metrics (in "metrics" domain)
        Set<ObjectName> mbeans = mBeanServer.queryNames(
            new ObjectName("metrics:name=org.apache.cassandra.metrics.RE2.*,type=*"), null
        );

        // Verify Cassandra-prefixed MBeans exist
        assertThat(mbeans)
            .as("Cassandra-prefixed MBeans should exist in JMX")
            .isNotEmpty();

        // Verify specific Cassandra metric exists
        boolean foundCassandraMetric = mbeans.stream()
            .anyMatch(name -> name.toString().startsWith("metrics:name=org.apache.cassandra.metrics.RE2."));

        assertThat(foundCassandraMetric)
            .as("Should have org.apache.cassandra.metrics.RE2.* metrics in JMX")
            .isTrue();
    }

    @Test
    void testJmxGaugeReadable() throws Exception {
        // Create config
        RE2Config config = RE2MetricsConfig.withMetrics(registry, "jmx.readable.test", false);
        Pattern.setGlobalCache(new PatternCache(config));

        // Compile patterns
        Pattern.compile("p1");
        Pattern.compile("p2");
        Pattern.compile("p3");

        // Get MBean server
        MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

        // Find cache.patterns.current.count gauge (Dropwizard format: metrics:name=X,type=gauges)
        ObjectName cacheSizeName = new ObjectName("metrics:name=jmx.readable.test.cache.patterns.current.count,type=gauges");

        // Verify MBean exists
        assertThat(mBeanServer.isRegistered(cacheSizeName))
            .as("cache.patterns.current.count gauge should be registered in JMX")
            .isTrue();

        // Read value via JMX
        Object value = mBeanServer.getAttribute(cacheSizeName, "Value");

        // Verify we can read the value and it's correct
        assertThat(value)
            .as("Should be able to read gauge value via JMX")
            .isInstanceOf(Number.class);

        int size = ((Number) value).intValue();
        assertThat(size)
            .as("Cache size via JMX should reflect actual cache state (3 patterns)")
            .isEqualTo(3);
    }

    @Test
    void testJmxTimerStatistics() throws Exception {
        // Create config
        RE2Config config = RE2MetricsConfig.withMetrics(registry, "jmx.timer.test", false);
        Pattern.setGlobalCache(new PatternCache(config));

        // Compile patterns to generate latency data
        for (int i = 0; i < 50; i++) {
            Pattern.compile("timer_pattern_" + i);
        }

        // Verify metric exists in registry first
        assertThat(registry.getTimers().keySet())
            .as("Timer should exist in MetricRegistry")
            .contains("jmx.timer.test.patterns.compilation.latency");

        // Get MBean server
        MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

        // Find compilation latency timer
        ObjectName timerName = new ObjectName("metrics:name=jmx.timer.test.patterns.compilation.latency,type=timers");

        assertThat(mBeanServer.isRegistered(timerName))
            .as("Compilation latency timer should be in JMX")
            .isTrue();

        // Verify Timer provides count
        Object count = mBeanServer.getAttribute(timerName, "Count");
        long countValue = ((Number) count).longValue();
        assertThat(countValue)
            .as("Timer count via JMX")
            .isEqualTo(50);

        // Verify Timer attributes exist (min/max can be 0 for fast operations)
        Object min = mBeanServer.getAttribute(timerName, "Min");
        assertThat(min).as("Timer min attribute exists").isNotNull();

        Object max = mBeanServer.getAttribute(timerName, "Max");
        assertThat(max).as("Timer max attribute exists").isNotNull();

        Object mean = mBeanServer.getAttribute(timerName, "Mean");
        assertThat(((Number) mean).doubleValue())
            .as("Timer mean via JMX")
            .isGreaterThan(0.0);

        // Verify Timer provides percentiles
        Object p50 = mBeanServer.getAttribute(timerName, "50thPercentile");
        assertThat(((Number) p50).doubleValue())
            .as("Timer 50th percentile via JMX")
            .isGreaterThan(0.0);

        Object p95 = mBeanServer.getAttribute(timerName, "95thPercentile");
        assertThat(((Number) p95).doubleValue())
            .as("Timer 95th percentile via JMX")
            .isGreaterThan(0.0);

        Object p99 = mBeanServer.getAttribute(timerName, "99thPercentile");
        assertThat(((Number) p99).doubleValue())
            .as("Timer 99th percentile via JMX")
            .isGreaterThan(0.0);

        Object p999 = mBeanServer.getAttribute(timerName, "999thPercentile");
        assertThat(((Number) p999).doubleValue())
            .as("Timer 99.9th percentile via JMX")
            .isGreaterThan(0.0);

        // Verify rates
        Object oneMinRate = mBeanServer.getAttribute(timerName, "OneMinuteRate");
        assertThat(oneMinRate)
            .as("Timer should provide 1-minute rate via JMX")
            .isNotNull();
    }

    @Test
    void testAllMetricTypesInJmx() throws Exception {
        // Create config
        RE2Config config = RE2MetricsConfig.withMetrics(registry, "jmx.all.test", false);
        Pattern.setGlobalCache(new PatternCache(config));

        // Generate metrics of all types
        Pattern p = Pattern.compile("test.*");
        Pattern.compile("test.*"); // cache hit

        try (Matcher m = p.matcher("test123")) {
            m.matches();
        }

        // Trigger error
        try {
            Pattern.compile("(invalid");
        } catch (Exception e) {
            // Expected
        }

        MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

        // Verify counters in JMX
        ObjectName compiledCounter = new ObjectName("metrics:name=jmx.all.test.patterns.compiled.total.count,type=counters");
        assertThat(mBeanServer.isRegistered(compiledCounter)).isTrue();
        assertThat(((Number) mBeanServer.getAttribute(compiledCounter, "Count")).longValue()).isGreaterThan(0);

        ObjectName hitsCounter = new ObjectName("metrics:name=jmx.all.test.patterns.cache.hits.total.count,type=counters");
        assertThat(mBeanServer.isRegistered(hitsCounter)).isTrue();
        assertThat(((Number) mBeanServer.getAttribute(hitsCounter, "Count")).longValue()).isEqualTo(1);

        // Verify timers in JMX
        ObjectName compilationTimer = new ObjectName("metrics:name=jmx.all.test.patterns.compilation.latency,type=timers");
        assertThat(mBeanServer.isRegistered(compilationTimer)).isTrue();
        assertThat(((Number) mBeanServer.getAttribute(compilationTimer, "Count")).longValue()).isGreaterThan(0);

        // Verify gauges in JMX
        ObjectName cacheSize = new ObjectName("metrics:name=jmx.all.test.cache.patterns.current.count,type=gauges");
        assertThat(mBeanServer.isRegistered(cacheSize)).isTrue();

        ObjectName nativeMemory = new ObjectName("metrics:name=jmx.all.test.cache.native_memory.current.bytes,type=gauges");
        assertThat(mBeanServer.isRegistered(nativeMemory)).isTrue();
        assertThat(((Number) mBeanServer.getAttribute(nativeMemory, "Value")).longValue()).isGreaterThan(0);

        // Verify deferred metrics in JMX (NEW)
        ObjectName deferredCount = new ObjectName("metrics:name=jmx.all.test.cache.deferred.patterns.current.count,type=gauges");
        assertThat(mBeanServer.isRegistered(deferredCount)).isTrue();

        ObjectName deferredPeak = new ObjectName("metrics:name=jmx.all.test.cache.deferred.patterns.peak.count,type=gauges");
        assertThat(mBeanServer.isRegistered(deferredPeak)).isTrue();
    }

    @Test
    void testJmxCounterIncrementsCorrectly() throws Exception {
        RE2Config config = RE2MetricsConfig.withMetrics(registry, "jmx.increment.test", false);
        Pattern.setGlobalCache(new PatternCache(config));

        // Compile one pattern to create the counter
        Pattern.compile("initial");

        MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
        ObjectName compiledCounter = new ObjectName("metrics:name=jmx.increment.test.patterns.compiled.total.count,type=counters");

        // Get initial count
        long countBefore = ((Number) mBeanServer.getAttribute(compiledCounter, "Count")).longValue();
        assertThat(countBefore).isGreaterThanOrEqualTo(1); // At least the initial pattern

        // Compile 5 more patterns
        for (int i = 0; i < 5; i++) {
            Pattern.compile("inc_pattern_" + i);
        }

        // Verify counter incremented correctly via JMX
        long countAfter = ((Number) mBeanServer.getAttribute(compiledCounter, "Count")).longValue();
        assertThat(countAfter - countBefore)
            .as("Counter should have incremented by 5 via JMX")
            .isEqualTo(5);
    }
}

