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

import com.axonops.libre2.cache.PatternCache;
import com.axonops.libre2.cache.RE2Config;
import com.axonops.libre2.jni.RE2LibraryLoader;
import com.axonops.libre2.jni.RE2NativeJNI;
import com.axonops.libre2.metrics.MetricNames;
import com.axonops.libre2.metrics.RE2MetricsRegistry;
import com.axonops.libre2.util.PatternHasher;
import com.axonops.libre2.util.ResourceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

// DirectBuffer is a public interface - no reflection needed
import sun.nio.ch.DirectBuffer;

/**
 * A compiled regular expression pattern.
 *
 * Thread-safe: Pattern instances can be safely shared between threads.
 * Multiple threads can call matcher() concurrently on the same Pattern.
 *
 * Resource Management: Patterns from compile() are cached and managed automatically.
 * Do NOT call close() on cached patterns (it's a no-op). For testing, use compileWithoutCache().
 *
 * Reference Counting: Patterns are not freed while Matchers are active (prevents use-after-free).
 *
 * @since 1.0.0
 */
public final class Pattern implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(Pattern.class);

    // Ensure native library is loaded
    static {
        RE2LibraryLoader.loadLibrary();
    }

    // Global pattern cache (mutable for testing only)
    private static volatile PatternCache cache = new PatternCache(RE2Config.DEFAULT);

    /**
     * Gets the global pattern cache (for internal use).
     */
    public static PatternCache getGlobalCache() {
        return cache;
    }

    private final String patternString;
    private final boolean caseSensitive;
    private final long nativeHandle;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final boolean fromCache;
    private final java.util.concurrent.atomic.AtomicInteger refCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int maxMatchersPerPattern = RE2Config.DEFAULT.maxMatchersPerPattern();
    private final long nativeMemoryBytes;

    Pattern(String patternString, boolean caseSensitive, long nativeHandle) {
        this(patternString, caseSensitive, nativeHandle, false);
    }

    Pattern(String patternString, boolean caseSensitive, long nativeHandle, boolean fromCache) {
        this.patternString = Objects.requireNonNull(patternString);
        this.caseSensitive = caseSensitive;
        this.nativeHandle = nativeHandle;
        this.fromCache = fromCache;

        // Query native memory size
        this.nativeMemoryBytes = RE2NativeJNI.patternMemory(nativeHandle);

        logger.trace("RE2: Pattern created - length: {}, caseSensitive: {}, fromCache: {}, nativeBytes: {}",
            patternString.length(), caseSensitive, fromCache, nativeMemoryBytes);
    }

    public static Pattern compile(String pattern) {
        return compile(pattern, true);
    }

    public static Pattern compile(String pattern, boolean caseSensitive) {
        Objects.requireNonNull(pattern, "pattern cannot be null");

        // Try cache first
        return cache.getOrCompile(pattern, caseSensitive, () -> compileUncached(pattern, caseSensitive));
    }

    /**
     * Compiles a pattern without using the cache (for testing/special cases).
     *
     * The returned pattern is NOT managed by the cache and MUST be closed.
     *
     * @param pattern regex pattern
     * @return uncached pattern (must close)
     */
    public static Pattern compileWithoutCache(String pattern) {
        return compileWithoutCache(pattern, true);
    }

    /**
     * Compiles a pattern without using the cache (for testing/special cases).
     *
     * The returned pattern is NOT managed by the cache and MUST be closed.
     *
     * @param pattern regex pattern
     * @param caseSensitive case sensitivity
     * @return uncached pattern (must close)
     */
    public static Pattern compileWithoutCache(String pattern, boolean caseSensitive) {
        // Compile with fromCache=false so it can actually be closed
        return doCompile(pattern, caseSensitive, false);
    }

    /**
     * Compiles a pattern for caching (internal use).
     */
    private static Pattern compileUncached(String pattern, boolean caseSensitive) {
        // Compile with fromCache=true so users can't close it (cache manages it)
        return doCompile(pattern, caseSensitive, true);
    }

    /**
     * Actual compilation logic.
     */
    private static Pattern doCompile(String pattern, boolean caseSensitive, boolean fromCache) {
        RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();
        String hash = PatternHasher.hash(pattern);

        // Reject empty patterns (matches old wrapper behavior)
        if (pattern.isEmpty()) {
            throw new PatternCompilationException(pattern, "Pattern is null or empty");
        }

        // Track allocation and enforce maxSimultaneousCompiledPatterns limit
        // This is ACTIVE count, not cumulative - patterns can be freed and recompiled
        cache.getResourceTracker().trackPatternAllocated(cache.getConfig().maxSimultaneousCompiledPatterns(), metrics);

        long startNanos = System.nanoTime();
        long handle = 0;
        boolean compilationSuccessful = false;

        try {
            handle = RE2NativeJNI.compile(pattern, caseSensitive);

            if (handle == 0 || !RE2NativeJNI.patternOk(handle)) {
                String error = RE2NativeJNI.getError();

                // Compilation failed - record error
                metrics.incrementCounter(MetricNames.ERRORS_COMPILATION_FAILED);
                logger.debug("RE2: Pattern compilation failed - hash: {}, error: {}", hash, error);

                // Will be cleaned up in finally block
                throw new PatternCompilationException(pattern, error != null ? error : "Unknown error");
            }

            long durationNanos = System.nanoTime() - startNanos;
            metrics.recordTimer(MetricNames.PATTERNS_COMPILATION_LATENCY, durationNanos);
            metrics.incrementCounter(MetricNames.PATTERNS_COMPILED);

            Pattern compiled = new Pattern(pattern, caseSensitive, handle, fromCache);
            logger.trace("RE2: Pattern compiled - hash: {}, length: {}, caseSensitive: {}, fromCache: {}, nativeBytes: {}, timeNs: {}",
                hash, pattern.length(), caseSensitive, fromCache, compiled.nativeMemoryBytes, durationNanos);

            compilationSuccessful = true;
            return compiled;

        } catch (ResourceException e) {
            // Resource limit hit - count already rolled back by trackPatternAllocated
            throw e;

        } finally {
            // Clean up if compilation failed
            if (!compilationSuccessful) {
                // Free handle if allocated
                if (handle != 0) {
                    try {
                        RE2NativeJNI.freePattern(handle);
                    } catch (Exception e) {
                        // Silently ignore - best effort cleanup
                    }
                }

                // Decrement count (allocation failed)
                cache.getResourceTracker().trackPatternFreed(metrics);
            }
        }
    }

    public Matcher matcher(String input) {
        checkNotClosed();
        return new Matcher(this, input);
    }

    public boolean matches(String input) {
        try (Matcher m = matcher(input)) {
            return m.matches();
        }
    }

    /**
     * Tests if content at memory address fully matches this pattern (zero-copy).
     *
     * <p>This method accepts a raw memory address and length, enabling zero-copy matching
     * with any off-heap memory system.</p>
     *
     * <p><strong>Performance:</strong> 46-99% faster than String API depending on input size.
     * For 10KB+ inputs, provides 99%+ improvement.</p>
     *
     * <p><strong>Memory Safety:</strong> The memory at {@code address} must:</p>
     * <ul>
     *   <li>Remain valid for the duration of this call</li>
     *   <li>Contain valid UTF-8 encoded text</li>
     *   <li>Not be released/freed until this method returns</li>
     * </ul>
     *
     * <p><strong>Usage with DirectByteBuffer:</strong></p>
     * <pre>{@code
     * import sun.nio.ch.DirectBuffer;
     *
     * Pattern pattern = Pattern.compile("\\d+");
     * ByteBuffer buffer = ByteBuffer.allocateDirect(1024);
     * buffer.put("12345".getBytes(StandardCharsets.UTF_8));
     * buffer.flip();
     *
     * long address = ((DirectBuffer) buffer).address();
     * int length = buffer.remaining();
     * boolean matches = pattern.matches(address, length);  // Zero-copy!
     * }</pre>
     *
     * <p><strong>Note:</strong> Most users should use {@link #matches(ByteBuffer)} instead,
     * which handles address extraction automatically.</p>
     *
     * @param address native memory address of UTF-8 encoded text
     * @param length number of bytes to read from the address
     * @return true if entire content matches this pattern, false otherwise
     * @throws IllegalArgumentException if address is 0 or length is negative
     * @throws IllegalStateException if pattern is closed
     * @see #matches(String) String-based variant
     * @see #matches(ByteBuffer) ByteBuffer variant with automatic routing
     * @since 1.1.0
     */
    public boolean matches(long address, int length) {
        checkNotClosed();
        if (address == 0) {
            throw new IllegalArgumentException("Address must not be 0");
        }
        if (length < 0) {
            throw new IllegalArgumentException("Length must not be negative: " + length);
        }

        long startNanos = System.nanoTime();
        boolean result = RE2NativeJNI.fullMatchDirect(nativeHandle, address, length);
        long durationNanos = System.nanoTime() - startNanos;

        // Track metrics - GLOBAL (ALL) + SPECIFIC (Zero-Copy)
        RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();

        // Global metrics (ALL matching operations)
        metrics.incrementCounter(MetricNames.MATCHING_OPERATIONS);
        metrics.recordTimer(MetricNames.MATCHING_LATENCY, durationNanos);
        metrics.recordTimer(MetricNames.MATCHING_FULL_MATCH_LATENCY, durationNanos);

        // Specific zero-copy metrics
        metrics.incrementCounter(MetricNames.MATCHING_ZERO_COPY_OPERATIONS);
        metrics.recordTimer(MetricNames.MATCHING_ZERO_COPY_LATENCY, durationNanos);

        return result;
    }

    /**
     * Tests if pattern matches anywhere in content at memory address (zero-copy).
     *
     * <p>This is the partial match variant - tests if pattern matches anywhere
     * within the input, not necessarily the entire content.</p>
     *
     * <p><strong>Performance:</strong> 46-99% faster than String API.</p>
     *
     * <p><strong>Memory Safety:</strong> The memory at {@code address} must remain
     * valid for the duration of this call.</p>
     *
     * <p><strong>Usage with DirectByteBuffer:</strong></p>
     * <pre>{@code
     * import sun.nio.ch.DirectBuffer;
     *
     * Pattern pattern = Pattern.compile("@[a-z]+\\.[a-z]+");
     * ByteBuffer buffer = ByteBuffer.allocateDirect(1024);
     * buffer.put("Contact: user@example.com".getBytes(StandardCharsets.UTF_8));
     * buffer.flip();
     *
     * long address = ((DirectBuffer) buffer).address();
     * int length = buffer.remaining();
     * boolean found = pattern.find(address, length);  // Zero-copy!
     * }</pre>
     *
     * <p><strong>Note:</strong> Most users should use {@link #find(ByteBuffer)} instead.</p>
     *
     * @param address native memory address of UTF-8 encoded text
     * @param length number of bytes to read from the address
     * @return true if pattern matches anywhere in content, false otherwise
     * @throws IllegalArgumentException if address is 0 or length is negative
     * @throws IllegalStateException if pattern is closed
     * @see #find(ByteBuffer) ByteBuffer variant with automatic routing
     * @since 1.1.0
     */
    public boolean find(long address, int length) {
        checkNotClosed();
        if (address == 0) {
            throw new IllegalArgumentException("Address must not be 0");
        }
        if (length < 0) {
            throw new IllegalArgumentException("Length must not be negative: " + length);
        }

        long startNanos = System.nanoTime();
        boolean result = RE2NativeJNI.partialMatchDirect(nativeHandle, address, length);
        long durationNanos = System.nanoTime() - startNanos;

        // Track metrics - GLOBAL (ALL) + SPECIFIC (Zero-Copy)
        RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();

        // Global metrics (ALL matching operations)
        metrics.incrementCounter(MetricNames.MATCHING_OPERATIONS);
        metrics.recordTimer(MetricNames.MATCHING_LATENCY, durationNanos);
        metrics.recordTimer(MetricNames.MATCHING_PARTIAL_MATCH_LATENCY, durationNanos);

        // Specific zero-copy metrics
        metrics.incrementCounter(MetricNames.MATCHING_ZERO_COPY_OPERATIONS);
        metrics.recordTimer(MetricNames.MATCHING_ZERO_COPY_LATENCY, durationNanos);

        return result;
    }

    // ========== Capture Group Operations ==========

    /**
     * Matches input and extracts capture groups.
     *
     * <p>This method performs a full match and returns a {@link MatchResult} containing
     * all captured groups. If the match fails, the MatchResult will have {@code matched() == false}.</p>
     *
     * <p><strong>Example - Extract email components:</strong></p>
     * <pre>{@code
     * Pattern pattern = Pattern.compile("([a-z]+)@([a-z]+)\\.([a-z]+)");
     * MatchResult result = pattern.match("user@example.com");
     *
     * if (result.matched()) {
     *     String full = result.group();      // "user@example.com"
     *     String user = result.group(1);     // "user"
     *     String domain = result.group(2);   // "example"
     *     String tld = result.group(3);      // "com"
     * }
     * }</pre>
     *
     * <p><strong>Named Groups:</strong></p>
     * <pre>{@code
     * Pattern pattern = Pattern.compile("(?P<year>\\d{4})-(?P<month>\\d{2})-(?P<day>\\d{2})");
     * MatchResult result = pattern.match("2025-11-24");
     *
     * if (result.matched()) {
     *     String year = result.group("year");   // "2025"
     *     String month = result.group("month"); // "11"
     *     String day = result.group("day");     // "24"
     * }
     * }</pre>
     *
     * @param input the string to match
     * @return MatchResult containing captured groups, or a failed match if no match
     * @throws NullPointerException if input is null
     * @throws IllegalStateException if pattern is closed
     * @see MatchResult
     * @see #find(String) for partial matching with groups
     * @see #findAll(String) for finding all matches with groups
     * @since 1.2.0
     */
    public MatchResult match(String input) {
        checkNotClosed();
        Objects.requireNonNull(input, "input cannot be null");

        long startNanos = System.nanoTime();

        String[] groups = RE2NativeJNI.extractGroups(nativeHandle, input);

        if (groups == null) {
            // No match - still track metrics (operation was attempted)
            long durationNanos = System.nanoTime() - startNanos;
            RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();

            // Global capture metrics
            metrics.incrementCounter(MetricNames.CAPTURE_OPERATIONS);
            metrics.recordTimer(MetricNames.CAPTURE_LATENCY, durationNanos);

            // Specific String capture metrics
            metrics.incrementCounter(MetricNames.CAPTURE_STRING_OPERATIONS);
            metrics.recordTimer(MetricNames.CAPTURE_STRING_LATENCY, durationNanos);

            return new MatchResult(input);
        }

        // For match() (full match semantics), verify the match covers entire input
        // extractGroups uses UNANCHORED, so we need to check manually
        if (!groups[0].equals(input)) {
            // Match found but doesn't cover entire input - this is a partial match
            long durationNanos = System.nanoTime() - startNanos;
            RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();

            // Global capture metrics
            metrics.incrementCounter(MetricNames.CAPTURE_OPERATIONS);
            metrics.recordTimer(MetricNames.CAPTURE_LATENCY, durationNanos);

            // Specific String capture metrics
            metrics.incrementCounter(MetricNames.CAPTURE_STRING_OPERATIONS);
            metrics.recordTimer(MetricNames.CAPTURE_STRING_LATENCY, durationNanos);

            return new MatchResult(input);
        }

        long durationNanos = System.nanoTime() - startNanos;

        // Track metrics - GLOBAL (ALL) + SPECIFIC (String)
        RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();

        // Global capture metrics (ALL capture operations)
        metrics.incrementCounter(MetricNames.CAPTURE_OPERATIONS);
        metrics.recordTimer(MetricNames.CAPTURE_LATENCY, durationNanos);

        // Specific String capture metrics
        metrics.incrementCounter(MetricNames.CAPTURE_STRING_OPERATIONS);
        metrics.recordTimer(MetricNames.CAPTURE_STRING_LATENCY, durationNanos);

        // Lazy-load named groups only if needed
        Map<String, Integer> namedGroupMap = getNamedGroupsMap();

        return new MatchResult(input, groups, namedGroupMap);
    }

    /**
     * Finds first match and extracts capture groups.
     *
     * <p>This method performs a partial match (searches anywhere in input) and returns
     * a {@link MatchResult} for the first match found. If no match is found, the MatchResult
     * will have {@code matched() == false}.</p>
     *
     * <p><strong>Example - Extract first email from text:</strong></p>
     * <pre>{@code
     * Pattern emailPattern = Pattern.compile("([a-z]+)@([a-z]+\\.[a-z]+)");
     * MatchResult result = emailPattern.find("Contact us at support@example.com or admin@test.org");
     *
     * if (result.matched()) {
     *     String email = result.group();       // "support@example.com" (first match)
     *     String user = result.group(1);       // "support"
     *     String domain = result.group(2);     // "example.com"
     * }
     * }</pre>
     *
     * @param input the string to search
     * @return MatchResult for first match found, or a failed match if no match
     * @throws NullPointerException if input is null
     * @throws IllegalStateException if pattern is closed
     * @see #match(String) for full matching with groups
     * @see #findAll(String) for finding all matches
     * @since 1.2.0
     */
    public MatchResult find(String input) {
        checkNotClosed();
        Objects.requireNonNull(input, "input cannot be null");

        long startNanos = System.nanoTime();

        // RE2 extractGroups does UNANCHORED match, so it finds first occurrence
        String[] groups = RE2NativeJNI.extractGroups(nativeHandle, input);

        long durationNanos = System.nanoTime() - startNanos;

        // Track metrics - GLOBAL (ALL) + SPECIFIC (String)
        RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();

        // Global capture metrics (ALL capture operations)
        metrics.incrementCounter(MetricNames.CAPTURE_OPERATIONS);
        metrics.recordTimer(MetricNames.CAPTURE_LATENCY, durationNanos);

        // Specific String capture metrics
        metrics.incrementCounter(MetricNames.CAPTURE_STRING_OPERATIONS);
        metrics.recordTimer(MetricNames.CAPTURE_STRING_LATENCY, durationNanos);

        if (groups == null) {
            return new MatchResult(input);
        }

        Map<String, Integer> namedGroupMap = getNamedGroupsMap();
        return new MatchResult(input, groups, namedGroupMap);
    }

    /**
     * Finds all non-overlapping matches and extracts capture groups from each.
     *
     * <p>This method finds all matches in the input and returns a list of {@link MatchResult}
     * objects, one for each match. Each MatchResult contains the captured groups for that match.</p>
     *
     * <p><strong>Example - Extract all phone numbers:</strong></p>
     * <pre>{@code
     * Pattern pattern = Pattern.compile("(\\d{3})-(\\d{4})");
     * List<MatchResult> matches = pattern.findAll("Call 555-1234 or 555-5678 for help");
     *
     * for (MatchResult match : matches) {
     *     String phone = match.group();       // "555-1234", "555-5678"
     *     String prefix = match.group(1);     // "555", "555"
     *     String number = match.group(2);     // "1234", "5678"
     * }
     * // matches.size() == 2
     * }</pre>
     *
     * <p><strong>Example - Parse structured log lines:</strong></p>
     * <pre>{@code
     * Pattern pattern = Pattern.compile("\\[(\\d+)\\] (\\w+): (.+)");
     * List<MatchResult> matches = pattern.findAll(logText);
     *
     * for (MatchResult match : matches) {
     *     String timestamp = match.group(1);
     *     String level = match.group(2);
     *     String message = match.group(3);
     *     // Process log entry
     * }
     * }</pre>
     *
     * @param input the string to search
     * @return list of MatchResult objects (one per match), or empty list if no matches
     * @throws NullPointerException if input is null
     * @throws IllegalStateException if pattern is closed
     * @see #match(String) for single full match
     * @see #find(String) for first match only
     * @since 1.2.0
     */
    public java.util.List<MatchResult> findAll(String input) {
        checkNotClosed();
        Objects.requireNonNull(input, "input cannot be null");

        long startNanos = System.nanoTime();

        String[][] allMatches = RE2NativeJNI.findAllMatches(nativeHandle, input);

        long durationNanos = System.nanoTime() - startNanos;
        int matchCount = (allMatches != null) ? allMatches.length : 0;

        // Track metrics - GLOBAL (ALL) + SPECIFIC (String)
        RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();

        // Global capture metrics (ALL capture operations)
        metrics.incrementCounter(MetricNames.CAPTURE_OPERATIONS);
        metrics.recordTimer(MetricNames.CAPTURE_LATENCY, durationNanos);

        // Specific String capture metrics
        metrics.incrementCounter(MetricNames.CAPTURE_STRING_OPERATIONS);
        metrics.recordTimer(MetricNames.CAPTURE_STRING_LATENCY, durationNanos);

        // Track number of matches found
        if (matchCount > 0) {
            metrics.incrementCounter(MetricNames.CAPTURE_FINDALL_MATCHES, matchCount);
        }

        if (allMatches == null || allMatches.length == 0) {
            return java.util.Collections.emptyList();
        }

        // Lazy-load named groups (shared by all MatchResults)
        Map<String, Integer> namedGroupMap = getNamedGroupsMap();

        java.util.List<MatchResult> results = new java.util.ArrayList<>(allMatches.length);
        for (String[] groups : allMatches) {
            results.add(new MatchResult(input, groups, namedGroupMap));
        }

        return results;
    }

    // ========== Bulk Capture Operations ==========

    /**
     * Full match multiple inputs with capture groups (bulk operation).
     *
     * <p>Processes all inputs in a single operation, extracting capture groups from each.</p>
     *
     * <p><strong>Example - Extract email components from multiple inputs:</strong></p>
     * <pre>{@code
     * Pattern emailPattern = Pattern.compile("([a-z]+)@([a-z]+\\.[a-z]+)");
     * String[] emails = {"user@example.com", "admin@test.org", "invalid"};
     *
     * MatchResult[] results = emailPattern.matchAllWithGroups(emails);
     * // results[0].matched() = true, group(1) = "user", group(2) = "example.com"
     * // results[1].matched() = true, group(1) = "admin", group(2) = "test.org"
     * // results[2].matched() = false
     * }</pre>
     *
     * @param inputs array of strings to match
     * @return array of MatchResults (parallel to inputs, remember to close each)
     * @throws NullPointerException if inputs is null
     * @throws IllegalStateException if pattern is closed
     * @since 1.2.0
     */
    public MatchResult[] matchAllWithGroups(String[] inputs) {
        checkNotClosed();
        Objects.requireNonNull(inputs, "inputs cannot be null");

        if (inputs.length == 0) {
            return new MatchResult[0];
        }

        long startNanos = System.nanoTime();

        // Call extractGroups for each input individually
        // Note: extractGroupsBulk returns String[][] with all inputs concatenated,
        // so we process individually for now (can optimize later with proper bulk native method)
        Map<String, Integer> namedGroupMap = getNamedGroupsMap();
        MatchResult[] results = new MatchResult[inputs.length];

        for (int i = 0; i < inputs.length; i++) {
            String[] groups = RE2NativeJNI.extractGroups(nativeHandle, inputs[i]);
            if (groups != null && groups.length > 0) {
                results[i] = new MatchResult(inputs[i], groups, namedGroupMap);
            } else {
                results[i] = new MatchResult(inputs[i]);
            }
        }

        long durationNanos = System.nanoTime() - startNanos;
        long perItemNanos = durationNanos / inputs.length;

        // Track metrics - GLOBAL (ALL) + SPECIFIC (Bulk)
        RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();

        // Global capture metrics (per-item for comparability)
        metrics.incrementCounter(MetricNames.CAPTURE_OPERATIONS, inputs.length);
        metrics.recordTimer(MetricNames.CAPTURE_LATENCY, perItemNanos);

        // Specific bulk capture metrics
        metrics.incrementCounter(MetricNames.CAPTURE_BULK_OPERATIONS);
        metrics.incrementCounter(MetricNames.CAPTURE_BULK_ITEMS, inputs.length);
        metrics.recordTimer(MetricNames.CAPTURE_BULK_LATENCY, perItemNanos);

        return results;
    }

    /**
     * Full match multiple inputs with capture groups (bulk operation, collection variant).
     *
     * @param inputs collection of strings to match
     * @return array of MatchResults (parallel to inputs, remember to close each)
     * @throws NullPointerException if inputs is null
     * @throws IllegalStateException if pattern is closed
     * @since 1.2.0
     */
    public MatchResult[] matchAllWithGroups(java.util.Collection<String> inputs) {
        checkNotClosed();
        Objects.requireNonNull(inputs, "inputs cannot be null");

        String[] array = inputs.toArray(new String[0]);
        return matchAllWithGroups(array);
    }

    /**
     * Matches input and extracts capture groups (zero-copy).
     *
     * <p>Zero-copy variant using raw memory address.</p>
     *
     * @param address native memory address of UTF-8 encoded text
     * @param length number of bytes to read
     * @return MatchResult with captured groups, or failed match if no match
     * @throws IllegalArgumentException if address is 0 or length is negative
     * @throws IllegalStateException if pattern is closed
     * @since 1.2.0
     */
    public MatchResult match(long address, int length) {
        checkNotClosed();
        if (address == 0) {
            throw new IllegalArgumentException("Address must not be 0");
        }
        if (length < 0) {
            throw new IllegalArgumentException("Length must not be negative: " + length);
        }

        long startNanos = System.nanoTime();

        String[] groups = RE2NativeJNI.extractGroupsDirect(nativeHandle, address, length);

        long durationNanos = System.nanoTime() - startNanos;

        // Track metrics - GLOBAL (ALL) + SPECIFIC (Zero-Copy)
        RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();

        // Global capture metrics
        metrics.incrementCounter(MetricNames.CAPTURE_OPERATIONS);
        metrics.recordTimer(MetricNames.CAPTURE_LATENCY, durationNanos);

        // Specific zero-copy capture metrics
        metrics.incrementCounter(MetricNames.CAPTURE_ZERO_COPY_OPERATIONS);
        metrics.recordTimer(MetricNames.CAPTURE_ZERO_COPY_LATENCY, durationNanos);

        if (groups == null) {
            // Need input as String for MatchResult - this is a limitation
            // User must pass String for failed matches
            return new MatchResult("");  // Empty input for failed zero-copy match
        }

        // For zero-copy, we don't have the original String, so MatchResult.input() will be group[0]
        Map<String, Integer> namedGroupMap = getNamedGroupsMap();
        return new MatchResult(groups[0], groups, namedGroupMap);
    }

    /**
     * Matches ByteBuffer content and extracts capture groups (zero-copy).
     *
     * <p>Automatically routes to zero-copy (DirectByteBuffer) or String (heap).</p>
     *
     * @param buffer ByteBuffer containing UTF-8 text
     * @return MatchResult with captured groups
     * @throws NullPointerException if buffer is null
     * @throws IllegalStateException if pattern is closed
     * @since 1.2.0
     */
    public MatchResult match(ByteBuffer buffer) {
        checkNotClosed();
        Objects.requireNonNull(buffer, "buffer cannot be null");

        if (buffer.isDirect()) {
            long address = ((DirectBuffer) buffer).address() + buffer.position();
            int length = buffer.remaining();
            return match(address, length);
        } else {
            // Heap - convert to String and use String variant
            byte[] bytes = new byte[buffer.remaining()];
            buffer.duplicate().get(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return match(text);
        }
    }


    /**
     * Helper: Get named groups map for this pattern (lazy-loaded and cached).
     */
    private Map<String, Integer> getNamedGroupsMap() {
        String[] namedGroupsArray = RE2NativeJNI.getNamedGroups(nativeHandle);

        if (namedGroupsArray == null || namedGroupsArray.length == 0) {
            return Collections.emptyMap();
        }

        // Parse flattened array: [name1, index1_str, name2, index2_str, ...]
        Map<String, Integer> map = new java.util.HashMap<>();
        for (int i = 0; i < namedGroupsArray.length; i += 2) {
            String name = namedGroupsArray[i];
            int index = Integer.parseInt(namedGroupsArray[i + 1]);
            map.put(name, index);
        }

        return map;
    }

    // ========== Capture Group Zero-Copy Operations ==========

    /**
     * Matches and extracts capture groups using zero-copy (address variant).
     *
     * @param address native memory address of UTF-8 text
     * @param length number of bytes
     * @return MatchResult with captured groups
     * @throws IllegalArgumentException if address is 0 or length is negative
     * @throws IllegalStateException if pattern is closed
     * @see #match(String) String variant
     * @since 1.2.0
     */
    public MatchResult matchWithGroups(long address, int length) {
        checkNotClosed();
        if (address == 0) {
            throw new IllegalArgumentException("Address must not be 0");
        }
        if (length < 0) {
            throw new IllegalArgumentException("Length must not be negative: " + length);
        }

        long startNanos = System.nanoTime();
        String[] groups = RE2NativeJNI.extractGroupsDirect(nativeHandle, address, length);
        long durationNanos = System.nanoTime() - startNanos;

        // Track metrics - GLOBAL (ALL) + SPECIFIC (Zero-Copy)
        RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();

        metrics.incrementCounter(MetricNames.CAPTURE_OPERATIONS);
        metrics.recordTimer(MetricNames.CAPTURE_LATENCY, durationNanos);
        metrics.incrementCounter(MetricNames.CAPTURE_ZERO_COPY_OPERATIONS);
        metrics.recordTimer(MetricNames.CAPTURE_ZERO_COPY_LATENCY, durationNanos);

        if (groups == null) {
            return new MatchResult("");
        }

        Map<String, Integer> namedGroupMap = getNamedGroupsMap();
        return new MatchResult(groups[0], groups, namedGroupMap);
    }

    /**
     * Matches and extracts capture groups (ByteBuffer zero-copy).
     *
     * @param buffer ByteBuffer
     * @return MatchResult with captured groups
     * @throws NullPointerException if buffer is null
     * @throws IllegalStateException if pattern is closed
     * @since 1.2.0
     */
    public MatchResult matchWithGroups(ByteBuffer buffer) {
        checkNotClosed();
        Objects.requireNonNull(buffer, "buffer cannot be null");

        if (buffer.isDirect()) {
            long address = ((DirectBuffer) buffer).address() + buffer.position();
            int length = buffer.remaining();
            return matchWithGroups(address, length);
        } else {
            byte[] bytes = new byte[buffer.remaining()];
            buffer.duplicate().get(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return match(text);
        }
    }

    /**
     * Finds and extracts capture groups using zero-copy (address variant).
     *
     * @param address native memory address
     * @param length number of bytes
     * @return MatchResult for first match
     * @throws IllegalArgumentException if address is 0 or length is negative
     * @throws IllegalStateException if pattern is closed
     * @since 1.2.0
     */
    public MatchResult findWithGroups(long address, int length) {
        checkNotClosed();
        if (address == 0) {
            throw new IllegalArgumentException("Address must not be 0");
        }
        if (length < 0) {
            throw new IllegalArgumentException("Length must not be negative: " + length);
        }

        long startNanos = System.nanoTime();
        String[] groups = RE2NativeJNI.extractGroupsDirect(nativeHandle, address, length);
        long durationNanos = System.nanoTime() - startNanos;

        RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();

        metrics.incrementCounter(MetricNames.CAPTURE_OPERATIONS);
        metrics.recordTimer(MetricNames.CAPTURE_LATENCY, durationNanos);
        metrics.incrementCounter(MetricNames.CAPTURE_ZERO_COPY_OPERATIONS);
        metrics.recordTimer(MetricNames.CAPTURE_ZERO_COPY_LATENCY, durationNanos);

        if (groups == null) {
            return new MatchResult("");
        }

        Map<String, Integer> namedGroupMap = getNamedGroupsMap();
        return new MatchResult(groups[0], groups, namedGroupMap);
    }

    /**
     * Finds and extracts capture groups (ByteBuffer zero-copy).
     *
     * @param buffer ByteBuffer
     * @return MatchResult for first match
     * @throws NullPointerException if buffer is null
     * @throws IllegalStateException if pattern is closed
     * @since 1.2.0
     */
    public MatchResult findWithGroups(ByteBuffer buffer) {
        checkNotClosed();
        Objects.requireNonNull(buffer, "buffer cannot be null");

        if (buffer.isDirect()) {
            long address = ((DirectBuffer) buffer).address() + buffer.position();
            int length = buffer.remaining();
            return findWithGroups(address, length);
        } else {
            byte[] bytes = new byte[buffer.remaining()];
            buffer.duplicate().get(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return find(text);
        }
    }

    /**
     * Finds all matches and extracts capture groups using zero-copy (address variant).
     *
     * @param address native memory address
     * @param length number of bytes
     * @return list of MatchResult objects
     * @throws IllegalArgumentException if address is 0 or length is negative
     * @throws IllegalStateException if pattern is closed
     * @since 1.2.0
     */
    public java.util.List<MatchResult> findAllWithGroups(long address, int length) {
        checkNotClosed();
        if (address == 0) {
            throw new IllegalArgumentException("Address must not be 0");
        }
        if (length < 0) {
            throw new IllegalArgumentException("Length must not be negative: " + length);
        }

        long startNanos = System.nanoTime();
        String[][] allMatches = RE2NativeJNI.findAllMatchesDirect(nativeHandle, address, length);
        long durationNanos = System.nanoTime() - startNanos;

        int matchCount = (allMatches != null) ? allMatches.length : 0;

        RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();

        metrics.incrementCounter(MetricNames.CAPTURE_OPERATIONS);
        metrics.recordTimer(MetricNames.CAPTURE_LATENCY, durationNanos);
        metrics.incrementCounter(MetricNames.CAPTURE_ZERO_COPY_OPERATIONS);
        metrics.recordTimer(MetricNames.CAPTURE_ZERO_COPY_LATENCY, durationNanos);

        if (matchCount > 0) {
            metrics.incrementCounter(MetricNames.CAPTURE_FINDALL_MATCHES, matchCount);
        }

        if (allMatches == null || allMatches.length == 0) {
            return java.util.Collections.emptyList();
        }

        Map<String, Integer> namedGroupMap = getNamedGroupsMap();

        java.util.List<MatchResult> results = new java.util.ArrayList<>(allMatches.length);
        for (String[] groups : allMatches) {
            results.add(new MatchResult(groups[0], groups, namedGroupMap));
        }

        return results;
    }

    /**
     * Finds all matches and extracts capture groups (ByteBuffer zero-copy).
     *
     * @param buffer ByteBuffer
     * @return list of MatchResult objects
     * @throws NullPointerException if buffer is null
     * @throws IllegalStateException if pattern is closed
     * @since 1.2.0
     */
    public java.util.List<MatchResult> findAllWithGroups(ByteBuffer buffer) {
        checkNotClosed();
        Objects.requireNonNull(buffer, "buffer cannot be null");

        if (buffer.isDirect()) {
            long address = ((DirectBuffer) buffer).address() + buffer.position();
            int length = buffer.remaining();
            return findAllWithGroups(address, length);
        } else {
            byte[] bytes = new byte[buffer.remaining()];
            buffer.duplicate().get(bytes);
            String text = new String(bytes, StandardCharsets.UTF_8);
            return findAll(text);
        }
    }

    // ========== Replace Operations ==========

    /**
     * Replaces the first match of this pattern in the input with the replacement string.
     *
     * <p>If the pattern matches, the first occurrence is replaced. If no match is found,
     * the original input is returned unchanged.</p>
     *
     * <p><strong>Backreferences:</strong> RE2 supports backreferences using {@code \\1}, {@code \\2}, etc.
     * (note the double backslash for Java string escaping). Unlike java.util.regex which uses
     * {@code $1}, {@code $2}, RE2 uses backslash notation.</p>
     *
     * <p><strong>Example - Simple replacement:</strong></p>
     * <pre>{@code
     * Pattern pattern = Pattern.compile("\\d+");
     * String result = pattern.replaceFirst("Item 123 costs $456", "XXX");
     * // result = "Item XXX costs $456"
     * }</pre>
     *
     * <p><strong>Example - Backreferences:</strong></p>
     * <pre>{@code
     * Pattern pattern = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
     * String result = pattern.replaceFirst("Date: 2025-11-24", "\\2/\\3/\\1");
     * // result = "Date: 11/24/2025" (reordered date components)
     * }</pre>
     *
     * @param input the input string
     * @param replacement the replacement string (supports {@code \\1}, {@code \\2}, etc. backreferences)
     * @return the input with the first match replaced, or original input if no match
     * @throws NullPointerException if input or replacement is null
     * @throws IllegalStateException if pattern is closed
     * @see #replaceAll(String, String) to replace all matches
     * @since 1.2.0
     */
    public String replaceFirst(String input, String replacement) {
        checkNotClosed();
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(replacement, "replacement cannot be null");

        long startNanos = System.nanoTime();

        String result = RE2NativeJNI.replaceFirst(nativeHandle, input, replacement);

        long durationNanos = System.nanoTime() - startNanos;

        // Track metrics - GLOBAL (ALL) + SPECIFIC (String)
        RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();

        // Global replace metrics (ALL replace operations)
        metrics.incrementCounter(MetricNames.REPLACE_OPERATIONS);
        metrics.recordTimer(MetricNames.REPLACE_LATENCY, durationNanos);

        // Specific String replace metrics
        metrics.incrementCounter(MetricNames.REPLACE_STRING_OPERATIONS);
        metrics.recordTimer(MetricNames.REPLACE_STRING_LATENCY, durationNanos);

        return result != null ? result : input;
    }

    /**
     * Replaces all matches of this pattern in the input with the replacement string.
     *
     * <p>All non-overlapping matches are replaced. If no matches are found, the original
     * input is returned unchanged.</p>
     *
     * <p><strong>Backreferences:</strong> Use {@code \\1}, {@code \\2}, etc. for captured groups.</p>
     *
     * <p><strong>Example - Replace all digits:</strong></p>
     * <pre>{@code
     * Pattern pattern = Pattern.compile("\\d+");
     * String result = pattern.replaceAll("Item 123 costs $456", "XXX");
     * // result = "Item XXX costs $XXX"
     * }</pre>
     *
     * <p><strong>Example - Redact emails:</strong></p>
     * <pre>{@code
     * Pattern emailPattern = Pattern.compile("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
     * String result = emailPattern.replaceAll("Contact user@example.com or admin@test.org", "[REDACTED]");
     * // result = "Contact [REDACTED] or [REDACTED]"
     * }</pre>
     *
     * <p><strong>Example - Backreferences for formatting:</strong></p>
     * <pre>{@code
     * Pattern pattern = Pattern.compile("(\\d{3})-(\\d{4})");
     * String result = pattern.replaceAll("Call 555-1234 or 555-5678", "(\\1) \\2");
     * // result = "Call (555) 1234 or (555) 5678"
     * }</pre>
     *
     * @param input the input string
     * @param replacement the replacement string (supports {@code \\1}, {@code \\2}, etc. backreferences)
     * @return the input with all matches replaced, or original input if no matches
     * @throws NullPointerException if input or replacement is null
     * @throws IllegalStateException if pattern is closed
     * @see #replaceFirst(String, String) to replace only the first match
     * @since 1.2.0
     */
    public String replaceAll(String input, String replacement) {
        checkNotClosed();
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(replacement, "replacement cannot be null");

        long startNanos = System.nanoTime();

        String result = RE2NativeJNI.replaceAll(nativeHandle, input, replacement);

        long durationNanos = System.nanoTime() - startNanos;

        // Track metrics - GLOBAL (ALL) + SPECIFIC (String)
        RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();

        // Global replace metrics (ALL replace operations)
        metrics.incrementCounter(MetricNames.REPLACE_OPERATIONS);
        metrics.recordTimer(MetricNames.REPLACE_LATENCY, durationNanos);

        // Specific String replace metrics
        metrics.incrementCounter(MetricNames.REPLACE_STRING_OPERATIONS);
        metrics.recordTimer(MetricNames.REPLACE_STRING_LATENCY, durationNanos);

        return result != null ? result : input;
    }

    /**
     * Replaces all matches in multiple strings (bulk operation).
     *
     * <p>Processes all inputs in a single JNI call for better performance.</p>
     *
     * <p><strong>Example - Batch redaction:</strong></p>
     * <pre>{@code
     * Pattern ssnPattern = Pattern.compile("\\d{3}-\\d{2}-\\d{4}");
     * String[] logs = {
     *     "User 123-45-6789 logged in",
     *     "No PII here",
     *     "SSN: 987-65-4321"
     * };
     *
     * String[] redacted = ssnPattern.replaceAll(logs, "[REDACTED]");
     * // redacted = ["User [REDACTED] logged in", "No PII here", "SSN: [REDACTED]"]
     * }</pre>
     *
     * @param inputs array of strings to process
     * @param replacement the replacement string (supports backreferences)
     * @return array of strings with matches replaced (parallel to inputs)
     * @throws NullPointerException if inputs or replacement is null
     * @throws IllegalStateException if pattern is closed
     * @see #replaceAll(String, String) single-string variant
     * @since 1.2.0
     */
    public String[] replaceAll(String[] inputs, String replacement) {
        checkNotClosed();
        Objects.requireNonNull(inputs, "inputs cannot be null");
        Objects.requireNonNull(replacement, "replacement cannot be null");

        if (inputs.length == 0) {
            return new String[0];
        }

        long startNanos = System.nanoTime();

        String[] results = RE2NativeJNI.replaceAllBulk(nativeHandle, inputs, replacement);

        long durationNanos = System.nanoTime() - startNanos;
        long perItemNanos = durationNanos / inputs.length;

        // Track metrics - GLOBAL (ALL) + SPECIFIC (String Bulk)
        RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();

        // Global replace metrics (ALL replace operations) - use per-item for comparability
        metrics.incrementCounter(MetricNames.REPLACE_OPERATIONS, inputs.length);
        metrics.recordTimer(MetricNames.REPLACE_LATENCY, perItemNanos);

        // Specific String bulk replace metrics
        metrics.incrementCounter(MetricNames.REPLACE_BULK_OPERATIONS);
        metrics.incrementCounter(MetricNames.REPLACE_BULK_ITEMS, inputs.length);
        metrics.recordTimer(MetricNames.REPLACE_BULK_LATENCY, perItemNanos);

        return results != null ? results : inputs;
    }

    /**
     * Replaces all matches in a collection (bulk operation).
     *
     * <p>Processes all inputs in a single JNI call for better performance.</p>
     *
     * @param inputs collection of strings to process
     * @param replacement the replacement string (supports backreferences)
     * @return list of strings with matches replaced (same order as inputs)
     * @throws NullPointerException if inputs or replacement is null
     * @throws IllegalStateException if pattern is closed
     * @see #replaceAll(String, String) single-string variant
     * @since 1.2.0
     */
    public java.util.List<String> replaceAll(java.util.Collection<String> inputs, String replacement) {
        checkNotClosed();
        Objects.requireNonNull(inputs, "inputs cannot be null");
        Objects.requireNonNull(replacement, "replacement cannot be null");

        if (inputs.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        String[] array = inputs.toArray(new String[0]);
        String[] results = replaceAll(array, replacement);

        return java.util.Arrays.asList(results);
    }

    // ========== Phase 3: Zero-Copy Replace Operations ==========

    /**
     * Replaces first match using zero-copy memory access (off-heap memory).
     *
     * <p><strong>Zero-copy operation:</strong> Accesses off-heap memory directly without copying.
     * Caller must ensure memory remains valid during this call.</p>
     *
     * @param address native memory address (from DirectByteBuffer or native allocator)
     * @param length number of bytes to process
     * @param replacement the replacement string (supports backreferences)
     * @return string with first match replaced
     * @throws IllegalStateException if pattern is closed
     * @throws NullPointerException if replacement is null
     * @since 1.2.0
     */
    public String replaceFirst(long address, int length, String replacement) {
        checkNotClosed();
        Objects.requireNonNull(replacement, "replacement cannot be null");

        long startNanos = System.nanoTime();

        String result = RE2NativeJNI.replaceFirstDirect(nativeHandle, address, length, replacement);

        long durationNanos = System.nanoTime() - startNanos;

        // Track metrics - GLOBAL (ALL) + SPECIFIC (Zero-Copy)
        RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();

        // Global replace metrics
        metrics.incrementCounter(MetricNames.REPLACE_OPERATIONS);
        metrics.recordTimer(MetricNames.REPLACE_LATENCY, durationNanos);

        // Specific zero-copy replace metrics
        metrics.incrementCounter(MetricNames.REPLACE_ZERO_COPY_OPERATIONS);
        metrics.recordTimer(MetricNames.REPLACE_ZERO_COPY_LATENCY, durationNanos);

        return result;
    }

    /**
     * Replaces first match using ByteBuffer (zero-copy if direct, converted if heap).
     *
     * @param input ByteBuffer containing UTF-8 encoded text
     * @param replacement the replacement string (supports backreferences)
     * @return string with first match replaced
     * @throws IllegalStateException if pattern is closed
     * @throws NullPointerException if input or replacement is null
     * @since 1.2.0
     */
    public String replaceFirst(java.nio.ByteBuffer input, String replacement) {
        checkNotClosed();
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(replacement, "replacement cannot be null");

        if (input.isDirect()) {
            // Zero-copy path
            long address = ((DirectBuffer) input).address() + input.position();
            int length = input.remaining();
            return replaceFirst(address, length, replacement);
        } else {
            // Heap buffer - convert to String
            byte[] bytes = new byte[input.remaining()];
            input.duplicate().get(bytes);
            String str = new String(bytes, StandardCharsets.UTF_8);
            return replaceFirst(str, replacement);
        }
    }

    /**
     * Replaces all matches using zero-copy memory access (off-heap memory).
     *
     * @param address native memory address (from DirectByteBuffer or native allocator)
     * @param length number of bytes to process
     * @param replacement the replacement string (supports backreferences)
     * @return string with all matches replaced
     * @throws IllegalStateException if pattern is closed
     * @throws NullPointerException if replacement is null
     * @since 1.2.0
     */
    public String replaceAll(long address, int length, String replacement) {
        checkNotClosed();
        Objects.requireNonNull(replacement, "replacement cannot be null");

        long startNanos = System.nanoTime();

        String result = RE2NativeJNI.replaceAllDirect(nativeHandle, address, length, replacement);

        long durationNanos = System.nanoTime() - startNanos;

        // Track metrics - GLOBAL (ALL) + SPECIFIC (Zero-Copy)
        RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();

        // Global replace metrics
        metrics.incrementCounter(MetricNames.REPLACE_OPERATIONS);
        metrics.recordTimer(MetricNames.REPLACE_LATENCY, durationNanos);

        // Specific zero-copy replace metrics
        metrics.incrementCounter(MetricNames.REPLACE_ZERO_COPY_OPERATIONS);
        metrics.recordTimer(MetricNames.REPLACE_ZERO_COPY_LATENCY, durationNanos);

        return result;
    }

    /**
     * Replaces all matches using ByteBuffer (zero-copy if direct, converted if heap).
     *
     * @param input ByteBuffer containing UTF-8 encoded text
     * @param replacement the replacement string (supports backreferences)
     * @return string with all matches replaced
     * @throws IllegalStateException if pattern is closed
     * @throws NullPointerException if input or replacement is null
     * @since 1.2.0
     */
    public String replaceAll(java.nio.ByteBuffer input, String replacement) {
        checkNotClosed();
        Objects.requireNonNull(input, "input cannot be null");
        Objects.requireNonNull(replacement, "replacement cannot be null");

        if (input.isDirect()) {
            // Zero-copy path
            long address = ((DirectBuffer) input).address() + input.position();
            int length = input.remaining();
            return replaceAll(address, length, replacement);
        } else {
            // Heap buffer - convert to String
            byte[] bytes = new byte[input.remaining()];
            input.duplicate().get(bytes);
            String str = new String(bytes, StandardCharsets.UTF_8);
            return replaceAll(str, replacement);
        }
    }

    /**
     * Replaces all matches in multiple off-heap buffers (bulk zero-copy operation).
     *
     * @param addresses native memory addresses (from DirectByteBuffer or native allocator)
     * @param lengths number of bytes for each address
     * @param replacement the replacement string (supports backreferences)
     * @return array of strings with all matches replaced (parallel to inputs)
     * @throws IllegalStateException if pattern is closed
     * @throws NullPointerException if addresses, lengths, or replacement is null
     * @throws IllegalArgumentException if addresses and lengths have different lengths
     * @since 1.2.0
     */
    public String[] replaceAll(long[] addresses, int[] lengths, String replacement) {
        checkNotClosed();
        Objects.requireNonNull(addresses, "addresses cannot be null");
        Objects.requireNonNull(lengths, "lengths cannot be null");
        Objects.requireNonNull(replacement, "replacement cannot be null");

        if (addresses.length != lengths.length) {
            throw new IllegalArgumentException("addresses and lengths must have the same length");
        }

        if (addresses.length == 0) {
            return new String[0];
        }

        long startNanos = System.nanoTime();

        String[] results = RE2NativeJNI.replaceAllDirectBulk(nativeHandle, addresses, lengths, replacement);

        long durationNanos = System.nanoTime() - startNanos;
        long perItemNanos = durationNanos / addresses.length;

        // Track metrics - GLOBAL (ALL) + SPECIFIC (Zero-Copy Bulk)
        RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();

        // Global replace metrics (per-item for comparability)
        metrics.incrementCounter(MetricNames.REPLACE_OPERATIONS, addresses.length);
        metrics.recordTimer(MetricNames.REPLACE_LATENCY, perItemNanos);

        // Specific zero-copy bulk replace metrics
        metrics.incrementCounter(MetricNames.REPLACE_BULK_ZERO_COPY_OPERATIONS);
        metrics.incrementCounter(MetricNames.REPLACE_BULK_ZERO_COPY_ITEMS, addresses.length);
        metrics.recordTimer(MetricNames.REPLACE_BULK_ZERO_COPY_LATENCY, perItemNanos);

        return results;
    }

    /**
     * Replaces all matches in multiple ByteBuffers (bulk operation, zero-copy if direct).
     *
     * @param inputs array of ByteBuffers containing UTF-8 encoded text
     * @param replacement the replacement string (supports backreferences)
     * @return array of strings with all matches replaced (parallel to inputs)
     * @throws IllegalStateException if pattern is closed
     * @throws NullPointerException if inputs or replacement is null
     * @since 1.2.0
     */
    public String[] replaceAll(java.nio.ByteBuffer[] inputs, String replacement) {
        checkNotClosed();
        Objects.requireNonNull(inputs, "inputs cannot be null");
        Objects.requireNonNull(replacement, "replacement cannot be null");

        if (inputs.length == 0) {
            return new String[0];
        }

        // Check if all buffers are direct - if so, use zero-copy bulk path
        boolean allDirect = true;
        for (java.nio.ByteBuffer buffer : inputs) {
            if (!buffer.isDirect()) {
                allDirect = false;
                break;
            }
        }

        if (allDirect) {
            // Zero-copy bulk path
            long[] addresses = new long[inputs.length];
            int[] lengths = new int[inputs.length];

            for (int i = 0; i < inputs.length; i++) {
                addresses[i] = ((DirectBuffer) inputs[i]).address() + inputs[i].position();
                lengths[i] = inputs[i].remaining();
            }

            return replaceAll(addresses, lengths, replacement);
        } else {
            // Mixed or heap buffers - process individually
            String[] results = new String[inputs.length];
            for (int i = 0; i < inputs.length; i++) {
                results[i] = replaceAll(inputs[i], replacement);
            }
            return results;
        }
    }

    public String pattern() {
        return patternString;
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    /**
     * Gets the native (off-heap) memory consumed by this compiled pattern.
     *
     * This is the size of the compiled DFA/NFA program in bytes.
     * Useful for monitoring memory pressure from pattern compilation.
     *
     * @return size in bytes
     * @throws IllegalStateException if pattern is closed
     */
    public long getNativeMemoryBytes() {
        checkNotClosed();
        return nativeMemoryBytes;
    }

    /**
     * Gets the DFA fanout for this pattern.
     *
     * <p>Returns an array where index i contains the number of bytes that lead to
     * different DFA states at position i. Useful for analyzing pattern complexity.</p>
     *
     * @return array of fanout values (one per byte position in DFA)
     * @throws IllegalStateException if pattern is closed
     * @since 1.2.0
     */
    public int[] getProgramFanout() {
        checkNotClosed();
        return RE2NativeJNI.programFanout(nativeHandle);
    }

    /**
     * Escapes special regex characters for literal matching.
     *
     * <p>Converts a literal string into a regex pattern that matches that exact string.
     * Special characters like . * + ? ( ) [ ] { } ^ $ | \ are escaped.</p>
     *
     * <p><strong>Example:</strong></p>
     * <pre>{@code
     * String literal = "price: $9.99";
     * String escaped = Pattern.quoteMeta(literal);
     * // escaped = "price: \\$9\\.99"
     *
     * Pattern p = Pattern.compile(escaped);
     * boolean matches = p.matches("price: $9.99");  // true
     * }</pre>
     *
     * @param text literal text to escape
     * @return escaped pattern that matches the literal text exactly
     * @throws NullPointerException if text is null
     * @since 1.2.0
     */
    public static String quoteMeta(String text) {
        return RE2NativeJNI.quoteMeta(text);
    }

    long getNativeHandle() {
        checkNotClosed();
        return nativeHandle;
    }

    /**
     * Increments reference count (called by Matcher constructor).
     *
     * @throws ResourceException if maxMatchersPerPattern exceeded
     */
    void incrementRefCount() {
        int current = refCount.incrementAndGet();

        if (current > maxMatchersPerPattern) {
            refCount.decrementAndGet(); // Roll back
            throw new ResourceException(
                "Maximum matchers per pattern exceeded: " + maxMatchersPerPattern +
                " (current matchers on this pattern: " + current + ")");
        }
    }

    /**
     * Decrements reference count (called by Matcher.close()).
     */
    void decrementRefCount() {
        refCount.decrementAndGet();
    }

    /**
     * Gets current reference count (for testing/monitoring).
     *
     * @return number of active matchers using this pattern
     */
    public int getRefCount() {
        return refCount.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Checks if the native pattern pointer is still valid.
     *
     * Used for defensive validation to detect memory corruption or
     * other issues that could cause the native pointer to become invalid.
     *
     * @return true if pattern is valid, false if closed or native pointer invalid
     */
    public boolean isValid() {
        if (closed.get()) {
            return false;
        }
        try {
            return RE2NativeJNI.patternOk(nativeHandle);
        } catch (Exception e) {
            logger.warn("RE2: Exception while validating pattern", e);
            return false;
        }
    }

    @Override
    public void close() {
        if (fromCache) {
            // This is expected behavior when using try-with-resources with cached patterns
            logger.trace("RE2: Attempted to close cached pattern (ignoring - cache manages lifecycle)");
            return;
        }

        // Attempt 1: Try graceful close
        forceClose(false);

        // If still has active matchers, wait briefly and force release
        if (!closed.get() && refCount.get() > 0) {
            try {
                Thread.sleep(100);  // Give matchers time to close
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            // Attempt 2: Force release regardless (DANGEROUS if matchers still active)
            if (!closed.get()) {
                logger.warn("RE2: Pattern still has {} active matcher(s) after 100ms wait - forcing release anyway", refCount.get());
                forceClose(true);
            }
        }
    }

    /**
     * Force closes the pattern (INTERNAL USE ONLY - called by cache during eviction).
     *
     * <p><strong>DO NOT CALL THIS METHOD.</strong> This is internal API for PatternCache.
     * Use {@link #close()} instead.
     *
     * <p>Attempts graceful close first (waits for matchers). If matchers still active after wait,
     * can force release regardless of reference count.
     *
     * <p>Public for PatternCache access (different package), but not part of public API.
     */
    public void forceClose() {
        forceClose(false);
    }

    /**
     * Force closes the pattern with optional unconditional release.
     *
     * <p><strong>INTERNAL USE ONLY.</strong>
     *
     * @param releaseRegardless if true, releases even if matchers are active (DANGEROUS - can cause crashes)
     */
    public void forceClose(boolean releaseRegardless) {
        // First attempt: graceful close if no active matchers
        if (refCount.get() > 0) {
            if (!releaseRegardless) {
                logger.warn("RE2: Cannot force close pattern - still in use by {} matcher(s)", refCount.get());
                return;
            } else {
                // DANGEROUS: Forcing release despite active matchers
                logger.error("RE2: FORCE releasing pattern despite {} active matcher(s) - " +
                    "this may cause use-after-free crashes if matchers are still being used!", refCount.get());
            }
        }

        if (closed.compareAndSet(false, true)) {
            logger.trace("RE2: Force closing pattern - fromCache: {}, releaseRegardless: {}", fromCache, releaseRegardless);

            // CRITICAL: Always track freed, even if freePattern throws
            try {
                RE2NativeJNI.freePattern(nativeHandle);
            } catch (Exception e) {
                logger.error("RE2: Error freeing pattern native handle", e);
            } finally {
                // Always track freed (all patterns were tracked when allocated)
                cache.getResourceTracker().trackPatternFreed(cache.getConfig().metricsRegistry());
            }
        }
    }

    /**
     * Gets cache statistics (for monitoring).
     */
    public static com.axonops.libre2.cache.CacheStatistics getCacheStatistics() {
        return cache.getStatistics();
    }

    /**
     * Clears the pattern cache (for testing/maintenance).
     */
    public static void clearCache() {
        cache.clear();
    }

    /**
     * Fully resets the cache including statistics (for testing only).
     */
    public static void resetCache() {
        cache.reset();
    }

    /**
     * Reconfigures the cache with new settings (for testing only).
     *
     * This replaces the existing cache with a new one using the provided config.
     * All cached patterns are cleared.
     *
     * @param config the new configuration
     */
    public static void configureCache(RE2Config config) {
        cache.reconfigure(config);
    }

    /**
     * Sets a new global cache (for testing only).
     *
     * WARNING: This replaces the entire global cache. Use with caution.
     * Primarily for tests that need to inject a custom cache with metrics.
     *
     * @param newCache the new cache to use globally
     */
    public static void setGlobalCache(PatternCache newCache) {
        cache = newCache;
    }

    /**
     * Gets the current cache configuration.
     *
     * @return the current RE2Config
     */
    public static RE2Config getCacheConfig() {
        return cache.getConfig();
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("RE2: Pattern is closed");
        }
    }

    // ========== Bulk Matching Operations ==========

    /**
     * Matches multiple inputs in a single JNI call (minimizes overhead).
     *
     * <p>This method processes an entire collection in one native call, significantly reducing
     * JNI crossing overhead compared to calling {@link #matches(String)} in a loop. The performance
     * benefit increases with collection size and pattern complexity.
     *
     * <p><b>Example - Validate multiple emails:</b>
     * <pre>{@code
     * Pattern emailPattern = Pattern.compile("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
     * List<String> emails = List.of("user@example.com", "invalid", "admin@test.org");
     * boolean[] results = emailPattern.matchAll(emails);
     * // results = [true, false, true]
     *
     * // Use results
     * for (int i = 0; i < emails.size(); i++) {
     *     if (results[i]) {
     *         System.out.println("Valid: " + emails.get(i));
     *     }
     * }
     * }</pre>
     *
     * <p><b>Performance Characteristics:</b>
     * <ul>
     *   <li><b>Throughput:</b> ~3-5 million matches/second for simple patterns</li>
     *   <li><b>Overhead:</b> Single JNI call (~50ns) vs N calls (~50ns each)</li>
     *   <li><b>Best for:</b> 100+ strings, or complex patterns where matching cost > JNI cost</li>
     *   <li><b>Benchmark:</b> See {@code BulkMatchingPerformanceTest} for detailed comparisons</li>
     * </ul>
     *
     * <p><b>Supported Collection Types:</b>
     * <ul>
     *   <li>{@link java.util.List} - ArrayList, LinkedList, Vector, etc.</li>
     *   <li>{@link java.util.Set} - HashSet, TreeSet, LinkedHashSet, etc.</li>
     *   <li>{@link java.util.Queue} - LinkedList, ArrayDeque, PriorityQueue, etc.</li>
     *   <li>Any Collection implementation</li>
     * </ul>
     *
     * <p><b>Thread Safety:</b> Pattern is thread-safe, but if the collection is being
     * modified concurrently by other threads, you must synchronize externally.
     *
     * @param inputs collection of strings to match (supports List, Set, Queue, etc.)
     * @return boolean array parallel to inputs (same size and order) indicating matches
     * @throws NullPointerException if inputs is null
     * @see #matchAll(String[]) array variant
     * @see #filter(java.util.Collection) to extract only matching elements
     * @since 1.0.0
     */
    public boolean[] matchAll(java.util.Collection<String> inputs) {
        Objects.requireNonNull(inputs, "inputs cannot be null");
        if (inputs.isEmpty()) {
            return new boolean[0];
        }

        try {
            String[] array = inputs.toArray(new String[0]);
            return matchAll(array);
        } catch (ArrayStoreException e) {
            throw new IllegalArgumentException(
                "Collection contains non-String elements. All elements must be String type. " +
                "If you have Collection<Integer> or other types, convert to strings first: " +
                "collection.stream().map(Object::toString).toList()", e);
        }
    }

    /**
     * Matches multiple inputs in a single JNI call (array variant).
     *
     * <p>Optimized for arrays - no collection conversion overhead.
     *
     * <p><b>Example - Process array of phone numbers:</b>
     * <pre>{@code
     * Pattern phonePattern = Pattern.compile("\\d{3}-\\d{4}");
     * String[] phones = {"123-4567", "invalid", "999-8888"};
     * boolean[] results = phonePattern.matchAll(phones);
     * // results = [true, false, true]
     * }</pre>
     *
     * @param inputs array of strings to match
     * @return boolean array parallel to inputs indicating matches
     * @throws NullPointerException if inputs is null
     * @see #matchAll(java.util.Collection) collection variant
     * @since 1.0.0
     */
    public boolean[] matchAll(String[] inputs) {
        Objects.requireNonNull(inputs, "inputs cannot be null");
        checkNotClosed();

        if (inputs.length == 0) {
            return new boolean[0];
        }

        // Convert String[] to byte[][] for optimized JNI transfer (30-60% faster)
        byte[][] utf8Arrays = new byte[inputs.length][];
        int[] lengths = new int[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            utf8Arrays[i] = inputs[i].getBytes(StandardCharsets.UTF_8);
            lengths[i] = utf8Arrays[i].length;
        }

        long startNanos = System.nanoTime();
        boolean[] results = RE2NativeJNI.fullMatchBulkBytes(nativeHandle, utf8Arrays, lengths);
        long durationNanos = System.nanoTime() - startNanos;

        // Track metrics - GLOBAL (ALL) + SPECIFIC (String Bulk)
        RE2MetricsRegistry metrics = Pattern.getGlobalCache().getConfig().metricsRegistry();
        long perItemNanos = inputs.length > 0 ? durationNanos / inputs.length : 0;

        // Global metrics (ALL matching operations) - use per-item latency for comparability
        metrics.incrementCounter(MetricNames.MATCHING_OPERATIONS, inputs.length);
        metrics.recordTimer(MetricNames.MATCHING_LATENCY, perItemNanos);
        metrics.recordTimer(MetricNames.MATCHING_FULL_MATCH_LATENCY, perItemNanos);

        // Specific String bulk metrics
        metrics.incrementCounter(MetricNames.MATCHING_BULK_OPERATIONS);
        metrics.incrementCounter(MetricNames.MATCHING_BULK_ITEMS, inputs.length);
        metrics.recordTimer(MetricNames.MATCHING_BULK_LATENCY, perItemNanos);

        return results != null ? results : new boolean[inputs.length];
    }

    /**
     * Tests if pattern matches anywhere in multiple strings (partial match bulk).
     *
     * <p>This is the bulk variant of {@link Matcher#find()} - tests if the pattern
     * matches anywhere within each input string (not necessarily the full string).</p>
     *
     * <p>Processes all inputs in a single JNI call for better performance.</p>
     *
     * <p><strong>Example - Find which strings contain pattern:</strong></p>
     * <pre>{@code
     * Pattern emailPattern = Pattern.compile("[a-z]+@[a-z]+\\.[a-z]+");
     * String[] texts = {
     *     "user@example.com",           // contains email
     *     "Contact: admin@test.org",    // contains email
     *     "No email here"                // no email
     * };
     * boolean[] results = emailPattern.findAll(texts);
     * // results = [true, true, false]
     * }</pre>
     *
     * @param inputs array of strings to search
     * @return boolean array (parallel to inputs) indicating if pattern found in each
     * @throws NullPointerException if inputs is null
     * @throws IllegalStateException if pattern is closed
     * @see #matchAll(String[]) for full match bulk variant
     * @see Matcher#find() for single-string partial match
     * @since 1.2.0
     */
    public boolean[] findAll(String[] inputs) {
        Objects.requireNonNull(inputs, "inputs cannot be null");
        checkNotClosed();

        if (inputs.length == 0) {
            return new boolean[0];
        }

        // Convert String[] to byte[][] for optimized JNI transfer (40-60% faster)
        byte[][] utf8Arrays = new byte[inputs.length][];
        int[] lengths = new int[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            utf8Arrays[i] = inputs[i].getBytes(StandardCharsets.UTF_8);
            lengths[i] = utf8Arrays[i].length;
        }

        long startNanos = System.nanoTime();
        boolean[] results = RE2NativeJNI.partialMatchBulkBytes(nativeHandle, utf8Arrays, lengths);
        long durationNanos = System.nanoTime() - startNanos;

        // Track metrics - GLOBAL (ALL) + SPECIFIC (String Bulk)
        RE2MetricsRegistry metrics = Pattern.getGlobalCache().getConfig().metricsRegistry();
        long perItemNanos = inputs.length > 0 ? durationNanos / inputs.length : 0;

        // Global metrics (ALL matching operations)
        metrics.incrementCounter(MetricNames.MATCHING_OPERATIONS, inputs.length);
        metrics.recordTimer(MetricNames.MATCHING_LATENCY, perItemNanos);
        metrics.recordTimer(MetricNames.MATCHING_PARTIAL_MATCH_LATENCY, perItemNanos);

        // Specific String bulk metrics
        metrics.incrementCounter(MetricNames.MATCHING_BULK_OPERATIONS);
        metrics.incrementCounter(MetricNames.MATCHING_BULK_ITEMS, inputs.length);
        metrics.recordTimer(MetricNames.MATCHING_BULK_LATENCY, perItemNanos);

        return results != null ? results : new boolean[inputs.length];
    }

    /**
     * Tests if pattern matches anywhere in multiple strings (partial match bulk, collection variant).
     *
     * <p>Convenience wrapper for {@link #findAll(String[])} accepting any Collection.</p>
     *
     * @param inputs collection of strings to search
     * @return boolean array (parallel to inputs) indicating if pattern found in each
     * @throws NullPointerException if inputs is null
     * @throws IllegalStateException if pattern is closed
     * @since 1.2.0
     */
    public boolean[] findAll(java.util.Collection<String> inputs) {
        Objects.requireNonNull(inputs, "inputs cannot be null");
        if (inputs.isEmpty()) {
            return new boolean[0];
        }

        String[] array = inputs.toArray(new String[0]);
        return findAll(array);
    }

    /**
     * Matches multiple memory regions in a single JNI call (zero-copy bulk).
     *
     * <p>This method accepts arrays of memory addresses and lengths, enabling efficient
     * zero-copy bulk matching with any off-heap memory system.</p>
     *
     * <p><strong>Performance:</strong> 91.5% faster than String bulk API. Combines
     * bulk matching (single JNI call) with zero-copy memory access.</p>
     *
     * <p><strong>Memory Safety:</strong> All memory regions must remain valid
     * for the duration of this call.</p>
     *
     * <p><strong>Usage with DirectByteBuffer array:</strong></p>
     * <pre>{@code
     * import sun.nio.ch.DirectBuffer;
     *
     * Pattern pattern = Pattern.compile("\\d+");
     * ByteBuffer[] buffers = ...; // Multiple DirectByteBuffers
     *
     * long[] addresses = new long[buffers.length];
     * int[] lengths = new int[buffers.length];
     * for (int i = 0; i < buffers.length; i++) {
     *     addresses[i] = ((DirectBuffer) buffers[i]).address();
     *     lengths[i] = buffers[i].remaining();
     * }
     *
     * boolean[] results = pattern.matchAll(addresses, lengths);  // 91.5% faster!
     * }</pre>
     *
     * @param addresses array of native memory addresses
     * @param lengths array of byte lengths (must be same length as addresses)
     * @return boolean array (parallel to inputs) indicating matches
     * @throws NullPointerException if addresses or lengths is null
     * @throws IllegalArgumentException if arrays have different lengths
     * @throws IllegalStateException if pattern is closed
     * @see #matchAll(String[]) String-based bulk variant
     * @since 1.1.0
     */
    public boolean[] matchAll(long[] addresses, int[] lengths) {
        checkNotClosed();
        Objects.requireNonNull(addresses, "addresses cannot be null");
        Objects.requireNonNull(lengths, "lengths cannot be null");

        if (addresses.length != lengths.length) {
            throw new IllegalArgumentException(
                "Address and length arrays must have same size: addresses=" + addresses.length + ", lengths=" + lengths.length);
        }

        if (addresses.length == 0) {
            return new boolean[0];
        }

        long startNanos = System.nanoTime();
        boolean[] results = RE2NativeJNI.fullMatchDirectBulk(nativeHandle, addresses, lengths);
        long durationNanos = System.nanoTime() - startNanos;

        // Track metrics - GLOBAL (ALL) + SPECIFIC (Bulk Zero-Copy)
        RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();
        long perItemNanos = addresses.length > 0 ? durationNanos / addresses.length : 0;

        // Global metrics (ALL matching operations) - use per-item latency for comparability
        metrics.incrementCounter(MetricNames.MATCHING_OPERATIONS, addresses.length);
        metrics.recordTimer(MetricNames.MATCHING_LATENCY, perItemNanos);
        metrics.recordTimer(MetricNames.MATCHING_FULL_MATCH_LATENCY, perItemNanos);

        // Specific bulk zero-copy metrics
        metrics.incrementCounter(MetricNames.MATCHING_BULK_ZERO_COPY_OPERATIONS);
        metrics.incrementCounter(MetricNames.MATCHING_BULK_ITEMS, addresses.length);
        metrics.recordTimer(MetricNames.MATCHING_BULK_ZERO_COPY_LATENCY, perItemNanos);

        return results != null ? results : new boolean[addresses.length];
    }

    /**
     * Partial match on multiple memory regions in a single JNI call (zero-copy bulk).
     *
     * <p>Tests if pattern matches anywhere in each memory region.</p>
     *
     * <p><strong>Performance:</strong> 91.5% faster than String bulk API.</p>
     *
     * @param addresses array of native memory addresses
     * @param lengths array of byte lengths (must be same length as addresses)
     * @return boolean array indicating if pattern found in each input
     * @throws NullPointerException if addresses or lengths is null
     * @throws IllegalArgumentException if arrays have different lengths
     * @throws IllegalStateException if pattern is closed
     * @since 1.1.0
     */
    public boolean[] findAll(long[] addresses, int[] lengths) {
        checkNotClosed();
        Objects.requireNonNull(addresses, "addresses cannot be null");
        Objects.requireNonNull(lengths, "lengths cannot be null");

        if (addresses.length != lengths.length) {
            throw new IllegalArgumentException(
                "Address and length arrays must have same size: addresses=" + addresses.length + ", lengths=" + lengths.length);
        }

        if (addresses.length == 0) {
            return new boolean[0];
        }

        long startNanos = System.nanoTime();
        boolean[] results = RE2NativeJNI.partialMatchDirectBulk(nativeHandle, addresses, lengths);
        long durationNanos = System.nanoTime() - startNanos;

        // Track metrics - GLOBAL (ALL) + SPECIFIC (Bulk Zero-Copy)
        RE2MetricsRegistry metrics = cache.getConfig().metricsRegistry();
        long perItemNanos = addresses.length > 0 ? durationNanos / addresses.length : 0;

        // Global metrics (ALL matching operations) - use per-item latency for comparability
        metrics.incrementCounter(MetricNames.MATCHING_OPERATIONS, addresses.length);
        metrics.recordTimer(MetricNames.MATCHING_LATENCY, perItemNanos);
        metrics.recordTimer(MetricNames.MATCHING_PARTIAL_MATCH_LATENCY, perItemNanos);

        // Specific bulk zero-copy metrics
        metrics.incrementCounter(MetricNames.MATCHING_BULK_ZERO_COPY_OPERATIONS);
        metrics.incrementCounter(MetricNames.MATCHING_BULK_ITEMS, addresses.length);
        metrics.recordTimer(MetricNames.MATCHING_BULK_ZERO_COPY_LATENCY, perItemNanos);

        return results != null ? results : new boolean[addresses.length];
    }

    /**
     * Matches multiple ByteBuffers in a single operation (bulk with auto-routing).
     *
     * <p>Automatically routes each buffer: DirectByteBuffer → zero-copy, heap → String.</p>
     *
     * <p><strong>Example - Bulk process Cassandra cells:</strong></p>
     * <pre>{@code
     * Pattern pattern = Pattern.compile("valid_.*");
     * ByteBuffer[] cells = getCellsFromCassandra();  // Array of DirectByteBuffers
     *
     * boolean[] results = pattern.matchAll(cells);
     * // Each DirectByteBuffer uses zero-copy (46-99% faster)
     * }</pre>
     *
     * @param buffers array of ByteBuffers to match
     * @return boolean array (parallel to inputs) indicating matches
     * @throws NullPointerException if buffers is null
     * @throws IllegalStateException if pattern is closed
     * @since 1.2.0
     */
    public boolean[] matchAll(ByteBuffer[] buffers) {
        checkNotClosed();
        Objects.requireNonNull(buffers, "buffers cannot be null");

        if (buffers.length == 0) {
            return new boolean[0];
        }

        // Check if all are direct - if so, use zero-copy bulk path
        boolean allDirect = true;
        for (ByteBuffer buf : buffers) {
            if (buf != null && !buf.isDirect()) {
                allDirect = false;
                break;
            }
        }

        if (allDirect) {
            // Zero-copy path - extract addresses
            long[] addresses = new long[buffers.length];
            int[] lengths = new int[buffers.length];
            for (int i = 0; i < buffers.length; i++) {
                if (buffers[i] != null) {
                    addresses[i] = ((DirectBuffer) buffers[i]).address() + buffers[i].position();
                    lengths[i] = buffers[i].remaining();
                }
            }
            return matchAll(addresses, lengths);
        } else {
            // Mixed or heap - convert to Strings
            String[] strings = new String[buffers.length];
            for (int i = 0; i < buffers.length; i++) {
                if (buffers[i] != null) {
                    byte[] bytes = new byte[buffers[i].remaining()];
                    buffers[i].duplicate().get(bytes);
                    strings[i] = new String(bytes, StandardCharsets.UTF_8);
                }
            }
            return matchAll(strings);
        }
    }

    /**
     * Tests if pattern matches anywhere in multiple ByteBuffers (partial match bulk).
     *
     * <p>Bulk variant of partial matching with automatic routing.</p>
     *
     * @param buffers array of ByteBuffers to search
     * @return boolean array indicating if pattern found in each
     * @throws NullPointerException if buffers is null
     * @throws IllegalStateException if pattern is closed
     * @since 1.2.0
     */
    public boolean[] findAll(ByteBuffer[] buffers) {
        checkNotClosed();
        Objects.requireNonNull(buffers, "buffers cannot be null");

        if (buffers.length == 0) {
            return new boolean[0];
        }

        // Check if all are direct
        boolean allDirect = true;
        for (ByteBuffer buf : buffers) {
            if (buf != null && !buf.isDirect()) {
                allDirect = false;
                break;
            }
        }

        if (allDirect) {
            // Zero-copy path
            long[] addresses = new long[buffers.length];
            int[] lengths = new int[buffers.length];
            for (int i = 0; i < buffers.length; i++) {
                if (buffers[i] != null) {
                    addresses[i] = ((DirectBuffer) buffers[i]).address() + buffers[i].position();
                    lengths[i] = buffers[i].remaining();
                }
            }
            return findAll(addresses, lengths);
        } else {
            // Mixed or heap - convert to Strings
            String[] strings = new String[buffers.length];
            for (int i = 0; i < buffers.length; i++) {
                if (buffers[i] != null) {
                    byte[] bytes = new byte[buffers[i].remaining()];
                    buffers[i].duplicate().get(bytes);
                    strings[i] = new String(bytes, StandardCharsets.UTF_8);
                }
            }
            return findAll(strings);
        }
    }

    /**
     * Extracts capture groups from content at memory address (zero-copy input).
     *
     * <p>Reads text directly from the memory address and extracts all capture groups.
     * The input is zero-copy, but output creates new Java Strings for the groups.</p>
     *
     * @param address native memory address of UTF-8 encoded text
     * @param length number of bytes to read from the address
     * @return String array where [0] = full match, [1+] = capturing groups, or null if no match
     * @throws IllegalArgumentException if address is 0 or length is negative
     * @throws IllegalStateException if pattern is closed
     * @since 1.1.0
     */
    public String[] extractGroups(long address, int length) {
        checkNotClosed();
        if (address == 0) {
            throw new IllegalArgumentException("Address must not be 0");
        }
        if (length < 0) {
            throw new IllegalArgumentException("Length must not be negative: " + length);
        }

        return RE2NativeJNI.extractGroupsDirect(nativeHandle, address, length);
    }

    /**
     * Finds all non-overlapping matches at memory address (zero-copy input).
     *
     * <p>Reads text directly from the memory address and finds all matches.
     * The input is zero-copy, but output creates new Java Strings.</p>
     *
     * @param address native memory address of UTF-8 encoded text
     * @param length number of bytes to read from the address
     * @return array of match results with capture groups, or null if no matches
     * @throws IllegalArgumentException if address is 0 or length is negative
     * @throws IllegalStateException if pattern is closed
     * @since 1.1.0
     */
    public String[][] findAllMatches(long address, int length) {
        checkNotClosed();
        if (address == 0) {
            throw new IllegalArgumentException("Address must not be 0");
        }
        if (length < 0) {
            throw new IllegalArgumentException("Length must not be negative: " + length);
        }

        return RE2NativeJNI.findAllMatchesDirect(nativeHandle, address, length);
    }

    // ========== ByteBuffer API (Automatic Zero-Copy Routing) ==========

    /**
     * Tests if ByteBuffer content fully matches this pattern.
     *
     * <p>This method intelligently routes to the optimal implementation:</p>
     * <ul>
     *   <li><strong>DirectByteBuffer:</strong> Uses zero-copy via {@link #matches(long, int)} (46-99% faster)</li>
     *   <li><strong>HeapByteBuffer:</strong> Converts to String and uses {@link #matches(String)}</li>
     * </ul>
     *
     * <p><strong>Usage Example:</strong></p>
     * <pre>{@code
     * Pattern pattern = Pattern.compile("\\d+");
     *
     * // DirectByteBuffer - zero-copy, 46-99% faster
     * ByteBuffer directBuffer = ByteBuffer.allocateDirect(1024);
     * directBuffer.put("12345".getBytes(StandardCharsets.UTF_8));
     * directBuffer.flip();
     * boolean r1 = pattern.matches(directBuffer);  // Zero-copy!
     *
     * // HeapByteBuffer - falls back to String API
     * ByteBuffer heapBuffer = ByteBuffer.wrap("67890".getBytes(StandardCharsets.UTF_8));
     * boolean r2 = pattern.matches(heapBuffer);  // Converted to String
     * }</pre>
     *
     * <p><strong>Performance:</strong> When using DirectByteBuffer, provides 46-99% improvement.
     * When using heap ByteBuffer, equivalent to String API (no improvement).</p>
     *
     * <p><strong>Memory Safety:</strong> The buffer's backing memory must remain valid
     * for the duration of this call. Do NOT release direct buffers until method returns.</p>
     *
     * @param buffer ByteBuffer containing UTF-8 encoded text (direct or heap-backed)
     * @return true if entire content matches this pattern, false otherwise
     * @throws NullPointerException if buffer is null
     * @throws IllegalStateException if pattern is closed
     * @see #matches(String) String-based variant
     * @see #matches(long, int) Raw address variant
     * @since 1.1.0
     */
    public boolean matches(ByteBuffer buffer) {
        checkNotClosed();
        Objects.requireNonNull(buffer, "buffer cannot be null");

        if (buffer.isDirect()) {
            // Zero-copy path for DirectByteBuffer
            // DirectBuffer is a public interface - simple cast works
            long address = ((DirectBuffer) buffer).address() + buffer.position();
            int length = buffer.remaining();
            return matches(address, length);
        } else {
            // Heap-backed ByteBuffer - convert to String
            return matchesFromByteBuffer(buffer);
        }
    }

    /**
     * Tests if pattern matches anywhere in ByteBuffer content.
     *
     * <p>Intelligently routes to zero-copy (DirectByteBuffer) or String API (heap buffer).</p>
     *
     * <p><strong>Performance:</strong> 46-99% faster for DirectByteBuffer.</p>
     *
     * @param buffer ByteBuffer containing UTF-8 encoded text
     * @return true if pattern matches anywhere in content, false otherwise
     * @throws NullPointerException if buffer is null
     * @throws IllegalStateException if pattern is closed
     * @since 1.1.0
     */
    public boolean find(ByteBuffer buffer) {
        checkNotClosed();
        Objects.requireNonNull(buffer, "buffer cannot be null");

        if (buffer.isDirect()) {
            // Zero-copy path
            long address = ((DirectBuffer) buffer).address() + buffer.position();
            int length = buffer.remaining();
            return find(address, length);
        } else {
            // Heap-backed - convert to String
            return findFromByteBuffer(buffer);
        }
    }

    /**
     * Extracts capture groups from ByteBuffer content.
     *
     * <p>Intelligently routes to zero-copy (DirectByteBuffer) or String API (heap buffer).</p>
     *
     * @param buffer ByteBuffer containing UTF-8 encoded text
     * @return String array where [0] = full match, [1+] = capturing groups, or null if no match
     * @throws NullPointerException if buffer is null
     * @throws IllegalStateException if pattern is closed
     * @since 1.1.0
     */
    public String[] extractGroups(ByteBuffer buffer) {
        checkNotClosed();
        Objects.requireNonNull(buffer, "buffer cannot be null");

        if (buffer.isDirect()) {
            // Zero-copy path
            long address = ((DirectBuffer) buffer).address() + buffer.position();
            int length = buffer.remaining();
            return extractGroups(address, length);
        } else {
            // Heap-backed
            return extractGroupsFromByteBuffer(buffer);
        }
    }

    /**
     * Finds all non-overlapping matches in ByteBuffer content.
     *
     * <p>Intelligently routes to zero-copy (DirectByteBuffer) or String API (heap buffer).</p>
     *
     * @param buffer ByteBuffer containing UTF-8 encoded text
     * @return array of match results with capture groups, or null if no matches
     * @throws NullPointerException if buffer is null
     * @throws IllegalStateException if pattern is closed
     * @since 1.1.0
     */
    public String[][] findAllMatches(ByteBuffer buffer) {
        checkNotClosed();
        Objects.requireNonNull(buffer, "buffer cannot be null");

        if (buffer.isDirect()) {
            // Zero-copy path
            long address = ((DirectBuffer) buffer).address() + buffer.position();
            int length = buffer.remaining();
            return findAllMatches(address, length);
        } else {
            // Heap-backed
            return findAllMatchesFromByteBuffer(buffer);
        }
    }

    /**
     * Helper: Extract String from ByteBuffer for matches() (heap-backed fallback).
     */
    private boolean matchesFromByteBuffer(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);  // Use duplicate to not modify position
        String text = new String(bytes, StandardCharsets.UTF_8);
        return matches(text);
    }

    /**
     * Helper: Extract String from ByteBuffer for find() (heap-backed fallback).
     */
    private boolean findFromByteBuffer(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        String text = new String(bytes, StandardCharsets.UTF_8);
        try (Matcher m = matcher(text)) {
            return m.find();
        }
    }

    /**
     * Helper: Extract String from ByteBuffer for extractGroups() (heap-backed fallback).
     */
    private String[] extractGroupsFromByteBuffer(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        String text = new String(bytes, StandardCharsets.UTF_8);
        return RE2NativeJNI.extractGroups(nativeHandle, text);
    }

    /**
     * Helper: Extract String from ByteBuffer for findAllMatches() (heap-backed fallback).
     */
    private String[][] findAllMatchesFromByteBuffer(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.remaining()];
        buffer.duplicate().get(bytes);
        String text = new String(bytes, StandardCharsets.UTF_8);
        return RE2NativeJNI.findAllMatches(nativeHandle, text);
    }

    /**
     * Filters collection, returning only matching elements.
     *
     * <p>Creates a new {@link java.util.List} containing only strings that match this pattern.
     * The original collection is not modified. Uses bulk matching internally for performance.
     *
     * <p><b>Example - Extract valid email addresses:</b>
     * <pre>{@code
     * Pattern emailPattern = Pattern.compile("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
     * List<String> inputs = List.of(
     *     "user@example.com",    // matches
     *     "invalid_email",       // doesn't match
     *     "admin@test.org"       // matches
     * );
     * List<String> validEmails = emailPattern.filter(inputs);
     * // validEmails = ["user@example.com", "admin@test.org"]
     * }</pre>
     *
     * <p><b>Collection Types:</b>
     * <ul>
     *   <li><b>Input:</b> Any Collection (List, Set, Queue, etc.)</li>
     *   <li><b>Output:</b> Always returns {@link java.util.ArrayList}</li>
     *   <li><b>Order:</b> Preserves iteration order of input collection</li>
     * </ul>
     *
     * <p><b>Common Use Cases:</b>
     * <ul>
     *   <li>Extracting valid data from mixed datasets</li>
     *   <li>Data cleaning (filter valid records)</li>
     *   <li>Log filtering (extract matching log lines)</li>
     *   <li>Cassandra SAI: Filter partition keys matching regex</li>
     * </ul>
     *
     * <p><b>Performance:</b> Throughput ~3.9 million strings/second (benchmark: 10k strings in 2.6ms)
     *
     * @param inputs collection to filter (List, Set, Queue, or any Collection)
     * @return new List containing only matching elements (preserves input order)
     * @throws NullPointerException if inputs is null
     * @see #filterNot(java.util.Collection) inverse operation
     * @see #retainMatches(java.util.Collection) in-place variant
     * @see #matchAll(java.util.Collection) to get boolean array
     * @since 1.0.0
     */
    public java.util.List<String> filter(java.util.Collection<String> inputs) {
        Objects.requireNonNull(inputs, "inputs cannot be null");

        if (inputs.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        String[] array;
        try {
            array = inputs.toArray(new String[0]);
        } catch (ArrayStoreException e) {
            throw new IllegalArgumentException(
                "Collection contains non-String elements. Use stream().map(Object::toString).toList() to convert.", e);
        }

        boolean[] matches = matchAll(array);

        java.util.List<String> result = new java.util.ArrayList<>();
        for (int i = 0; i < array.length; i++) {
            if (matches[i]) {
                result.add(array[i]);
            }
        }

        return result;
    }

    /**
     * Filters collection, returning only non-matching elements (inverse of {@link #filter}).
     *
     * <p>Creates a new {@link java.util.List} containing only strings that do NOT match this pattern.
     * The original collection is not modified.
     *
     * <p><b>Example - Remove test data, keep production:</b>
     * <pre>{@code
     * Pattern testPattern = Pattern.compile("test_.*");
     * List<String> allKeys = List.of("test_key1", "prod_key1", "test_key2", "prod_key2");
     * List<String> prodKeys = testPattern.filterNot(allKeys);
     * // prodKeys = ["prod_key1", "prod_key2"]
     * }</pre>
     *
     * <p><b>Use Cases:</b>
     * <ul>
     *   <li>Removing test/debug data from production datasets</li>
     *   <li>Blacklist filtering (exclude matching patterns)</li>
     *   <li>Data sanitization (remove sensitive patterns)</li>
     * </ul>
     *
     * @param inputs collection to filter
     * @return new List containing only non-matching elements (preserves input order)
     * @throws NullPointerException if inputs is null
     * @see #filter(java.util.Collection) inverse operation (keep matches)
     * @see #removeMatches(java.util.Collection) in-place variant
     * @since 1.0.0
     */
    public java.util.List<String> filterNot(java.util.Collection<String> inputs) {
        Objects.requireNonNull(inputs, "inputs cannot be null");

        if (inputs.isEmpty()) {
            return new java.util.ArrayList<>();
        }

        String[] array;
        try {
            array = inputs.toArray(new String[0]);
        } catch (ArrayStoreException e) {
            throw new IllegalArgumentException(
                "Collection contains non-String elements. Use stream().map(Object::toString).toList() to convert.", e);
        }

        boolean[] matches = matchAll(array);

        java.util.List<String> result = new java.util.ArrayList<>();
        for (int i = 0; i < array.length; i++) {
            if (!matches[i]) {  // Inverted logic
                result.add(array[i]);
            }
        }

        return result;
    }

    /**
     * Removes non-matching elements from collection (in-place mutation).
     *
     * <p><b>MUTATES THE INPUT:</b> This method modifies the provided collection by removing
     * elements that don't match the pattern. Only matching elements remain after this call.
     *
     * <p><b>Example - Clean invalid data in-place:</b>
     * <pre>{@code
     * Pattern validPattern = Pattern.compile("[a-zA-Z0-9_]+");
     * List<String> usernames = new ArrayList<>(List.of("user1", "invalid@", "admin", "bad#name"));
     *
     * int removed = validPattern.retainMatches(usernames);
     * // removed = 2
     * // usernames now = ["user1", "admin"] (invalid entries removed)
     * }</pre>
     *
     * <p><b>When to Use:</b>
     * <ul>
     *   <li><b>Use this:</b> When you want to modify the collection in-place (memory efficient)</li>
     *   <li><b>Use {@link #filter}:</b> When you need to preserve the original collection</li>
     * </ul>
     *
     * <p><b>Collection Requirements:</b>
     * <ul>
     *   <li>Collection must be mutable (support {@code iterator().remove()})</li>
     *   <li>Works with: ArrayList, LinkedList, HashSet, TreeSet, etc.</li>
     *   <li>Fails with: Collections.unmodifiableList(), List.of(), Set.of(), etc.</li>
     * </ul>
     *
     * @param inputs mutable collection to filter (List, Set, Queue, etc.)
     * @return number of elements removed
     * @throws NullPointerException if inputs is null
     * @throws UnsupportedOperationException if collection is immutable
     * @see #filter(java.util.Collection) non-mutating variant
     * @see #removeMatches(java.util.Collection) inverse (remove matches, keep non-matches)
     * @since 1.0.0
     */
    public int retainMatches(java.util.Collection<String> inputs) {
        Objects.requireNonNull(inputs, "inputs cannot be null");

        if (inputs.isEmpty()) {
            return 0;
        }

        String[] array;
        try {
            array = inputs.toArray(new String[0]);
        } catch (ArrayStoreException e) {
            throw new IllegalArgumentException(
                "Collection contains non-String elements. Use stream().map(Object::toString).toList() to convert.", e);
        }

        boolean[] matches = matchAll(array);

        int removed = 0;
        java.util.Iterator<String> it = inputs.iterator();
        int i = 0;
        while (it.hasNext()) {
            it.next();
            if (!matches[i++]) {
                it.remove();
                removed++;
            }
        }

        return removed;
    }

    /**
     * Removes matching elements from collection (in-place mutation, inverse of {@link #retainMatches}).
     *
     * <p><b>MUTATES THE INPUT:</b> This method modifies the provided collection by removing
     * elements that match the pattern. Only non-matching elements remain after this call.
     *
     * <p><b>Example - Remove sensitive data patterns:</b>
     * <pre>{@code
     * Pattern ssnPattern = Pattern.compile("\\d{3}-\\d{2}-\\d{4}");  // SSN format
     * List<String> logLines = new ArrayList<>(List.of(
     *     "User logged in",
     *     "SSN: 123-45-6789",  // sensitive!
     *     "Processing request",
     *     "SSN: 987-65-4321"   // sensitive!
     * ));
     *
     * int removed = ssnPattern.removeMatches(logLines);
     * // removed = 2
     * // logLines now = ["User logged in", "Processing request"] (SSNs removed)
     * }</pre>
     *
     * <p><b>Common Use Cases:</b>
     * <ul>
     *   <li>Data sanitization (remove PII, credentials, etc.)</li>
     *   <li>Blacklist filtering (remove known bad patterns)</li>
     *   <li>Log cleaning (strip sensitive information)</li>
     * </ul>
     *
     * @param inputs mutable collection to filter (List, Set, Queue, etc.)
     * @return number of elements removed
     * @throws NullPointerException if inputs is null
     * @throws UnsupportedOperationException if collection is immutable
     * @see #retainMatches(java.util.Collection) inverse (keep matches, remove non-matches)
     * @see #filterNot(java.util.Collection) non-mutating variant
     * @since 1.0.0
     */
    public int removeMatches(java.util.Collection<String> inputs) {
        Objects.requireNonNull(inputs, "inputs cannot be null");

        if (inputs.isEmpty()) {
            return 0;
        }

        String[] array;
        try {
            array = inputs.toArray(new String[0]);
        } catch (ArrayStoreException e) {
            throw new IllegalArgumentException(
                "Collection contains non-String elements. Use stream().map(Object::toString).toList() to convert.", e);
        }

        boolean[] matches = matchAll(array);

        int removed = 0;
        java.util.Iterator<String> it = inputs.iterator();
        int i = 0;
        while (it.hasNext()) {
            it.next();
            if (matches[i++]) {  // Inverted logic
                it.remove();
                removed++;
            }
        }

        return removed;
    }

    // ========== Map Filtering Operations ==========

    /**
     * Filters map by matching keys against pattern (returns new map).
     *
     * <p>Creates a new {@link java.util.HashMap} containing only entries whose keys match
     * this pattern. Values are preserved, keys are tested against the pattern.
     *
     * <p><b>Example - Filter configuration by environment prefix:</b>
     * <pre>{@code
     * Pattern prodPattern = Pattern.compile("prod_.*");
     * Map<String, String> allConfig = Map.of(
     *     "prod_db_host", "prod-db.example.com",
     *     "test_db_host", "test-db.example.com",
     *     "prod_api_key", "abc123",
     *     "test_api_key", "xyz789"
     * );
     *
     * Map<String, String> prodConfig = prodPattern.filterByKey(allConfig);
     * // prodConfig = {"prod_db_host": "prod-db.example.com", "prod_api_key": "abc123"}
     * }</pre>
     *
     * <p><b>Example - Cassandra SAI filter by partition key pattern:</b>
     * <pre>{@code
     * // Filter partition keys matching date pattern
     * Pattern datePattern = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
     * Map<String, Row> partitions = ...; // partition key → row
     * Map<String, Row> datePartitions = datePattern.filterByKey(partitions);
     * }</pre>
     *
     * <p><b>Performance:</b> Throughput ~2.6 million entries/second (benchmark: 10k entries in 3.9ms)
     *
     * @param <V> value type
     * @param inputs map where keys (String type) are tested against pattern
     * @return new HashMap containing only entries whose keys match
     * @throws NullPointerException if inputs is null
     * @see #filterByValue(java.util.Map) filter by values instead of keys
     * @see #filterNotByKey(java.util.Map) inverse (keep non-matching keys)
     * @see #retainMatchesByKey(java.util.Map) in-place variant
     * @since 1.0.0
     */
    public <V> java.util.Map<String, V> filterByKey(java.util.Map<String, V> inputs) {
        Objects.requireNonNull(inputs, "inputs cannot be null");

        if (inputs.isEmpty()) {
            return new java.util.HashMap<>();
        }

        // Extract keys for bulk matching
        java.util.List<java.util.Map.Entry<String, V>> entries = new java.util.ArrayList<>(inputs.entrySet());
        String[] keys = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            keys[i] = entries.get(i).getKey();
        }

        boolean[] matches = matchAll(keys);

        // Build result map
        java.util.Map<String, V> result = new java.util.HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            if (matches[i]) {
                java.util.Map.Entry<String, V> entry = entries.get(i);
                result.put(entry.getKey(), entry.getValue());
            }
        }

        return result;
    }

    /**
     * Filters map by matching values against pattern (returns new map).
     *
     * <p>Creates a new {@link java.util.HashMap} containing only entries whose values match
     * this pattern. Keys are preserved, values are tested against the pattern.
     *
     * <p><b>Example - Filter user map by valid email addresses:</b>
     * <pre>{@code
     * Pattern emailPattern = Pattern.compile("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
     * Map<Integer, String> userEmails = Map.of(
     *     1, "user@example.com",    // valid email
     *     2, "invalid_email",       // not an email
     *     3, "admin@test.org"       // valid email
     * );
     *
     * Map<Integer, String> validUsers = emailPattern.filterByValue(userEmails);
     * // validUsers = {1: "user@example.com", 3: "admin@test.org"}
     * }</pre>
     *
     * <p><b>Example - Extract records with matching status:</b>
     * <pre>{@code
     * Pattern activePattern = Pattern.compile("active|running|online");
     * Map<String, String> services = Map.of(
     *     "web", "active",
     *     "db", "stopped",
     *     "cache", "running"
     * );
     *
     * Map<String, String> activeServices = activePattern.filterByValue(services);
     * // activeServices = {"web": "active", "cache": "running"}
     * }</pre>
     *
     * @param <K> key type
     * @param inputs map where values (String type) are tested against pattern
     * @return new HashMap containing only entries whose values match
     * @throws NullPointerException if inputs is null
     * @see #filterByKey(java.util.Map) filter by keys instead of values
     * @see #filterNotByValue(java.util.Map) inverse (keep non-matching values)
     * @see #retainMatchesByValue(java.util.Map) in-place variant
     * @since 1.0.0
     */
    public <K> java.util.Map<K, String> filterByValue(java.util.Map<K, String> inputs) {
        Objects.requireNonNull(inputs, "inputs cannot be null");

        if (inputs.isEmpty()) {
            return new java.util.HashMap<>();
        }

        // Extract values for bulk matching
        java.util.List<java.util.Map.Entry<K, String>> entries = new java.util.ArrayList<>(inputs.entrySet());
        String[] values = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            values[i] = entries.get(i).getValue();
        }

        boolean[] matches = matchAll(values);

        // Build result map
        java.util.Map<K, String> result = new java.util.HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            if (matches[i]) {
                java.util.Map.Entry<K, String> entry = entries.get(i);
                result.put(entry.getKey(), entry.getValue());
            }
        }

        return result;
    }

    /**
     * Filters map by keys NOT matching pattern (inverse of {@link #filterByKey}).
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * Pattern tmpPattern = Pattern.compile("tmp_.*");
     * Map<String, String> data = Map.of("tmp_cache", "...", "prod_data", "...");
     * Map<String, String> permanent = tmpPattern.filterNotByKey(data);
     * // permanent = {"prod_data": "..."}
     * }</pre>
     *
     * @param <V> value type
     * @param inputs map where keys are tested
     * @return new HashMap with entries whose keys do NOT match
     * @throws NullPointerException if inputs is null
     * @see #filterByKey inverse
     * @since 1.0.0
     */
    public <V> java.util.Map<String, V> filterNotByKey(java.util.Map<String, V> inputs) {
        Objects.requireNonNull(inputs, "inputs cannot be null");

        if (inputs.isEmpty()) {
            return new java.util.HashMap<>();
        }

        java.util.List<java.util.Map.Entry<String, V>> entries = new java.util.ArrayList<>(inputs.entrySet());
        String[] keys = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            keys[i] = entries.get(i).getKey();
        }

        boolean[] matches = matchAll(keys);

        java.util.Map<String, V> result = new java.util.HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            if (!matches[i]) {  // Inverted
                java.util.Map.Entry<String, V> entry = entries.get(i);
                result.put(entry.getKey(), entry.getValue());
            }
        }

        return result;
    }

    /**
     * Filters map by values NOT matching pattern (inverse of {@link #filterByValue}).
     *
     * <p><b>Example - Exclude error statuses:</b>
     * <pre>{@code
     * Pattern errorPattern = Pattern.compile("error|failed|timeout");
     * Map<String, String> jobStatuses = Map.of("job1", "success", "job2", "error", "job3", "complete");
     * Map<String, String> successful = errorPattern.filterNotByValue(jobStatuses);
     * // successful = {"job1": "success", "job3": "complete"}
     * }</pre>
     *
     * @param <K> key type
     * @param inputs map where values are tested
     * @return new HashMap with entries whose values do NOT match
     * @throws NullPointerException if inputs is null
     * @see #filterByValue inverse
     * @since 1.0.0
     */
    public <K> java.util.Map<K, String> filterNotByValue(java.util.Map<K, String> inputs) {
        Objects.requireNonNull(inputs, "inputs cannot be null");

        if (inputs.isEmpty()) {
            return new java.util.HashMap<>();
        }

        java.util.List<java.util.Map.Entry<K, String>> entries = new java.util.ArrayList<>(inputs.entrySet());
        String[] values = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            values[i] = entries.get(i).getValue();
        }

        boolean[] matches = matchAll(values);

        java.util.Map<K, String> result = new java.util.HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            if (!matches[i]) {  // Inverted
                java.util.Map.Entry<K, String> entry = entries.get(i);
                result.put(entry.getKey(), entry.getValue());
            }
        }

        return result;
    }

    /**
     * Removes entries where keys don't match (in-place map mutation).
     *
     * <p><b>MUTATES INPUT:</b> Keeps only entries whose keys match the pattern.
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * Pattern userPattern = Pattern.compile("user_\\d+");
     * Map<String, Object> cache = new HashMap<>(Map.of("user_123", obj1, "sys_config", obj2));
     * int removed = userPattern.retainMatchesByKey(cache);
     * // removed = 1, cache = {"user_123": obj1}
     * }</pre>
     *
     * @param <V> value type
     * @param map mutable map to filter by keys
     * @return number of entries removed
     * @throws NullPointerException if map is null
     * @throws UnsupportedOperationException if map is immutable
     * @see #filterByKey non-mutating variant
     * @since 1.0.0
     */
    public <V> int retainMatchesByKey(java.util.Map<String, V> map) {
        Objects.requireNonNull(map, "map cannot be null");

        if (map.isEmpty()) {
            return 0;
        }

        String[] keys = map.keySet().toArray(new String[0]);
        boolean[] matches = matchAll(keys);

        int removed = 0;
        java.util.Iterator<java.util.Map.Entry<String, V>> it = map.entrySet().iterator();
        int i = 0;
        while (it.hasNext()) {
            it.next();
            if (!matches[i++]) {
                it.remove();
                removed++;
            }
        }

        return removed;
    }

    /**
     * Removes entries where values don't match (in-place map mutation).
     *
     * <p><b>MUTATES INPUT:</b> Keeps only entries whose values match.
     *
     * <p><b>Example:</b>
     * <pre>{@code
     * Pattern activePattern = Pattern.compile("active|online");
     * Map<String, String> servers = new HashMap<>(Map.of("web", "active", "db", "offline"));
     * userPattern.retainMatchesByValue(servers);
     * // servers = {"web": "active"}
     * }</pre>
     *
     * @param <K> key type
     * @param map mutable map to filter by values
     * @return number of entries removed
     * @throws NullPointerException if map is null
     * @throws UnsupportedOperationException if map is immutable
     * @see #filterByValue non-mutating variant
     * @since 1.0.0
     */
    public <K> int retainMatchesByValue(java.util.Map<K, String> map) {
        Objects.requireNonNull(map, "map cannot be null");

        if (map.isEmpty()) {
            return 0;
        }

        // Extract values maintaining entry order
        java.util.List<java.util.Map.Entry<K, String>> entries = new java.util.ArrayList<>(map.entrySet());
        String[] values = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            values[i] = entries.get(i).getValue();
        }

        boolean[] matches = matchAll(values);

        int removed = 0;
        java.util.Iterator<java.util.Map.Entry<K, String>> it = map.entrySet().iterator();
        int i = 0;
        while (it.hasNext()) {
            it.next();
            if (!matches[i++]) {
                it.remove();
                removed++;
            }
        }

        return removed;
    }

    /**
     * Removes entries where keys match (in-place, inverse of {@link #retainMatchesByKey}).
     *
     * <p><b>MUTATES INPUT:</b> Removes entries whose keys match the pattern.
     *
     * <p><b>Example - Remove temporary cache entries:</b>
     * <pre>{@code
     * Pattern tmpPattern = Pattern.compile("tmp_.*");
     * Map<String, Data> cache = new HashMap<>(Map.of("tmp_123", data1, "perm_456", data2));
     * tmpPattern.removeMatchesByKey(cache);
     * // cache = {"perm_456": data2}
     * }</pre>
     *
     * @param <V> value type
     * @param map mutable map to filter by keys
     * @return number of entries removed
     * @throws NullPointerException if map is null
     * @throws UnsupportedOperationException if map is immutable
     * @see #filterNotByKey non-mutating variant
     * @since 1.0.0
     */
    public <V> int removeMatchesByKey(java.util.Map<String, V> map) {
        Objects.requireNonNull(map, "map cannot be null");

        if (map.isEmpty()) {
            return 0;
        }

        String[] keys = map.keySet().toArray(new String[0]);
        boolean[] matches = matchAll(keys);

        int removed = 0;
        java.util.Iterator<java.util.Map.Entry<String, V>> it = map.entrySet().iterator();
        int i = 0;
        while (it.hasNext()) {
            it.next();
            if (matches[i++]) {  // Inverted - remove if MATCHES
                it.remove();
                removed++;
            }
        }

        return removed;
    }

    /**
     * Removes entries where values match (in-place, inverse of {@link #retainMatchesByValue}).
     *
     * <p><b>MUTATES INPUT:</b> Removes entries whose values match the pattern.
     *
     * <p><b>Example - Remove failed jobs:</b>
     * <pre>{@code
     * Pattern failedPattern = Pattern.compile("failed|error|timeout");
     * Map<Integer, String> jobs = new HashMap<>(Map.of(1, "success", 2, "failed", 3, "complete"));
     * failedPattern.removeMatchesByValue(jobs);
     * // jobs = {1: "success", 3: "complete"}
     * }</pre>
     *
     * @param <K> key type
     * @param map mutable map to filter by values
     * @return number of entries removed
     * @throws NullPointerException if map is null
     * @throws UnsupportedOperationException if map is immutable
     * @see #filterNotByValue non-mutating variant
     * @since 1.0.0
     */
    public <K> int removeMatchesByValue(java.util.Map<K, String> map) {
        Objects.requireNonNull(map, "map cannot be null");

        if (map.isEmpty()) {
            return 0;
        }

        java.util.List<java.util.Map.Entry<K, String>> entries = new java.util.ArrayList<>(map.entrySet());
        String[] values = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            values[i] = entries.get(i).getValue();
        }

        boolean[] matches = matchAll(values);

        int removed = 0;
        java.util.Iterator<java.util.Map.Entry<K, String>> it = map.entrySet().iterator();
        int i = 0;
        while (it.hasNext()) {
            it.next();
            if (matches[i++]) {  // Inverted - remove if MATCHES
                it.remove();
                removed++;
            }
        }

        return removed;
    }
}
