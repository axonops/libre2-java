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

import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.metrics.MetricNames;
import com.axonops.libre2.metrics.RE2MetricsRegistry;
import com.axonops.libre2.util.PatternHasher;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-performance thread-safe cache for compiled patterns with dual eviction.
 *
 * <p>Eviction strategies: 1. LRU (soft limit): When cache exceeds max size, async evict least
 * recently used 2. Idle time: Background thread evicts patterns idle beyond timeout
 *
 * <p>Performance characteristics: - Lock-free cache reads (ConcurrentHashMap) - Lock-free timestamp
 * updates (AtomicLong) - Non-blocking eviction (async LRU, concurrent idle scan) - Soft limits:
 * cache can temporarily exceed maxSize by ~10%
 *
 * @since 1.0.0
 */
public final class PatternCache {
  private static final Logger logger = LoggerFactory.getLogger(PatternCache.class);

  private volatile RE2Config config;
  private final com.axonops.libre2.util.ResourceTracker resourceTracker;

  public RE2Config getConfig() {
    return config;
  }

  public com.axonops.libre2.util.ResourceTracker getResourceTracker() {
    return resourceTracker;
  }

  // ConcurrentHashMap for lock-free reads/writes
  private ConcurrentHashMap<CacheKey, CachedPattern> cache;
  private IdleEvictionTask evictionTask;

  // Single-thread executor for async LRU eviction (doesn't block cache access)
  private ExecutorService lruEvictionExecutor;

  // Deferred cleanup: Patterns evicted from cache but still in use (refCount > 0)
  private final CopyOnWriteArrayList<CachedPattern> deferredCleanup = new CopyOnWriteArrayList<>();

  // Statistics (all atomic, lock-free)
  private final AtomicLong hits = new AtomicLong(0);
  private final AtomicLong misses = new AtomicLong(0);
  private final AtomicLong evictionsLRU = new AtomicLong(0);
  private final AtomicLong evictionsIdle = new AtomicLong(0);
  private final AtomicLong evictionsDeferred = new AtomicLong(0);

  // Native memory tracking (off-heap memory consumed by patterns)
  private final AtomicLong totalNativeMemoryBytes = new AtomicLong(0);
  private final AtomicLong peakNativeMemoryBytes = new AtomicLong(0);

  // Deferred cleanup tracking
  private final AtomicLong deferredNativeMemoryBytes = new AtomicLong(0);
  private final AtomicLong peakDeferredNativeMemoryBytes = new AtomicLong(0);
  private final AtomicInteger peakDeferredPatternCount = new AtomicInteger(0);

  // Invalid pattern recompilations (defensive check triggered)
  private final AtomicLong invalidPatternRecompilations = new AtomicLong(0);

  /**
   * Creates a new pattern cache with the given configuration.
   *
   * @param config the cache configuration
   */
  public PatternCache(RE2Config config) {
    this.config = config;
    this.resourceTracker = new com.axonops.libre2.util.ResourceTracker();

    if (config.cacheEnabled()) {
      // ConcurrentHashMap for lock-free concurrent access
      this.cache = new ConcurrentHashMap<>(config.maxCacheSize());

      // Single-thread executor for async LRU eviction
      this.lruEvictionExecutor =
          Executors.newSingleThreadExecutor(
              r -> {
                Thread t = new Thread(r, "RE2-LRU-Eviction");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
              });

      // Start idle eviction background thread
      this.evictionTask = new IdleEvictionTask(this, config);
      this.evictionTask.start();

      // Note: Shutdown hooks not registered to avoid test interference.
      // Tests create multiple cache instances and explicit cleanup in tearDown.
      // Production applications should call shutdown() explicitly or rely on
      // try-with-resources if cache lifecycle is scoped.

      logger.debug(
          "RE2: Pattern cache initialized - maxSize: {}, idleTimeout: {}s, scanInterval: {}s, deferredCleanup: every {}s",
          config.maxCacheSize(),
          config.idleTimeoutSeconds(),
          config.evictionScanIntervalSeconds(),
          config.deferredCleanupIntervalSeconds());

      // Register cache metrics (gauges)
      registerCacheMetrics();
    } else {
      this.cache = null;
      this.evictionTask = null;
      this.lruEvictionExecutor = null;
      logger.info("RE2: Pattern caching disabled");
    }
  }

