package com.axonops.libre2.api;

/**
 * Thrown when a regex operation exceeds timeout.
 *
 * @since 1.0.0
 */
public final class RE2TimeoutException extends RE2Exception {

    private final long timeoutMillis;

    public RE2TimeoutException(long timeoutMillis) {
        super("RE2: Operation timed out after " + timeoutMillis + " ms");
        this.timeoutMillis = timeoutMillis;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }
}
