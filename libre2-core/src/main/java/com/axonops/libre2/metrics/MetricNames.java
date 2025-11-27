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

package com.axonops.libre2.metrics;

/**
 * Metric name constants for RE2 library instrumentation.
 *
 * <p>Provides 25 metrics across 6 categories for comprehensive observability of the RE2 pattern
 * cache, native memory management, and matching performance.
 *
 * <h2>Architecture Overview</h2>
 *
 * <h3>Pattern Cache</h3>
 *
 * <p>The RE2 library maintains an automatic LRU cache of compiled regex patterns to avoid expensive
 * recompilation. When {@link com.axonops.libre2.api.Pattern#compile(String)} is called:
 *
 * <ol>
 *   <li><b>Cache Hit</b> - Pattern found in cache, returned immediately (no compilation)
 *   <li><b>Cache Miss</b> - Pattern compiled via native JNI, stored in cache for reuse
 * </ol>
 *
 * <p><b>Dual Eviction Strategy:</b>
 *
 * <ul>
 *   <li><b>LRU Eviction</b> - When cache exceeds maxCacheSize, least-recently-used patterns evicted
 *   <li><b>Idle Eviction</b> - Background thread evicts patterns unused for idleTimeoutSeconds
 * </ul>
 *
 * <p>This dual strategy provides:
 *
 * <ul>
 *   <li>Short-term performance (LRU keeps hot patterns)
 *   <li>Long-term memory hygiene (idle eviction prevents unbounded growth)
 * </ul>
 *
 * <h3>Deferred Cleanup Queue</h3>
 *
 * <p>Patterns cannot be immediately freed from native memory if they are in use by active {@link
 * com.axonops.libre2.api.Matcher} instances. When a pattern is evicted (LRU or idle) but has active
 * matchers:
 *
 * <ol>
 *   <li>Pattern removed from cache (no longer available for new compilations)
 *   <li>Pattern moved to <b>deferred cleanup queue</b> (awaiting matcher closure)
 *   <li>When all matchers close, pattern freed from native memory
 * </ol>
 *
 * <p><b>Why Deferred Cleanup Matters:</b>
 *
 * <ul>
 *   <li>Prevents use-after-free crashes (matchers reference native memory)
 *   <li>Allows safe concurrent pattern eviction and matching operations
 *   <li>High deferred counts indicate matchers not closed promptly (potential leak)
 * </ul>
 *
 * <p>A background task runs every {@code deferredCleanupIntervalSeconds} to free patterns whose
 * matchers have closed.
 *
 * <h3>Native Memory Tracking</h3>
 *
 * <p>Compiled RE2 patterns are stored in <b>off-heap native memory</b> (not JVM heap) to:
 *
 * <ul>
 *   <li>Avoid Java GC pressure (large regex automata can be 100s of KB)
 *   <li>Leverage RE2's optimized C++ memory layout
 *   <li>Prevent OutOfMemoryError in high-throughput scenarios
 * </ul>
 *
 * <p><b>Exact Memory Measurement:</b> When a pattern is compiled, the native library reports exact
 * memory usage via {@code Pattern.getNativeMemoryBytes()}. This is NOT an estimate - it's the
 * actual allocation size from RE2's internal accounting.
 *
 * <p><b>Memory Lifecycle:</b>
 *
 * <ol>
 *   <li>Pattern compiled → native memory allocated (tracked in CACHE_NATIVE_MEMORY)
 *   <li>Pattern evicted but in use → moved to deferred (tracked in CACHE_DEFERRED_MEMORY)
 *   <li>All matchers closed → pattern freed (memory reclaimed, counters decremented)
 * </ol>
 *
 * <p><b>Total Native Memory = Cache Memory + Deferred Memory</b>
 *
 * <h2>Metric Categories</h2>
 *
 * <ul>
 *   <li><b>Pattern Compilation (5 metrics)</b> - Compilation performance and cache efficiency
 *   <li><b>Cache State (3 metrics)</b> - Current cache size and memory usage
 *   <li><b>Cache Evictions (3 metrics)</b> - Eviction types (LRU, idle, deferred) and frequencies
 *   <li><b>Deferred Cleanup (4 metrics)</b> - Patterns awaiting cleanup (in use by matchers)
 *   <li><b>Resource Management (4 metrics)</b> - Active patterns/matchers and cleanup tracking
 *   <li><b>Performance (3 metrics)</b> - Matching operation latencies (RE2 guarantees linear time)
 *   <li><b>Errors (3 metrics)</b> - Compilation failures and resource exhaustion
 * </ul>
 *
 * <h2>Metric Types</h2>
 *
 * <ul>
 *   <li><b>Counter</b> - Monotonically increasing count (suffix: {@code .total.count})
 *   <li><b>Timer</b> - Latency histogram with percentiles (suffix: {@code .latency})
 *   <li><b>Gauge</b> - Current or peak value (suffix: {@code .current.*} or {@code .peak.*})
 * </ul>
 *
 * <h2>Usage Example</h2>
 *
 * <pre>{@code
 * // Initialize with Dropwizard Metrics and JMX
 * MetricRegistry registry = new MetricRegistry();
 * RE2Config config = RE2MetricsConfig.withMetrics(registry, "myapp.re2", true);
 * Pattern.setGlobalCache(new PatternCache(config));
 *
 * // Compile patterns (automatically cached and metered)
 * Pattern emailPattern = Pattern.compile("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
 * try (Matcher m = emailPattern.matcher("user@example.com")) {
 *     boolean matches = m.matches(); // Latency recorded in MATCHING_FULL_MATCH_LATENCY
 * }
 *
 * // Access metrics programmatically
 * Counter compilations = registry.counter(
 *     MetricRegistry.name("myapp.re2", MetricNames.PATTERNS_COMPILED));
 * Histogram compileLatency = registry.histogram(
 *     MetricRegistry.name("myapp.re2", MetricNames.PATTERNS_COMPILATION_LATENCY));
 *
 * // Or via JMX (if enableJmx=true):
 * // - metrics:name=myapp.re2.patterns.compiled.total.count,type=counters
 * // - metrics:name=myapp.re2.patterns.compilation.latency,type=timers
 * }</pre>
 *
 * <h2>Monitoring Recommendations</h2>
 *
 * <ul>
 *   <li><b>Cache Hit Rate:</b> PATTERNS_CACHE_HITS / (PATTERNS_CACHE_HITS + PATTERNS_CACHE_MISSES)
 *       - Target: >90% for steady-state workloads
 *   <li><b>Deferred Cleanup:</b> CACHE_DEFERRED_PATTERNS_COUNT should be low (near zero) - High
 *       values indicate matchers not closed (potential leak)
 *   <li><b>Memory Growth:</b> CACHE_NATIVE_MEMORY + CACHE_DEFERRED_MEMORY = total off-heap - Should
 *       stabilize after warmup, not grow unbounded
 *   <li><b>Eviction Balance:</b> CACHE_EVICTIONS_IDLE should dominate over CACHE_EVICTIONS_LRU -
 *       Means cache sized correctly, idle patterns cleaned up
 * </ul>
 *
 * @since 1.0.0
 * @see com.axonops.libre2.cache.PatternCache
 * @see com.axonops.libre2.api.Pattern
 * @see com.axonops.libre2.api.Matcher
 */