  /**
   * Gets or compiles a pattern.
   *
   * <p>Lock-free for cache hits. Only compiles patterns when necessary. Uses computeIfAbsent for
   * safe concurrent compilation.
   *
   * @param patternString regex pattern
   * @param caseSensitive case sensitivity flag
   * @param compiler function to compile pattern on cache miss
   * @return cached or newly compiled pattern
   */
  public Pattern getOrCompile(
      String patternString, boolean caseSensitive, java.util.function.Supplier<Pattern> compiler) {
    RE2MetricsRegistry metrics = config.metricsRegistry();

    if (!config.cacheEnabled()) {
      misses.incrementAndGet();
      metrics.incrementCounter(MetricNames.PATTERNS_CACHE_MISSES);
      return compiler.get();
    }

    CacheKey key = new CacheKey(patternString, caseSensitive);

    // Try to get existing pattern (lock-free read)
    CachedPattern cached = cache.get(key);
    if (cached != null) {
      // Optional defensive check: validate native pointer is still valid
      if (config.validateCachedPatterns() && !cached.pattern().isValid()) {
        String hash = PatternHasher.hash(patternString);
        logger.warn("RE2: Invalid cached pattern detected - hash: {}, recompiling", hash);
        invalidPatternRecompilations.incrementAndGet();
        metrics.incrementCounter(MetricNames.PATTERNS_INVALID_RECOMPILED);
        // Remove invalid pattern and decrement memory
        if (cache.remove(key, cached)) {
          totalNativeMemoryBytes.addAndGet(-cached.memoryBytes());
        }
        // Fall through to recompile below
      } else {
        // Cache hit - update access time atomically
        cached.touch();
        hits.incrementAndGet();
        metrics.incrementCounter(MetricNames.PATTERNS_CACHE_HITS);
        logger.trace("RE2: Cache hit - hash: {}", PatternHasher.hash(patternString));
        return cached.pattern();
      }
    }

    // Cache miss - use computeIfAbsent for safe concurrent compilation
    // Only ONE thread compiles each unique pattern
    misses.incrementAndGet();
    metrics.incrementCounter(MetricNames.PATTERNS_CACHE_MISSES);
    logger.trace("RE2: Cache miss - hash: {}, compiling", PatternHasher.hash(patternString));

    // Track whether this thread compiled a new pattern
    final long[] addedMemory = {0};

    CachedPattern newCached =
        cache.computeIfAbsent(
            key,
            k -> {
              // This lambda executes atomically for this key only
              // Other keys can be accessed concurrently
              Pattern pattern = compiler.get();
              CachedPattern created = new CachedPattern(pattern);
              addedMemory[0] = created.memoryBytes();
              return created;
            });

    // If we compiled a new pattern, update memory tracking
    if (addedMemory[0] > 0) {
      totalNativeMemoryBytes.addAndGet(addedMemory[0]);
      updatePeakMemory();
    }

    // If we just added a new pattern, check if we need async LRU eviction
    // Soft limit: trigger eviction if over, but don't block
    int currentSize = cache.size();
    if (currentSize > config.maxCacheSize()) {
      triggerAsyncLRUEviction(currentSize - config.maxCacheSize());
    }

    return newCached.pattern();
  }

  /**
   * Triggers async LRU eviction (doesn't block caller).
   *
   * <p>Soft limit approach: cache can temporarily exceed maxSize while eviction runs in background.
   */
  private void triggerAsyncLRUEviction(int toEvict) {
    if (toEvict <= 0) {
      return;
    }

    lruEvictionExecutor.submit(
        () -> {
          try {
            evictLRUBatch(toEvict);
          } catch (Exception e) {
            logger.warn("RE2: Error during async LRU eviction", e);
          }
        });
  }

