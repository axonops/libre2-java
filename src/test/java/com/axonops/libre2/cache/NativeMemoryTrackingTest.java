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

package com.axonops.libre2.cache;

import com.axonops.libre2.api.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive tests for native memory tracking in the pattern cache.
 *
 * Tests verify that off-heap memory usage is accurately tracked as patterns
 * are compiled, cached, and evicted.
 */
class NativeMemoryTrackingTest {

    @BeforeEach
    void setUp() {
        Pattern.resetCache();
    }

    @AfterEach
    void tearDown() {
        Pattern.resetCache();
    }

    @Test
    void testPattern_ReportsNonZeroMemory() {
        Pattern p = Pattern.compile("test");

        assertThat(p.getNativeMemoryBytes()).isGreaterThan(0);
    }

    @Test
    void testPattern_SimplePatternMemory() {
        Pattern p = Pattern.compile("hello");

        // Simple patterns should be reasonably small
        assertThat(p.getNativeMemoryBytes()).isGreaterThan(0);
        assertThat(p.getNativeMemoryBytes()).isLessThan(10000); // < 10KB
    }

    @Test
    void testPattern_ComplexPatternUsesMoreMemory() {
        Pattern simple = Pattern.compile("a");
        Pattern complex = Pattern.compile("(\\w+|\\d+|[a-z]{10,50}){0,20}");

        // Complex patterns compile to larger DFA/NFA programs
        assertThat(complex.getNativeMemoryBytes()).isGreaterThan(simple.getNativeMemoryBytes());
    }

    @Test
    void testPattern_UncachedPatternReportsMemory() {
        Pattern p = Pattern.compileWithoutCache("test");
        try {
            assertThat(p.getNativeMemoryBytes()).isGreaterThan(0);
        } finally {
            p.close();
        }
    }

    @Test
    void testCache_TracksTotalMemory() {
        // Compile first pattern
        Pattern p1 = Pattern.compile("pattern1");
        long p1Memory = p1.getNativeMemoryBytes();
        CacheStatistics stats1 = Pattern.getCacheStatistics();

        assertThat(stats1.nativeMemoryBytes()).isEqualTo(p1Memory);

        // Compile second pattern
        Pattern p2 = Pattern.compile("pattern2");
        long p2Memory = p2.getNativeMemoryBytes();
        CacheStatistics stats2 = Pattern.getCacheStatistics();

        assertThat(stats2.nativeMemoryBytes()).isEqualTo(p1Memory + p2Memory);
    }

    @Test
    void testCache_MemoryIncreasesWithPatterns() {
        CacheStatistics before = Pattern.getCacheStatistics();
        assertThat(before.nativeMemoryBytes()).isEqualTo(0);

        // Compile 10 patterns and track exact memory
        long expectedMemory = 0;
        Pattern[] patterns = new Pattern[10];
        for (int i = 0; i < 10; i++) {
            patterns[i] = Pattern.compile("pattern" + i);
            expectedMemory += patterns[i].getNativeMemoryBytes();
        }

        CacheStatistics after = Pattern.getCacheStatistics();
        assertThat(after.nativeMemoryBytes()).isEqualTo(expectedMemory);
        assertThat(after.currentSize()).isEqualTo(10);
    }

    @Test
    void testCache_CacheHitDoesNotIncreaseMemory() {
        Pattern p = Pattern.compile("test");
        long patternMemory = p.getNativeMemoryBytes();
        CacheStatistics stats1 = Pattern.getCacheStatistics();

        assertThat(stats1.nativeMemoryBytes()).isEqualTo(patternMemory);

        // Cache hit - should not change memory
        Pattern p2 = Pattern.compile("test");
        CacheStatistics stats2 = Pattern.getCacheStatistics();

        assertThat(p2).isSameAs(p); // Same instance
        assertThat(stats2.nativeMemoryBytes()).isEqualTo(patternMemory);
        assertThat(stats2.hits()).isEqualTo(1);
    }