public final class MetricNames {
  private MetricNames() {}

  // ========================================
  // Pattern Compilation Metrics (5)
  // ========================================

  /**
   * Total patterns compiled (cache misses).
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> Each time a pattern is compiled via native JNI
   *
   * <p><b>Interpretation:</b> High values indicate poor cache hit rate or many unique patterns
   */
  public static final String PATTERNS_COMPILED = "patterns.compiled.total.count";

  /**
   * Total cache hits (pattern found in cache).
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> When Pattern.compile() finds pattern already cached
   *
   * <p><b>Interpretation:</b> High hit rate (hits / (hits + misses)) indicates effective caching
   */
  public static final String PATTERNS_CACHE_HITS = "patterns.cache.hits.total.count";

  /**
   * Total cache misses (pattern not in cache, compilation required).
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> When Pattern.compile() must compile new pattern
   *
   * <p><b>Interpretation:</b> Equal to PATTERNS_COMPILED; compare to hits for hit rate
   */
  public static final String PATTERNS_CACHE_MISSES = "patterns.cache.misses.total.count";

  /**
   * Pattern compilation latency histogram.
   *
   * <p><b>Type:</b> Timer (nanoseconds)
   *
   * <p><b>Recorded:</b> For each successful pattern compilation (native JNI call)
   *
   * <p><b>Provides:</b> min, max, mean, p50, p75, p95, p98, p99, p99.9, rates (1m, 5m, 15m)
   *
   * <p><b>Interpretation:</b> High latencies indicate complex regex patterns or platform issues
   */
  public static final String PATTERNS_COMPILATION_LATENCY = "patterns.compilation.latency";

