package com.axonops.libre2.metrics;

import com.axonops.libre2.api.MatchResult;
import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.cache.PatternCache;
import com.axonops.libre2.cache.RE2Config;
import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive metrics verification test.
 *
 * Verifies that key methods in Pattern.java correctly record metrics.
 * Tests that Global = Sum of Specifics for all operation types.
 */
@DisplayName("Comprehensive Metrics Verification")
class ComprehensiveMetricsIT {

    private MetricRegistry registry;
    private PatternCache originalCache;

    @BeforeEach
    void setup() {
        // Save original cache
        originalCache = Pattern.getGlobalCache();

        // Create test registry
        registry = new MetricRegistry();

        // Create config with Dropwizard metrics
        RE2Config config = RE2Config.builder()
            .metricsRegistry(new DropwizardMetricsAdapter(registry, "test.re2"))
            .build();

        // Inject test cache
        Pattern.setGlobalCache(new PatternCache(config));
    }

    @AfterEach
    void cleanup() {
        // Restore original cache
        Pattern.setGlobalCache(originalCache);
    }

    // ========== Matching Operations Tests ==========

    @Test
    @DisplayName("matches(String) via Matcher records global metrics")
    void matchesString_recordsMetrics() {
        Pattern p = Pattern.compile("unique-pattern-1:\\d+");

        p.matches("test:123");

        // Global matching metrics (recorded by Matcher)
        assertThat(registry.counter("test.re2.matching.operations.total.count").getCount()).isGreaterThanOrEqualTo(1);
        assertThat(registry.timer("test.re2.matching.full_match.latency").getCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("matchAll(String[]) records bulk metrics")
    void matchAllStringArray_recordsBulkMetrics() {
        Pattern p = Pattern.compile("(\\d+)");

        String[] inputs = {"123", "456", "789"};
        p.matchAll(inputs);

        // Global metrics - should count items
        assertThat(registry.counter("test.re2.matching.operations.total.count").getCount()).isEqualTo(3);

        // Specific bulk metrics
        assertThat(registry.counter("test.re2.matching.bulk.operations.total.count").getCount()).isEqualTo(1);
        assertThat(registry.counter("test.re2.matching.bulk.items.total.count").getCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("matchAll bulk operations record correct counts")
    void matchAllRecordsCorrectCounts() {
        Pattern p = Pattern.compile("unique-bulk-test:\\d+");

        long globalBefore = registry.counter("test.re2.matching.operations.total.count").getCount();
        long bulkOpsBefore = registry.counter("test.re2.matching.bulk.operations.total.count").getCount();
        long bulkItemsBefore = registry.counter("test.re2.matching.bulk.items.total.count").getCount();

        // Bulk operation (3 items)
        p.matchAll(new String[]{"test:123", "test:456", "test:789"});

        // Check increments
        long globalDelta = registry.counter("test.re2.matching.operations.total.count").getCount() - globalBefore;
        long bulkOpsDelta = registry.counter("test.re2.matching.bulk.operations.total.count").getCount() - bulkOpsBefore;
        long bulkItemsDelta = registry.counter("test.re2.matching.bulk.items.total.count").getCount() - bulkItemsBefore;

        // Should record 1 bulk operation with 3 items
        assertThat(bulkOpsDelta).isEqualTo(1);
        assertThat(bulkItemsDelta).isEqualTo(3);
        // Global should equal items count for bulk ops
        assertThat(globalDelta).isEqualTo(bulkItemsDelta);
    }

    // ========== Capture Operations Tests ==========

    @Test
    @DisplayName("match(String) records capture metrics")
    void matchString_recordsCaptureMetrics() {
        Pattern p = Pattern.compile("(\\d+)");

        try (MatchResult result = p.match("123")) {
            result.matched();
        }

        // Global capture metrics
        assertThat(registry.counter("test.re2.capture.operations.total.count").getCount()).isEqualTo(1);
        assertThat(registry.timer("test.re2.capture.latency").getCount()).isGreaterThan(0);

        // Specific String metrics
        assertThat(registry.counter("test.re2.capture.string.operations.total.count").getCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("matchAllWithGroups(String[]) records bulk capture metrics")
    void matchAllWithGroupsStringArray_recordsBulkMetrics() {
        Pattern p = Pattern.compile("(\\d+)");

        String[] inputs = {"123", "456", "abc"};
        MatchResult[] results = p.matchAllWithGroups(inputs);
        try {
            for (MatchResult r : results) {
                r.matched();
            }
        } finally {
            for (MatchResult r : results) {
                r.close();
            }
        }

        // Global metrics - count items
        assertThat(registry.counter("test.re2.capture.operations.total.count").getCount()).isEqualTo(3);

        // Specific bulk metrics
        assertThat(registry.counter("test.re2.capture.bulk.operations.total.count").getCount()).isEqualTo(1);
        assertThat(registry.counter("test.re2.capture.bulk.items.total.count").getCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("findAll(String) records findAll match count")
    void findAllString_recordsMatchCount() {
        Pattern p = Pattern.compile("(\\d+)");

        List<MatchResult> matches = p.findAll("a1b22c333");
        try {
            assertThat(matches).hasSize(3);
        } finally {
            matches.forEach(MatchResult::close);
        }

        // Should track match count
        assertThat(registry.counter("test.re2.capture.findall.matches.total.count").getCount()).isEqualTo(3);
    }

    // ========== Replace Operations Tests ==========

    @Test
    @DisplayName("replaceFirst(String) records replace metrics")
    void replaceFirstString_recordsMetrics() {
        Pattern p = Pattern.compile("(\\d+)");

        p.replaceFirst("123", "X");

        // Global replace metrics
        assertThat(registry.counter("test.re2.replace.operations.total.count").getCount()).isEqualTo(1);
        assertThat(registry.timer("test.re2.replace.latency").getCount()).isGreaterThan(0);

        // Specific String metrics
        assertThat(registry.counter("test.re2.replace.string.operations.total.count").getCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("replaceAll(String[]) records bulk replace metrics")
    void replaceAllStringArray_recordsBulkMetrics() {
        Pattern p = Pattern.compile("(\\d+)");

        String[] inputs = {"123", "456", "789"};
        p.replaceAll(inputs, "X");

        // Global metrics - count items
        assertThat(registry.counter("test.re2.replace.operations.total.count").getCount()).isEqualTo(3);

        // Specific bulk metrics
        assertThat(registry.counter("test.re2.replace.bulk.operations.total.count").getCount()).isEqualTo(1);
        assertThat(registry.counter("test.re2.replace.bulk.items.total.count").getCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Global replace = sum of String + Bulk")
    void replaceGlobalEqualsSum() {
        Pattern p = Pattern.compile("(\\d+)");

        // String operation
        p.replaceFirst("123", "X");

        // Bulk operation (3 items)
        p.replaceAll(new String[]{"456", "789", "abc"}, "Y");

        // Global should be 1 + 3 = 4
        long global = registry.counter("test.re2.replace.operations.total.count").getCount();
        long string = registry.counter("test.re2.replace.string.operations.total.count").getCount();
        long bulkItems = registry.counter("test.re2.replace.bulk.items.total.count").getCount();

        assertThat(global).isEqualTo(string + bulkItems);
        assertThat(global).isEqualTo(4);
    }
}
