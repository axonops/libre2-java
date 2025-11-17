package com.axonops.libre2.api;

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
 * Must be closed to free native resources.
 *
 * @since 1.0.0
 */
public final class Pattern implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(Pattern.class);

    private final String patternString;
    private final boolean caseSensitive;
    private final Pointer nativePattern;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    Pattern(String patternString, boolean caseSensitive, Pointer nativePattern) {
        this.patternString = Objects.requireNonNull(patternString);
        this.caseSensitive = caseSensitive;
        this.nativePattern = Objects.requireNonNull(nativePattern);

        logger.debug("RE2: Pattern created - length: {}, caseSensitive: {}", patternString.length(), caseSensitive);
    }

    public static Pattern compile(String pattern) {
        return compile(pattern, true);
    }

    public static Pattern compile(String pattern, boolean caseSensitive) {
        Objects.requireNonNull(pattern, "pattern cannot be null");

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

        return new Pattern(pattern, caseSensitive, ptr);
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
        if (closed.compareAndSet(false, true)) {
            logger.debug("RE2: Closing pattern");
            RE2Native lib = RE2LibraryLoader.loadLibrary();
            lib.re2_free_pattern(nativePattern);
        }
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("RE2: Pattern is closed");
        }
    }
}