  /**
   * Evicts least-recently-used patterns.
   *
   * <p>Uses sample-based LRU: samples subset of cache and evicts oldest. Much faster than scanning
   * entire cache.
   *
   * <p>IMPORTANT: Patterns accessed within the last 100ms are protected from eviction to prevent
   * race conditions where a pattern is evicted before the caller can use it.
   */
  private void evictLRUBatch(int toEvict) {
    int actualToEvict = Math.min(toEvict, cache.size() - config.maxCacheSize());
    if (actualToEvict <= 0) {
      return;
    }

    // Sample-based LRU: sample a subset and evict oldest
    // This is O(sample size) not O(cache size)
    int sampleSize = Math.min(500, cache.size());

    // Minimum age before a pattern can be evicted (configurable, default 1 second)
    // This prevents evicting patterns before the caller has a chance to use them
    long minAgeNanos = config.evictionProtectionMs() * 1_000_000L;
    long cutoffTime = System.nanoTime() - minAgeNanos;

    List<Map.Entry<CacheKey, CachedPattern>> candidates =
        cache.entrySet().stream()
            .filter(
                e -> e.getValue().lastAccessTimeNanos() < cutoffTime) // Only evict "old" patterns
            .limit(sampleSize)
            .sorted(Comparator.comparingLong(e -> e.getValue().lastAccessTimeNanos()))
            .limit(actualToEvict)
            .collect(Collectors.toList());

    int evicted = 0;
    for (Map.Entry<CacheKey, CachedPattern> entry : candidates) {
      CachedPattern cached = entry.getValue();

      // Only evict if we successfully remove from map
      if (cache.remove(entry.getKey(), cached)) {
        // Decrement memory tracking (pattern removed from cache)
        totalNativeMemoryBytes.addAndGet(-cached.memoryBytes());

        if (cached.pattern().getRefCount() > 0) {
          // Pattern in use - defer cleanup
          deferredCleanup.add(cached);
          evictionsDeferred.incrementAndGet();

          // Track deferred memory
          long deferredMemory = deferredNativeMemoryBytes.addAndGet(cached.memoryBytes());
          updatePeakDeferredMemory(deferredMemory);

          logger.trace(
              "RE2: LRU evicting pattern (deferred - {} active matchers): {}",
              cached.pattern().getRefCount(),
              entry.getKey());
        } else {
          // Safe to free immediately
          cached.forceClose();
          evictionsLRU.incrementAndGet();
          config.metricsRegistry().incrementCounter(MetricNames.CACHE_EVICTIONS_LRU);
          logger.trace("RE2: LRU evicting pattern (immediate): {}", entry.getKey());
        }
        evicted++;
      }
    }

    if (evicted > 0) {
      logger.debug(
          "RE2: LRU eviction completed - evicted: {}, cacheSize: {}/{}",
          evicted,
          cache.size(),
          config.maxCacheSize());
    }
  }

