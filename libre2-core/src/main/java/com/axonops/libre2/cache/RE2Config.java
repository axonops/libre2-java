/*
 * Copyright 2025 AxonOps
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.axonops.libre2.cache;

import com.axonops.libre2.metrics.NoOpMetricsRegistry;
import com.axonops.libre2.metrics.RE2MetricsRegistry;
import java.util.Objects;

/**
 * Configuration for RE2 library including caching, resource limits, and metrics.
 *
 * <p>Immutable configuration using Java 17 records. Controls pattern caching behavior, dual
 * eviction strategy (LRU + idle timeout), resource limits, and metrics integration.
 *
 * <h2>Architecture Overview</h2>
 *
 * <h3>Pattern Cache</h3>
 *
 * <p>RE2 automatically caches compiled patterns to avoid expensive recompilation. The cache uses a
 * <b>dual eviction strategy</b>:
 *
 * <ol>
 *   <li><b>LRU Eviction</b> - When cache exceeds {@code maxCacheSize}, least-recently-used patterns
 *       evicted
 *   <li><b>Idle Eviction</b> - Background thread evicts patterns unused for {@code
 *       idleTimeoutSeconds}
 * </ol>
 *
 * <p><b>Why Dual Eviction?</b>
 *
 * <ul>
 *   <li>LRU provides short-term performance (keeps hot patterns in cache)
 *   <li>Idle timeout prevents long-term memory growth (cleans abandoned patterns)
 *   <li>Without idle eviction: cache fills with one-time patterns, never cleans up
 *   <li>Without LRU: high-traffic patterns repeatedly recompiled, poor performance
 * </ul>
 *
 * <h3>Deferred Cleanup</h3>
 *
 * <p>When a pattern is evicted but still in use by active {@link com.axonops.libre2.api.Matcher}
 * instances, it's moved to a <b>deferred cleanup queue</b> and freed once all matchers close. A
 * background task runs every {@code deferredCleanupIntervalSeconds} to reclaim memory.
 *
 * <h3>Resource Limits</h3>
 *
 * <p>Two critical safety limits:
 *
 * <ul>
 *   <li><b>maxSimultaneousCompiledPatterns</b> - Maximum ACTIVE patterns (not cumulative). Prevents
 *       unbounded native memory growth. Patterns can be freed and recompiled.
 *   <li><b>maxMatchersPerPattern</b> - Maximum matchers per pattern. Prevents single pattern from
 *       exhausting matcher resources.
 * </ul>
 *
 * <h3>Eviction Protection</h3>
 *
 * <p>{@code evictionProtectionMs} prevents race conditions where a pattern is compiled, then
 * immediately evicted before the caller can use it. Newly compiled patterns are protected from
 * eviction for this duration (default: 1 second).
 *
 * <h2>Configuration Examples</h2>
 *
 * <h3>Default Production Config</h3>
 *
 * <pre>{@code
 * // Use defaults (50K cache, 5min idle, metrics disabled)
 * RE2Config config = RE2Config.DEFAULT;
 * Pattern.setGlobalCache(new PatternCache(config));
 * }</pre>
 *
 * <h3>High-Traffic Server</h3>
 *
 * <pre>{@code
 * // Larger cache, faster cleanup, metrics enabled
 * RE2Config config = RE2Config.builder()
 *     .maxCacheSize(100_000)              // 100K patterns (~100-400MB)
 *     .idleTimeoutSeconds(600)            // 10 minutes (less aggressive cleanup)
 *     .evictionScanIntervalSeconds(30)    // Scan every 30s (faster cleanup)
 *     .deferredCleanupIntervalSeconds(2)  // Quick deferred cleanup
 *     .metricsRegistry(new DropwizardMetricsAdapter(registry, "myapp.re2"))
 *     .build();
 * }</pre>
 *
 * <h3>Memory-Constrained Environment</h3>
 *
 * <pre>{@code
 * // Smaller cache, aggressive cleanup
 * RE2Config config = RE2Config.builder()
 *     .maxCacheSize(5_000)                // Only 5K patterns (~5-20MB)
 *     .idleTimeoutSeconds(60)             // 1 minute idle timeout
 *     .evictionScanIntervalSeconds(15)    // Scan every 15s
 *     .maxSimultaneousCompiledPatterns(10_000) // Lower simultaneous limit
 *     .build();
 * }</pre>
 *
 * <h3>Manual Resource Management (No Cache)</h3>
 *
 * <pre>{@code
 * // Disable cache, manage patterns manually
 * RE2Config config = RE2Config.NO_CACHE;
 * // Users must call pattern.close() manually
 * }</pre>
 *
 * <h2>Tuning Recommendations</h2>
 *
 * <h3>Cache Size</h3>
 *
 * <ul>
 *   <li><b>Default: 50,000 patterns</b> (~50-200MB native memory, negligible in large clusters)
 *   <li>Set based on unique pattern count in workload (monitor cache hit rate via metrics)
 *   <li>Each pattern: ~170-250 bytes typical, but can be 10KB+ for complex regex
 *   <li>Target: >90% cache hit rate for steady-state workloads
 * </ul>
 *
 * <h3>Idle Timeout</h3>
 *
 * <ul>
 *   <li><b>Default: 300 seconds (5 minutes)</b>
 *   <li>Shorter (1-2min): Aggressive cleanup, good for memory-constrained or bursty workloads
 *   <li>Longer (10-30min): Less cleanup overhead, good for stable high-traffic servers
 *   <li>Balance: cleanup speed vs. keeping warm patterns available
 * </ul>
 *
 * <h3>Scan Interval</h3>
 *
 * <ul>
 *   <li><b>Default: 60 seconds</b>
 *   <li>Faster (15-30s): More CPU overhead, faster cleanup of idle patterns
 *   <li>Slower (2-5min): Less CPU overhead, slower cleanup
 *   <li>Must be ≤ idleTimeoutSeconds for timely cleanup
 * </ul>
 *
 * <h3>Deferred Cleanup</h3>
 *
 * <ul>
 *   <li><b>Default: 5 seconds</b>
 *   <li>Should be frequent (2-10s) to quickly reclaim memory from evicted patterns
 *   <li>Must be ≤ evictionScanIntervalSeconds (runs at least as often as idle eviction)
 *   <li>Monitor {@code cache.deferred.patterns.current.count} - should stay near zero
 * </ul>
 *
 * <h3>Resource Limits</h3>
 *
 * <ul>
 *   <li><b>maxSimultaneousCompiledPatterns default: 100,000</b> - ACTIVE count, not cumulative
 *   <li>Increase if hitting limit (check {@code errors.resource.exhausted} metric)
 *   <li>This is a safety net, not a performance tuning parameter
 *   <li><b>maxMatchersPerPattern default: 10,000</b> - prevents single pattern exhaustion
 * </ul>
 *
 * <h3>Validation</h3>
 *
 * <ul>
 *   <li><b>Default: enabled</b>
 *   <li>Defensive check for native pointer validity on cache retrieval
 *   <li>Tiny overhead (~1 JNI call), provides crash safety
 *   <li>Only disable if absolute maximum performance required
 * </ul>
 *
 * @param cacheEnabled Enable pattern caching (if false, users manage patterns manually)
 * @param maxCacheSize Maximum patterns in cache before LRU eviction (must be > 0 if cache enabled)
 * @param idleTimeoutSeconds Evict patterns unused for this duration (must be > 0 if cache enabled)
 * @param evictionScanIntervalSeconds How often idle eviction task runs (must be > 0 and ≤
 *     idleTimeoutSeconds)
 * @param deferredCleanupIntervalSeconds How often deferred cleanup runs (must be > 0 and ≤
 *     evictionScanIntervalSeconds)
 * @param evictionProtectionMs Protect newly compiled patterns from eviction for this duration
 *     (prevents race conditions)
 * @param maxSimultaneousCompiledPatterns Maximum ACTIVE patterns (not cumulative - patterns can be
 *     freed/recompiled)
 * @param maxMatchersPerPattern Maximum matchers per pattern (prevents single pattern exhaustion)
 * @param validateCachedPatterns Validate native pointers on cache retrieval (defensive check, tiny
 *     overhead)
 * @param metricsRegistry Metrics implementation (use {@link
 *     com.axonops.libre2.metrics.NoOpMetricsRegistry} for zero overhead)
 * @since 1.0.0
 * @see com.axonops.libre2.cache.PatternCache
 * @see com.axonops.libre2.metrics.MetricNames
 */
