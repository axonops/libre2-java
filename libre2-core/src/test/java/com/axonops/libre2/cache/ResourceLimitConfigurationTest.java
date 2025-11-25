package com.axonops.libre2.cache;

import com.axonops.libre2.api.Matcher;
import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.api.ResourceException;
import com.axonops.libre2.util.ResourceTracker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * CRITICAL: Tests that resource limits are enforced and are ACTIVE (not cumulative).
 */
class ResourceLimitConfigurationTest {

    @BeforeEach
    void setUp() {
        Pattern.resetCache();
        Pattern.getGlobalCache().getResourceTracker().reset();
    }

    @AfterEach
    void tearDown() {
        Pattern.resetCache();
        Pattern.getGlobalCache().getResourceTracker().reset();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testMaxSimultaneousPatterns_NotCumulative() {
        // CRITICAL TEST: Verify limit is ACTIVE count, not cumulative

        // Compile 10 patterns
        Pattern[] patterns = new Pattern[10];
        for (int i = 0; i < 10; i++) {
            patterns[i] = Pattern.compileWithoutCache("pattern" + i);
        }

        assertThat(Pattern.getGlobalCache().getResourceTracker().getActivePatternCount()).isEqualTo(10);
        assertThat(Pattern.getGlobalCache().getResourceTracker().getTotalPatternsCompiled()).isEqualTo(10);

        // Close all 10 patterns
        for (Pattern p : patterns) {
            p.close();
        }

        assertThat(Pattern.getGlobalCache().getResourceTracker().getActivePatternCount()).isEqualTo(0); // Active = 0
        assertThat(Pattern.getGlobalCache().getResourceTracker().getTotalPatternsCompiled()).isEqualTo(10); // Cumulative = 10

        // Compile 10 NEW patterns - should SUCCESS (not "20 compiled" - patterns were freed)
        for (int i = 0; i < 10; i++) {
            Pattern p = Pattern.compileWithoutCache("new" + i);
            assertThat(p).isNotNull();
            p.close();
        }

        // Active still 0, but cumulative is now 20
        assertThat(Pattern.getGlobalCache().getResourceTracker().getActivePatternCount()).isEqualTo(0);
        assertThat(Pattern.getGlobalCache().getResourceTracker().getTotalPatternsCompiled()).isEqualTo(20);

        // This proves limit is NOT cumulative!
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testResourceStatisticsActiveVsCumulative() {
        // Compile and close patterns repeatedly
        for (int i = 0; i < 100; i++) {
            Pattern p = Pattern.compileWithoutCache("test" + i);
            p.close();
        }

        com.axonops.libre2.util.ResourceTracker.ResourceStatistics stats = Pattern.getGlobalCache().getResourceTracker().getStatistics();

        assertThat(stats.activePatterns()).isEqualTo(0); // None active
        assertThat(stats.totalCompiled()).isEqualTo(100); // 100 compiled over lifetime
        assertThat(stats.totalClosed()).isEqualTo(100); // 100 closed
        assertThat(stats.hasPotentialLeaks()).isFalse(); // No leaks
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testLeakDetection() {
        // Compile patterns but don't close some
        for (int i = 0; i < 10; i++) {
            Pattern p = Pattern.compileWithoutCache("test" + i);
            if (i < 5) {
                p.close(); // Close first 5
            }
            // Last 5 remain open (leak)
        }

        com.axonops.libre2.util.ResourceTracker.ResourceStatistics stats = Pattern.getGlobalCache().getResourceTracker().getStatistics();

        assertThat(stats.activePatterns()).isEqualTo(5); // 5 still active
        assertThat(stats.totalCompiled()).isEqualTo(10);
        assertThat(stats.totalClosed()).isEqualTo(5);
        assertThat(stats.hasPotentialLeaks()).isFalse(); // Not a leak if still active
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testMaxMatchersPerPattern_Enforced() {
        Pattern p = Pattern.compile("test");

        // Create matchers up to limit (default 10K)
        Matcher[] matchers = new Matcher[10000];
        for (int i = 0; i < 10000; i++) {
            matchers[i] = p.matcher("test");
        }

        assertThat(p.getRefCount()).isEqualTo(10000);

        // 10,001st matcher should be rejected
        assertThatThrownBy(() -> p.matcher("test"))
            .isInstanceOf(ResourceException.class)
            .hasMessageContaining("Maximum matchers per pattern exceeded");

        // Clean up
        for (Matcher m : matchers) {
            m.close();
        }

        assertThat(p.getRefCount()).isEqualTo(0);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void testMaxMatchersLimit_MatchersClosedReusesSlot() {
        Pattern p = Pattern.compile("test");

        // Create 5000 matchers
        Matcher[] matchers = new Matcher[5000];
        for (int i = 0; i < 5000; i++) {
            matchers[i] = p.matcher("test");
        }

        assertThat(p.getRefCount()).isEqualTo(5000);

        // Close 4000 matchers
        for (int i = 0; i < 4000; i++) {
            matchers[i].close();
        }

        assertThat(p.getRefCount()).isEqualTo(1000);

        // Can now create 9000 more matchers (total limit 10K, currently 1K active)
        Matcher[] moreMatchers = new Matcher[9000];
        for (int i = 0; i < 9000; i++) {
            moreMatchers[i] = p.matcher("test");
        }

        assertThat(p.getRefCount()).isEqualTo(10000); // At limit

        // Clean up
        for (int i = 1000; i < 5000; i++) {
            matchers[i].close();
        }
        for (Matcher m : moreMatchers) {
            m.close();
        }
    }
}