  /**
   * Patterns recompiled after cache validation detected corruption.
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> When cached pattern fails validation and is recompiled
   *
   * <p><b>Interpretation:</b> Should be zero; non-zero indicates serious native memory corruption
   */
  public static final String PATTERNS_INVALID_RECOMPILED =
      "patterns.invalid.recompiled.total.count";

  // ========================================
  // Cache State Metrics (3)
  // ========================================

  /**
   * Current number of patterns in cache.
   *
   * <p><b>Type:</b> Gauge (count)
   *
   * <p><b>Updated:</b> On cache insertions and evictions
   *
   * <p><b>Interpretation:</b> Should stay below configured maxCacheSize; sudden drops indicate
   * evictions
   */
  public static final String CACHE_PATTERNS_COUNT = "cache.patterns.current.count";

  /**
   * Current native memory used by cached patterns.
   *
   * <p><b>Type:</b> Gauge (bytes)
   *
   * <p><b>Updated:</b> On cache insertions and evictions
   *
   * <p><b>Interpretation:</b> Exact off-heap memory usage (reported by RE2 native library)
   */
  public static final String CACHE_NATIVE_MEMORY = "cache.native_memory.current.bytes";

  /**
   * Peak native memory used by cached patterns (high water mark).
   *
   * <p><b>Type:</b> Gauge (bytes)
   *
   * <p><b>Updated:</b> When current memory exceeds previous peak
   *
   * <p><b>Interpretation:</b> Maximum exact memory usage; helps size cache limits
   */
  public static final String CACHE_NATIVE_MEMORY_PEAK = "cache.native_memory.peak.bytes";

  // ========================================
  // Cache Eviction Metrics (3)
  // ========================================

  /**
   * Patterns evicted due to LRU cache overflow.
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> When cache exceeds maxCacheSize and LRU pattern evicted
   *
   * <p><b>Interpretation:</b> High values indicate cache too small or working set exceeds limit
   */
  public static final String CACHE_EVICTIONS_LRU = "cache.evictions.lru.total.count";

  /**
   * Patterns evicted due to idle timeout.
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> When background task evicts pattern unused for idleTimeoutSeconds
   *
   * <p><b>Interpretation:</b> High values indicate many patterns accessed once then abandoned
   */
  public static final String CACHE_EVICTIONS_IDLE = "cache.evictions.idle.total.count";

  /**
   * Patterns freed from deferred cleanup queue (were in use when eviction attempted).
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> When deferred cleanup task successfully frees pattern after matchers
   * closed
   *
   * <p><b>Interpretation:</b> Normal during concurrent workloads; see deferred metrics for backlog
   */
  public static final String CACHE_EVICTIONS_DEFERRED = "cache.evictions.deferred.total.count";

  // ========================================
  // Deferred Cleanup Metrics (4)
  // ========================================

  /**
   * Current number of patterns awaiting deferred cleanup.
   *
   * <p><b>Type:</b> Gauge (count)
   *
   * <p><b>Updated:</b> When patterns moved to deferred queue or freed
   *
   * <p><b>Interpretation:</b> Should be low; high values indicate matchers not closed promptly
   */
  public static final String CACHE_DEFERRED_PATTERNS_COUNT =
      "cache.deferred.patterns.current.count";

  /**
   * Peak number of patterns in deferred cleanup queue.
   *
   * <p><b>Type:</b> Gauge (count)
   *
   * <p><b>Updated:</b> When deferred count exceeds previous peak
   *
   * <p><b>Interpretation:</b> High peaks indicate bursts of concurrent matcher usage
   */
  public static final String CACHE_DEFERRED_PATTERNS_PEAK = "cache.deferred.patterns.peak.count";

  /**
   * Current native memory held by deferred cleanup patterns.
   *
   * <p><b>Type:</b> Gauge (bytes)
   *
   * <p><b>Updated:</b> When patterns added to or freed from deferred queue
   *
   * <p><b>Interpretation:</b> Exact memory not yet reclaimed; large values indicate matcher leak
   * risk
   */
  public static final String CACHE_DEFERRED_MEMORY = "cache.deferred.native_memory.current.bytes";

