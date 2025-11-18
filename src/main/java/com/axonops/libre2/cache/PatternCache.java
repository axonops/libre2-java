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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * High-performance thread-safe cache for compiled patterns with dual eviction.
 *
 * Eviction strategies:
 * 1. LRU (soft limit): When cache exceeds max size, async evict least recently used
 * 2. Idle time: Background thread evicts patterns idle beyond timeout
 *
 * Performance characteristics:
 * - Lock-free cache reads (ConcurrentHashMap)
 * - Lock-free timestamp updates (AtomicLong)
 * - Non-blocking eviction (async LRU, concurrent idle scan)
 * - Soft limits: cache can temporarily exceed maxSize by ~10%
 *
 * @since 1.0.0
 */
public final class PatternCache {
    private static final Logger logger = LoggerFactory.getLogger(PatternCache.class);

    private final RE2Config config;

    public RE2Config getConfig() {
        return config;
    }

    // ConcurrentHashMap for lock-free reads/writes
    private final ConcurrentHashMap<CacheKey, CachedPattern> cache;
    private final IdleEvictionTask evictionTask;

    // Single-thread executor for async LRU eviction (doesn't block cache access)
    private final ExecutorService lruEvictionExecutor;

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

    // Invalid pattern recompilations (defensive check triggered)
    private final AtomicLong invalidPatternRecompilations = new AtomicLong(0);