  /**
   * Evicts idle patterns (called by background thread).
   *
   * <p>Non-blocking: uses ConcurrentHashMap iteration which doesn't block other threads accessing
   * the cache.
   *
   * @return number of patterns evicted
   */
  int evictIdlePatterns() {
    if (!config.cacheEnabled()) {
      return 0;
    }

    long cutoffNanos = System.nanoTime() - (config.idleTimeoutSeconds() * 1_000_000_000L);
    AtomicLong evictedCount = new AtomicLong(0);

    // Non-blocking iteration - other threads can access cache concurrently
    cache
        .entrySet()
        .removeIf(
            entry -> {
              CachedPattern cached = entry.getValue();

              if (cached.lastAccessTimeNanos() < cutoffNanos) {
                // Decrement memory tracking (pattern removed from cache)
                totalNativeMemoryBytes.addAndGet(-cached.memoryBytes());

                if (cached.pattern().getRefCount() > 0) {
                  // Pattern idle but still in use - defer cleanup
                  deferredCleanup.add(cached);
                  evictionsDeferred.incrementAndGet();

                  // Track deferred memory
                  long deferredMemory = deferredNativeMemoryBytes.addAndGet(cached.memoryBytes());
                  updatePeakDeferredMemory(deferredMemory);

                  // Track deferred pattern count peak
                  int deferredCount = deferredCleanup.size();
                  updatePeakDeferredPatternCount(deferredCount);

                  logger.trace(
                      "RE2: Idle evicting pattern (deferred - {} active matchers): {}",
                      cached.pattern().getRefCount(),
                      entry.getKey());
                } else {
                  // Can free immediately
                  logger.trace("RE2: Idle evicting pattern (immediate): {}", entry.getKey());
                  cached.forceClose();
                  evictionsIdle.incrementAndGet();
                  config.metricsRegistry().incrementCounter(MetricNames.CACHE_EVICTIONS_IDLE);
                }
                evictedCount.incrementAndGet();
                return true; // Remove from map
              }
              return false; // Keep in map
            });

    // Clean up deferred patterns
    int deferredCleaned = cleanupDeferredPatterns();

    int evicted = (int) evictedCount.get();
    if (evicted > 0 || deferredCleaned > 0) {
      logger.debug(
          "RE2: Idle eviction completed - evicted: {}, deferred cleaned: {}, cacheSize: {}",
          evicted,
          deferredCleaned,
          cache.size());
    }

    return evicted;
  }

  /**
   * Cleans up deferred patterns that are no longer in use.
   *
   * @return number of patterns cleaned
   */
  int cleanupDeferredPatterns() {
    int cleaned = 0;

    for (CachedPattern deferred : deferredCleanup) {
      if (deferred.pattern().getRefCount() == 0) {
        // Now safe to free
        logger.trace("RE2: Cleaning up deferred pattern");
        deferred.forceClose();
        deferredCleanup.remove(deferred);

        // Decrement deferred memory tracking
        deferredNativeMemoryBytes.addAndGet(-deferred.memoryBytes());

        // Note: evictionsDeferred already incremented when added to deferred list
        config.metricsRegistry().incrementCounter(MetricNames.CACHE_EVICTIONS_DEFERRED);
        cleaned++;
      }
    }

    if (cleaned > 0) {
      logger.trace("RE2: Deferred cleanup completed - freed: {}", cleaned);
    }

    return cleaned;
  }

  /** Gets current cache hit rate as percentage. */
  private double getCacheHitRate() {
    long totalRequests = hits.get() + misses.get();
    if (totalRequests == 0) {
      return 0.0;
    }
    return (hits.get() * 100.0) / totalRequests;
  }

  /** Gets cache statistics snapshot. */
  public CacheStatistics getStatistics() {
    int currentSize = config.cacheEnabled() ? cache.size() : 0;
    int deferredSize = deferredCleanup.size();

    return new CacheStatistics(
        hits.get(),
        misses.get(),
        evictionsLRU.get(),
        evictionsIdle.get(),
        evictionsDeferred.get(),
        currentSize,
        config.maxCacheSize(),
        deferredSize,
        totalNativeMemoryBytes.get(),
        peakNativeMemoryBytes.get(),
        invalidPatternRecompilations.get());
  }

