package com.axonops.libre2.metrics;

import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.cache.PatternCache;
import com.axonops.libre2.cache.RE2Config;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests specifically for native memory tracking metrics.
 *
 * Verifies that memory gauges accurately track native memory allocation and deallocation.
 */
class NativeMemoryMetricsIT {

    private MetricRegistry registry;
    private PatternCache originalCache;

    @BeforeEach
    void setup() {
        originalCache = Pattern.getGlobalCache();
        registry = new MetricRegistry();

        RE2Config config = RE2Config.builder()
            .metricsRegistry(new DropwizardMetricsAdapter(registry, "memory.test"))
            .build();

        Pattern.setGlobalCache(new PatternCache(config));
    }

    @AfterEach
    void cleanup() {
        Pattern.setGlobalCache(originalCache);
    }

    @Test
    void testMemoryIncreasesWhenPatternsAdded() {
        Gauge<Long> nativeMemory = (Gauge<Long>) registry.getGauges().get("memory.test.cache.native_memory.current.bytes");
        Gauge<Long> peakMemory = (Gauge<Long>) registry.getGauges().get("memory.test.cache.native_memory.peak.bytes");

        // Initial state - should be zero
        long memoryBefore = nativeMemory.getValue();
        assertThat(memoryBefore).isEqualTo(0L);

        // Compile first pattern
        Pattern p1 = Pattern.compile("test.*pattern");
        long memoryAfterP1 = nativeMemory.getValue();

        // Memory should have increased
        assertThat(memoryAfterP1)
            .as("Native memory should increase when pattern added")
            .isGreaterThan(memoryBefore);

        // Compile second pattern (different, larger pattern)
        Pattern p2 = Pattern.compile("very.*complex.*regex.*with.*many.*terms");
        long memoryAfterP2 = nativeMemory.getValue();

        // Memory should have increased again
        assertThat(memoryAfterP2)
            .as("Native memory should increase when second pattern added")
            .isGreaterThan(memoryAfterP1);

        // Peak should track maximum
        assertThat(peakMemory.getValue())
            .as("Peak memory should be >= current memory")
            .isGreaterThanOrEqualTo(memoryAfterP2);
    }

    @Test
    void testMemoryDecreasesWhenPatternsEvicted() {
        // Create small cache to trigger eviction
        RE2Config smallCache = RE2Config.builder()
            .maxCacheSize(3)
            .evictionProtectionMs(0)
            .metricsRegistry(new DropwizardMetricsAdapter(registry, "eviction.memory.test"))
            .build();

        Pattern.setGlobalCache(new PatternCache(smallCache));

        Gauge<Long> nativeMemory = (Gauge<Long>) registry.getGauges().get("eviction.memory.test.cache.native_memory.current.bytes");

        // Add 3 patterns (fill cache)
        Pattern.compile("pattern1");
        Pattern.compile("pattern2");
        Pattern.compile("pattern3");

        long memoryWith3Patterns = nativeMemory.getValue();
        assertThat(memoryWith3Patterns).as("Should have memory with 3 patterns").isGreaterThan(0);

        // Add 5 more patterns (should trigger eviction of first 3)
        for (int i = 4; i <= 8; i++) {
            Pattern.compile("pattern" + i);
        }

        // Wait for async eviction
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // ignore
        }

        // Memory should have stabilized (evicted old patterns, added new ones)
        // Should be approximately same as before (3 patterns worth of memory)
        long memoryAfterEviction = nativeMemory.getValue();

        // Verify memory didn't grow unbounded (eviction freed memory)
        assertThat(memoryAfterEviction)
            .as("Memory should not grow unbounded - eviction should free memory")
            .isLessThan(memoryWith3Patterns * 2); // Shouldn't double

