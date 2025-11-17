package com.axonops.libre2.api;

/**
 * Thrown when native library operations fail.
 *
 * @since 1.0.0
 */
public final class NativeLibraryException extends RE2Exception {

    public NativeLibraryException(String message) {
        super("RE2: Native library error: " + message);
    }

    public NativeLibraryException(String message, Throwable cause) {
        super("RE2: Native library error: " + message, cause);
    }
}
