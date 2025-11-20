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
 * Thread safety verification tests.
 */
class ThreadSafetyTest {

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
    void testConcurrentCacheMapAccess_100Threads() throws InterruptedException {
        int threadCount = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    start.await();

                    int op = threadId % 10;
                    if (op < 3) {
                        // 30%: Insert new
                        Pattern.compile("new" + threadId);
                    } else if (op < 7) {
                        // 40%: Get (cache hit)
                        Pattern.compile("existing");
                    } else {
                        // 30%: Get with iteration (cache statistics)
                        Pattern.getCacheStatistics();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        // Pre-populate "existing"
        Pattern.compile("existing");

        start.countDown();
        done.await();

        assertThat(errors.get()).isEqualTo(0);

        // Verify cache is consistent
        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.totalRequests()).isGreaterThan(0);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testConcurrentMetricsUpdates_100Threads() throws InterruptedException {
        int threadCount = 100;
        int opsPerThread = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < opsPerThread; j++) {
                        // Each operation increments metrics
                        Pattern.compile("pattern" + (j % 10)); // Some hits, some misses
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

        // Verify metrics are accurate (no lost increments)
        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.totalRequests()).isEqualTo(threadCount * opsPerThread);
        assertThat(stats.hits() + stats.misses()).isEqualTo(threadCount * opsPerThread);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testConcurrentRefCountUpdates_100Threads() throws InterruptedException {
        Pattern p = Pattern.compile("test");

        int threadCount = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        // 100 threads create and close matchers simultaneously
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    try (var m = p.matcher("test")) {
                        m.matches();
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

        // After all matchers closed, refCount should be 0 (no lost increments/decrements)
        assertThat(p.getRefCount()).isEqualTo(0);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testNoConcurrentModificationException() throws InterruptedException {
        int threadCount = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    start.await();
                    // Mix of operations that iterate and modify cache
                    if (threadId % 2 == 0) {
                        Pattern.compile("new" + threadId);
                    } else {
                        Pattern.getCacheStatistics(); // Iterates cache
                    }
                } catch (java.util.ConcurrentModificationException e) {
                    errors.incrementAndGet();
                } catch (Exception e) {
                    // Other exceptions are OK for this test
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        done.await();

        // Should never throw ConcurrentModificationException
        assertThat(errors.get()).isEqualTo(0);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testNoDeadlockUnderLoad() throws InterruptedException {
        int threadCount = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    start.await();
                    // Operations that acquire locks in different orders
                    for (int j = 0; j < 100; j++) {
                        Pattern p = Pattern.compile("pattern" + (j % 20));
                        try (var m = p.matcher("test")) {
                            m.find();
                        }
                        Pattern.getCacheStatistics();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();

        // Wait with timeout - if deadlock, this will timeout
        boolean completed = done.await(30, TimeUnit.SECONDS);

        assertThat(completed).isTrue(); // No deadlock
        assertThat(errors.get()).isEqualTo(0);
    }
}