        // More precise: should be similar to initial 3-pattern memory
        assertThat(memoryAfterEviction)
            .as("Memory should be similar to initial state (evicted old, added new)")
            .isBetween(memoryWith3Patterns / 2, memoryWith3Patterns * 2);
    }

    @Test
    void testMemoryTrackingAccuracy() {
        Gauge<Long> nativeMemory = (Gauge<Long>) registry.getGauges().get("memory.test.cache.native_memory.current.bytes");
        Gauge<Integer> cacheSize = (Gauge<Integer>) registry.getGauges().get("memory.test.cache.patterns.current.count");

        // Track actual pattern memory to verify gauge accuracy
        long expectedTotalMemory = 0;

        for (int i = 0; i < 10; i++) {
            Pattern pattern = Pattern.compile("pattern_" + i);

            // Get actual native memory from pattern
            long patternMemory = pattern.getNativeMemoryBytes();
            expectedTotalMemory += patternMemory;

            // Verify gauge matches expected
            long gaugeMemory = nativeMemory.getValue();
            assertThat(gaugeMemory)
                .as("Gauge should exactly match sum of pattern memory (pattern %d)", i)
                .isEqualTo(expectedTotalMemory);

            // Cache size should match patterns added
            int currentSize = cacheSize.getValue();
            assertThat(currentSize)
                .as("Cache size should equal patterns added")
                .isEqualTo(i + 1);
        }

        // Verify final memory is exact
        assertThat(nativeMemory.getValue())
            .as("Final memory gauge should exactly match sum of all pattern memory")
            .isEqualTo(expectedTotalMemory);
    }

    @Test
    void testPeakMemoryTracksMaximum() {
        Gauge<Long> nativeMemory = (Gauge<Long>) registry.getGauges().get("memory.test.cache.native_memory.current.bytes");
        Gauge<Long> peakMemory = (Gauge<Long>) registry.getGauges().get("memory.test.cache.native_memory.peak.bytes");

        // Add patterns and track expected memory
        long expectedMemory = 0;
        for (int i = 0; i < 10; i++) {
            Pattern pattern = Pattern.compile("pattern_with_some_complexity_" + i);
            expectedMemory += pattern.getNativeMemoryBytes();

            // Verify gauge tracks actual memory
            assertThat(nativeMemory.getValue())
                .as("Current memory gauge should match sum of pattern sizes")
                .isEqualTo(expectedMemory);

            // Peak should always be >= current
            assertThat(peakMemory.getValue())
                .as("Peak should be >= current after adding pattern " + i)
                .isGreaterThanOrEqualTo(nativeMemory.getValue());
        }

        long finalMemory = nativeMemory.getValue();
        long finalPeak = peakMemory.getValue();

        // Peak should equal current (we only added, never removed)
        assertThat(finalPeak)
            .as("Peak should equal current (only added patterns, never removed)")
            .isEqualTo(finalMemory);

        // Clear one pattern from cache to reduce memory
        Pattern.clearCache();

        // After clear, current = 0 but peak should still be the old maximum
        // NOTE: resetCache() resets peak (by design), but clearCache() doesn't reset stats
        assertThat(nativeMemory.getValue())
            .as("Current memory should be 0 after clear")
            .isEqualTo(0L);
    }

    @Test
    void testMemoryConsistencyWithCacheOperations() {
        Gauge<Long> nativeMemory = (Gauge<Long>) registry.getGauges().get("memory.test.cache.native_memory.current.bytes");
        Gauge<Integer> cacheSize = (Gauge<Integer>) registry.getGauges().get("memory.test.cache.patterns.current.count");

        // Add 5 patterns
        Pattern p1 = Pattern.compile("p1");
        Pattern p2 = Pattern.compile("p2");
        Pattern p3 = Pattern.compile("p3");
        Pattern p4 = Pattern.compile("p4");
        Pattern p5 = Pattern.compile("p5");

        long memory5Patterns = nativeMemory.getValue();
        assertThat(cacheSize.getValue()).isEqualTo(5);
        assertThat(memory5Patterns).isGreaterThan(0);

        // Compile duplicate (cache hit - no new memory)
        Pattern p1Again = Pattern.compile("p1");
        long memoryAfterCacheHit = nativeMemory.getValue();

        assertThat(memoryAfterCacheHit)
            .as("Memory should NOT increase on cache hit")
            .isEqualTo(memory5Patterns);

        assertThat(cacheSize.getValue())
            .as("Cache size should NOT increase on cache hit")
            .isEqualTo(5);

        // Clear cache
        Pattern.clearCache();

        // Memory should be 0, cache empty
        assertThat(nativeMemory.getValue())
            .as("Memory should be 0 after clear")
            .isEqualTo(0L);

        assertThat(cacheSize.getValue())
            .as("Cache should be empty after clear")
            .isEqualTo(0);
    }
}
