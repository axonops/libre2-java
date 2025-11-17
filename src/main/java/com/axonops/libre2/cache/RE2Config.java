package com.axonops.libre2.cache;

import java.time.Duration;

/**
 * Configuration for RE2 pattern caching.
 *
 * Immutable configuration using Java 17 records.
 *
 * @since 1.0.0
 */
public record RE2Config(
    int cacheMaxSize,
    Duration idleTimeout,
    Duration evictionScanInterval,
    boolean cacheEnabled
) {

    /**
     * Default configuration for production use.
     */
    public static final RE2Config DEFAULT = new RE2Config(
        1000,                          // Max 1000 patterns
        Duration.ofSeconds(300),       // 5 minute idle timeout
        Duration.ofSeconds(60),        // Scan every 60 seconds
        true                           // Cache enabled
    );

    /**
     * Configuration with caching disabled.
     */
    public static final RE2Config NO_CACHE = new RE2Config(
        0,
        Duration.ZERO,
        Duration.ZERO,
        false
    );

    /**
     * Compact constructor with validation.
     */
    public RE2Config {
        if (cacheEnabled) {
            if (cacheMaxSize <= 0) {
                throw new IllegalArgumentException("Cache max size must be positive");
            }
            if (idleTimeout.isNegative() || idleTimeout.isZero()) {
                throw new IllegalArgumentException("Idle timeout must be positive");
            }
            if (evictionScanInterval.isNegative() || evictionScanInterval.isZero()) {
                throw new IllegalArgumentException("Eviction scan interval must be positive");
            }
            if (evictionScanInterval.compareTo(idleTimeout) > 0) {
                throw new IllegalArgumentException("Scan interval should not exceed idle timeout");
            }
        }
    }

    /**
     * Creates a builder for custom configuration.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int cacheMaxSize = 1000;
        private Duration idleTimeout = Duration.ofSeconds(300);
        private Duration evictionScanInterval = Duration.ofSeconds(60);
        private boolean cacheEnabled = true;

        public Builder cacheMaxSize(int size) {
            this.cacheMaxSize = size;
            return this;
        }

        public Builder idleTimeout(Duration timeout) {
            this.idleTimeout = timeout;
            return this;
        }

        public Builder evictionScanInterval(Duration interval) {
            this.evictionScanInterval = interval;
            return this;
        }

        public Builder cacheEnabled(boolean enabled) {
            this.cacheEnabled = enabled;
            return this;
        }

        public RE2Config build() {
            return new RE2Config(cacheMaxSize, idleTimeout, evictionScanInterval, cacheEnabled);
        }
    }
}
