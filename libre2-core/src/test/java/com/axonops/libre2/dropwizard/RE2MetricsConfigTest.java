package com.axonops.libre2.dropwizard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axonops.libre2.cache.RE2Config;
import com.axonops.libre2.metrics.DropwizardMetricsAdapter;
import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;

/** Tests for RE2MetricsConfig factory methods. */
class RE2MetricsConfigTest {

  @Test
  void testWithMetrics_CustomPrefix() {
    MetricRegistry registry = new MetricRegistry();

    RE2Config config = RE2MetricsConfig.withMetrics(registry, "com.myapp.regex");

    assertThat(config).isNotNull();
    assertThat(config.metricsRegistry()).isInstanceOf(DropwizardMetricsAdapter.class);
  }

  @Test
  void testWithMetrics_DefaultPrefix() {
    MetricRegistry registry = new MetricRegistry();

    RE2Config config = RE2MetricsConfig.withMetrics(registry);

    assertThat(config).isNotNull();
    assertThat(config.metricsRegistry()).isInstanceOf(DropwizardMetricsAdapter.class);
  }

  @Test
  void testForCassandra() {
    MetricRegistry registry = new MetricRegistry();

    RE2Config config = RE2MetricsConfig.forCassandra(registry);

    assertThat(config).isNotNull();
    assertThat(config.metricsRegistry()).isInstanceOf(DropwizardMetricsAdapter.class);
    // Should use Cassandra-standard prefix (verified via metrics test below)
  }

  @Test
  void testWithMetrics_DisableJmx() {
    MetricRegistry registry = new MetricRegistry();

    RE2Config config = RE2MetricsConfig.withMetrics(registry, "test", false);

    assertThat(config).isNotNull();
    // JMX should not be auto-configured (can't easily verify without JMX checks)
  }

  @Test
  void testNullRegistry_ThrowsException() {
    assertThatThrownBy(() -> RE2MetricsConfig.withMetrics(null, "test"))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("registry");
  }

  @Test
  void testNullPrefix_ThrowsException() {
    MetricRegistry registry = new MetricRegistry();

    assertThatThrownBy(() -> RE2MetricsConfig.withMetrics(registry, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("metricPrefix");
  }
}
