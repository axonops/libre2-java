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
 * Edge case tests for LRU and idle eviction behavior.
 */
class EvictionEdgeCasesTest {

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
    void testLRUEviction_LastAccessTimeUpdated() {
        // Compile patterns in order
        Pattern p1 = Pattern.compile("pattern1");
        Pattern p2 = Pattern.compile("pattern2");
        Pattern p3 = Pattern.compile("pattern3");

        // Access p1 again (updates its position in LRU)
        Pattern p1Again = Pattern.compile("pattern1"); // Cache hit

        assertThat(p1Again).isSameAs(p1); // Same instance from cache

        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.hits()).isEqualTo(1);
        assertThat(stats.currentSize()).isEqualTo(3);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testLRUEviction_MultipleEvictions() {
        int cacheSize = 1000;

        // Add exactly cache size
        for (int i = 0; i < cacheSize; i++) {
            Pattern.compile("pattern" + i);
        }

        CacheStatistics before = Pattern.getCacheStatistics();
        assertThat(before.currentSize()).isEqualTo(cacheSize);
        assertThat(before.evictionsLRU()).isEqualTo(0);

        // Add 100 more - should trigger 100 LRU evictions
        for (int i = cacheSize; i < cacheSize + 100; i++) {
            Pattern.compile("pattern" + i);
        }

        CacheStatistics after = Pattern.getCacheStatistics();
        assertThat(after.currentSize()).isEqualTo(cacheSize);
        assertThat(after.evictionsLRU()).isEqualTo(100);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testEvictionWithActiveMatchers() {
        // Compile patterns up to cache limit
        for (int i = 0; i < 1000; i++) {
            Pattern.compile("filler" + i);
        }

        // Compile one more pattern with active matcher
        Pattern p = Pattern.compile("important");
        Matcher m = p.matcher("test");

        assertThat(p.getRefCount()).isEqualTo(1); // Matcher holding reference

        // Try to trigger eviction
        for (int i = 0; i < 100; i++) {
            Pattern.compile("new" + i);
        }

        // Pattern with active matcher might still be in cache (not evicted)
        // Or if evicted, it's not freed due to refCount
        assertThat(p.isClosed()).isFalse(); // Not freed while matcher active

        m.close();
        assertThat(p.getRefCount()).isEqualTo(0);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testCacheClearWithActiveMatchers() {
        Pattern p = Pattern.compile("test");
        Matcher m = p.matcher("test");

        assertThat(p.getRefCount()).isEqualTo(1);

        // Clear cache (calls forceClose on all patterns)
        Pattern.clearCache();

        // Pattern should NOT be closed (matcher still active)
        assertThat(p.isClosed()).isFalse();

        // Matcher should still work
        assertThat(m.matches()).isTrue();

        m.close();

        // Now pattern can be closed
        p.forceClose();
        assertThat(p.isClosed()).isTrue();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testEvictionDeterministic() {
        // Add patterns in specific order
        for (int i = 0; i < 10; i++) {
            Pattern.compile("pattern" + i);
        }

        // Access patterns in different order
        Pattern.compile("pattern5"); // Hit
        Pattern.compile("pattern2"); // Hit
        Pattern.compile("pattern8"); // Hit

        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.hits()).isEqualTo(3);

        // LRU order is deterministic based on access pattern
        assertThat(stats.currentSize()).isEqualTo(10);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testCaseSensitivityInEviction() {
        // Same pattern string, different case sensitivity
        Pattern p1 = Pattern.compile("TEST", true);  // Case-sensitive
        Pattern p2 = Pattern.compile("TEST", false); // Case-insensitive

        // Should be separate cache entries
        assertThat(p1).isNotSameAs(p2);

        CacheStatistics stats = Pattern.getCacheStatistics();
        assertThat(stats.currentSize()).isEqualTo(2);

        // Both should survive eviction (different keys)
        Pattern.compile("TEST", true);  // Hit on p1
        Pattern.compile("TEST", false); // Hit on p2

        stats = Pattern.getCacheStatistics();
        assertThat(stats.hits()).isEqualTo(2);
    }
}
