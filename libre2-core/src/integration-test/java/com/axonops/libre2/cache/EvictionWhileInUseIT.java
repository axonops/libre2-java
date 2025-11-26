package com.axonops.libre2.cache;

import com.axonops.libre2.api.Matcher;
import com.axonops.libre2.api.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * CRITICAL TESTS: Verifies patterns can be evicted while in use without crashing.
 *
 * These tests verify the reference counting mechanism prevents use-after-free bugs.
 */
class EvictionWhileInUseIT {

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
    void testReferenceCountIncrements() {
        Pattern p = Pattern.compile("test");

        // Initially refCount should be 0
        assertThat(p.getRefCount()).isEqualTo(0);

        // Create matcher - refCount should increment
        Matcher m = p.matcher("test");
        assertThat(p.getRefCount()).isEqualTo(1);

        // Create another matcher - refCount should increment again
        Matcher m2 = p.matcher("test");
        assertThat(p.getRefCount()).isEqualTo(2);

        // Close first matcher - refCount decrements
        m.close();
        assertThat(p.getRefCount()).isEqualTo(1);

        // Close second matcher - refCount back to 0
        m2.close();
        assertThat(p.getRefCount()).isEqualTo(0);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testPatternNotFreedWhileMatcherActive() {
        Pattern p = Pattern.compile("test");

        // Create matcher (refCount = 1)
        Matcher m = p.matcher("test");
        assertThat(p.getRefCount()).isEqualTo(1);

        // Try to force close pattern (should be deferred due to refCount)
        p.forceClose();

        // Pattern should NOT be closed
        assertThat(p.isClosed()).isFalse();

        // Matcher should still work
        assertThat(m.matches()).isTrue();

        // Close matcher
        m.close();
        assertThat(p.getRefCount()).isEqualTo(0);

        // Now force close should work
        p.forceClose();
        assertThat(p.isClosed()).isTrue();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testMultipleMatchersPreventEviction() {
        Pattern p = Pattern.compile("test");

        // Create 10 matchers
        Matcher[] matchers = new Matcher[10];
        for (int i = 0; i < 10; i++) {
            matchers[i] = p.matcher("test");
        }

        assertThat(p.getRefCount()).isEqualTo(10);

        // Try to evict - should fail due to refCount
        p.forceClose();
        assertThat(p.isClosed()).isFalse();

        // Close matchers one by one
        for (int i = 0; i < 9; i++) {
            matchers[i].close();
            assertThat(p.getRefCount()).isEqualTo(10 - i - 1);
            assertThat(p.isClosed()).isFalse(); // Still in use
        }

        // Close last matcher
        matchers[9].close();
        assertThat(p.getRefCount()).isEqualTo(0);

        // Now eviction can succeed
        p.forceClose();
        assertThat(p.isClosed()).isTrue();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testConcurrentMatchersOnSamePattern() throws InterruptedException {
        Pattern p = Pattern.compile("test");

        int threadCount = 100;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger(0);

        // 100 threads all create matchers on same pattern simultaneously
        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    start.await(); // Wait for signal
                    try (Matcher m = p.matcher("test")) {
                        boolean matches = m.matches();
                        assertThat(matches).isTrue();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }

        // Signal all threads to start simultaneously
        start.countDown();

        // Wait for all to complete
        done.await();

        assertThat(errors.get()).isEqualTo(0);

        // After all matchers closed, refCount should be 0
        assertThat(p.getRefCount()).isEqualTo(0);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testEvictionDeferredWhileInUse() {
        // Compile pattern
        Pattern p = Pattern.compile("test");

        // Create matcher (refCount = 1)
        Matcher m = p.matcher("test");

        // Trigger eviction while matcher active
        Pattern.clearCache(); // This calls forceClose() on all cached patterns

        // Pattern should NOT be closed (matcher still using it)
        assertThat(p.isClosed()).isFalse();

        // Matcher should still work
        assertThat(m.matches()).isTrue();

        // Close matcher
        m.close();

        // Pattern can now be closed
        p.forceClose();
        assertThat(p.isClosed()).isTrue();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testPatternRecompiledWhileOldStillInUse() {
        // Clear cache first
        Pattern.resetCache();

        // Compile and get pattern from cache
        Pattern p1 = Pattern.compile("test");
        Matcher m1 = p1.matcher("test");

        // Force evict (but won't actually close due to refCount)
        Pattern.clearCache();

        // Compile same pattern again - should create NEW instance (cache was cleared)
        Pattern p2 = Pattern.compile("test");

        // p1 and p2 should be different instances (cache was cleared)
        assertThat(p1).isNotSameAs(p2);

        // Both should work independently
        assertThat(m1.matches()).isTrue();
        assertThat(p2.matches("test")).isTrue();

        // Close matcher on p1
        m1.close();

        // Now p1 can be freed
        p1.forceClose();
        assertThat(p1.isClosed()).isTrue();

        // p2 should still work
        assertThat(p2.matches("test")).isTrue();
    }
}