  /**
   * Peak native memory held by deferred cleanup patterns.
   *
   * <p><b>Type:</b> Gauge (bytes)
   *
   * <p><b>Updated:</b> When deferred memory exceeds previous peak
   *
   * <p><b>Interpretation:</b> Maximum exact memory from in-use patterns
   */
  public static final String CACHE_DEFERRED_MEMORY_PEAK = "cache.deferred.native_memory.peak.bytes";

  // ========================================
  // Resource Management Metrics (4)
  // ========================================

  /**
   * Current number of active (compiled) patterns across all caches.
   *
   * <p><b>Type:</b> Gauge (count)
   *
   * <p><b>Updated:</b> On pattern compilation and cleanup
   *
   * <p><b>Interpretation:</b> Should stay below maxSimultaneousCompiledPatterns limit
   */
  public static final String RESOURCES_PATTERNS_ACTIVE = "resources.patterns.active.current.count";

  /**
   * Current number of active matchers.
   *
   * <p><b>Type:</b> Gauge (count)
   *
   * <p><b>Updated:</b> On matcher creation and close
   *
   * <p><b>Interpretation:</b> High values indicate many concurrent matching operations
   */
  public static final String RESOURCES_MATCHERS_ACTIVE = "resources.matchers.active.current.count";

  /**
   * Total patterns freed (native memory deallocated).
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> When pattern's native handle freed via freePattern()
   *
   * <p><b>Interpretation:</b> Should approximately equal PATTERNS_COMPILED over time
   */
  public static final String RESOURCES_PATTERNS_FREED = "resources.patterns.freed.total.count";

  /**
   * Total matchers freed.
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> When Matcher.close() completes
   *
   * <p><b>Interpretation:</b> Tracks matcher lifecycle; useful for leak detection
   */
  public static final String RESOURCES_MATCHERS_FREED = "resources.matchers.freed.total.count";

  // ========================================
  // Performance Metrics - Matching
  // ========================================
  // Pattern: Global metrics (ALL) + Specific breakdown (String, Bulk, Zero-Copy)

  /**
   * Total matching operations (ALL - String + Bulk + Zero-Copy).
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> For EVERY matches() or find() call regardless of variant
   *
   * <p><b>Interpretation:</b> Total matching workload across all API variants
   *
   * <p><b>Breakdown:</b> Sum of MATCHING_STRING_OPERATIONS + MATCHING_BULK_OPERATIONS +
   * MATCHING_ZERO_COPY_OPERATIONS
   */
  public static final String MATCHING_OPERATIONS = "matching.operations.total.count";

  /**
   * Matching operation latency (ALL variants).
   *
   * <p><b>Type:</b> Timer (nanoseconds)
   *
   * <p><b>Recorded:</b> For EVERY matching operation (String, bulk, zero-copy)
   *
   * <p><b>Interpretation:</b> Overall matching performance across all variants
   */
  public static final String MATCHING_LATENCY = "matching.latency";

  /**
   * Full match operation latency (ALL variants).
   *
   * <p><b>Type:</b> Timer (nanoseconds)
   *
   * <p><b>Recorded:</b> For each full match (String or zero-copy)
   *
   * <p><b>Interpretation:</b> Full match performance
   */
  public static final String MATCHING_FULL_MATCH_LATENCY = "matching.full_match.latency";

  /**
   * Partial match operation latency (ALL variants).
   *
   * <p><b>Type:</b> Timer (nanoseconds)
   *
   * <p><b>Recorded:</b> For each partial match (String or zero-copy)
   *
   * <p><b>Interpretation:</b> Partial match performance
   */
  public static final String MATCHING_PARTIAL_MATCH_LATENCY = "matching.partial_match.latency";

  // --- String-specific matching metrics ---

  /**
   * String-based matching operations only.
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> For each matches(String) or find(String) call
   *
   * <p><b>Interpretation:</b> String API usage (subset of MATCHING_OPERATIONS)
   */
  public static final String MATCHING_STRING_OPERATIONS = "matching.string.operations.total.count";

  /**
   * String-based matching latency.
   *
   * <p><b>Type:</b> Timer (nanoseconds)
   *
   * <p><b>Recorded:</b> For each String matching operation
   *
   * <p><b>Interpretation:</b> String API performance baseline
   */
  public static final String MATCHING_STRING_LATENCY = "matching.string.latency";

