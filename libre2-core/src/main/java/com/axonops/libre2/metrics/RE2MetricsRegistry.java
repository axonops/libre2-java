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

import java.util.function.Supplier;

/**
 * Abstract metrics registry interface for libre2-java.
 *
 * <p>Allows the library to work with or without Dropwizard Metrics dependency.
 * Implementations can use Dropwizard Metrics, custom metrics systems, or no-op.
 *
 * <p><strong>Metric Types (following Dropwizard patterns):</strong>
 * <ul>
 *   <li><strong>Counter:</strong> Atomic long counter (incrementing values)</li>
 *   <li><strong>Timer:</strong> Measures duration in nanoseconds with histogram</li>
 *   <li><strong>Gauge:</strong> Instantaneous value computed on-demand via supplier</li>
 * </ul>
 *
 * <p><strong>Thread Safety:</strong> All implementations must be thread-safe.
 *
 * @since 0.9.1
 */
public interface RE2MetricsRegistry {

    /**
     * Increment a counter by 1.
     *
     * <p>Thread-safe: Multiple threads can increment the same counter concurrently.
     *
     * @param name metric name (e.g., "patterns.compiled")
     */
    void incrementCounter(String name);

    /**
     * Increment a counter by a specific delta.
     *
     * <p>Thread-safe: Multiple threads can increment the same counter concurrently.
     *
     * @param name metric name (e.g., "cache.evictions_lru")
     * @param delta amount to increment (must be non-negative)
     */
    void incrementCounter(String name, long delta);

    /**
     * Record a timer measurement in nanoseconds.
     *
     * <p>Timers maintain histograms of duration measurements, allowing
     * calculation of percentiles (P50, P99, etc.).
     *
     * <p>Thread-safe: Multiple threads can record to the same timer concurrently.
     *
     * @param name metric name (e.g., "patterns.compilation_time")
     * @param durationNanos duration in nanoseconds
     */
    void recordTimer(String name, long durationNanos);

    /**
     * Register a gauge that computes its value on-demand.
     *
     * <p>The supplier will be called each time the gauge is read (e.g., via JMX).
     * The supplier should be fast and not block.
     *
     * <p>If a gauge with this name already exists, it should be replaced.
     *
     * @param name metric name (e.g., "cache.size")
     * @param valueSupplier function that returns the current value
     */
    void registerGauge(String name, Supplier<Number> valueSupplier);

    /**
     * Remove a previously registered gauge.
     *
     * <p>Used during cleanup to prevent memory leaks.
     * If no gauge exists with this name, this is a no-op.
     *
     * @param name metric name to remove
     */
    void removeGauge(String name);
}