public record RE2Config(
    boolean cacheEnabled,
    int maxCacheSize,
    long idleTimeoutSeconds,
    long evictionScanIntervalSeconds,
    long deferredCleanupIntervalSeconds,
    long evictionProtectionMs,
    int maxSimultaneousCompiledPatterns,
    int maxMatchersPerPattern,
    boolean validateCachedPatterns,
    RE2MetricsRegistry metricsRegistry) {

  /**
   * Default configuration for production use.
   *
   * <p>Defaults chosen for typical production clusters: - Cache: 50K patterns (~50-200MB,
   * negligible in large clusters) - Idle timeout: 5 minutes (patterns auto-cleaned after
   * inactivity) - Scan interval: 1 minute (balance cleanup speed vs CPU) - Deferred cleanup: 5
   * seconds (frequent cleanup of evicted-but-in-use patterns) - Simultaneous limit: 100K ACTIVE
   * patterns (NOT cumulative - patterns can be freed/recompiled) - Matchers per pattern: 10K
   * (prevents per-pattern exhaustion) - Validate cached patterns: enabled (defensive check for
   * native pointer validity) - Eviction protection: 1000ms (protects recently-used patterns from
   * immediate eviction) - Metrics: disabled (NoOp - zero overhead)
   */
  public static final RE2Config DEFAULT =
      new RE2Config(
          true, // Cache enabled
          50000, // Max 50K cached patterns (~50-200MB)
          300, // 5 minute idle timeout
          60, // Scan every 60 seconds
          5, // Deferred cleanup every 5 seconds
          1000, // 1 second eviction protection
          100000, // Max 100K simultaneous active patterns
          10000, // Max 10K matchers per pattern
          true, // Validate cached patterns (defensive check)
          NoOpMetricsRegistry.INSTANCE // Metrics disabled (zero overhead)
          );

  /** Configuration with caching disabled. Users manage all pattern resources manually. */
  public static final RE2Config NO_CACHE =
      new RE2Config(
          false, // Cache disabled
          0, // Ignored when cache disabled
          0, // Ignored when cache disabled
          0, // Ignored when cache disabled
          0, // Ignored when cache disabled
          0, // Ignored when cache disabled
          100000, // Still enforce simultaneous limit
          10000, // Still enforce matcher limit
          false, // No validation needed when no cache
          NoOpMetricsRegistry.INSTANCE // Metrics disabled
          );

  /**
   * Compact constructor with validation.
   *
   * <p>CRITICAL: maxSimultaneousCompiledPatterns is SIMULTANEOUS/ACTIVE count, NOT cumulative.
   */
  public RE2Config {
    // Always validate resource limits (even if cache disabled)
    if (maxSimultaneousCompiledPatterns <= 0) {
      throw new IllegalArgumentException(
          "maxSimultaneousCompiledPatterns must be positive (this is SIMULTANEOUS active count, not cumulative)");
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
        throw new IllegalArgumentException(
            "idleTimeoutSeconds must be positive when cache enabled");
      }
      if (evictionScanIntervalSeconds <= 0) {
        throw new IllegalArgumentException(
            "evictionScanIntervalSeconds must be positive when cache enabled");
      }
      if (deferredCleanupIntervalSeconds <= 0) {
        throw new IllegalArgumentException(
            "deferredCleanupIntervalSeconds must be positive when cache enabled");
      }
      if (evictionProtectionMs < 0) {
        throw new IllegalArgumentException(
            "evictionProtectionMs must be non-negative when cache enabled");
      }

      // Warn if scan interval exceeds idle timeout (still valid, just suboptimal)
      if (evictionScanIntervalSeconds > idleTimeoutSeconds) {
        System.err.println(
            "WARNING: evictionScanIntervalSeconds ("
                + evictionScanIntervalSeconds
                + "s) exceeds idleTimeoutSeconds ("
                + idleTimeoutSeconds
                + "s) - idle patterns may not be evicted promptly");
      }

      // Deferred cleanup should be frequent (warn if too slow)
      if (deferredCleanupIntervalSeconds > 30) {
        System.err.println(
            "WARNING: deferredCleanupIntervalSeconds ("
                + deferredCleanupIntervalSeconds
                + "s) is quite long - evicted patterns may retain memory for extended periods");
      }

      // Deferred cleanup must be at least as frequent as idle eviction
      if (deferredCleanupIntervalSeconds > evictionScanIntervalSeconds) {
        throw new IllegalArgumentException(
            "deferredCleanupIntervalSeconds ("
                + deferredCleanupIntervalSeconds
                + "s) must be <= evictionScanIntervalSeconds ("
                + evictionScanIntervalSeconds
                + "s) - deferred cleanup should run at least as often as idle eviction");
      }

      // Cache size must not exceed simultaneous limit
      if (maxCacheSize > maxSimultaneousCompiledPatterns) {
        throw new IllegalArgumentException(
            "maxCacheSize ("
                + maxCacheSize
                + ") cannot exceed maxSimultaneousCompiledPatterns ("
                + maxSimultaneousCompiledPatterns
                + ")");
      }
    }
  }

  /**
   * Creates a builder for custom configuration.
   *
   * <p>Builder starts with defaults and allows selective overrides:
   *
   * <pre>{@code
   * RE2Config config = RE2Config.builder()
   *     .maxCacheSize(100_000)
   *     .idleTimeoutSeconds(600)
   *     .build();
   * }</pre>
   *
   * @return new builder with default values
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Builder for custom RE2 configuration.
   *
   * <p>All fields start with production defaults from {@link #DEFAULT}. Use builder to selectively
   * override only the parameters you need to change.
   */
  public static class Builder {
    private boolean cacheEnabled = true;
    private int maxCacheSize = 50000;
    private long idleTimeoutSeconds = 300;
    private long evictionScanIntervalSeconds = 60;
    private long deferredCleanupIntervalSeconds = 5;
    private long evictionProtectionMs = 1000;
    private int maxSimultaneousCompiledPatterns = 100000;
    private int maxMatchersPerPattern = 10000;
    private boolean validateCachedPatterns = true;
    private RE2MetricsRegistry metricsRegistry = NoOpMetricsRegistry.INSTANCE;

    /**
     * Enable or disable pattern caching.
     *
     * <p>If disabled, users must manually manage pattern lifecycle (call {@code close()}).
     * Disabling cache eliminates all caching overhead but requires careful resource management.
     *
     * @param enabled true to enable caching (default), false to disable
     * @return this builder
     */
    public Builder cacheEnabled(boolean enabled) {
      this.cacheEnabled = enabled;
      return this;
    }

    /**
     * Set maximum number of patterns in cache before LRU eviction.
     *
     * <p><b>Default: 50,000</b> (~50-200MB native memory)
     *
     * <p>Typical memory per pattern: 170-250 bytes, but complex regex can be 10KB+
     *
     * <p>Monitor {@code cache.patterns.current.count} and cache hit rate to tune.
     *
     * @param size maximum cached patterns (must be > 0)
     * @return this builder
     */
    public Builder maxCacheSize(int size) {
      this.maxCacheSize = size;
      return this;
    }

    /**
     * Set idle timeout for pattern eviction.
     *
     * <p><b>Default: 300 seconds (5 minutes)</b>
     *
     * <p>Patterns unused for this duration are evicted by background thread.
     *
     * <ul>
     *   <li>Shorter (60-120s): Aggressive cleanup, better for bursty workloads
     *   <li>Longer (600-1800s): Less cleanup overhead, better for stable workloads
     * </ul>
     *
     * @param seconds idle timeout in seconds (must be > 0)
     * @return this builder
     */
    public Builder idleTimeoutSeconds(long seconds) {
      this.idleTimeoutSeconds = seconds;
      return this;
    }

    /**
     * Set how often idle eviction task runs.
     *
     * <p><b>Default: 60 seconds</b>
     *
     * <p>Must be ≤ {@code idleTimeoutSeconds} for timely cleanup.
     *
     * <ul>
     *   <li>Faster (15-30s): More CPU overhead, faster cleanup
     *   <li>Slower (120-300s): Less overhead, slower cleanup
     * </ul>
     *
     * @param seconds scan interval in seconds (must be > 0 and ≤ idleTimeoutSeconds)
     * @return this builder
     */
    public Builder evictionScanIntervalSeconds(long seconds) {
      this.evictionScanIntervalSeconds = seconds;
      return this;
    }

    /**
     * Set how often deferred cleanup task runs.
     *
     * <p><b>Default: 5 seconds</b>
     *
     * <p>Deferred cleanup reclaims memory from evicted patterns still in use by matchers. Should be
     * frequent (2-10s) for quick memory reclamation.
     *
     * <p>Must be ≤ {@code evictionScanIntervalSeconds}.
     *
     * @param seconds cleanup interval in seconds (must be > 0 and ≤ evictionScanIntervalSeconds)
     * @return this builder
     */
    public Builder deferredCleanupIntervalSeconds(long seconds) {
      this.deferredCleanupIntervalSeconds = seconds;
      return this;
    }

    /**
     * Set eviction protection period for newly compiled patterns.
     *
     * <p><b>Default: 1000ms (1 second)</b>
     *
     * <p>Prevents race condition where pattern is compiled then immediately evicted before caller
     * can use it. Newly compiled patterns protected for this duration.
     *
     * <p>Set to 0 to disable (not recommended unless you understand the race condition).
     *
     * @param ms protection period in milliseconds (must be ≥ 0)
     * @return this builder
     */
    public Builder evictionProtectionMs(long ms) {
      this.evictionProtectionMs = ms;
      return this;
    }

    /**
     * Set maximum simultaneous ACTIVE compiled patterns.
     *
     * <p><b>Default: 100,000</b>
     *
     * <p><b>IMPORTANT:</b> This is ACTIVE (simultaneous) count, NOT cumulative. Patterns can be
     * freed and recompiled - this limit prevents unbounded memory growth.
     *
     * <p>Increase if hitting {@code errors.resource.exhausted} metric.
     *
     * <p>This is a safety limit, not a performance tuning parameter.
     *
     * @param max maximum active patterns (must be > 0, must be ≥ maxCacheSize)
     * @return this builder
     */
    public Builder maxSimultaneousCompiledPatterns(int max) {
      this.maxSimultaneousCompiledPatterns = max;
      return this;
    }

    /**
     * Set maximum matchers per pattern.
     *
     * <p><b>Default: 10,000</b>
     *
     * <p>Prevents single pattern from exhausting matcher resources. Rarely needs tuning unless you
     * have extremely high concurrent matching on one pattern.
     *
     * @param max maximum matchers per pattern (must be > 0)
     * @return this builder
     */
    public Builder maxMatchersPerPattern(int max) {
      this.maxMatchersPerPattern = max;
      return this;
    }

    /**
     * Enable or disable validation of cached patterns.
     *
     * <p><b>Default: enabled (true)</b>
     *
     * <p>Performs defensive check for native pointer validity on cache retrieval. Tiny overhead (~1
     * JNI call), provides crash safety against native memory corruption.
     *
     * <p>Only disable if absolute maximum performance required.
     *
     * @param validate true to validate (default), false to skip validation
     * @return this builder
     */
    public Builder validateCachedPatterns(boolean validate) {
      this.validateCachedPatterns = validate;
      return this;
    }

    /**
     * Set metrics registry for instrumentation.
     *
     * <p><b>Default: {@link com.axonops.libre2.metrics.NoOpMetricsRegistry} (zero overhead)</b>
     *
     * <p>Use {@link com.axonops.libre2.metrics.DropwizardMetricsAdapter} for Dropwizard Metrics
     * integration. See {@link com.axonops.libre2.metrics.MetricNames} for available metrics.
     *
     * <p>Example:
     *
     * <pre>{@code
     * MetricRegistry registry = new MetricRegistry();
     * RE2Config config = RE2Config.builder()
     *     .metricsRegistry(new DropwizardMetricsAdapter(registry, "myapp.re2"))
     *     .build();
     * }</pre>
     *
     * @param metricsRegistry metrics implementation (must not be null)
     * @return this builder
     * @throws NullPointerException if metricsRegistry is null
     */
    public Builder metricsRegistry(RE2MetricsRegistry metricsRegistry) {
      this.metricsRegistry =
          Objects.requireNonNull(metricsRegistry, "metricsRegistry cannot be null");
      return this;
    }

    /**
     * Build immutable configuration.
     *
     * <p>Validates all parameters and their relationships. Throws {@link IllegalArgumentException}
     * if configuration is invalid.
     *
     * @return validated immutable configuration
     * @throws IllegalArgumentException if configuration is invalid
     */
    public RE2Config build() {
      return new RE2Config(
          cacheEnabled,
          maxCacheSize,
          idleTimeoutSeconds,
          evictionScanIntervalSeconds,
          deferredCleanupIntervalSeconds,
          evictionProtectionMs,
          maxSimultaneousCompiledPatterns,
          maxMatchersPerPattern,
          validateCachedPatterns,
          metricsRegistry);
    }
  }
}
