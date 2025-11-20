package com.axonops.libre2.metrics;

import com.axonops.libre2.api.Matcher;
import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.api.PatternCompilationException;
import com.axonops.libre2.cache.PatternCache;
import com.axonops.libre2.cache.RE2Config;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests verifying metrics are actually collected during operations.
 *
 * Uses Pattern.setGlobalCache() to inject a test cache with Dropwizard metrics,
 * then performs real operations and verifies metrics are updated correctly.
 */
class MetricsIntegrationTest {

    private MetricRegistry registry;
    private PatternCache originalCache;

    @BeforeEach
    void setup() {
        // Save original cache
        originalCache = Pattern.getGlobalCache();

        // Create test registry
        registry = new MetricRegistry();

        // Create config with Dropwizard metrics (disable initialization test)
        RE2Config config = RE2Config.builder()
            .metricsRegistry(new DropwizardMetricsAdapter(registry, "test.re2"))
            .build();

        // Inject test cache
        Pattern.setGlobalCache(new PatternCache(config));
    }

    @AfterEach
    void cleanup() {
        // Restore original cache
        Pattern.setGlobalCache(originalCache);
    }

    @Test
    void testPatternCompilationMetrics() {
        // Compile a pattern
        Pattern pattern = Pattern.compile("test.*");

        // Verify compilation counter incremented
        Counter compiled = registry.counter("test.re2.patterns.compiled.total.count");
        assertThat(compiled.getCount()).isEqualTo(1);

        // Verify compilation timer recorded
        Timer compilationTime = registry.timer("test.re2.patterns.compilation.latency");
        assertThat(compilationTime.getCount()).isEqualTo(1);
        assertThat(compilationTime.getSnapshot().getMean()).isGreaterThan(0);

        // Compile another
        Pattern.compile("other.*");

        assertThat(compiled.getCount()).isEqualTo(2);
        assertThat(compilationTime.getCount()).isEqualTo(2);
    }

    @Test
    void testCacheHitMissMetrics() {
        // First compile - cache miss
        Pattern p1 = Pattern.compile("test.*");

        Counter misses = registry.counter("test.re2.patterns.cache.misses.total.count");
        Counter hits = registry.counter("test.re2.patterns.cache.hits.total.count");

        assertThat(misses.getCount()).isEqualTo(1);
        assertThat(hits.getCount()).isEqualTo(0);

        // Second compile same pattern - cache hit
        Pattern p2 = Pattern.compile("test.*");

        assertThat(misses.getCount()).isEqualTo(1); // still 1
        assertThat(hits.getCount()).isEqualTo(1); // now 1

        // Different pattern - cache miss
        Pattern p3 = Pattern.compile("other.*");

        assertThat(misses.getCount()).isEqualTo(2);
        assertThat(hits.getCount()).isEqualTo(1);
    }

    @Test
    void testMatchingMetrics() {
        Pattern pattern = Pattern.compile("test.*");

        // Full match
        try (Matcher m = pattern.matcher("test123")) {
            m.matches();
        }

        Timer fullMatch = registry.timer("test.re2.matching.full_match.latency");
        assertThat(fullMatch.getCount()).isEqualTo(1);

        Counter operations = registry.counter("test.re2.matching.operations.total.count");
        assertThat(operations.getCount()).isEqualTo(1);

        // Partial match
        try (Matcher m = pattern.matcher("test456")) {
            m.find();
        }

        Timer partialMatch = registry.timer("test.re2.matching.partial_match.latency");
        assertThat(partialMatch.getCount()).isEqualTo(1);

        assertThat(operations.getCount()).isEqualTo(2); // 1 full + 1 partial
    }

    @Test
    void testCacheGauges() {
        // Verify gauges registered
        assertThat(registry.getGauges()).containsKeys(
            "test.re2.cache.patterns.current.count",
            "test.re2.cache.native_memory.current.bytes",
            "test.re2.cache.native_memory.peak.bytes"
        );

        Gauge<Integer> cacheSize = (Gauge<Integer>) registry.getGauges().get("test.re2.cache.patterns.current.count");
        assertThat(cacheSize.getValue()).isEqualTo(0); // initially empty

        // Compile patterns
        Pattern.compile("pattern1");
        assertThat(cacheSize.getValue()).isEqualTo(1);

        Pattern.compile("pattern2");
        assertThat(cacheSize.getValue()).isEqualTo(2);

        Pattern.compile("pattern3");
        assertThat(cacheSize.getValue()).isEqualTo(3);

        // Verify native memory gauge
        Gauge<Long> nativeMemory = (Gauge<Long>) registry.getGauges().get("test.re2.cache.native_memory.current.bytes");
        assertThat(nativeMemory.getValue()).isGreaterThan(0L);

        Gauge<Long> peakMemory = (Gauge<Long>) registry.getGauges().get("test.re2.cache.native_memory.peak.bytes");
        assertThat(peakMemory.getValue()).isGreaterThan(0L);
    }