  // --- Bulk-specific matching metrics ---

  /**
   * Bulk matching operations (matchAll, filter with String arrays/collections).
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> Once per bulk call
   *
   * <p><b>Interpretation:</b> Bulk API usage (subset of MATCHING_OPERATIONS)
   */
  public static final String MATCHING_BULK_OPERATIONS = "matching.bulk.operations.total.count";

  /**
   * Total items processed in bulk matching.
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> By number of items in each bulk call
   *
   * <p><b>Interpretation:</b> Total strings processed via bulk
   */
  public static final String MATCHING_BULK_ITEMS = "matching.bulk.items.total.count";

  /**
   * Bulk matching latency (per item average).
   *
   * <p><b>Type:</b> Timer (nanoseconds per item)
   *
   * <p><b>Recorded:</b> Average latency per item
   *
   * <p><b>Interpretation:</b> Should be lower than single due to JNI amortization
   */
  public static final String MATCHING_BULK_LATENCY = "matching.bulk.latency";

  // --- Zero-copy specific matching metrics ---

  /**
   * Zero-copy matching operations (ByteBuffer or address/length - single).
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> For each zero-copy single match
   *
   * <p><b>Interpretation:</b> Zero-copy API adoption (subset of MATCHING_OPERATIONS)
   */
  public static final String MATCHING_ZERO_COPY_OPERATIONS =
      "matching.zero_copy.operations.total.count";

  /**
   * Zero-copy matching latency.
   *
   * <p><b>Type:</b> Timer (nanoseconds)
   *
   * <p><b>Recorded:</b> For each zero-copy single match
   *
   * <p><b>Interpretation:</b> Should be 46-99% faster than String
   */
  public static final String MATCHING_ZERO_COPY_LATENCY = "matching.zero_copy.latency";

  /**
   * Zero-copy bulk matching operations (address/length arrays).
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> Once per zero-copy bulk call
   *
   * <p><b>Interpretation:</b> Zero-copy bulk usage
   */
  public static final String MATCHING_BULK_ZERO_COPY_OPERATIONS =
      "matching.bulk.zero_copy.operations.total.count";

  /**
   * Zero-copy bulk matching latency (per item).
   *
   * <p><b>Type:</b> Timer (nanoseconds per item)
   *
   * <p><b>Recorded:</b> Per-item latency for zero-copy bulk
   *
   * <p><b>Interpretation:</b> Fastest path (bulk + zero-copy)
   */
  public static final String MATCHING_BULK_ZERO_COPY_LATENCY = "matching.bulk.zero_copy.latency";

  // ========================================
  // Performance Metrics - Capture Groups
  // ========================================
  // Pattern: Global metrics (ALL) + Specific breakdown (String, Bulk, Zero-Copy)

  /**
   * Total capture group operations (ALL - String + Bulk + Zero-Copy).
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> For EVERY match(), find(), findAll() with group extraction
   *
   * <p><b>Interpretation:</b> Total capture workload across all variants
   *
   * <p><b>Breakdown:</b> Sum of CAPTURE_STRING_OPERATIONS + CAPTURE_BULK_OPERATIONS +
   * CAPTURE_ZERO_COPY_OPERATIONS
   */
  public static final String CAPTURE_OPERATIONS = "capture.operations.total.count";

  /**
   * Capture group extraction latency (ALL variants).
   *
   * <p><b>Type:</b> Timer (nanoseconds)
   *
   * <p><b>Recorded:</b> For EVERY capture operation (String, bulk, zero-copy)
   *
   * <p><b>Interpretation:</b> Overall capture performance across all variants
   */
  public static final String CAPTURE_LATENCY = "capture.latency";

  // --- String-specific capture metrics ---

  /**
   * String-based capture operations only.
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> For each match(String), find(String), findAll(String)
   *
   * <p><b>Interpretation:</b> String capture API usage (subset of CAPTURE_OPERATIONS)
   */
  public static final String CAPTURE_STRING_OPERATIONS = "capture.string.operations.total.count";

  /**
   * String-based capture latency.
   *
   * <p><b>Type:</b> Timer (nanoseconds)
   *
   * <p><b>Recorded:</b> For each String capture operation
   *
   * <p><b>Interpretation:</b> String capture performance baseline
   */
  public static final String CAPTURE_STRING_LATENCY = "capture.string.latency";

  // --- Bulk-specific capture metrics ---

