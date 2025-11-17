package com.axonops.libre2.cache;

import com.axonops.libre2.api.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe LRU cache for compiled patterns with dual eviction.
 *
 * Eviction strategies:
 * 1. LRU: When cache reaches max size, evict least recently used
 * 2. Idle time: Background thread evicts patterns idle beyond timeout
 *
 * Thread-safe for concurrent access.
 *
 * @since 1.0.0
 */
public final class PatternCache {
    private static final Logger logger = LoggerFactory.getLogger(PatternCache.class);

    private final RE2Config config;

    public RE2Config getConfig() {
        return config;
    }
    private final Map<CacheKey, CachedPattern> cache;
    private final IdleEvictionTask evictionTask;

    // Deferred cleanup: Patterns evicted from cache but still in use (refCount > 0)
    // These will be freed when refCount reaches 0
    private final java.util.List<CachedPattern> deferredCleanup = new java.util.concurrent.CopyOnWriteArrayList<>();

    // Statistics
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictionsLRU = new AtomicLong(0);
    private final AtomicLong evictionsIdle = new AtomicLong(0);
    private final AtomicLong evictionsDeferred = new AtomicLong(0);

    public PatternCache(RE2Config config) {
        this.config = config;

        if (config.cacheEnabled()) {
            // LinkedHashMap in access-order mode for LRU
            this.cache = new LinkedHashMap<>(config.maxCacheSize(), 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<CacheKey, CachedPattern> eldest) {
                    if (size() > config.maxCacheSize()) {
                        CachedPattern evicted = eldest.getValue();

                        // Check if pattern can be freed immediately
                        if (evicted.pattern().getRefCount() > 0) {
                            // Pattern still in use - defer cleanup
                            logger.debug("RE2: LRU evicting pattern (deferred cleanup - {} active matchers): {}",
                                evicted.pattern().getRefCount(), eldest.getKey());
                            deferredCleanup.add(evicted);
                            evictionsDeferred.incrementAndGet();
                        } else {
                            // Can free immediately
                            logger.debug("RE2: LRU evicting pattern (immediate): {}", eldest.getKey());
                            evicted.forceClose();
                            evictionsLRU.incrementAndGet();
                        }

                        return true; // Remove from cache either way
                    }
                    return false;
                }
            };

            // Start idle eviction background thread
            this.evictionTask = new IdleEvictionTask(this, config);
            this.evictionTask.start();

            logger.info("RE2: Pattern cache initialized - maxSize: {}, idleTimeout: {}s, scanInterval: {}s",
                config.maxCacheSize(),
                config.idleTimeoutSeconds(),
                config.evictionScanIntervalSeconds());
        } else {
            this.cache = null;
            this.evictionTask = null;
            logger.info("RE2: Pattern caching disabled");
        }
    }

    /**
     * Gets or compiles a pattern.
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

        synchronized (cache) {
            CachedPattern cached = cache.get(key);

            if (cached != null) {
                // Cache hit - update access time
                cached.updateAccessTime();
                hits.incrementAndGet();
                logger.debug("RE2: Cache hit - pattern: {}", key);
                return cached.pattern();
            }

            // Cache miss - compile and cache
            misses.incrementAndGet();
            logger.debug("RE2: Cache miss - compiling pattern: {}", key);

            Pattern pattern = compiler.get();
            cache.put(key, new CachedPattern(pattern));

            // LRU eviction happens automatically in LinkedHashMap.put()
            // If eldest pattern has refCount > 0, it goes to deferred cleanup list
            // Eventually freed when refCount reaches 0

            return pattern;
        }
    }

    /**
     * Evicts idle patterns (called by background thread).
     *
     * Also cleans up deferred patterns that are now safe to free.
     *
     * @return number of patterns evicted
     */
    int evictIdlePatterns() {
        if (!config.cacheEnabled()) {
            return 0;
        }

        Instant cutoff = Instant.now().minusSeconds(config.idleTimeoutSeconds());
        int evicted = 0;

        synchronized (cache) {
            var iterator = cache.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                CachedPattern cached = entry.getValue();

                if (cached.lastAccessTime().isBefore(cutoff)) {
                    if (cached.pattern().getRefCount() > 0) {
                        // Pattern idle but still in use - defer cleanup
                        logger.debug("RE2: Idle evicting pattern (deferred - {} active matchers): {}",
                            cached.pattern().getRefCount(), entry.getKey());
                        deferredCleanup.add(cached);
                        iterator.remove();
                        evictionsDeferred.incrementAndGet();
                        evicted++;
                    } else {
                        // Can free immediately
                        logger.debug("RE2: Idle evicting pattern (immediate): {}", entry.getKey());
                        cached.forceClose();
                        iterator.remove();
                        evictionsIdle.addAndGet(1);
                        evicted++;
                    }
                }
            }
        }

        // Clean up deferred patterns (now safe to free)
        int deferredCleaned = cleanupDeferredPatterns();

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
    private int cleanupDeferredPatterns() {
        int cleaned = 0;

        // CopyOnWriteArrayList allows safe iteration + modification
        for (CachedPattern deferred : deferredCleanup) {
            if (deferred.pattern().getRefCount() == 0) {
                // Now safe to free
                logger.debug("RE2: Cleaning up deferred pattern");
                deferred.forceClose();
                deferredCleanup.remove(deferred);
                evictionsLRU.incrementAndGet(); // Count as LRU eviction
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
            deferredSize
        );
    }

    /**
     * Clears the cache and closes all cached patterns.
     */
    public void clear() {
        if (!config.cacheEnabled()) {
            return;
        }

        synchronized (cache) {
            logger.info("RE2: Clearing cache - {} patterns", cache.size());

            for (CachedPattern cached : cache.values()) {
                cached.forceClose(); // Force close even if from cache
            }

            cache.clear();
        }
    }

    /**
     * Resets cache statistics (for testing only).
     */
    public void resetStatistics() {
        hits.set(0);
        misses.set(0);
        evictionsLRU.set(0);
        evictionsIdle.set(0);
        logger.debug("RE2: Cache statistics reset");
    }

    /**
     * Full reset for testing (clears cache and resets statistics).
     */
    public void reset() {
        clear();
        resetStatistics();
        com.axonops.libre2.util.ResourceTracker.reset(); // Reset resource tracker too
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
     * Cached pattern with access time tracking.
     */
    private static class CachedPattern {
        private final Pattern pattern;
        private volatile Instant lastAccessTime;

        CachedPattern(Pattern pattern) {
            this.pattern = pattern;
            this.lastAccessTime = Instant.now();
        }

        Pattern pattern() {
            return pattern;
        }

        Instant lastAccessTime() {
            return lastAccessTime;
        }

        void updateAccessTime() {
            this.lastAccessTime = Instant.now();
        }

        void forceClose() {
            pattern.forceClose(); // Calls package-private method
        }
    }
}