    public PatternCache(RE2Config config) {
        this.config = config;

        if (config.cacheEnabled()) {
            // ConcurrentHashMap for lock-free concurrent access
            this.cache = new ConcurrentHashMap<>(config.maxCacheSize());

            // Single-thread executor for async LRU eviction
            this.lruEvictionExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "RE2-LRU-Eviction");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });

            // Start idle eviction background thread
            this.evictionTask = new IdleEvictionTask(this, config);
            this.evictionTask.start();

            // Register shutdown hook for graceful cleanup
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("RE2: Shutdown hook triggered - cleaning up cache");
                shutdown();
            }, "RE2-Shutdown"));

            logger.info("RE2: Pattern cache initialized - maxSize: {}, idleTimeout: {}s, scanInterval: {}s, deferredCleanup: every {}s",
                config.maxCacheSize(),
                config.idleTimeoutSeconds(),
                config.evictionScanIntervalSeconds(),
                config.deferredCleanupIntervalSeconds());
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
     * Lock-free for cache hits. Only compiles patterns when necessary.
     * Uses computeIfAbsent for safe concurrent compilation.
     *
     * @param patternString regex pattern
     * @param caseSensitive case sensitivity flag
     * @param compiler function to compile pattern on cache miss
     * @return cached or newly compiled pattern
     */
    public Pattern getOrCompile(String patternString, boolean caseSensitive,
                                 java.util.function.Supplier<Pattern> compiler) {
        if (!config.cacheEnabled()) {
            misses.incrementAndGet();
            return compiler.get();
        }

        CacheKey key = new CacheKey(patternString, caseSensitive);

        // Try to get existing pattern (lock-free read)
        CachedPattern cached = cache.get(key);
        if (cached != null) {
            // Optional defensive check: validate native pointer is still valid
            if (config.validateCachedPatterns() && !cached.pattern().isValid()) {
                logger.warn("RE2: Invalid pattern detected in cache (recompiling): {}", key);
                invalidPatternRecompilations.incrementAndGet();
                // Remove invalid pattern and decrement memory
                if (cache.remove(key, cached)) {
                    totalNativeMemoryBytes.addAndGet(-cached.memoryBytes());
                }
                // Fall through to recompile below
            } else {
                // Cache hit - update access time atomically
                cached.touch();
                hits.incrementAndGet();
                logger.debug("RE2: Cache hit - pattern: {}", key);
                return cached.pattern();
            }
        }

        // Cache miss - use computeIfAbsent for safe concurrent compilation
        // Only ONE thread compiles each unique pattern
        misses.incrementAndGet();
        logger.debug("RE2: Cache miss - compiling pattern: {}", key);

        // Track whether this thread compiled a new pattern
        final long[] addedMemory = {0};

        CachedPattern newCached = cache.computeIfAbsent(key, k -> {
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
     * Soft limit approach: cache can temporarily exceed maxSize
     * while eviction runs in background.
     */
    private void triggerAsyncLRUEviction(int toEvict) {
        if (toEvict <= 0) return;

        lruEvictionExecutor.submit(() -> {
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
     * Uses sample-based LRU: samples subset of cache and evicts oldest.
     * Much faster than scanning entire cache.
     */
    private void evictLRUBatch(int toEvict) {
        int actualToEvict = Math.min(toEvict, cache.size() - config.maxCacheSize());
        if (actualToEvict <= 0) return;

        // Sample-based LRU: sample a subset and evict oldest
        // This is O(sample size) not O(cache size)
        int sampleSize = Math.min(500, cache.size());

        List<Map.Entry<CacheKey, CachedPattern>> candidates = cache.entrySet()
            .stream()
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
                    logger.debug("RE2: LRU evicting pattern (deferred - {} active matchers): {}",
                        cached.pattern().getRefCount(), entry.getKey());
                } else {
                    // Safe to free immediately
                    cached.forceClose();
                    evictionsLRU.incrementAndGet();
                    logger.debug("RE2: LRU evicting pattern (immediate): {}", entry.getKey());
                }
                evicted++;
            }
        }

        if (evicted > 0) {
            logger.debug("RE2: Async LRU eviction completed - evicted: {}", evicted);
        }
    }

    /**
     * Evicts idle patterns (called by background thread).
     *
     * Non-blocking: uses ConcurrentHashMap iteration which doesn't block
     * other threads accessing the cache.
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
        cache.entrySet().removeIf(entry -> {
            CachedPattern cached = entry.getValue();

            if (cached.lastAccessTimeNanos() < cutoffNanos) {
                // Decrement memory tracking (pattern removed from cache)
                totalNativeMemoryBytes.addAndGet(-cached.memoryBytes());

                if (cached.pattern().getRefCount() > 0) {
                    // Pattern idle but still in use - defer cleanup
                    logger.debug("RE2: Idle evicting pattern (deferred - {} active matchers): {}",
                        cached.pattern().getRefCount(), entry.getKey());
                    deferredCleanup.add(cached);
                    evictionsDeferred.incrementAndGet();
                } else {
                    // Can free immediately
                    logger.debug("RE2: Idle evicting pattern (immediate): {}", entry.getKey());
                    cached.forceClose();
                    evictionsIdle.incrementAndGet();
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
            logger.info("RE2: Eviction completed - idle evicted: {}, deferred cleaned: {}", evicted, deferredCleaned);
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
                logger.debug("RE2: Cleaning up deferred pattern");
                deferred.forceClose();
                deferredCleanup.remove(deferred);
                evictionsLRU.incrementAndGet();
                cleaned++;
            }
        }

        return cleaned;
    }

    /**
     * Gets cache statistics snapshot.
     */
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
            invalidPatternRecompilations.get()
        );
    }

    /**
     * Clears the cache and closes all cached patterns.
     */
    public void clear() {
        if (!config.cacheEnabled()) {
            return;
        }

        int cacheSize = cache.size();
        int deferredSize = deferredCleanup.size();

        logger.info("RE2: Clearing cache - {} cached patterns, {} deferred patterns",
            cacheSize, deferredSize);

        // Close and remove all cached patterns
        cache.forEach((key, cached) -> {
            cached.forceClose();
        });
        cache.clear();

        // Close all deferred patterns
        for (CachedPattern deferred : deferredCleanup) {
            deferred.forceClose();
        }
        deferredCleanup.clear();

        // Reset memory tracking (all patterns removed)
        totalNativeMemoryBytes.set(0);
    }

    /**
     * Resets cache statistics (for testing only).
     */
    public void resetStatistics() {
        hits.set(0);
        misses.set(0);
        evictionsLRU.set(0);
        evictionsIdle.set(0);
        evictionsDeferred.set(0);
        peakNativeMemoryBytes.set(totalNativeMemoryBytes.get());
        invalidPatternRecompilations.set(0);
        logger.debug("RE2: Cache statistics reset");
    }

    /**
     * Full reset for testing (clears cache and resets statistics).
     */
    public void reset() {
        clear();
        resetStatistics();
        com.axonops.libre2.util.ResourceTracker.reset();
        logger.debug("RE2: Cache fully reset");
    }

    /**
     * Shuts down the cache (stops eviction thread, clears cache).
     */
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
     * Cache key combining pattern string and case-sensitivity.
     */
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
     * Uses nanoTime for efficient timestamp comparison without object allocation.
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

    /**
     * Updates peak memory if current total exceeds it.
     */
    private void updatePeakMemory() {
        long current = totalNativeMemoryBytes.get();
        long peak;
        do {
            peak = peakNativeMemoryBytes.get();
        } while (current > peak && !peakNativeMemoryBytes.compareAndSet(peak, current));
    }
}
