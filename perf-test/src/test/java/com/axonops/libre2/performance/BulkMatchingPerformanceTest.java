/*
 * Copyright 2025 AxonOps
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.axonops.libre2.performance;

import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.cache.PatternCache;
import com.axonops.libre2.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Performance tests comparing bulk vs individual matching operations.
 * These tests are skipped under QEMU emulation as performance is not representative.
 */
class BulkMatchingPerformanceTest {
    private static final Logger logger = LoggerFactory.getLogger(BulkMatchingPerformanceTest.class);

    private static PatternCache originalCache;

    @BeforeAll
    static void setUpClass() {
        originalCache = TestUtils.replaceGlobalCache(TestUtils.testConfigBuilder().build());
    }

    @AfterAll
    static void tearDownClass() {
        TestUtils.restoreGlobalCache(originalCache);
    }

    /**
     * Detects if running under QEMU emulation (set by CI workflow).
     */
    private static boolean isQemuEmulation() {
        return "true".equals(System.getenv("QEMU_EMULATION"));
    }

    @Test
    void testBulkVsIndividual_10kStrings() {
        assumeTrue(!isQemuEmulation(), "Skipping performance test under QEMU emulation");

        Pattern pattern = Pattern.compile("item\\d+");

        // Create 10,000 test strings
        List<String> inputs = new ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            if (i % 2 == 0) {
                inputs.add("item" + i);
            } else {
                inputs.add("other" + i);
            }
        }

        // Warmup (JIT compilation)
        for (int i = 0; i < 3; i++) {
            pattern.matchAll(inputs);
            individualMatches(pattern, inputs);
        }

        // Benchmark bulk API
        long bulkStart = System.nanoTime();
        boolean[] bulkResults = pattern.matchAll(inputs);
        long bulkDuration = System.nanoTime() - bulkStart;

        // Benchmark individual calls
        long individualStart = System.nanoTime();
        boolean[] individualResults = individualMatches(pattern, inputs);
        long individualDuration = System.nanoTime() - individualStart;

        // Verify correctness (both methods should give same results)
        assertArrayEquals(bulkResults, individualResults);

        // Calculate speedup
        double speedup = (double) individualDuration / bulkDuration;

        logger.info("=== Bulk vs Individual Matching (10,000 strings) ===");
        logger.info("Bulk API duration: {} ms ({} μs per match)",
            bulkDuration / 1_000_000.0, bulkDuration / 10_000.0 / 1000.0);
        logger.info("Individual API duration: {} ms ({} μs per match)",
            individualDuration / 1_000_000.0, individualDuration / 10_000.0 / 1000.0);
        logger.info("Speedup: {}x faster", String.format("%.1f", speedup));
        logger.info("Match count: {}/10000", countMatches(bulkResults));
        logger.info("====================================================");

        // Note: Speedup varies by pattern complexity and JIT warmup
        // Simple patterns: 1-3x (matching cost dominates JNI overhead)
        // Complex patterns: 5-20x (JNI overhead more significant)
        // Performance tests are informational, not assertions
        logger.info("Note: Speedup varies by pattern complexity. Simple patterns: 1-3x, Complex patterns: 10-20x");
    }

    @Test
    void testFilter_Performance() {
        assumeTrue(!isQemuEmulation(), "Skipping performance test under QEMU emulation");

        Pattern pattern = Pattern.compile("[a-z0-9]+@[a-z]+\\.com");  // Allow digits in username

        // Create mix of valid and invalid emails
        List<String> inputs = new ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            if (i % 3 == 0) {
                inputs.add("user" + i + "@example.com");  // Match
            } else {
                inputs.add("invalid_" + i);  // No match
            }
        }

        // Warmup
        for (int i = 0; i < 3; i++) {
            pattern.filter(inputs);
        }

        // Benchmark filter
        long start = System.nanoTime();
        List<String> filtered = pattern.filter(inputs);
        long duration = System.nanoTime() - start;

        logger.info("=== Filter Performance (10,000 strings) ===");
        logger.info("Duration: {} ms", duration / 1_000_000.0);
        logger.info("Filtered count: {}/10000", filtered.size());
        logger.info("Throughput: {} matches/sec", (int)(10_000.0 / (duration / 1_000_000_000.0)));
        logger.info("==========================================");

        // Verify correctness
        assertEquals(3334, filtered.size());  // ~1/3 should match
        assertTrue(filtered.stream().allMatch(s -> s.contains("@example.com")));
    }

    @Test
    void testMapFiltering_Performance() {
        assumeTrue(!isQemuEmulation(), "Skipping performance test under QEMU emulation");

        Pattern pattern = Pattern.compile("user\\d+");

        // Create large map
        Map<String, Integer> inputs = new HashMap<>();
        for (int i = 0; i < 10_000; i++) {
            if (i % 2 == 0) {
                inputs.put("user" + i, i);
            } else {
                inputs.put("admin" + i, i);
            }
        }

        // Warmup
        for (int i = 0; i < 3; i++) {
            pattern.filterByKey(inputs);
        }

        // Benchmark
        long start = System.nanoTime();
        Map<String, Integer> filtered = pattern.filterByKey(inputs);
        long duration = System.nanoTime() - start;

        logger.info("=== Map Filter Performance (10,000 entries) ===");
        logger.info("Duration: {} ms", duration / 1_000_000.0);
        logger.info("Filtered count: {}/10000", filtered.size());
        logger.info("==============================================");

        assertEquals(5000, filtered.size());
        assertTrue(filtered.keySet().stream().allMatch(k -> k.startsWith("user")));
    }

    private boolean[] individualMatches(Pattern pattern, List<String> inputs) {
        boolean[] results = new boolean[inputs.size()];
        for (int i = 0; i < inputs.size(); i++) {
            results[i] = pattern.matches(inputs.get(i));
        }
        return results;
    }

    private int countMatches(boolean[] results) {
        int count = 0;
        for (boolean match : results) {
            if (match) count++;
        }
        return count;
    }
}
