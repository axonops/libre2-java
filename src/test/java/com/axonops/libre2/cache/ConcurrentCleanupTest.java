package com.axonops.libre2.cache;

import com.axonops.libre2.api.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for concurrent resource cleanup.
 */
class ConcurrentCleanupTest {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testConcurrentClose_CacheDisabled_100Threads() throws InterruptedException {
        int threadCount = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        Pattern[] patterns = new Pattern[threadCount];
        for (int i = 0; i < threadCount; i++) {
            patterns[i] = Pattern.compileWithoutCache("test" + i);
        }

        // All threads close simultaneously
        for (int i = 0; i < threadCount; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    start.await();
                    patterns[threadId].close();
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

        // All should be closed
        for (Pattern p : patterns) {
            assertThat(p.isClosed()).isTrue();
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testConcurrentClose_RaceCondition() throws InterruptedException {
        Pattern p = Pattern.compileWithoutCache("test");

        int threadCount = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger closeCalls = new AtomicInteger(0);

        // 10 threads try to close same pattern simultaneously
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    p.close(); // Should be idempotent
                    closeCalls.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        done.await();

        // All threads should have called close without exception
        assertThat(closeCalls.get()).isEqualTo(threadCount);

        // Pattern should be closed exactly once (idempotent)
        assertThat(p.isClosed()).isTrue();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testLRUEvictionWithConcurrentUse() throws InterruptedException {
        int threadCount = 50;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            int threadId = i;
            new Thread(() -> {
                try {
                    start.await();
                    // Each thread compiles 30 patterns (total 1500 > cache size)
                    for (int j = 0; j < 1100; j++) {
                        Pattern p = Pattern.compile("t" + threadId + "_p" + j);
                        assertThat(p.matches("t" + threadId + "_p" + j)).isTrue();
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

        // Cache should be at max, with many LRU evictions
        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.currentSize()).isLessThanOrEqualTo(50000);
        // With 1500 patterns compiled, should have ~500+ evictions
        assertThat(stats.evictionsLRU()).isGreaterThan(5000);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testConcurrentForceClose_100Threads() throws InterruptedException {
        Pattern p = Pattern.compile("test");

        int threadCount = 100;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    p.forceClose(); // All try to force close simultaneously
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
        assertThat(p.isClosed()).isTrue(); // Closed exactly once
    }
}
