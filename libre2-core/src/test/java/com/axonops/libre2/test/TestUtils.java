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
package com.axonops.libre2.test;

import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.cache.PatternCache;
import com.axonops.libre2.cache.RE2Config;
import com.axonops.libre2.metrics.DropwizardMetricsAdapter;
import com.axonops.libre2.metrics.NoOpMetricsRegistry;
import com.codahale.metrics.MetricRegistry;

/**
 * Test utilities for RE2 test setup and teardown.
 *
 * <p>Provides helper methods to reduce test boilerplate:
 * <ul>
 *   <li>Cache configuration with sensible test defaults</li>
 *   <li>Metrics registry setup</li>
 *   <li>Global cache management</li>
 * </ul>
 *
 * <h2>Usage Patterns</h2>
 *
 * <h3>Simple Test Without Metrics</h3>
 * <pre>{@code
 * @Test
 * void myTest() {
 *     Pattern pattern = Pattern.compile("test.*");
 *     // Test uses default global cache
 * }
 * }</pre>
 *
 * <h3>Test With Custom Cache Configuration</h3>
 * <pre>{@code
 * private PatternCache originalCache;
 *
 * @BeforeEach
 * void setup() {
 *     RE2Config config = TestUtils.testConfigBuilder()
 *         .maxCacheSize(100)
 *         .build();
 *     originalCache = TestUtils.replaceGlobalCache(config);
 * }
 *
 * @AfterEach
 * void cleanup() {
 *     TestUtils.restoreGlobalCache(originalCache);
 * }
 * }</pre>
 *
 * <h3>Test With Metrics</h3>
 * <pre>{@code
 * private PatternCache originalCache;
 * private MetricRegistry registry;
 *
 * @BeforeEach
 * void setup() {
 *     registry = new MetricRegistry();
 *     originalCache = TestUtils.replaceGlobalCacheWithMetrics(registry, "test.prefix");
 * }
 *
 * @AfterEach
 * void cleanup() {
 *     TestUtils.restoreGlobalCache(originalCache);
 * }
 *
 * @Test
 * void myTest() {
 *     Pattern.compile("test.*");
 *     Counter counter = registry.counter("test.prefix.patterns.compiled.total.count");
 *     assertThat(counter.getCount()).isEqualTo(1);
 * }
 * }</pre>
 *
 * @since 1.0.0
 */
public final class TestUtils {
    private TestUtils() {
        // Utility class
    }

    /**
     * Creates test configuration with sensible defaults for testing.
     *
     * <p>Differs from production defaults:
     * <ul>
     *   <li>Smaller cache (5000 vs 50000) - faster tests</li>
     *   <li>Shorter timeouts (faster eviction in tests)</li>
     *   <li>No JMX (avoids InstanceAlreadyExistsException)</li>
     * </ul>
     *
     * @return builder with test defaults
     */
    public static RE2Config.Builder testConfigBuilder() {
        return RE2Config.builder()
            .maxCacheSize(5000)
            .idleTimeoutSeconds(60)
            .evictionScanIntervalSeconds(15)
            .deferredCleanupIntervalSeconds(2)
            .metricsRegistry(NoOpMetricsRegistry.INSTANCE);
    }

    /**
     * Creates test configuration with Dropwizard metrics (JMX disabled).
     *
     * <p>Use when you need to verify metrics in tests.
     * JMX is always disabled to prevent InstanceAlreadyExistsException in test suites.
     *
     * @param registry Dropwizard MetricRegistry
     * @param prefix metric name prefix
     * @return builder with metrics enabled
     */
    public static RE2Config.Builder testConfigWithMetrics(MetricRegistry registry, String prefix) {
        return testConfigBuilder()
            .metricsRegistry(new DropwizardMetricsAdapter(registry, prefix));
    }

    /**
     * Replaces global cache with custom configuration.
     *
     * <p>Saves and returns original cache for restoration in teardown.
     *
     * <p>Example:
     * <pre>{@code
     * @BeforeEach
     * void setup() {
     *     RE2Config config = TestUtils.testConfigBuilder().maxCacheSize(100).build();
     *     originalCache = TestUtils.replaceGlobalCache(config);
     * }
     *
     * @AfterEach
     * void cleanup() {
     *     TestUtils.restoreGlobalCache(originalCache);
     * }
     * }</pre>
     *
     * @param config custom configuration
     * @return original cache (save for restoration)
     */
    public static PatternCache replaceGlobalCache(RE2Config config) {
        PatternCache original = Pattern.getGlobalCache();
        Pattern.setGlobalCache(new PatternCache(config));
        return original;
    }

    /**
     * Replaces global cache with metrics-enabled configuration.
     *
     * <p>Convenience method that creates config with Dropwizard metrics adapter.
     * JMX is disabled to prevent test suite conflicts.
     *
     * <p>Example:
     * <pre>{@code
     * @BeforeEach
     * void setup() {
     *     registry = new MetricRegistry();
     *     originalCache = TestUtils.replaceGlobalCacheWithMetrics(registry, "test.prefix");
     * }
     * }</pre>
     *
     * @param registry Dropwizard MetricRegistry
     * @param prefix metric name prefix
     * @return original cache (save for restoration)
     */
    public static PatternCache replaceGlobalCacheWithMetrics(MetricRegistry registry, String prefix) {
        RE2Config config = testConfigWithMetrics(registry, prefix).build();
        return replaceGlobalCache(config);
    }

    /**
     * Restores original global cache.
     *
     * <p>Call in @AfterEach to restore cache state after test.
     *
     * @param originalCache cache to restore (returned from replaceGlobalCache)
     */
    public static void restoreGlobalCache(PatternCache originalCache) {
        if (originalCache != null) {
            Pattern.setGlobalCache(originalCache);
        }
    }
}
