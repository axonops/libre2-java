package com.axonops.libre2.api;

import com.axonops.libre2.cache.PatternCache;
import com.axonops.libre2.cache.RE2Config;
import com.axonops.libre2.jni.RE2LibraryLoader;
import com.axonops.libre2.jni.RE2Native;
import com.sun.jna.Pointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A compiled regular expression pattern.
 *
 * Must be closed to free native resources (unless from cache).
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
        RE2Native lib = RE2LibraryLoader.loadLibrary();
        byte[] bytes = pattern.getBytes(StandardCharsets.UTF_8);

        Pointer ptr = lib.re2_compile(pattern, bytes.length, caseSensitive ? 1 : 0);

        if (ptr == null || lib.re2_pattern_ok(ptr) != 1) {
            String error = lib.re2_get_error();
            if (ptr != null) {
                lib.re2_free_pattern(ptr);
            }
            throw new PatternCompilationException(pattern, error != null ? error : "Unknown error");
        }

        return new Pattern(pattern, caseSensitive, ptr, fromCache);
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
     * Do not call this method directly - use close() instead.
     * This method bypasses the fromCache check and always closes.
     *
     * @deprecated Internal use only
     */
    @Deprecated
    public void forceClose() {
        if (closed.compareAndSet(false, true)) {
            logger.debug("RE2: Force closing pattern");
            RE2Native lib = RE2LibraryLoader.loadLibrary();
            lib.re2_free_pattern(nativePattern);
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
