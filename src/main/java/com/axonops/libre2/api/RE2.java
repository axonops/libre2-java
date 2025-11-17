package com.axonops.libre2.api;

/**
 * Main entry point for RE2 regex operations.
 *
 * Thread-safe: All methods can be called concurrently from multiple threads.
 *
 * @since 1.0.0
 */
public final class RE2 {

    private RE2() {
        // Utility class
    }

    public static Pattern compile(String pattern) {
        return Pattern.compile(pattern);
    }

    public static Pattern compile(String pattern, boolean caseSensitive) {
        return Pattern.compile(pattern, caseSensitive);
    }

    public static boolean matches(String pattern, String input) {
        try (Pattern p = compile(pattern)) {
            return p.matches(input);
        }
    }
}
