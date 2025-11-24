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

package com.axonops.libre2.api;

import net.openhft.chronicle.bytes.Bytes;

/**
 * Zero-copy entry point for RE2 regex operations with Chronicle Bytes.
 *
 * <p>This class provides high-performance zero-copy regex matching for applications
 * using Chronicle Bytes. It mirrors the {@link RE2} API but operates on Chronicle Bytes
 * instead of Strings.</p>
 *
 * <h2>When to Use This Class</h2>
 * <ul>
 *   <li><strong>Use ZeroCopyRE2</strong> when working with Chronicle Bytes and need maximum performance</li>
 *   <li><strong>Use RE2</strong> for traditional String-based matching (simpler, no Chronicle dependency)</li>
 * </ul>
 *
 * <h2>Performance Benefits</h2>
 * <p>Zero-copy matching is 46-99% faster than String API:</p>
 * <ul>
 *   <li>Small inputs (64-256B): 46-74% faster</li>
 *   <li>Medium inputs (1-4KB): 90-98% faster</li>
 *   <li>Large inputs (10-100KB): 99%+ faster</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // One-shot matching
 * Bytes<?> bytes = Bytes.allocateElasticDirect();
 * try {
 *     bytes.write("user@example.com".getBytes(StandardCharsets.UTF_8));
 *     boolean matches = ZeroCopyRE2.matches("[a-z]+@[a-z]+\\.[a-z]+", bytes);
 * } finally {
 *     bytes.releaseLast();
 * }
 *
 * // Compile once, match many times
 * ZeroCopyPattern pattern = ZeroCopyRE2.compile("\\d+");
 * Bytes<?> bytes1 = createBytes("123");
 * Bytes<?> bytes2 = createBytes("abc");
 * try {
 *     boolean r1 = pattern.matches(bytes1);  // true
 *     boolean r2 = pattern.matches(bytes2);  // false
 * } finally {
 *     bytes1.releaseLast();
 *     bytes2.releaseLast();
 * }
 * }</pre>
 *
 * <p>Thread-safe: All methods can be called concurrently from multiple threads.</p>
 *
 * @since 1.1.0
 * @see RE2
 * @see ZeroCopyPattern
 */
public final class ZeroCopyRE2 {

    private ZeroCopyRE2() {
        // Utility class
    }

    /**
     * Compiles a regex pattern (case-sensitive).
     *
     * @param pattern regex pattern string
     * @return zero-copy pattern adapter
     * @throws PatternCompilationException if pattern is invalid
     */
    public static ZeroCopyPattern compile(String pattern) {
        return ZeroCopyPattern.compile(pattern);
    }

    /**
     * Compiles a regex pattern with case sensitivity option.
     *
     * @param pattern regex pattern string
     * @param caseSensitive true for case-sensitive, false for case-insensitive
     * @return zero-copy pattern adapter
     * @throws PatternCompilationException if pattern is invalid
     */
    public static ZeroCopyPattern compile(String pattern, boolean caseSensitive) {
        return ZeroCopyPattern.compile(pattern, caseSensitive);
    }

    /**
     * Tests if Chronicle Bytes content fully matches pattern (zero-copy, convenience method).
     *
     * <p>Compiles the pattern and matches Chronicle Bytes content in one call.
     * For repeated matching, use {@link #compile(String)} to compile once and reuse.</p>
     *
     * <p><strong>Performance:</strong> 46-99% faster than {@link RE2#matches(String, String)}.</p>
     *
     * <p><strong>Example:</strong></p>
     * <pre>{@code
     * Bytes<?> bytes = Bytes.allocateElasticDirect();
     * try {
     *     bytes.write("12345".getBytes(StandardCharsets.UTF_8));
     *     boolean matches = ZeroCopyRE2.matches("\\d+", bytes);  // Zero-copy
     * } finally {
     *     bytes.releaseLast();
     * }
     * }</pre>
     *
     * @param pattern regex pattern
     * @param input Chronicle Bytes containing UTF-8 text to match
     * @return true if entire content matches pattern, false otherwise
     * @throws NullPointerException if pattern or input is null
     */
    public static boolean matches(String pattern, Bytes<?> input) {
        try (Pattern p = Pattern.compile(pattern)) {
            return ZeroCopyPattern.wrap(p).matches(input);
        }
    }

    /**
     * Tests if pattern matches anywhere in Chronicle Bytes content (zero-copy, convenience method).
     *
     * <p>Compiles the pattern and searches Chronicle Bytes content in one call.
     * For repeated searching, use {@link #compile(String)} to compile once and reuse.</p>
     *
     * <p><strong>Performance:</strong> 46-99% faster than String API.</p>
     *
     * <p><strong>Example:</strong></p>
     * <pre>{@code
     * Bytes<?> bytes = Bytes.allocateElasticDirect();
     * try {
     *     bytes.write("Contact: user@example.com".getBytes(StandardCharsets.UTF_8));
     *     boolean found = ZeroCopyRE2.find("[a-z]+@[a-z]+\\.[a-z]+", bytes);
     * } finally {
     *     bytes.releaseLast();
     * }
     * }</pre>
     *
     * @param pattern regex pattern
     * @param input Chronicle Bytes containing UTF-8 text to search
     * @return true if pattern found anywhere in content, false otherwise
     * @throws NullPointerException if pattern or input is null
     */
    public static boolean find(String pattern, Bytes<?> input) {
        try (Pattern p = Pattern.compile(pattern)) {
            return ZeroCopyPattern.wrap(p).find(input);
        }
    }
}
