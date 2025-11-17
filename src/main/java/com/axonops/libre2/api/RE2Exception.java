package com.axonops.libre2.api;

/**
 * Base exception for all RE2-related errors.
 *
 * Sealed class ensuring exhaustive handling of all error types.
 *
 * @since 1.0.0
 */
public sealed class RE2Exception extends RuntimeException
    permits PatternCompilationException,
            NativeLibraryException,
            RE2TimeoutException,
            ResourceException {

    public RE2Exception(String message) {
        super(message);
    }

    public RE2Exception(String message, Throwable cause) {
        super(message, cause);
    }
}
