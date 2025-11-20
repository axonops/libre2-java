package com.axonops.libre2.metrics;

import com.axonops.libre2.api.Matcher;
import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.cache.RE2Config;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Dropwizard Metrics integration.
 *
 * Tests that the adapter correctly delegates to Dropwizard MetricRegistry.
 */
class MetricsIntegrationTest {

    @Test
    void testDropwizardAdapter_IncrementCounter() {
        MetricRegistry registry = new MetricRegistry();
        DropwizardMetricsAdapter adapter = new DropwizardMetricsAdapter(registry, "test");

        adapter.incrementCounter("test.counter");
        adapter.incrementCounter("test.counter");
        adapter.incrementCounter("test.counter", 5);

        Counter counter = registry.counter("test.test.counter");
        assertThat(counter.getCount()).isEqualTo(7); // 1 + 1 + 5
    }

    @Test
    void testDropwizardAdapter_RecordTimer() {
        MetricRegistry registry = new MetricRegistry();
        DropwizardMetricsAdapter adapter = new DropwizardMetricsAdapter(registry, "test");

        adapter.recordTimer("test.timer", 1000000); // 1ms in nanos
        adapter.recordTimer("test.timer", 2000000); // 2ms in nanos

        Timer timer = registry.timer("test.test.timer");
        assertThat(timer.getCount()).isEqualTo(2);
        assertThat(timer.getSnapshot().getMean()).isGreaterThan(0);
    }

    @Test
    void testDropwizardAdapter_RegisterGauge() {
        MetricRegistry registry = new MetricRegistry();
        DropwizardMetricsAdapter adapter = new DropwizardMetricsAdapter(registry, "test");

        adapter.registerGauge("test.gauge", () -> 42);

        Gauge<Number> gauge = (Gauge<Number>) registry.getGauges().get("test.test.gauge");
        assertThat(gauge.getValue()).isEqualTo(42);
    }

    @Test
    void testDropwizardAdapter_CustomPrefix() {
        MetricRegistry registry = new MetricRegistry();
        DropwizardMetricsAdapter adapter = new DropwizardMetricsAdapter(registry, "org.apache.cassandra.metrics.RE2");

        adapter.incrementCounter("patterns.compiled");

        // Verify metric appears with custom prefix
        Counter counter = registry.counter("org.apache.cassandra.metrics.RE2.patterns.compiled");
        assertThat(counter.getCount()).isEqualTo(1);
    }

    @Test
    void testDropwizardAdapter_DefaultPrefix() {
        MetricRegistry registry = new MetricRegistry();
        DropwizardMetricsAdapter adapter = new DropwizardMetricsAdapter(registry);

        adapter.incrementCounter("test.counter");

        // Verify default prefix is used
        Counter counter = registry.counter("com.axonops.libre2.test.counter");
        assertThat(counter.getCount()).isEqualTo(1);
    }

    @Test
    void testNoOpMetrics_ZeroOverhead() {
        // Default config uses NoOpMetricsRegistry
        RE2Config noOpConfig = RE2Config.DEFAULT;
        assertThat(noOpConfig.metricsRegistry()).isInstanceOf(NoOpMetricsRegistry.class);

        // Perform operations - should work fine with no metrics
        Pattern pattern = Pattern.compile("test.*");
        try (Matcher matcher = pattern.matcher("test123")) {
            boolean result = matcher.matches();
            assertThat(result).isTrue();
        }

        // NoOp metrics don't throw exceptions
        NoOpMetricsRegistry noOp = NoOpMetricsRegistry.INSTANCE;
        noOp.incrementCounter("test");
        noOp.recordTimer("test", 100);
        noOp.registerGauge("test", () -> 42);
        // All no-ops - no failures
    }
}