  /**
   * Clears the cache and closes all cached patterns.
   *
   * <p>Patterns with active matchers (refCount > 0) are moved to deferred list instead of being
   * forcibly closed.
   */
  public void clear() {
    if (!config.cacheEnabled()) {
      return;
    }

    int cacheSize = cache.size();
    int deferredSize = deferredCleanup.size();

    logger.debug(
        "RE2: Clearing cache - {} cached patterns, {} deferred patterns", cacheSize, deferredSize);

    // Close and remove all cached patterns
    // Patterns with active matchers go to deferred list
    cache.forEach(
        (key, cached) -> {
          if (cached.pattern().getRefCount() > 0) {
            // Pattern still in use - move to deferred list instead of closing
            deferredCleanup.add(cached);
            logger.trace(
                "RE2: Clearing cache - pattern still in use, moving to deferred: refCount={}",
                cached.pattern().getRefCount());
          } else {
            // Safe to close immediately
            cached.forceClose();
          }
        });
    cache.clear();

    // Close deferred patterns that are no longer in use
    deferredCleanup.removeIf(
        deferred -> {
          if (deferred.pattern().getRefCount() == 0) {
            deferred.forceClose();
            return true; // Remove from list
          }
          return false; // Keep in list
        });

    // Reset memory tracking (all non-deferred patterns removed)
    totalNativeMemoryBytes.set(0);
    // Note: deferred memory is tracked separately
  }

  /** Resets cache statistics (for testing only). */
  public void resetStatistics() {
    hits.set(0);
    misses.set(0);
    evictionsLRU.set(0);
    evictionsIdle.set(0);
    evictionsDeferred.set(0);
    peakNativeMemoryBytes.set(totalNativeMemoryBytes.get());
    invalidPatternRecompilations.set(0);
    logger.trace("RE2: Cache statistics reset");
  }

  /**
   * Full reset for testing (clears cache and resets statistics).
   *
   * <p>Only resets ResourceTracker if no deferred patterns remain. Deferred patterns will be freed
   * later and need correct tracking.
   */
  public void reset() {
    clear();
    resetStatistics();

    // Only reset ResourceTracker if deferred list is empty
    // Deferred patterns will be freed later and call trackPatternFreed()
    if (deferredCleanup.isEmpty()) {
      resourceTracker.reset();
      logger.trace("RE2: Cache fully reset (including ResourceTracker)");
    } else {
      logger.debug(
          "RE2: Cache reset but ResourceTracker NOT reset - {} deferred patterns will be freed later",
          deferredCleanup.size());
    }
  }

  /**
   * Reconfigures the cache with new settings (for testing only).
   *
   * <p>This clears the existing cache and reinitializes with the new config.
   *
   * @param newConfig the new configuration
   */
  public synchronized void reconfigure(RE2Config newConfig) {
    logger.info("RE2: Reconfiguring cache with new settings");

    // Stop existing eviction task
    if (evictionTask != null) {
      evictionTask.stop();
    }

    // Shutdown existing LRU executor
    if (lruEvictionExecutor != null) {
      lruEvictionExecutor.shutdown();
    }

    // Clear existing cache
    clear();
    resetStatistics();
    resourceTracker.reset();

    // Update config
    this.config = newConfig;

    // Reinitialize if cache enabled
    if (newConfig.cacheEnabled()) {
      this.cache = new ConcurrentHashMap<>(newConfig.maxCacheSize());

      this.lruEvictionExecutor =
          Executors.newSingleThreadExecutor(
              r -> {
                Thread t = new Thread(r, "RE2-LRU-Eviction");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
              });

      this.evictionTask = new IdleEvictionTask(this, newConfig);
      this.evictionTask.start();

      logger.info(
          "RE2: Cache reconfigured - maxSize: {}, idleTimeout: {}s, maxSimultaneousPatterns: {}",
          newConfig.maxCacheSize(),
          newConfig.idleTimeoutSeconds(),
          newConfig.maxSimultaneousCompiledPatterns());
    } else {
      this.cache = null;
      this.evictionTask = null;
      this.lruEvictionExecutor = null;
      logger.info("RE2: Cache disabled after reconfiguration");
    }
  }

  /** Shuts down the cache (stops eviction thread, clears cache). */
  public void shutdown() {
    logger.info("RE2: Shutting down cache");

    if (evictionTask != null) {
      evictionTask.stop();
    }

    if (lruEvictionExecutor != null) {
      lruEvictionExecutor.shutdown();
    }

    clear();
  }

