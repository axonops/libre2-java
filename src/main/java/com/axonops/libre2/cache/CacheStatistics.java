package com.axonops.libre2.cache;

/**
 * Cache statistics for monitoring and metrics.
 *
 * Immutable snapshot of cache state at a point in time.
 *
 * @since 1.0.0
 */
public record CacheStatistics(
    long hits,
    long misses,
    long evictionsLRU,
    long evictionsIdle,
    long evictionsDeferred,
    int currentSize,
    int maxSize,
    int deferredCleanupSize
) {

    /**
     * Calculates hit rate.
     *
     * @return hit rate between 0.0 and 1.0, or 0.0 if no requests
     */
    public double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }

    /**
     * Calculates miss rate.
     *
     * @return miss rate between 0.0 and 1.0, or 0.0 if no requests
     */
    public double missRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) misses / total;
    }

    /**
     * Total number of evictions (LRU + idle + deferred cleanup).
     */
    public long totalEvictions() {
        return evictionsLRU + evictionsIdle;
    }

    /**
     * Total patterns pending deferred cleanup (evicted but not yet freed).
     */
    public int deferredCleanupPending() {
        return deferredCleanupSize;
    }

    /**
     * Total number of requests (hits + misses).
     */
    public long totalRequests() {
        return hits + misses;
    }

    /**
     * Cache utilization percentage.
     *
     * @return utilization between 0.0 and 1.0
     */
    public double utilization() {
        return maxSize == 0 ? 0.0 : (double) currentSize / maxSize;
    }
}
