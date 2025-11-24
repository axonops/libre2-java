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

package com.axonops.libre2.jni;

import net.openhft.chronicle.bytes.Bytes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance benchmarks comparing String API vs Zero-Copy Direct API.
 *
 * <p>These benchmarks measure the performance improvement from using Chronicle Bytes
 * direct memory access versus traditional String-based matching.</p>
 *
 * <h2>Expected Results</h2>
 * <ul>
 *   <li>Small inputs (&lt;100 bytes): 10-30% improvement</li>
 *   <li>Medium inputs (1KB-10KB): 30-50% improvement</li>
 *   <li>Large inputs (&gt;10KB): 50-100% improvement</li>
 * </ul>
 *
 * <p>Run with: {@code mvn test -Dtest=ZeroCopyPerformanceTest -Dperformance.test=true}</p>
 */
@DisplayName("Zero-Copy Performance Benchmarks")
@EnabledIfSystemProperty(named = "performance.test", matches = "true")
class ZeroCopyPerformanceTest {

    private static final int WARMUP_ITERATIONS = 1000;
    private static final int BENCHMARK_ITERATIONS = 10000;
    private static final int BULK_SIZE = 100;

    private static long patternHandle;

    @BeforeAll
    static void setUp() {
        RE2LibraryLoader.loadLibrary();
        // Use a moderately complex pattern
        patternHandle = RE2NativeJNI.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b", false);
        assertThat(patternHandle).isNotZero();
    }

    @AfterAll
    static void tearDown() {
        if (patternHandle != 0) {
            RE2NativeJNI.freePattern(patternHandle);
        }
    }

    /**
     * Helper method to safely use Bytes with automatic cleanup.
     * Chronicle Bytes doesn't implement AutoCloseable, so we use this wrapper.
     *
     * IMPORTANT: Uses allocateElasticDirect() to create OFF-HEAP memory, not heap-backed.
     * Heap-backed Bytes don't support addressForRead() because GC can move them.
     */
    private static <T> T withBytes(String text, java.util.function.Function<Bytes<?>, T> action) {
        Bytes<?> bytes = Bytes.allocateElasticDirect();
        try {
            bytes.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return action.apply(bytes);
        } finally {
            bytes.releaseLast();
        }
    }

    // ========== Small Input Benchmarks (64 bytes) ==========

    @Test
    @DisplayName("Benchmark: Small input (64 bytes) - String API")
    void benchmarkSmallInput_stringApi() {
        String input = generateInput(64);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            RE2NativeJNI.partialMatch(patternHandle, input);
        }

