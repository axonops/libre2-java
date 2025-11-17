package com.axonops.libre2.cache;

import java.time.Duration;

/**
 * Configuration for RE2 library including caching and resource limits.
 *
 * Immutable configuration using Java 17 records.
 *
 * @since 1.0.0
 */
public record RE2Config(
    boolean cacheEnabled,
    int maxCacheSize,
    long idleTimeoutSeconds,
    long evictionScanIntervalSeconds,
    long deferredCleanupIntervalSeconds,
    int maxSimultaneousCompiledPatterns,
    int maxMatchersPerPattern
) {

    /**
     * Default configuration for production Cassandra use.
     *
     * Defaults chosen for typical 128GB+ Cassandra clusters:
     * - Cache: 50K patterns (~50-200MB, negligible in large clusters)
     * - Idle timeout: 5 minutes (patterns auto-cleaned after inactivity)
     * - Scan interval: 1 minute (balance cleanup speed vs CPU)
     * - Deferred cleanup: 5 seconds (frequent cleanup of evicted-but-in-use patterns)
     * - Simultaneous limit: 100K ACTIVE patterns (NOT cumulative - patterns can be freed/recompiled)
     * - Matchers per pattern: 10K (prevents per-pattern exhaustion)
     */
    public static final RE2Config DEFAULT = new RE2Config(
        true,                          // Cache enabled
        50000,                         // Max 50K cached patterns (~50-200MB)
        300,                           // 5 minute idle timeout
        60,                            // Scan every 60 seconds
        5,                             // Deferred cleanup every 5 seconds
        100000,                        // Max 100K simultaneous active patterns
        10000                          // Max 10K matchers per pattern
    );

    /**
     * Configuration with caching disabled.
     * Users manage all pattern resources manually.
     */
    public static final RE2Config NO_CACHE = new RE2Config(
        false,                         // Cache disabled
        0,                             // Ignored when cache disabled
        0,                             // Ignored when cache disabled
        0,                             // Ignored when cache disabled
        0,                             // Ignored when cache disabled
        100000,                        // Still enforce simultaneous limit
        10000                          // Still enforce matcher limit
    );

    /**
     * Compact constructor with validation.
     *
     * CRITICAL: maxSimultaneousCompiledPatterns is SIMULTANEOUS/ACTIVE count, NOT cumulative.
     */
    public RE2Config {
        // Always validate resource limits (even if cache disabled)
        if (maxSimultaneousCompiledPatterns <= 0) {
            throw new IllegalArgumentException("maxSimultaneousCompiledPatterns must be positive (this is SIMULTANEOUS active count, not cumulative)");
        }
        if (maxMatchersPerPattern <= 0) {
            throw new IllegalArgumentException("maxMatchersPerPattern must be positive");
        }

        // Validate cache parameters only if cache enabled
        if (cacheEnabled) {
            if (maxCacheSize <= 0) {
                throw new IllegalArgumentException("maxCacheSize must be positive when cache enabled");
            }
            if (idleTimeoutSeconds <= 0) {
                throw new IllegalArgumentException("idleTimeoutSeconds must be positive when cache enabled");
            }
            if (evictionScanIntervalSeconds <= 0) {
                throw new IllegalArgumentException("evictionScanIntervalSeconds must be positive when cache enabled");
            }
            if (deferredCleanupIntervalSeconds <= 0) {
                throw new IllegalArgumentException("deferredCleanupIntervalSeconds must be positive when cache enabled");
            }

            // Warn if scan interval exceeds idle timeout (still valid, just suboptimal)
            if (evictionScanIntervalSeconds > idleTimeoutSeconds) {
                System.err.println("WARNING: evictionScanIntervalSeconds (" + evictionScanIntervalSeconds +
                    "s) exceeds idleTimeoutSeconds (" + idleTimeoutSeconds + "s) - idle patterns may not be evicted promptly");
            }

            // Deferred cleanup should be frequent (warn if too slow)
            if (deferredCleanupIntervalSeconds > 30) {
                System.err.println("WARNING: deferredCleanupIntervalSeconds (" + deferredCleanupIntervalSeconds +
                    "s) is quite long - evicted patterns may retain memory for extended periods");
            }

            // Deferred cleanup must be at least as frequent as idle eviction
            if (deferredCleanupIntervalSeconds > evictionScanIntervalSeconds) {
                throw new IllegalArgumentException("deferredCleanupIntervalSeconds (" + deferredCleanupIntervalSeconds +
                    "s) must be <= evictionScanIntervalSeconds (" + evictionScanIntervalSeconds +
                    "s) - deferred cleanup should run at least as often as idle eviction");
            }

            // Cache size must not exceed simultaneous limit
            if (maxCacheSize > maxSimultaneousCompiledPatterns) {
                throw new IllegalArgumentException("maxCacheSize (" + maxCacheSize +
                    ") cannot exceed maxSimultaneousCompiledPatterns (" + maxSimultaneousCompiledPatterns + ")");
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
        private boolean cacheEnabled = true;
        private int maxCacheSize = 50000;
        private long idleTimeoutSeconds = 300;
        private long evictionScanIntervalSeconds = 60;
        private long deferredCleanupIntervalSeconds = 5;
        private int maxSimultaneousCompiledPatterns = 100000;
        private int maxMatchersPerPattern = 10000;

        public Builder cacheEnabled(boolean enabled) {
            this.cacheEnabled = enabled;
            return this;
        }

        public Builder maxCacheSize(int size) {
            this.maxCacheSize = size;
            return this;
        }

        public Builder idleTimeoutSeconds(long seconds) {
            this.idleTimeoutSeconds = seconds;
            return this;
        }

        public Builder evictionScanIntervalSeconds(long seconds) {
            this.evictionScanIntervalSeconds = seconds;
            return this;
        }

        public Builder deferredCleanupIntervalSeconds(long seconds) {
            this.deferredCleanupIntervalSeconds = seconds;
            return this;
        }

        public Builder maxSimultaneousCompiledPatterns(int max) {
            this.maxSimultaneousCompiledPatterns = max;
            return this;
        }

        public Builder maxMatchersPerPattern(int max) {
            this.maxMatchersPerPattern = max;
            return this;
        }

        public RE2Config build() {
            return new RE2Config(
                cacheEnabled,
                maxCacheSize,
                idleTimeoutSeconds,
                evictionScanIntervalSeconds,
                deferredCleanupIntervalSeconds,
                maxSimultaneousCompiledPatterns,
                maxMatchersPerPattern
            );
        }
    }
}