  /**
   * Bulk capture operations (extractGroupsBulk, matchAll with groups).
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> Once per bulk capture call
   *
   * <p><b>Interpretation:</b> Bulk capture API usage (subset of CAPTURE_OPERATIONS)
   */
  public static final String CAPTURE_BULK_OPERATIONS = "capture.bulk.operations.total.count";

  /**
   * Total items in bulk capture operations.
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> By number of items in each bulk capture
   *
   * <p><b>Interpretation:</b> Total strings processed via bulk capture
   */
  public static final String CAPTURE_BULK_ITEMS = "capture.bulk.items.total.count";

  /**
   * Bulk capture latency (per item average).
   *
   * <p><b>Type:</b> Timer (nanoseconds per item)
   *
   * <p><b>Recorded:</b> Average latency per item in bulk capture
   *
   * <p><b>Interpretation:</b> Should be lower than single due to JNI amortization
   */
  public static final String CAPTURE_BULK_LATENCY = "capture.bulk.latency";

  // --- Zero-copy specific capture metrics ---

  /**
   * Zero-copy capture operations (ByteBuffer, address/length - single).
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> For each zero-copy single capture
   *
   * <p><b>Interpretation:</b> Zero-copy capture adoption (subset of CAPTURE_OPERATIONS)
   */
  public static final String CAPTURE_ZERO_COPY_OPERATIONS =
      "capture.zero_copy.operations.total.count";

  /**
   * Zero-copy capture latency.
   *
   * <p><b>Type:</b> Timer (nanoseconds)
   *
   * <p><b>Recorded:</b> For each zero-copy capture
   *
   * <p><b>Interpretation:</b> Should be 46-99% faster than String
   */
  public static final String CAPTURE_ZERO_COPY_LATENCY = "capture.zero_copy.latency";

  /**
   * Zero-copy bulk capture operations.
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> Once per zero-copy bulk capture call
   *
   * <p><b>Interpretation:</b> Zero-copy bulk capture usage
   */
  public static final String CAPTURE_BULK_ZERO_COPY_OPERATIONS =
      "capture.bulk.zero_copy.operations.total.count";

  /**
   * Zero-copy bulk capture latency (per item).
   *
   * <p><b>Type:</b> Timer (nanoseconds per item)
   *
   * <p><b>Recorded:</b> Per-item latency for zero-copy bulk capture
   *
   * <p><b>Interpretation:</b> Fastest capture path
   */
  public static final String CAPTURE_BULK_ZERO_COPY_LATENCY = "capture.bulk.zero_copy.latency";

  /**
   * Total matches found by findAll operations (ALL variants).
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> By number of matches found in each findAll()
   *
   * <p><b>Interpretation:</b> Total matches extracted across all findAll calls
   */
  public static final String CAPTURE_FINDALL_MATCHES = "capture.findall.matches.total.count";

  // ========================================
  // Performance Metrics - Replace
  // ========================================
  // Pattern: Global metrics (ALL) + Specific breakdown (String, Bulk, Zero-Copy)

  /**
   * Total replace operations (ALL - String + Bulk + Zero-Copy).
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> For EVERY replaceFirst(), replaceAll() regardless of variant
   *
   * <p><b>Interpretation:</b> Total replace workload across all variants
   *
   * <p><b>Breakdown:</b> Sum of REPLACE_STRING_OPERATIONS + REPLACE_BULK_OPERATIONS +
   * REPLACE_ZERO_COPY_OPERATIONS
   */
  public static final String REPLACE_OPERATIONS = "replace.operations.total.count";

  /**
   * Replace operation latency (ALL variants).
   *
   * <p><b>Type:</b> Timer (nanoseconds)
   *
   * <p><b>Recorded:</b> For EVERY replace operation (String, bulk, zero-copy)
   *
   * <p><b>Interpretation:</b> Overall replace performance across all variants
   */
  public static final String REPLACE_LATENCY = "replace.latency";

  // --- String-specific replace metrics ---

  /**
   * String-based replace operations only.
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> For each replaceFirst(String) or replaceAll(String)
   *
   * <p><b>Interpretation:</b> String replace API usage (subset of REPLACE_OPERATIONS)
   */
  public static final String REPLACE_STRING_OPERATIONS = "replace.string.operations.total.count";