    @Test
    void testCache_ClearResetsMemoryToZero() {
        // Compile some patterns and track exact memory
        long expectedMemory = 0;
        for (int i = 0; i < 5; i++) {
            Pattern p = Pattern.compile("pattern" + i);
            expectedMemory += p.getNativeMemoryBytes();
        }

        CacheStatistics before = Pattern.getCacheStatistics();
        assertThat(before.nativeMemoryBytes()).isEqualTo(expectedMemory);
        assertThat(before.currentSize()).isEqualTo(5);

        // Clear cache
        Pattern.clearCache();

        CacheStatistics after = Pattern.getCacheStatistics();
        assertThat(after.nativeMemoryBytes()).isEqualTo(0);
        assertThat(after.currentSize()).isEqualTo(0);
    }

    @Test
    void testCache_PeakMemoryTracked() {
        // Compile patterns and track exact memory
        long expectedMemory = 0;
        for (int i = 0; i < 10; i++) {
            Pattern p = Pattern.compile("pattern" + i);
            expectedMemory += p.getNativeMemoryBytes();
        }

        CacheStatistics stats = Pattern.getCacheStatistics();

        // Peak should equal current (no evictions yet)
        assertThat(stats.nativeMemoryBytes()).isEqualTo(expectedMemory);
        assertThat(stats.peakNativeMemoryBytes()).isEqualTo(expectedMemory);
    }

