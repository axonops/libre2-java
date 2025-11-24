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

package com.axonops.libre2.metrics;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Dropwizard Metrics adapter for libre2-java.
 *
 * <p>Wraps a Dropwizard {@link MetricRegistry} and delegates all metric
 * operations to it. This allows libre2-java to integrate with any application
 * or framework that uses Dropwizard Metrics (Cassandra, Spring Boot, etc.).
 *
 * <p><strong>Thread Safety:</strong> MetricRegistry and all Dropwizard metric
 * types are thread-safe. This adapter is fully thread-safe.
 *
 * <p><strong>Usage Examples:</strong>
 * <pre>{@code
 * // Standalone application:
 * MetricRegistry registry = new MetricRegistry();
 * RE2MetricsRegistry metrics = new DropwizardMetricsAdapter(registry, "com.myapp.regex");
 *
 * // Framework integration (e.g., Cassandra):
 * MetricRegistry frameworkRegistry = getFrameworkRegistry();
 * RE2MetricsRegistry metrics = new DropwizardMetricsAdapter(frameworkRegistry, "org.framework.RE2");
 * }</pre>
 *
 * @since 0.9.1
 */
public final class DropwizardMetricsAdapter implements RE2MetricsRegistry {

    private final MetricRegistry registry;
    private final String prefix;

    /**
     * Creates adapter with default metric prefix: {@code com.axonops.libre2}
     *
     * @param registry the Dropwizard MetricRegistry to register metrics with
     * @throws NullPointerException if registry is null
     */
    public DropwizardMetricsAdapter(MetricRegistry registry) {
        this(registry, "com.axonops.libre2");
    }

    /**
     * Creates adapter with custom metric prefix.
     *
     * <p>The prefix determines the metric namespace in the registry and JMX.
     * For example, with prefix {@code "com.myapp.regex"}, metrics will appear as:
     * <ul>
     *   <li>{@code com.myapp.regex.patterns.compiled}</li>
     *   <li>{@code com.myapp.regex.cache.size}</li>
     *   <li>etc.</li>
     * </ul>
     *
     * @param registry the Dropwizard MetricRegistry to register metrics with
     * @param prefix the metric name prefix (e.g., "com.myapp.regex")
     * @throws NullPointerException if registry or prefix is null
     */
    public DropwizardMetricsAdapter(MetricRegistry registry, String prefix) {
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");
        this.prefix = Objects.requireNonNull(prefix, "prefix cannot be null");
    }

    @Override
    public void incrementCounter(String name) {
        registry.counter(metricName(name)).inc();
    }

    @Override
    public void incrementCounter(String name, long delta) {
        registry.counter(metricName(name)).inc(delta);
    }

    @Override
    public void recordTimer(String name, long durationNanos) {
        registry.timer(metricName(name)).update(durationNanos, TimeUnit.NANOSECONDS);
    }

    @Override
    public void registerGauge(String name, Supplier<Number> valueSupplier) {
        String fullName = metricName(name);

        // Remove existing gauge if present (idempotent registration)
        registry.remove(fullName);

        // Register new gauge
        registry.register(fullName, (Gauge<Number>) valueSupplier::get);
    }

    @Override
    public void removeGauge(String name) {
        registry.remove(metricName(name));
    }

    /**
     * Builds full metric name with prefix.
     *
     * <p>Uses Dropwizard's {@link MetricRegistry#name(String, String...)} utility
     * for consistent dot-separated naming.
     *
     * @param name metric name
     * @return full metric name with prefix
     */
    private String metricName(String name) {
        return MetricRegistry.name(prefix, name);
    }
}