  /**
   * Registers cache and resource metrics (gauges) with the metrics registry. Called during cache
   * initialization.
   */
  private void registerCacheMetrics() {
    RE2MetricsRegistry metrics = config.metricsRegistry();

    // Cache metrics
    metrics.registerGauge("cache.patterns.current.count", () -> cache != null ? cache.size() : 0);
    metrics.registerGauge("cache.native_memory.current.bytes", totalNativeMemoryBytes::get);
    metrics.registerGauge("cache.native_memory.peak.bytes", peakNativeMemoryBytes::get);

    // Resource management metrics (active counts only - freed counts are incremented directly)
    metrics.registerGauge(
        "resources.patterns.active.current.count", resourceTracker::getActivePatternCount);
    metrics.registerGauge(
        "resources.matchers.active.current.count", resourceTracker::getActiveMatcherCount);
    // Note: resources.patterns.freed.total.count and resources.matchers.freed.total.count
    // are Counters incremented in ResourceTracker.trackPatternFreed() and trackMatcherFreed()

    // Deferred cleanup metrics (current state)
    metrics.registerGauge("cache.deferred.patterns.current.count", deferredCleanup::size);
    metrics.registerGauge("cache.deferred.patterns.peak.count", peakDeferredPatternCount::get);
    metrics.registerGauge(
        "cache.deferred.native_memory.current.bytes", deferredNativeMemoryBytes::get);
    metrics.registerGauge(
        "cache.deferred.native_memory.peak.bytes", peakDeferredNativeMemoryBytes::get);

    logger.debug("RE2: Metrics registered - cache gauges, resource gauges, deferred gauges");
  }

  /** Cache key combining pattern string and case-sensitivity. */
  private record CacheKey(String pattern, boolean caseSensitive) {
    @Override
    public String toString() {
      return pattern.length() > 50
          ? pattern.substring(0, 47) + "... (case=" + caseSensitive + ")"
          : pattern + " (case=" + caseSensitive + ")";
    }
  }

  /**
   * Cached pattern with atomic access time tracking.
   *
   * <p>Uses nanoTime for efficient timestamp comparison without object allocation.
   */
  private static class CachedPattern {
    private final Pattern pattern;
    private final AtomicLong lastAccessTimeNanos;
    private final long memoryBytes;

    CachedPattern(Pattern pattern) {
      this.pattern = pattern;
      this.lastAccessTimeNanos = new AtomicLong(System.nanoTime());
      this.memoryBytes = pattern.getNativeMemoryBytes();
    }

    Pattern pattern() {
      return pattern;
    }

    long lastAccessTimeNanos() {
      return lastAccessTimeNanos.get();
    }

    long memoryBytes() {
      return memoryBytes;
    }

    void touch() {
      lastAccessTimeNanos.set(System.nanoTime());
    }

    void forceClose() {
      pattern.forceClose();
    }
  }

  /** Updates peak memory if current total exceeds it. */
  private void updatePeakMemory() {
    long current = totalNativeMemoryBytes.get();
    long peak;
    do {
      peak = peakNativeMemoryBytes.get();
    } while (current > peak && !peakNativeMemoryBytes.compareAndSet(peak, current));
  }

  /** Updates peak deferred memory if current exceeds it. */
  private void updatePeakDeferredMemory(long current) {
    long peak;
    do {
      peak = peakDeferredNativeMemoryBytes.get();
    } while (current > peak && !peakDeferredNativeMemoryBytes.compareAndSet(peak, current));
  }

  /** Updates peak deferred pattern count if current exceeds it. */
  private void updatePeakDeferredPatternCount(int current) {
    int peak;
    do {
      peak = peakDeferredPatternCount.get();
    } while (current > peak && !peakDeferredPatternCount.compareAndSet(peak, current));
  }
}
