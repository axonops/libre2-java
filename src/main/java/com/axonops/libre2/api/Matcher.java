package com.axonops.libre2.api;

import com.axonops.libre2.jni.RE2LibraryLoader;
import com.axonops.libre2.jni.RE2Native;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Performs regex matching operations.
 *
 * @since 1.0.0
 */
public final class Matcher implements AutoCloseable {

    private final Pattern pattern;
    private final String input;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    Matcher(Pattern pattern, String input) {
        this.pattern = Objects.requireNonNull(pattern);
        this.input = Objects.requireNonNull(input);

        // Increment reference count to prevent pattern being freed while in use
        pattern.incrementRefCount();
    }

    public boolean matches() {
        checkNotClosed();

        RE2Native lib = RE2LibraryLoader.loadLibrary();
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);

        int result = lib.re2_full_match(pattern.getNativePattern(), input, bytes.length);

        if (result == -1) {
            String error = lib.re2_get_error();
            throw new NativeLibraryException("Match failed: " + (error != null ? error : "Unknown error"));
        }

        return result == 1;
    }

    public boolean find() {
        checkNotClosed();

        RE2Native lib = RE2LibraryLoader.loadLibrary();
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);

        int result = lib.re2_partial_match(pattern.getNativePattern(), input, bytes.length);

        if (result == -1) {
            String error = lib.re2_get_error();
            throw new NativeLibraryException("Partial match failed: " + (error != null ? error : "Unknown error"));
        }

        return result == 1;
    }

    public Pattern pattern() {
        return pattern;
    }

    public String input() {
        return input;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // Decrement reference count - pattern can now be freed if evicted
            pattern.decrementRefCount();
        }
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("RE2: Matcher is closed");
        }
    }
}