    @Test
    void testCache_PeakMemoryPreservedAfterEviction() throws InterruptedException {
        // Fill cache to trigger eviction
        int count = 50100; // Just over default max of 50000
        for (int i = 0; i < count; i++) {
            Pattern.compile("pattern" + i);
        }

        // Wait for async eviction
        Thread.sleep(300);

        CacheStatistics stats = Pattern.getCacheStatistics();

        // Peak should be >= current (some evicted)
        assertThat(stats.peakNativeMemoryBytes()).isGreaterThanOrEqualTo(stats.nativeMemoryBytes());
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testCache_MemoryDecrementsOnLRUEviction() throws InterruptedException {
        // Fill cache beyond max size to trigger LRU eviction
        int maxSize = 50000;
        for (int i = 0; i < maxSize + 100; i++) {
            Pattern.compile("pattern" + i);
        }

        // Wait for async eviction
        Thread.sleep(300);

        CacheStatistics stats = Pattern.getCacheStatistics();

        // Memory should reflect current cache size, not all patterns ever compiled
        // With soft limits, cache can be slightly over max
        int expectedMaxPatterns = (int) (maxSize * 1.1);

        // Get a reference pattern for average size
        Pattern ref = Pattern.compile("reference");
        long avgSize = ref.getNativeMemoryBytes();

        // Memory should be roughly proportional to cache size
        long expectedMaxMemory = expectedMaxPatterns * avgSize * 2; // Allow 2x for variation
        assertThat(stats.nativeMemoryBytes()).isLessThan(expectedMaxMemory);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testCache_ConcurrentCompilationTracksMemory() throws InterruptedException {
        int threadCount = 50;
        int patternsPerThread = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        // Collect all patterns to calculate exact expected memory
        java.util.concurrent.ConcurrentHashMap<String, Pattern> allPatterns = new java.util.concurrent.ConcurrentHashMap<>();

        for (int i = 0; i < threadCount; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < patternsPerThread; j++) {
                        String key = "t" + threadId + "_p" + j;
                        Pattern p = Pattern.compile(key);
                        allPatterns.put(key, p);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        done.await();

        assertThat(errors.get()).isEqualTo(0);

        // Calculate exact expected memory from all patterns
        long expectedMemory = allPatterns.values().stream()
            .mapToLong(Pattern::getNativeMemoryBytes)
            .sum();

        CacheStatistics stats = Pattern.getCacheStatistics();
        int expectedPatterns = threadCount * patternsPerThread;

        assertThat(stats.currentSize()).isEqualTo(expectedPatterns);
        assertThat(stats.nativeMemoryBytes()).isEqualTo(expectedMemory);
    }

    @Test
    void testCache_DifferentCaseSensitivityTracksSeparately() {
        Pattern p1 = Pattern.compile("TEST", true);
        long p1Memory = p1.getNativeMemoryBytes();
        CacheStatistics stats1 = Pattern.getCacheStatistics();

        assertThat(stats1.nativeMemoryBytes()).isEqualTo(p1Memory);

        Pattern p2 = Pattern.compile("TEST", false);
        long p2Memory = p2.getNativeMemoryBytes();
        CacheStatistics stats2 = Pattern.getCacheStatistics();

        // Two separate patterns - exact sum
        assertThat(stats2.nativeMemoryBytes()).isEqualTo(p1Memory + p2Memory);
        assertThat(stats2.currentSize()).isEqualTo(2);
    }

    @Test
    void testCache_ResetClearsMemory() {
        // Compile patterns and track exact memory
        long expectedMemory = 0;
        for (int i = 0; i < 5; i++) {
            Pattern p = Pattern.compile("pattern" + i);
            expectedMemory += p.getNativeMemoryBytes();
        }

        assertThat(Pattern.getCacheStatistics().nativeMemoryBytes()).isEqualTo(expectedMemory);

        // Full reset
        Pattern.resetCache();

        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.nativeMemoryBytes()).isEqualTo(0);
        assertThat(stats.peakNativeMemoryBytes()).isEqualTo(0);
        assertThat(stats.currentSize()).isEqualTo(0);
    }

    @Test
    void testCache_MemoryConsistentWithPatternCount() {
        // Compile patterns and track exact memory
        int count = 100;
        long expectedMemory = 0;
        for (int i = 0; i < count; i++) {
            Pattern p = Pattern.compile(String.format("pat%03d", i)); // pat000, pat001, etc.
            expectedMemory += p.getNativeMemoryBytes();
        }

        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.currentSize()).isEqualTo(count);
        assertThat(stats.nativeMemoryBytes()).isEqualTo(expectedMemory);
    }

    @Test
    void testPattern_MemoryVariesByComplexity() {
        // Compile patterns of increasing complexity
        Pattern p1 = Pattern.compile("a");
        Pattern p2 = Pattern.compile("abc");
        Pattern p3 = Pattern.compile("[a-z]+");
        Pattern p4 = Pattern.compile("(a|b|c|d|e)+");
        Pattern p5 = Pattern.compile("(?:[a-z]+\\d+){1,10}");

        // All should report positive memory
        assertThat(p1.getNativeMemoryBytes()).isGreaterThan(0);
        assertThat(p2.getNativeMemoryBytes()).isGreaterThan(0);
        assertThat(p3.getNativeMemoryBytes()).isGreaterThan(0);
        assertThat(p4.getNativeMemoryBytes()).isGreaterThan(0);
        assertThat(p5.getNativeMemoryBytes()).isGreaterThan(0);
    }

    @Test
    void testCache_StatisticsSnapshotIsConsistent() {
        // Compile patterns and track exact memory
        long expectedMemory = 0;
        for (int i = 0; i < 10; i++) {
            Pattern p = Pattern.compile("pattern" + i);
            expectedMemory += p.getNativeMemoryBytes();
        }

        CacheStatistics stats = Pattern.getCacheStatistics();

        // Snapshot should have exact values
        assertThat(stats.currentSize()).isEqualTo(10);
        assertThat(stats.nativeMemoryBytes()).isEqualTo(expectedMemory);
        assertThat(stats.peakNativeMemoryBytes()).isEqualTo(expectedMemory);
        assertThat(stats.misses()).isEqualTo(10);
        assertThat(stats.hits()).isEqualTo(0);
    }
}
