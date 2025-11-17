package com.axonops.libre2.cache;

import com.axonops.libre2.api.Matcher;
import com.axonops.libre2.api.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * High-concurrency tests for pattern compilation and matching.
 *
 * Tests library behavior under extreme Cassandra-level concurrent load.
 */
class ConcurrencyTest {

    @BeforeEach
    void setUp() {
        Pattern.resetCache();
    }

    @AfterEach
    void tearDown() {
        Pattern.resetCache();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testConcurrentCompilation_100Threads() throws InterruptedException {
        int threadCount = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    start.await();
                    Pattern p = Pattern.compile("pattern" + threadId);
                    assertThat(p).isNotNull();
                    assertThat(p.matches("pattern" + threadId)).isTrue();
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown(); // Start all threads
        done.await();

        assertThat(errors.get()).isEqualTo(0);

        // Cache should have 100 patterns
        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.currentSize()).isEqualTo(100);
        assertThat(stats.misses()).isEqualTo(100);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testConcurrentCompilation_SamePattern_100Threads() throws InterruptedException {
        int threadCount = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);
        ConcurrentHashMap<Pattern, Boolean> uniqueInstances = new ConcurrentHashMap<>();

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    Pattern p = Pattern.compile("same_pattern");
                    uniqueInstances.put(p, true);
                    assertThat(p.matches("same_pattern")).isTrue();
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

        // All threads should get same instance from cache
        assertThat(uniqueInstances.size()).isEqualTo(1);

        // Cache stats: 1 miss, 99 hits
        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.misses()).isEqualTo(1);
        assertThat(stats.hits()).isEqualTo(99);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testConcurrentCompilation_RepeatingPattern_100Threads() throws InterruptedException {
        int threadCount = 100;
        String[] patterns = {"A", "B", "C"};
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    start.await();
                    String pattern = patterns[threadId % patterns.length];
                    Pattern p = Pattern.compile(pattern);
                    assertThat(p.matches(pattern)).isTrue();
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

        // Should have 3 patterns in cache
        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.currentSize()).isEqualTo(3);

        // High hit rate (100 requests, only 3 unique patterns)
        assertThat(stats.hitRate()).isGreaterThan(0.9);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testConcurrentMatching_100Threads_SamePattern() throws InterruptedException {
        Pattern p = Pattern.compile("test\\d+");

        int threadCount = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);
        AtomicInteger matchCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    start.await();
                    try (Matcher m = p.matcher("test" + threadId)) {
                        if (m.matches()) {
                            matchCount.incrementAndGet();
                        }
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
        assertThat(matchCount.get()).isEqualTo(100);

        // After all matchers closed, refCount should be 0
        assertThat(p.getRefCount()).isEqualTo(0);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testConcurrentMatching_100Threads_DifferentPatterns() throws InterruptedException {
        int threadCount = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    start.await();
                    Pattern p = Pattern.compile("pattern" + threadId);
                    try (Matcher m = p.matcher("pattern" + threadId)) {
                        assertThat(m.matches()).isTrue();
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
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testConcurrentCacheHitsAndMisses_100Threads() throws InterruptedException {
        // Pre-compile some patterns
        Pattern.compile("existing1");
        Pattern.compile("existing2");

        int threadCount = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    start.await();
                    if (threadId % 3 == 0) {
                        // Hit: compile existing pattern
                        Pattern.compile("existing1");
                    } else if (threadId % 3 == 1) {
                        // Hit: compile other existing pattern
                        Pattern.compile("existing2");
                    } else {
                        // Miss: compile new pattern
                        Pattern.compile("new" + threadId);
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

        // Verify cache metrics are accurate
        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.totalRequests()).isEqualTo(100 + 2); // +2 from pre-compile
        assertThat(stats.hits()).isGreaterThan(0);
        assertThat(stats.misses()).isGreaterThan(0);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testConcurrentCacheAndEviction_100Threads() throws InterruptedException {
        int threadCount = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        // Compile many patterns to trigger LRU eviction
        for (int i = 0; i < threadCount; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    start.await();
                    // Each thread compiles 600 patterns = 60K total > 50K cache
                    for (int j = 0; j < 600; j++) {
                        Pattern p = Pattern.compile("thread" + threadId + "_pattern" + j);
                        assertThat(p).isNotNull();
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

        // Cache should be at or below max size (LRU evictions occurred)
        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.currentSize()).isLessThanOrEqualTo(50000); // Default max
        assertThat(stats.evictionsLRU()).isGreaterThan(10000); // Some evictions happened
    }
}
