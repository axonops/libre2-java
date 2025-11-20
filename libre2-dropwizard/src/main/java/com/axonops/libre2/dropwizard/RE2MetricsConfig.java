/*
 * Copyright 2025 AxonOps
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.axonops.libre2.dropwizard;

import com.axonops.libre2.cache.RE2Config;
import com.axonops.libre2.metrics.DropwizardMetricsAdapter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.jmx.JmxReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Convenience factory for RE2Config with Dropwizard Metrics integration.
 *
 * <p>This class provides easy setup for applications using Dropwizard Metrics,
 * including automatic JMX exposure. Works with any framework that uses Dropwizard
 * (Cassandra, Spring Boot, Dropwizard apps, etc.).
 *
 * <p><strong>Usage Examples:</strong>
 * <pre>{@code
 * // Cassandra integration:
 * MetricRegistry cassandraRegistry = getCassandraMetricRegistry();
 * RE2Config config = RE2MetricsConfig.withMetrics(cassandraRegistry, "org.apache.cassandra.metrics.RE2");
 *
 * // Spring Boot integration:
 * MetricRegistry springRegistry = getSpringMetricRegistry();
 * RE2Config config = RE2MetricsConfig.withMetrics(springRegistry, "com.myapp.regex");
 *
 * // Standalone app:
 * MetricRegistry registry = new MetricRegistry();
 * RE2Config config = RE2MetricsConfig.withMetrics(registry, "com.mycompany.myapp.re2");
 * }</pre>
 *
 * <p><strong>JMX Exposure:</strong> This class automatically sets up JmxReporter
 * for the provided registry (if not already configured), ensuring all RE2 metrics
 * are visible via JMX.
 *
 * @since 0.9.1
 */
public final class RE2MetricsConfig {
    private static final Logger logger = LoggerFactory.getLogger(RE2MetricsConfig.class);
    private static volatile JmxReporter jmxReporter;

    private RE2MetricsConfig() {
        // Utility class
    }

    /**
     * Creates RE2Config with Dropwizard Metrics integration and automatic JMX.
     *
     * <p>This is the recommended method for integrating with frameworks like Cassandra
     * or Spring Boot that already have a MetricRegistry.
     *
     * <p><strong>Metric Prefix Examples:</strong>
     * <ul>
     *   <li>Cassandra: {@code "org.apache.cassandra.metrics.RE2"}</li>
     *   <li>Spring Boot: {@code "com.myapp.regex"}</li>
     *   <li>Generic: {@code "com.axonops.libre2"}</li>
     * </ul>
     *
     * @param registry the Dropwizard MetricRegistry to use
     * @param metricPrefix the metric namespace prefix
     * @return configured RE2Config with metrics enabled
     */
    public static RE2Config withMetrics(MetricRegistry registry, String metricPrefix) {
        return withMetrics(registry, metricPrefix, true);
    }

    /**
     * Creates RE2Config with Dropwizard Metrics integration.
     *
     * @param registry the Dropwizard MetricRegistry to use
     * @param metricPrefix the metric namespace prefix
     * @param enableJmx whether to automatically set up JMX exposure
     * @return configured RE2Config with metrics enabled
     */
    public static RE2Config withMetrics(MetricRegistry registry, String metricPrefix, boolean enableJmx) {
        Objects.requireNonNull(registry, "registry cannot be null");
        Objects.requireNonNull(metricPrefix, "metricPrefix cannot be null");

        if (enableJmx) {
            ensureJmxReporter(registry);
        }

        return RE2Config.builder()
            .metricsRegistry(new DropwizardMetricsAdapter(registry, metricPrefix))
            .build();
    }

    /**
     * Creates RE2Config with Dropwizard Metrics using default prefix.
     *
     * <p>Uses default metric prefix: {@code "com.axonops.libre2"}
     *
     * @param registry the Dropwizard MetricRegistry to use
     * @return configured RE2Config with metrics enabled
     */
    public static RE2Config withMetrics(MetricRegistry registry) {
        return withMetrics(registry, "com.axonops.libre2", true);
    }

    /**
     * Creates RE2Config optimized for Cassandra with standard metric prefix.
     *
     * <p>Convenience method for Cassandra integration. Uses Cassandra's standard
     * metric namespace: {@code "org.apache.cassandra.metrics.RE2"}
     *
     * @param cassandraRegistry Cassandra's singleton MetricRegistry
     * @return configured RE2Config for Cassandra
     */
    public static RE2Config forCassandra(MetricRegistry cassandraRegistry) {
        return withMetrics(cassandraRegistry, "org.apache.cassandra.metrics.RE2", true);
    }

    /**
     * Ensures JmxReporter is registered for the given MetricRegistry.
     *
     * <p>Idempotent: safe to call multiple times, only creates one reporter.
     * If the registry already has JMX exposure configured, this is harmless.
     *
     * @param registry the MetricRegistry to expose via JMX
     */
    private static synchronized void ensureJmxReporter(MetricRegistry registry) {
        if (jmxReporter == null) {
            try {
                logger.info("RE2: Registering JmxReporter for metrics");
                jmxReporter = JmxReporter.forRegistry(registry).build();
                jmxReporter.start();
                logger.info("RE2: JmxReporter started - metrics available via JMX");
            } catch (Exception e) {
                logger.warn("RE2: Failed to start JmxReporter (may already be configured)", e);
                // Not fatal - registry may already have JMX exposure
            }
        }
    }

    /**
     * Shutdown hook for clean JMX reporter cleanup.
     *
     * <p>Called automatically by JVM shutdown, or manually if needed.
     */
    public static synchronized void shutdown() {
        if (jmxReporter != null) {
            logger.info("RE2: Stopping JmxReporter");
            jmxReporter.stop();
            jmxReporter = null;
        }
    }
}
