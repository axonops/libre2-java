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
import com.axonops.libre2.jni.RE2Native;
import com.axonops.libre2.util.ResourceTracker;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
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

    // Global pattern cache
    private static final PatternCache cache = new PatternCache(RE2Config.DEFAULT);

    private final String patternString;
    private final boolean caseSensitive;
    private final Pointer nativePattern;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final boolean fromCache;
    private final java.util.concurrent.atomic.AtomicInteger refCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int maxMatchersPerPattern = RE2Config.DEFAULT.maxMatchersPerPattern();

    Pattern(String patternString, boolean caseSensitive, Pointer nativePattern) {
        this(patternString, caseSensitive, nativePattern, false);
    }

    Pattern(String patternString, boolean caseSensitive, Pointer nativePattern, boolean fromCache) {
        this.patternString = Objects.requireNonNull(patternString);
        this.caseSensitive = caseSensitive;
        this.nativePattern = Objects.requireNonNull(nativePattern);
        this.fromCache = fromCache;

        logger.debug("RE2: Pattern created - length: {}, caseSensitive: {}, fromCache: {}",
            patternString.length(), caseSensitive, fromCache);
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
        // Track allocation and enforce maxSimultaneousCompiledPatterns limit
        // This is ACTIVE count, not cumulative - patterns can be freed and recompiled
        ResourceTracker.trackPatternAllocated(cache.getConfig().maxSimultaneousCompiledPatterns());

        try {
            RE2Native lib = RE2LibraryLoader.loadLibrary();
            byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);

            Pointer ptr = lib.re2_compile(pattern, bytes.length, caseSensitive ? 1 : 0);

            if (ptr == null || lib.re2_pattern_ok(ptr) != 1) {
                String error = lib.re2_get_error();
                if (ptr != null) {
                    lib.re2_free_pattern(ptr);
                }
                // Compilation failed - decrement count
                ResourceTracker.trackPatternFreed();
                throw new PatternCompilationException(pattern, error != null ? error : "Unknown error");
            }

            return new Pattern(pattern, caseSensitive, ptr, fromCache);
        } catch (Exception e) {
            // If any exception, decrement count (unless it was ResourceException from limit)
            if (!(e instanceof ResourceException)) {
                ResourceTracker.trackPatternFreed();
            }
            throw e;
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

    Pointer getNativePattern() {
        checkNotClosed();
        return nativePattern;
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

    @Override
    public void close() {
        if (fromCache) {
            logger.warn("RE2: Attempted to close cached pattern (ignoring - cache manages lifecycle)");
            return;
        }

        forceClose();
    }

    /**
     * Force closes the pattern (INTERNAL USE ONLY - called by cache during eviction).
     *
     * CRITICAL: Only frees native resources if reference count is 0.
     * If refCount > 0, pattern is still in use by Matchers - defer cleanup.
     *
     * Do not call this method directly - use close() instead.
     * This method bypasses the fromCache check.
     *
     * @deprecated Internal use only
     */
    @Deprecated
    public void forceClose() {
        if (refCount.get() > 0) {
            logger.warn("RE2: Cannot force close pattern - still in use by {} matcher(s)", refCount.get());
            return;
        }

        if (closed.compareAndSet(false, true)) {
            logger.debug("RE2: Force closing pattern");
            RE2Native lib = RE2LibraryLoader.loadLibrary();
            lib.re2_free_pattern(nativePattern);

            // Track that pattern was freed (decrements active count)
            ResourceTracker.trackPatternFreed();
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

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("RE2: Pattern is closed");
        }
    }
}