        // Benchmark
        long startNanos = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            RE2NativeJNI.partialMatch(patternHandle, input);
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        double nsPerOp = (double) elapsedNanos / BENCHMARK_ITERATIONS;
        System.out.printf("Small (64B) String API: %.2f ns/op%n", nsPerOp);
    }

    @Test
    @DisplayName("Benchmark: Small input (64 bytes) - Direct API")
    void benchmarkSmallInput_directApi() {
        String input = generateInput(64);
        Bytes<?> bytes = createDirectBytes(input);
        try {
            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                RE2DirectMemory.partialMatch(patternHandle, bytes);
            }

            // Benchmark
            long startNanos = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                RE2DirectMemory.partialMatch(patternHandle, bytes);
            }
            long elapsedNanos = System.nanoTime() - startNanos;

            double nsPerOp = (double) elapsedNanos / BENCHMARK_ITERATIONS;
            System.out.printf("Small (64B) Direct API: %.2f ns/op%n", nsPerOp);
        } finally {
            bytes.releaseLast();
        }
    }

    // ========== Medium Input Benchmarks (1KB) ==========

    @Test
    @DisplayName("Benchmark: Medium input (1KB) - String API")
    void benchmarkMediumInput_stringApi() {
        String input = generateInput(1024);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            RE2NativeJNI.partialMatch(patternHandle, input);
        }

        // Benchmark
        long startNanos = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            RE2NativeJNI.partialMatch(patternHandle, input);
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        double nsPerOp = (double) elapsedNanos / BENCHMARK_ITERATIONS;
        System.out.printf("Medium (1KB) String API: %.2f ns/op%n", nsPerOp);
    }

    @Test
    @DisplayName("Benchmark: Medium input (1KB) - Direct API")
    void benchmarkMediumInput_directApi() {
        String input = generateInput(1024);
        Bytes<?> bytes = createDirectBytes(input);
        try {
            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                RE2DirectMemory.partialMatch(patternHandle, bytes);
            }

            // Benchmark
            long startNanos = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                RE2DirectMemory.partialMatch(patternHandle, bytes);
            }
            long elapsedNanos = System.nanoTime() - startNanos;

            double nsPerOp = (double) elapsedNanos / BENCHMARK_ITERATIONS;
            System.out.printf("Medium (1KB) Direct API: %.2f ns/op%n", nsPerOp);
        } finally {
            bytes.releaseLast();
        }
    }

    // ========== Large Input Benchmarks (10KB) ==========

    @Test
    @DisplayName("Benchmark: Large input (10KB) - String API")
    void benchmarkLargeInput_stringApi() {
        String input = generateInput(10240);

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            RE2NativeJNI.partialMatch(patternHandle, input);
        }

        // Benchmark
        long startNanos = System.nanoTime();
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            RE2NativeJNI.partialMatch(patternHandle, input);
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        double nsPerOp = (double) elapsedNanos / BENCHMARK_ITERATIONS;
        System.out.printf("Large (10KB) String API: %.2f ns/op%n", nsPerOp);
    }

    @Test
    @DisplayName("Benchmark: Large input (10KB) - Direct API")
    void benchmarkLargeInput_directApi() {
        String input = generateInput(10240);
        Bytes<?> bytes = createDirectBytes(input);
        try {
            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                RE2DirectMemory.partialMatch(patternHandle, bytes);
            }

            // Benchmark
            long startNanos = System.nanoTime();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                RE2DirectMemory.partialMatch(patternHandle, bytes);
            }
            long elapsedNanos = System.nanoTime() - startNanos;

            double nsPerOp = (double) elapsedNanos / BENCHMARK_ITERATIONS;
            System.out.printf("Large (10KB) Direct API: %.2f ns/op%n", nsPerOp);
        } finally {
            bytes.releaseLast();
        }
    }

    // ========== Very Large Input Benchmarks (100KB) ==========

    @Test
    @DisplayName("Benchmark: Very Large input (100KB) - String API")
    void benchmarkVeryLargeInput_stringApi() {
        String input = generateInput(102400);
        int iterations = BENCHMARK_ITERATIONS / 10; // Fewer iterations for large input

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS / 10; i++) {
            RE2NativeJNI.partialMatch(patternHandle, input);
        }

        // Benchmark
        long startNanos = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            RE2NativeJNI.partialMatch(patternHandle, input);
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        double nsPerOp = (double) elapsedNanos / iterations;
        System.out.printf("Very Large (100KB) String API: %.2f ns/op%n", nsPerOp);
    }

    @Test
    @DisplayName("Benchmark: Very Large input (100KB) - Direct API")
    void benchmarkVeryLargeInput_directApi() {
        String input = generateInput(102400);
        int iterations = BENCHMARK_ITERATIONS / 10;
        Bytes<?> bytes = createDirectBytes(input);
        try {
            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS / 10; i++) {
                RE2DirectMemory.partialMatch(patternHandle, bytes);
            }

            // Benchmark
            long startNanos = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                RE2DirectMemory.partialMatch(patternHandle, bytes);
            }
            long elapsedNanos = System.nanoTime() - startNanos;

            double nsPerOp = (double) elapsedNanos / iterations;
            System.out.printf("Very Large (100KB) Direct API: %.2f ns/op%n", nsPerOp);
        } finally {
            bytes.releaseLast();
        }
    }

    // ========== Bulk Operations Benchmarks ==========

    @Test
    @DisplayName("Benchmark: Bulk operations (100 x 1KB) - String API")
    void benchmarkBulkOperations_stringApi() {
        String[] inputs = new String[BULK_SIZE];
        for (int i = 0; i < BULK_SIZE; i++) {
            inputs[i] = generateInput(1024);
        }

        int iterations = BENCHMARK_ITERATIONS / 10;

        // Warmup
        for (int i = 0; i < WARMUP_ITERATIONS / 10; i++) {
            RE2NativeJNI.partialMatchBulk(patternHandle, inputs);
        }

        // Benchmark
        long startNanos = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            RE2NativeJNI.partialMatchBulk(patternHandle, inputs);
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        double nsPerOp = (double) elapsedNanos / iterations;
        double nsPerItem = nsPerOp / BULK_SIZE;
        System.out.printf("Bulk (100x1KB) String API: %.2f ns/batch, %.2f ns/item%n", nsPerOp, nsPerItem);
    }

    @Test
    @DisplayName("Benchmark: Bulk operations (100 x 1KB) - Direct API")
    void benchmarkBulkOperations_directApi() {
        Bytes<?>[] inputs = new Bytes<?>[BULK_SIZE];
        try {
            for (int i = 0; i < BULK_SIZE; i++) {
                inputs[i] = createDirectBytes(generateInput(1024));
            }

            int iterations = BENCHMARK_ITERATIONS / 10;

            // Warmup
            for (int i = 0; i < WARMUP_ITERATIONS / 10; i++) {
                RE2DirectMemory.partialMatchBulk(patternHandle, inputs);
            }

            // Benchmark
            long startNanos = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                RE2DirectMemory.partialMatchBulk(patternHandle, inputs);
            }
            long elapsedNanos = System.nanoTime() - startNanos;

            double nsPerOp = (double) elapsedNanos / iterations;
            double nsPerItem = nsPerOp / BULK_SIZE;
            System.out.printf("Bulk (100x1KB) Direct API: %.2f ns/batch, %.2f ns/item%n", nsPerOp, nsPerItem);
        } finally {
            for (Bytes<?> b : inputs) {
                if (b != null) b.releaseLast();
            }
        }
    }

    // ========== Comprehensive Comparison ==========

    @Test
    @DisplayName("Comprehensive benchmark comparison")
    void comprehensiveBenchmark() {
        System.out.println("\n========== Zero-Copy Performance Benchmark ==========");
        System.out.println("Pattern: Email regex (moderately complex)");
        System.out.println("Iterations: " + BENCHMARK_ITERATIONS);
        System.out.println();

        int[] sizes = {64, 256, 1024, 4096, 10240, 51200, 102400};
        List<BenchmarkResult> results = new ArrayList<>();

        for (int size : sizes) {
            String input = generateInput(size);
            int iterations = size > 50000 ? BENCHMARK_ITERATIONS / 10 : BENCHMARK_ITERATIONS;
            int warmup = size > 50000 ? WARMUP_ITERATIONS / 10 : WARMUP_ITERATIONS;

            // String API benchmark
            for (int i = 0; i < warmup; i++) {
                RE2NativeJNI.partialMatch(patternHandle, input);
            }
            long stringStart = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                RE2NativeJNI.partialMatch(patternHandle, input);
            }
            long stringNanos = System.nanoTime() - stringStart;
            double stringNsPerOp = (double) stringNanos / iterations;

            // Direct API benchmark
            Bytes<?> bytes = createDirectBytes(input);
            try {
                for (int i = 0; i < warmup; i++) {
                    RE2DirectMemory.partialMatch(patternHandle, bytes);
                }
                long directStart = System.nanoTime();
                for (int i = 0; i < iterations; i++) {
                    RE2DirectMemory.partialMatch(patternHandle, bytes);
                }
                long directNanos = System.nanoTime() - directStart;
                double directNsPerOp = (double) directNanos / iterations;

                double speedup = (stringNsPerOp - directNsPerOp) / stringNsPerOp * 100;
                results.add(new BenchmarkResult(size, stringNsPerOp, directNsPerOp, speedup));
            } finally {
                bytes.releaseLast();
            }
        }

        // Print results table
        System.out.println("| Input Size | String API (ns) | Direct API (ns) | Improvement |");
        System.out.println("|------------|-----------------|-----------------|-------------|");
        for (BenchmarkResult result : results) {
            System.out.printf("| %10s | %15.2f | %15.2f | %10.1f%% |%n",
                formatSize(result.size), result.stringNsPerOp, result.directNsPerOp, result.speedupPercent);
        }
        System.out.println();
    }

    // ========== Helper Methods ==========

    /**
     * Generates test input of specified size with embedded email addresses.
     * This creates realistic test data that contains patterns to match.
     *
     * @param size approximate size in bytes
     * @return generated test string
     */
    private String generateInput(int size) {
        StringBuilder sb = new StringBuilder(size);
        String[] samples = {
            "Hello world this is some text with user@example.com embedded. ",
            "Random text without any emails to match against the pattern. ",
            "Contact us at support@company.org for more information. ",
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit. ",
            "Send feedback to feedback@test.io if you have suggestions. "
        };
        int idx = 0;
        while (sb.length() < size) {
            sb.append(samples[idx % samples.length]);
            idx++;
        }
        return sb.substring(0, size);
    }

    /**
     * Formats size in human-readable format.
     *
     * @param size size in bytes
     * @return formatted string (e.g., "64B", "1KB", "100KB")
     */
    private String formatSize(int size) {
        if (size < 1024) {
            return size + "B";
        } else if (size < 1024 * 1024) {
            return (size / 1024) + "KB";
        } else {
            return (size / (1024 * 1024)) + "MB";
        }
    }

    /**
     * Creates direct (off-heap) Bytes from a String.
     * Heap-backed Bytes don't support addressForRead().
     */
    private static Bytes<?> createDirectBytes(String text) {
        Bytes<?> bytes = Bytes.allocateElasticDirect();
        bytes.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return bytes;
    }

    /**
     * Record to hold benchmark results for comparison.
     */
    private record BenchmarkResult(int size, double stringNsPerOp, double directNsPerOp, double speedupPercent) {}
}
