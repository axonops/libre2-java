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

/**
 * Cache statistics for monitoring and metrics.
 *
 * <p>Immutable snapshot of cache state at a point in time.
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
    int deferredCleanupSize,
    long nativeMemoryBytes,
    long peakNativeMemoryBytes,
    long invalidPatternRecompilations) {

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

  /** Total number of evictions (LRU + idle + deferred cleanup). */
  public long totalEvictions() {
    return evictionsLRU + evictionsIdle;
  }

  /** Total patterns pending deferred cleanup (evicted but not yet freed). */
  public int deferredCleanupPending() {
    return deferredCleanupSize;
  }

  /** Total number of requests (hits + misses). */
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
