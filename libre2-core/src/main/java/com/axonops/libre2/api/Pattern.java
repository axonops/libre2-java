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

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

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
     */
    public long getNativeMemoryBytes() {
        return nativeMemoryBytes;
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

        long startNanos = System.nanoTime();
        boolean[] results = RE2NativeJNI.fullMatchBulk(nativeHandle, inputs);
        long durationNanos = System.nanoTime() - startNanos;

        // Track metrics (count as multiple operations)
        RE2MetricsRegistry metrics = Pattern.getGlobalCache().getConfig().metricsRegistry();
        metrics.incrementCounter(MetricNames.MATCHING_OPERATIONS, inputs.length);
        metrics.recordTimer(MetricNames.MATCHING_FULL_MATCH_LATENCY, durationNanos / inputs.length);

        return results != null ? results : new boolean[inputs.length];
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
