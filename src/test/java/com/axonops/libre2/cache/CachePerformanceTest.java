package com.axonops.libre2.cache;

import com.axonops.libre2.api.Matcher;
import com.axonops.libre2.api.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;

/**
 * Performance benchmark tests for the optimized PatternCache.
 *
 * These tests demonstrate the lock-free, non-blocking behavior
 * of the ConcurrentHashMap-based cache implementation.
 */
class CachePerformanceTest {
    private static final Logger logger = LoggerFactory.getLogger(CachePerformanceTest.class);

    @BeforeEach
    void setUp() {
        Pattern.resetCache();
    }

    @AfterEach
    void tearDown() {
        Pattern.resetCache();
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void testHighConcurrencyThroughput() throws InterruptedException {
        // Pre-warm cache with patterns
        for (int i = 0; i < 1000; i++) {
            Pattern.compile("pattern" + i);
        }

        int threadCount = 100;
        int operationsPerThread = 10000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicLong totalOps = new AtomicLong(0);
        AtomicInteger errors = new AtomicInteger(0);

        long startTime = System.nanoTime();

        for (int i = 0; i < threadCount; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < operationsPerThread; j++) {
                        // 90% cache hits, 10% misses
                        String pattern = (j % 10 == 0)
                            ? "new_pattern_" + threadId + "_" + j
                            : "pattern" + (j % 1000);
                        Pattern p = Pattern.compile(pattern);
                        try (Matcher m = p.matcher("test")) {
                            m.matches();
                        }
                        totalOps.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    logger.error("Thread error", e);
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        done.await();

        long endTime = System.nanoTime();
        double durationMs = (endTime - startTime) / 1_000_000.0;
        double opsPerSecond = (totalOps.get() / durationMs) * 1000;

        CacheStatistics stats = Pattern.getCacheStatistics();

        logger.info("=== High Concurrency Throughput Test ===");
        logger.info("Threads: {}", threadCount);
        logger.info("Operations per thread: {}", operationsPerThread);
        logger.info("Total operations: {}", totalOps.get());
        logger.info("Duration: {} ms", String.format("%.2f", durationMs));
        logger.info("Throughput: {} ops/sec", String.format("%.0f", opsPerSecond));
        logger.info("Cache hits: {}", stats.hits());
        logger.info("Cache misses: {}", stats.misses());
        logger.info("Hit rate: {}%", String.format("%.1f", stats.hitRate() * 100));
        logger.info("========================================");

        // Verify all operations completed without errors
        assertThat(errors.get()).isEqualTo(0);
        long expected = (long) threadCount * operationsPerThread;
        assertThat(totalOps.get()).isEqualTo(expected);
        // With lock-free implementation, should achieve high throughput
        assertThat(opsPerSecond).isGreaterThan(50000); // At least 50K ops/sec
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testCacheHitLatency() throws InterruptedException {
        // Pre-warm cache
        Pattern testPattern = Pattern.compile("test_pattern");

        int iterations = 100000;
        long[] latencies = new long[iterations];

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            Pattern p = Pattern.compile("test_pattern");
            latencies[i] = System.nanoTime() - start;
        }

        // Calculate statistics
        java.util.Arrays.sort(latencies);
        long p50 = latencies[iterations / 2];
        long p99 = latencies[(int) (iterations * 0.99)];
        long p999 = latencies[(int) (iterations * 0.999)];

        double avgNs = java.util.Arrays.stream(latencies).average().orElse(0);

        logger.info("=== Cache Hit Latency Test ===");
        logger.info("Iterations: {}", iterations);
        logger.info("Average latency: {} ns ({} μs)", String.format("%.0f", avgNs), String.format("%.2f", avgNs / 1000));
        logger.info("P50 latency: {} ns ({} μs)", p50, p50 / 1000.0);
        logger.info("P99 latency: {} ns ({} μs)", p99, p99 / 1000.0);
        logger.info("P99.9 latency: {} ns ({} μs)", p999, p999 / 1000.0);
        logger.info("==============================");

        // With lock-free implementation, cache hits should be very fast
        assertThat(p50).isLessThan(10000); // < 10μs P50
        assertThat(p99).isLessThan(100000); // < 100μs P99
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testEvictionDoesNotBlockAccess() throws InterruptedException {
        // This test verifies that CACHE HITS remain fast during heavy eviction.
        // We measure cache hit latency (not compilation) while eviction runs.

        // Pre-compile patterns that we'll access (cache hits)
        String[] hitPatterns = new String[100];
        for (int i = 0; i < 100; i++) {
            hitPatterns[i] = "hit_pattern_" + i;
            Pattern.compile(hitPatterns[i]);
        }

        // Fill cache near capacity to trigger eviction
        for (int i = 0; i < 49900; i++) {
            Pattern.compile("fill_" + i);
        }

        int threadCount = 50;
        int operationsPerThread = 1000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicLong maxHitLatencyNs = new AtomicLong(0);
        AtomicInteger errors = new AtomicInteger(0);

        // Half threads do cache hits, half trigger evictions with new patterns
        for (int i = 0; i < threadCount; i++) {
            int threadId = i;
            boolean doHits = (i % 2 == 0);  // Even threads do hits

            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < operationsPerThread; j++) {
                        if (doHits) {
                            // Measure cache hit latency
                            long opStart = System.nanoTime();
                            Pattern p = Pattern.compile(hitPatterns[j % 100]);
                            try (Matcher m = p.matcher("test")) {
                                m.matches();
                            }
                            long latency = System.nanoTime() - opStart;

                            // Track max hit latency
                            long current;
                            do {
                                current = maxHitLatencyNs.get();
                            } while (latency > current && !maxHitLatencyNs.compareAndSet(current, latency));
                        } else {
                            // Trigger eviction by compiling new patterns
                            Pattern p = Pattern.compile("new_" + threadId + "_" + j);
                            try (Matcher m = p.matcher("test")) {
                                m.matches();
                            }
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    logger.error("Thread error", e);
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        done.await();

        double maxHitLatencyMs = maxHitLatencyNs.get() / 1_000_000.0;

        logger.info("=== Eviction Non-Blocking Test ===");
        logger.info("Threads: {} ({} doing hits, {} triggering eviction)", threadCount, threadCount/2, threadCount/2);
        logger.info("Operations per thread: {}", operationsPerThread);
        logger.info("Max CACHE HIT latency: {} ms", String.format("%.2f", maxHitLatencyMs));
        logger.info("Errors: {}", errors.get());
        logger.info("==================================");

        // Cache hits should be fast even during eviction
        // With lock-free implementation, hits are ConcurrentHashMap.get() + AtomicLong.set()
        // Allow 100ms for GC pauses from 50K+ patterns, still far better than synchronized
        // code that blocked for SECONDS during eviction scans.
        assertThat(maxHitLatencyMs).isLessThan(100.0);
        assertThat(errors.get()).isEqualTo(0);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testConcurrentCompilationScalability() throws InterruptedException {
        // Test that throughput stays high with concurrent threads
        // (old synchronized code would collapse to near-zero with many threads)
        int[] threadCounts = {1, 10, 50, 100};
        int operationsPerThread = 5000;

        logger.info("=== Scalability Test ===");

        // Pre-warm: ensure native library loaded and JIT warmed up
        Pattern.compile("warmup");

        double previousThroughput = 0;

        for (int threadCount : threadCounts) {
            Pattern.resetCache();

            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threadCount);
            AtomicLong totalOps = new AtomicLong(0);

            for (int i = 0; i < threadCount; i++) {
                int threadId = i;
                new Thread(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < operationsPerThread; j++) {
                            Pattern.compile("pattern" + threadId + "_" + (j % 100));
                            totalOps.incrementAndGet();
                        }
                    } catch (Exception e) {
                        logger.error("Thread error", e);
                    } finally {
                        done.countDown();
                    }
                }).start();
            }

            long startTime = System.nanoTime();
            start.countDown();
            done.await();
            long endTime = System.nanoTime();

            double durationMs = (endTime - startTime) / 1_000_000.0;
            double throughput = (totalOps.get() / durationMs) * 1000;

            logger.info("{} threads: {} ops/sec", threadCount, String.format("%.0f", throughput));

            // Key test: throughput should NOT collapse with more threads
            // Old synchronized implementation would collapse to near-zero
            // With lock-free implementation, throughput scales with thread count
            if (threadCount == 1) {
                // Single thread does cold compilation - expect at least 50K ops/sec
                assertThat(throughput).isGreaterThan(50000);
            } else {
                // Multi-threaded should scale - at least 100K ops/sec
                // (each thread compiles its own unique patterns, no contention)
                assertThat(throughput).isGreaterThan(100000);
            }

            previousThroughput = throughput;
        }

        logger.info("========================");
    }
}
