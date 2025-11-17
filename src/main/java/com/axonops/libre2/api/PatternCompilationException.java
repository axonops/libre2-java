package com.axonops.libre2.api;

/**
 * Thrown when a regex pattern fails to compile.
 *
 * @since 1.0.0
 */
public final class PatternCompilationException extends RE2Exception {

    private final String pattern;

    public PatternCompilationException(String pattern, String message) {
        super("RE2: Pattern compilation failed: " + message + " (pattern: " + truncate(pattern) + ")");
        this.pattern = pattern;
    }

    public String getPattern() {
        return pattern;
    }

    private static String truncate(String s) {
        return s != null && s.length() > 100 ? s.substring(0, 97) + "..." : s;
    }
}