  /**
   * String-based replace latency.
   *
   * <p><b>Type:</b> Timer (nanoseconds)
   *
   * <p><b>Recorded:</b> For each String replace operation
   *
   * <p><b>Interpretation:</b> String replace performance baseline
   */
  public static final String REPLACE_STRING_LATENCY = "replace.string.latency";

  // --- Bulk-specific replace metrics ---

  /**
   * Bulk replace operations (replaceAll with arrays/collections).
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> Once per bulk replace call
   *
   * <p><b>Interpretation:</b> Bulk replace API usage (subset of REPLACE_OPERATIONS)
   */
  public static final String REPLACE_BULK_OPERATIONS = "replace.bulk.operations.total.count";

  /**
   * Total items in bulk replace operations.
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> By number of items in each bulk replace
   *
   * <p><b>Interpretation:</b> Total strings processed via bulk replace
   */
  public static final String REPLACE_BULK_ITEMS = "replace.bulk.items.total.count";

  /**
   * Bulk replace latency (per item average).
   *
   * <p><b>Type:</b> Timer (nanoseconds per item)
   *
   * <p><b>Recorded:</b> Average latency per item in bulk replace
   *
   * <p><b>Interpretation:</b> Should be lower than single due to JNI amortization
   */
  public static final String REPLACE_BULK_LATENCY = "replace.bulk.latency";

  // --- Zero-copy specific replace metrics ---

  /**
   * Zero-copy replace operations (ByteBuffer, address/length - single).
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> For each zero-copy single replace
   *
   * <p><b>Interpretation:</b> Zero-copy replace adoption (subset of REPLACE_OPERATIONS)
   */
  public static final String REPLACE_ZERO_COPY_OPERATIONS =
      "replace.zero_copy.operations.total.count";

  /**
   * Zero-copy replace latency.
   *
   * <p><b>Type:</b> Timer (nanoseconds)
   *
   * <p><b>Recorded:</b> For each zero-copy replace
   *
   * <p><b>Interpretation:</b> Should be 46-99% faster than String
   */
  public static final String REPLACE_ZERO_COPY_LATENCY = "replace.zero_copy.latency";

  /**
   * Zero-copy bulk replace operations.
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> Once per zero-copy bulk replace call
   *
   * <p><b>Interpretation:</b> Zero-copy bulk replace usage
   */
  public static final String REPLACE_BULK_ZERO_COPY_OPERATIONS =
      "replace.bulk.zero_copy.operations.total.count";

  /**
   * Number of items processed in zero-copy bulk replace operations.
   *
   * <p><b>Type:</b> Counter (items)
   *
   * <p><b>Recorded:</b> Count of individual buffers/addresses processed in bulk zero-copy replace
   *
   * <p><b>Interpretation:</b> Total items in all REPLACE_BULK_ZERO_COPY_OPERATIONS calls
   */
  public static final String REPLACE_BULK_ZERO_COPY_ITEMS =
      "replace.bulk.zero_copy.items.total.count";

  /**
   * Zero-copy bulk replace latency (per item).
   *
   * <p><b>Type:</b> Timer (nanoseconds per item)
   *
   * <p><b>Recorded:</b> Per-item latency for zero-copy bulk replace
   *
   * <p><b>Interpretation:</b> Fastest replace path
   */
  public static final String REPLACE_BULK_ZERO_COPY_LATENCY = "replace.bulk.zero_copy.latency";

  // ========================================
  // Error Metrics (3)
  // ========================================

  /**
   * Pattern compilation failures (invalid regex syntax).
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> When RE2 rejects pattern as invalid
   *
   * <p><b>Interpretation:</b> User error (bad regex); check logs for pattern details
   */
  public static final String ERRORS_COMPILATION_FAILED = "errors.compilation.failed.total.count";

  /**
   * Native library load failures.
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> When RE2 native library fails to load at startup
   *
   * <p><b>Interpretation:</b> Critical error; check platform detection and library bundle
   */
  public static final String ERRORS_NATIVE_LIBRARY = "errors.native_library.total.count";

  /**
   * Resource limit exceeded (too many patterns or matchers).
   *
   * <p><b>Type:</b> Counter
   *
   * <p><b>Incremented:</b> When maxSimultaneousCompiledPatterns exceeded
   *
   * <p><b>Interpretation:</b> Safety limit hit; increase limit or reduce concurrency
   */
  public static final String ERRORS_RESOURCE_EXHAUSTED = "errors.resource.exhausted.total.count";
}
