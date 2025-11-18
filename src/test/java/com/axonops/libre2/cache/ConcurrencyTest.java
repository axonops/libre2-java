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

        // Cache stats: With lock-free implementation, multiple threads might see miss before first put completes
        // The key is that only 1 pattern ends up in cache and total requests = 100
        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.currentSize()).isEqualTo(1);
        assertThat(stats.totalRequests()).isEqualTo(100);
        // Most should be hits, but exact split depends on timing
        assertThat(stats.hits()).isGreaterThanOrEqualTo(90);
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

        // With lock-free implementation, hit/miss counts depend on timing
        // Multiple threads may call get() before any pattern is cached, leading to
        // more misses being recorded. The key invariant is that only 3 patterns
        // end up in cache and all operations complete successfully.
        assertThat(stats.totalRequests()).isEqualTo(100);
        // At minimum we have 3 misses (one per pattern)
        // Hits may be 0 if all threads race and see miss before any pattern is cached
        assertThat(stats.misses()).isGreaterThanOrEqualTo(3);
        assertThat(stats.hits() + stats.misses()).isEqualTo(100);
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

        // Wait for async LRU eviction to settle (must exceed evictionProtectionMs of 1 second)
        Thread.sleep(1500);

        assertThat(errors.get()).isEqualTo(0);

        // With soft limits, cache can temporarily exceed max but should settle back down
        // Allow up to 20% overage due to concurrent async eviction timing
        CacheStatistics stats = Pattern.getCacheStatistics();
        int maxAllowed = (int) (50000 * 1.2);
        assertThat(stats.currentSize()).isLessThanOrEqualTo(maxAllowed);
        // Evictions should have occurred (LRU or deferred)
        assertThat(stats.evictionsLRU() + stats.evictionsDeferred()).isGreaterThan(0);
    }
}