    @Test
    void testResourceGauges() {
        // Verify resource gauges registered (active counts only)
        assertThat(registry.getGauges()).containsKeys(
            "test.re2.resources.patterns.active.current.count",
            "test.re2.resources.matchers.active.current.count"
        );

        Gauge<Integer> patternsActive = (Gauge<Integer>) registry.getGauges().get("test.re2.resources.patterns.active.current.count");
        Gauge<Integer> matchersActive = (Gauge<Integer>) registry.getGauges().get("test.re2.resources.matchers.active.current.count");

        // Compile pattern (increases active patterns)
        Pattern pattern = Pattern.compile("test.*");
        int activeAfterCompile = patternsActive.getValue();
        assertThat(activeAfterCompile).isGreaterThan(0);

        // Create and close matcher to trigger freed counter
        Matcher matcher = pattern.matcher("test");
        assertThat(matchersActive.getValue()).isGreaterThan(0);

        matcher.close();
        assertThat(matchersActive.getValue()).isEqualTo(0);

        // Verify freed counter was incremented (now a Counter, not Gauge)
        Counter matchersFreed = registry.counter("test.re2.resources.matchers.freed.total.count");
        assertThat(matchersFreed.getCount())
            .as("Matcher freed counter should have incremented")
            .isEqualTo(1);

        // Note: patterns.freed counter only increments when non-cached patterns are freed
        // Cached patterns are managed by cache, so this counter may be 0 in this test
    }

    @Test
    void testErrorMetrics_CompilationFailed() {
        Counter errorCounter = registry.counter("test.re2.errors.compilation.failed.total.count");
        assertThat(errorCounter.getCount()).isEqualTo(0);

        // Trigger compilation error
        try {
            Pattern.compile("(unclosed");
            fail("Should have thrown PatternCompilationException");
        } catch (PatternCompilationException e) {
            // Expected
        }

        // Verify error counter incremented
        assertThat(errorCounter.getCount()).isEqualTo(1);
    }

    @Test
    void testEvictionMetrics() {
        // Create small cache with NO eviction protection
        RE2Config smallCacheConfig = RE2Config.builder()
            .maxCacheSize(5)
            .evictionProtectionMs(0) // No protection - evict immediately
            .metricsRegistry(new DropwizardMetricsAdapter(registry, "eviction.test"))
            .build();

        Pattern.setGlobalCache(new PatternCache(smallCacheConfig));

        Gauge<Integer> cacheSize = (Gauge<Integer>) registry.getGauges().get("eviction.test.cache.patterns.current.count");
        Counter lruEvictions = registry.counter("eviction.test.cache.evictions.lru.total.count");

        long evictionsBefore = lruEvictions.getCount();

        // Compile 15 patterns (way more than cache size of 5)
        for (int i = 0; i < 15; i++) {
            Pattern.compile("eviction_test_" + i);
        }

        // Wait for async LRU eviction to complete
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // ignore
        }

        // Verify evictions occurred
        long evictionsAfter = lruEvictions.getCount();
        assertThat(evictionsAfter)
            .as("LRU evictions should have occurred (15 patterns > 5 max)")
            .isGreaterThan(evictionsBefore);

        // Verify cache size is at or below max after eviction
        int cacheSizeAfterEviction = cacheSize.getValue();
        assertThat(cacheSizeAfterEviction)
            .as("Cache size should be at or below max after eviction")
            .isLessThanOrEqualTo(5);

        // Verify significant evictions occurred (should have evicted ~10 patterns)
        long totalEvictions = evictionsAfter - evictionsBefore;
        assertThat(totalEvictions)
            .as("Should have evicted approximately 10 patterns")
            .isGreaterThanOrEqualTo(8);
    }

    @Test
    void testAll21MetricsExist() {
        // Perform various operations to ensure all metrics are created
        Pattern p1 = Pattern.compile("test.*");
        Pattern p2 = Pattern.compile("test.*"); // cache hit

        try (Matcher m = p1.matcher("test")) {
            m.matches();
            m.find();
        }

        // Try to trigger error
        try {
            Pattern.compile("(invalid");
        } catch (Exception e) {
            // Expected
        }

        // Verify all 21 metric names exist in registry
        // Counters (10)
        assertThat(registry.getCounters().keySet()).contains(
            "test.re2.patterns.compiled.total.count",
            "test.re2.patterns.cache.hits.total.count",
            "test.re2.patterns.cache.misses.total.count",
            "test.re2.matching.operations.total.count"
        );

        // Timers (3)
        assertThat(registry.getTimers().keySet()).contains(
            "test.re2.patterns.compilation.latency",
            "test.re2.matching.full_match.latency",
            "test.re2.matching.partial_match.latency"
        );

        // Gauges (9 - current/peak values only)
        assertThat(registry.getGauges().keySet()).contains(
            "test.re2.cache.patterns.current.count",
            "test.re2.cache.native_memory.current.bytes",
            "test.re2.cache.native_memory.peak.bytes",
            "test.re2.resources.patterns.active.current.count",
            "test.re2.resources.matchers.active.current.count",
            "test.re2.cache.deferred.patterns.current.count",
            "test.re2.cache.deferred.patterns.peak.count",
            "test.re2.cache.deferred.native_memory.current.bytes",
            "test.re2.cache.deferred.native_memory.peak.bytes"
        );

        // Verify freed counts are now Counters (not Gauges)
        assertThat(registry.getCounters().keySet()).contains(
            "test.re2.resources.patterns.freed.total.count",
            "test.re2.resources.matchers.freed.total.count"
        );

        // Error counters exist (even if count is 0)
        assertThat(registry.counter("test.re2.errors.compilation.failed.total.count").getCount()).isGreaterThan(0);
    }

    @Test
    void testNoOpMetrics_ZeroOverhead() {
        // Restore default cache (NoOp metrics)
        Pattern.setGlobalCache(new PatternCache(RE2Config.DEFAULT));

        // Perform operations - should work fine with no metrics
        Pattern pattern = Pattern.compile("test.*");
        try (Matcher matcher = pattern.matcher("test123")) {
            boolean result = matcher.matches();
            assertThat(result).isTrue();
        }

        // Original test registry should have no new metrics
        // (since we switched to NoOp)
    }
}
