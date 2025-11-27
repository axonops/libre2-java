package com.axonops.libre2.stress;

import static org.assertj.core.api.Assertions.*;

import com.axonops.libre2.api.Matcher;
import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.cache.CacheStatistics;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Sustained load stress tests for production-level concurrency. */
class StressTest {

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
  void testSustainedLoad_1000Operations_100Threads() throws InterruptedException {
    int threadCount = 100;
    int operationsPerThread = 1000;
    CountDownLatch done = new CountDownLatch(threadCount);
    AtomicInteger errors = new AtomicInteger(0);
    AtomicLong compileOps = new AtomicLong(0);
    AtomicLong matchOps = new AtomicLong(0);

    for (int i = 0; i < threadCount; i++) {
      int threadId = i;
      new Thread(
              () -> {
                try {
                  for (int j = 0; j < operationsPerThread; j++) {
                    int op = j % 10;

                    if (op < 3) {
                      // 30%: Compile pattern
                      Pattern.compile("pattern" + (j % 50)); // Reuse some patterns
                      compileOps.incrementAndGet();
                    } else {
                      // 70%: Match using pattern
                      Pattern p = Pattern.compile("test\\d+");
                      try (Matcher m = p.matcher("test" + j)) {
                        m.find();
                      }
                      matchOps.incrementAndGet();
                    }
                  }
                } catch (Exception e) {
                  errors.incrementAndGet();
                } finally {
                  done.countDown();
                }
              })
          .start();
    }

    done.await();

    assertThat(errors.get()).isEqualTo(0);
    assertThat(compileOps.get() + matchOps.get()).isEqualTo(threadCount * operationsPerThread);

    // Cache should be stable (not growing unbounded)
    CacheStatistics stats = Pattern.getCacheStatistics();
    assertThat(stats.currentSize()).isLessThanOrEqualTo(50000);
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void testBurst_1000Patterns_Simultaneous() throws InterruptedException {
    int threadCount = 1000;
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threadCount);
    AtomicInteger errors = new AtomicInteger(0);

    for (int i = 0; i < threadCount; i++) {
      int threadId = i;
      new Thread(
              () -> {
                try {
                  start.await();
                  Pattern p = Pattern.compile("burst_pattern_" + threadId);
                  assertThat(p).isNotNull();
                } catch (Exception e) {
                  errors.incrementAndGet();
                } finally {
                  done.countDown();
                }
              })
          .start();
    }

    start.countDown();
    done.await();

    assertThat(errors.get()).isEqualTo(0);

    // Cache should handle burst, with evictions
    CacheStatistics stats = Pattern.getCacheStatistics();
    assertThat(stats.currentSize()).isLessThanOrEqualTo(50000);
    assertThat(stats.misses()).isEqualTo(1000);
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void testMemoryPressure_LargePatterns() throws InterruptedException {
    int threadCount = 100;
    CountDownLatch done = new CountDownLatch(threadCount);
    AtomicInteger errors = new AtomicInteger(0);

    // Build complex patterns (large when compiled)
    String complexPattern = "(\\w+|\\d+|[a-z]{10,50}|email@[\\w.]+|https?://[\\w./]+){0,20}";

    for (int i = 0; i < threadCount; i++) {
      new Thread(
              () -> {
                try {
                  Pattern p = Pattern.compile(complexPattern);
                  try (Matcher m = p.matcher("test123email@example.comhttp://test.com")) {
                    m.find();
                  }
                } catch (Exception e) {
                  errors.incrementAndGet();
                } finally {
                  done.countDown();
                }
              })
          .start();
    }

    done.await();

    assertThat(errors.get()).isEqualTo(0);

    // Cache should handle large patterns without OOM
    CacheStatistics stats = Pattern.getCacheStatistics();
    assertThat(stats.currentSize()).isGreaterThan(0);
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void testMemoryPressure_ManySmallPatterns() throws InterruptedException {
    int threadCount = 100;
    int patternsPerThread = 600; // 60K total > 50K cache
    CountDownLatch done = new CountDownLatch(threadCount);
    AtomicInteger errors = new AtomicInteger(0);

    for (int i = 0; i < threadCount; i++) {
      int threadId = i;
      new Thread(
              () -> {
                try {
                  for (int j = 0; j < patternsPerThread; j++) {
                    Pattern.compile("p" + threadId + "_" + j);
                  }
                } catch (Exception e) {
                  errors.incrementAndGet();
                } finally {
                  done.countDown();
                }
              })
          .start();
    }

    done.await();

    // Wait for async eviction to complete (must exceed evictionProtectionMs of 1 second)
    Thread.sleep(1500);

    assertThat(errors.get()).isEqualTo(0);

    // 60,000 patterns compiled - cache should enforce soft size limit
    CacheStatistics stats = Pattern.getCacheStatistics();
    // With soft limits, allow up to 20% overage during high concurrent load
    int maxAllowed = (int) (50000 * 1.2);
    assertThat(stats.currentSize()).isLessThanOrEqualTo(maxAllowed);
    // Some evictions should have occurred
    assertThat(stats.evictionsLRU() + stats.evictionsDeferred()).isGreaterThan(0);
  }
}
