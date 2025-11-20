package com.axonops.libre2.dropwizard;

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
}
