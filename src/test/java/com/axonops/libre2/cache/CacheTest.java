package com.axonops.libre2.cache;

import com.axonops.libre2.api.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for pattern cache functionality.
 */
class CacheTest {

    @BeforeEach
    void setUp() {
        // Fully reset cache and statistics before each test
        Pattern.resetCache();
    }

    @AfterEach
    void tearDown() {
        // Fully reset cache and statistics after each test
        Pattern.resetCache();
    }

    @Test
    void testCacheHitOnSecondCompile() {
        // First compile - should be a miss
        Pattern p1 = Pattern.compile("test");
        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.misses()).isEqualTo(1);
        assertThat(stats.hits()).isEqualTo(0);

        // Second compile of same pattern - should be a hit
        Pattern p2 = Pattern.compile("test");
        stats = Pattern.getCacheStatistics();
        assertThat(stats.hits()).isEqualTo(1);
        assertThat(stats.misses()).isEqualTo(1);

        // Should return same instance
        assertThat(p1).isSameAs(p2);
    }

    @Test
    void testCacheMissOnDifferentPattern() {
        Pattern p1 = Pattern.compile("pattern1");
        Pattern p2 = Pattern.compile("pattern2");

        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.misses()).isEqualTo(2); // Both were misses
        assertThat(stats.hits()).isEqualTo(0);

        // Should be different instances
        assertThat(p1).isNotSameAs(p2);
    }

    @Test
    void testCaseInsensitiveCreatesSeparateCacheEntry() {
        Pattern p1 = Pattern.compile("TEST", true);  // Case-sensitive
        Pattern p2 = Pattern.compile("TEST", false); // Case-insensitive

        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.misses()).isEqualTo(2); // Different cache keys
        assertThat(stats.currentSize()).isEqualTo(2);

        // Should be different instances
        assertThat(p1).isNotSameAs(p2);
    }

    @Test
    void testCacheHitRate() {
        // Compile 10 unique patterns
        for (int i = 0; i < 10; i++) {
            Pattern.compile("pattern" + i);
        }

        // Compile same 10 patterns again (all hits)
        for (int i = 0; i < 10; i++) {
            Pattern.compile("pattern" + i);
        }

        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.misses()).isEqualTo(10);
        assertThat(stats.hits()).isEqualTo(10);
        assertThat(stats.hitRate()).isEqualTo(0.5); // 50% hit rate
    }

    @Test
    void testCacheSizeLimit() {
        // Compile more patterns than cache max size
        int cacheSize = 50000;  // Default max size
        int patternsToCompile = cacheSize + 100;

        for (int i = 0; i < patternsToCompile; i++) {
            Pattern.compile("pattern" + i);
        }

        CacheStatistics stats = Pattern.getCacheStatistics();
        // Current size should not exceed max
        assertThat(stats.currentSize()).isLessThanOrEqualTo(cacheSize);

        // LRU evictions should have occurred
        assertThat(stats.evictionsLRU()).isGreaterThan(0);
    }

    @Test
    void testLRUEvictionOrder() {
        // Create cache with small max size for testing
        // Note: We're using global cache, so this tests default behavior

        // Compile patterns
        Pattern p1 = Pattern.compile("pattern1");
        Pattern p2 = Pattern.compile("pattern2");
        Pattern p3 = Pattern.compile("pattern3");

        // Access p1 and p3 (not p2)
        Pattern.compile("pattern1"); // Hit
        Pattern.compile("pattern3"); // Hit

        // p2 is least recently used

        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.hits()).isEqualTo(2); // p1 and p3
        assertThat(stats.currentSize()).isEqualTo(3);
    }

    @Test
    void testCacheStatisticsAccuracy() {
        Pattern.compile("a");
        Pattern.compile("b");
        Pattern.compile("a"); // Hit
        Pattern.compile("c");
        Pattern.compile("b"); // Hit

        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.totalRequests()).isEqualTo(5);
        assertThat(stats.hits()).isEqualTo(2);
        assertThat(stats.misses()).isEqualTo(3);
        assertThat(stats.currentSize()).isEqualTo(3); // a, b, c
    }

    @Test
    void testCacheUtilization() {
        // Empty cache
        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.utilization()).isEqualTo(0.0);

        // Add 5000 patterns
        for (int i = 0; i < 5000; i++) {
            Pattern.compile("pattern" + i);
        }

        stats = Pattern.getCacheStatistics();
        assertThat(stats.utilization()).isEqualTo(0.1); // 5000/50000 = 10%
    }

    @Test
    void testClearCache() {
        // Add patterns
        for (int i = 0; i < 10; i++) {
            Pattern.compile("pattern" + i);
        }

        CacheStatistics before = Pattern.getCacheStatistics();
        assertThat(before.currentSize()).isEqualTo(10);

        // Clear cache
        Pattern.clearCache();

        CacheStatistics after = Pattern.getCacheStatistics();
        assertThat(after.currentSize()).isEqualTo(0);

        // Hits/misses/evictions should be preserved
        assertThat(after.hits()).isEqualTo(before.hits());
        assertThat(after.misses()).isEqualTo(before.misses());
    }

    @Test
    void testCompileWithoutCacheDoesNotAffectCache() {
        // Compile without cache
        Pattern p1 = Pattern.compileWithoutCache("test");
        Pattern p2 = Pattern.compileWithoutCache("test");

        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.misses()).isEqualTo(0); // Not tracked in cache
        assertThat(stats.hits()).isEqualTo(0);
        assertThat(stats.currentSize()).isEqualTo(0);

        // Should be different instances
        assertThat(p1).isNotSameAs(p2);

        p1.close();
        p2.close();
    }

    @Test
    void testCachedPatternCannotBeClosed() {
        Pattern p = Pattern.compile("test");
        assertThat(p.isClosed()).isFalse();

        // Calling close() on cached pattern should be no-op
        p.close();

        // Pattern should still not be closed
        assertThat(p.isClosed()).isFalse();

        // Should still be usable
        assertThat(p.matches("test")).isTrue();
    }

    @Test
    void testForceCloseActuallyCloses() {
        Pattern p = Pattern.compileWithoutCache("test");
        assertThat(p.isClosed()).isFalse();

        p.forceClose();
        assertThat(p.isClosed()).isTrue();

        // Should not be usable after force close
        assertThatThrownBy(() -> p.matches("test"))
            .isInstanceOf(IllegalStateException.class);
    }
}
