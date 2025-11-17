package com.axonops.libre2.api;

/**
 * Thrown when resource management fails.
 *
 * @since 1.0.0
 */
public final class ResourceException extends RE2Exception {

    public ResourceException(String message) {
        super("RE2: Resource error: " + message);
    }

    public ResourceException(String message, Throwable cause) {
        super("RE2: Resource error: " + message, cause);
    }
}
