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
 * <p>The RE2 library maintains an automatic LRU cache of compiled regex patterns to avoid expensive
 * recompilation. When {@link com.axonops.libre2.api.Pattern#compile(String)} is called:
 * <ol>
 *   <li><b>Cache Hit</b> - Pattern found in cache, returned immediately (no compilation)</li>
 *   <li><b>Cache Miss</b> - Pattern compiled via native JNI, stored in cache for reuse</li>
 * </ol>
 *
 * <p><b>Dual Eviction Strategy:</b>
 * <ul>
 *   <li><b>LRU Eviction</b> - When cache exceeds maxCacheSize, least-recently-used patterns evicted</li>
 *   <li><b>Idle Eviction</b> - Background thread evicts patterns unused for idleTimeoutSeconds</li>
 * </ul>
 *
 * <p>This dual strategy provides:
 * <ul>
 *   <li>Short-term performance (LRU keeps hot patterns)</li>
 *   <li>Long-term memory hygiene (idle eviction prevents unbounded growth)</li>
 * </ul>
 *
 * <h3>Deferred Cleanup Queue</h3>
 * <p>Patterns cannot be immediately freed from native memory if they are in use by active
 * {@link com.axonops.libre2.api.Matcher} instances. When a pattern is evicted (LRU or idle)
 * but has active matchers:
 * <ol>
 *   <li>Pattern removed from cache (no longer available for new compilations)</li>
 *   <li>Pattern moved to <b>deferred cleanup queue</b> (awaiting matcher closure)</li>
 *   <li>When all matchers close, pattern freed from native memory</li>
 * </ol>
 *
 * <p><b>Why Deferred Cleanup Matters:</b>
 * <ul>
 *   <li>Prevents use-after-free crashes (matchers reference native memory)</li>
 *   <li>Allows safe concurrent pattern eviction and matching operations</li>
 *   <li>High deferred counts indicate matchers not closed promptly (potential leak)</li>
 * </ul>
 *
 * <p>A background task runs every {@code deferredCleanupIntervalSeconds} to free patterns
 * whose matchers have closed.
 *
 * <h3>Native Memory Tracking</h3>
 * <p>Compiled RE2 patterns are stored in <b>off-heap native memory</b> (not JVM heap) to:
 * <ul>
 *   <li>Avoid Java GC pressure (large regex automata can be 100s of KB)</li>
 *   <li>Leverage RE2's optimized C++ memory layout</li>
 *   <li>Prevent OutOfMemoryError in high-throughput scenarios</li>
 * </ul>
 *
 * <p><b>Exact Memory Measurement:</b> When a pattern is compiled, the native library reports
 * exact memory usage via {@code Pattern.getNativeMemoryBytes()}. This is NOT an estimate -
 * it's the actual allocation size from RE2's internal accounting.
 *
 * <p><b>Memory Lifecycle:</b>
 * <ol>
 *   <li>Pattern compiled → native memory allocated (tracked in CACHE_NATIVE_MEMORY)</li>
 *   <li>Pattern evicted but in use → moved to deferred (tracked in CACHE_DEFERRED_MEMORY)</li>
 *   <li>All matchers closed → pattern freed (memory reclaimed, counters decremented)</li>
 * </ol>
 *
 * <p><b>Total Native Memory = Cache Memory + Deferred Memory</b>
 *
 * <h2>Metric Categories</h2>
 * <ul>
 *   <li><b>Pattern Compilation (5 metrics)</b> - Compilation performance and cache efficiency</li>
 *   <li><b>Cache State (3 metrics)</b> - Current cache size and memory usage</li>
 *   <li><b>Cache Evictions (3 metrics)</b> - Eviction types (LRU, idle, deferred) and frequencies</li>
 *   <li><b>Deferred Cleanup (4 metrics)</b> - Patterns awaiting cleanup (in use by matchers)</li>
 *   <li><b>Resource Management (4 metrics)</b> - Active patterns/matchers and cleanup tracking</li>
 *   <li><b>Performance (3 metrics)</b> - Matching operation latencies (RE2 guarantees linear time)</li>
 *   <li><b>Errors (3 metrics)</b> - Compilation failures and resource exhaustion</li>
 * </ul>
 *
 * <h2>Metric Types</h2>
 * <ul>
 *   <li><b>Counter</b> - Monotonically increasing count (suffix: {@code .total.count})</li>
 *   <li><b>Timer</b> - Latency histogram with percentiles (suffix: {@code .latency})</li>
 *   <li><b>Gauge</b> - Current or peak value (suffix: {@code .current.*} or {@code .peak.*})</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
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
 * <ul>
 *   <li><b>Cache Hit Rate:</b> PATTERNS_CACHE_HITS / (PATTERNS_CACHE_HITS + PATTERNS_CACHE_MISSES)
 *       - Target: >90% for steady-state workloads</li>
 *   <li><b>Deferred Cleanup:</b> CACHE_DEFERRED_PATTERNS_COUNT should be low (near zero)
 *       - High values indicate matchers not closed (potential leak)</li>
 *   <li><b>Memory Growth:</b> CACHE_NATIVE_MEMORY + CACHE_DEFERRED_MEMORY = total off-heap
 *       - Should stabilize after warmup, not grow unbounded</li>
 *   <li><b>Eviction Balance:</b> CACHE_EVICTIONS_IDLE should dominate over CACHE_EVICTIONS_LRU
 *       - Means cache sized correctly, idle patterns cleaned up</li>
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
     * <p><b>Type:</b> Counter
     * <p><b>Incremented:</b> Each time a pattern is compiled via native JNI
     * <p><b>Interpretation:</b> High values indicate poor cache hit rate or many unique patterns
     */
    public static final String PATTERNS_COMPILED = "patterns.compiled.total.count";

    /**
     * Total cache hits (pattern found in cache).
     * <p><b>Type:</b> Counter
     * <p><b>Incremented:</b> When Pattern.compile() finds pattern already cached
     * <p><b>Interpretation:</b> High hit rate (hits / (hits + misses)) indicates effective caching
     */
    public static final String PATTERNS_CACHE_HITS = "patterns.cache.hits.total.count";

    /**
     * Total cache misses (pattern not in cache, compilation required).
     * <p><b>Type:</b> Counter
     * <p><b>Incremented:</b> When Pattern.compile() must compile new pattern
     * <p><b>Interpretation:</b> Equal to PATTERNS_COMPILED; compare to hits for hit rate
     */
    public static final String PATTERNS_CACHE_MISSES = "patterns.cache.misses.total.count";

    /**
     * Pattern compilation latency histogram.
     * <p><b>Type:</b> Timer (nanoseconds)
     * <p><b>Recorded:</b> For each successful pattern compilation (native JNI call)
     * <p><b>Provides:</b> min, max, mean, p50, p75, p95, p98, p99, p99.9, rates (1m, 5m, 15m)
     * <p><b>Interpretation:</b> High latencies indicate complex regex patterns or platform issues
     */
    public static final String PATTERNS_COMPILATION_LATENCY = "patterns.compilation.latency";

    /**
     * Patterns recompiled after cache validation detected corruption.
     * <p><b>Type:</b> Counter
     * <p><b>Incremented:</b> When cached pattern fails validation and is recompiled
     * <p><b>Interpretation:</b> Should be zero; non-zero indicates serious native memory corruption
     */
    public static final String PATTERNS_INVALID_RECOMPILED = "patterns.invalid.recompiled.total.count";

    // ========================================
    // Cache State Metrics (3)
    // ========================================

    /**
     * Current number of patterns in cache.
     * <p><b>Type:</b> Gauge (count)
     * <p><b>Updated:</b> On cache insertions and evictions
     * <p><b>Interpretation:</b> Should stay below configured maxCacheSize; sudden drops indicate evictions
     */
    public static final String CACHE_PATTERNS_COUNT = "cache.patterns.current.count";

    /**
     * Current native memory used by cached patterns.
     * <p><b>Type:</b> Gauge (bytes)
     * <p><b>Updated:</b> On cache insertions and evictions
     * <p><b>Interpretation:</b> Exact off-heap memory usage (reported by RE2 native library)
     */
    public static final String CACHE_NATIVE_MEMORY = "cache.native_memory.current.bytes";

    /**
     * Peak native memory used by cached patterns (high water mark).
     * <p><b>Type:</b> Gauge (bytes)
     * <p><b>Updated:</b> When current memory exceeds previous peak
     * <p><b>Interpretation:</b> Maximum exact memory usage; helps size cache limits
     */
    public static final String CACHE_NATIVE_MEMORY_PEAK = "cache.native_memory.peak.bytes";

    // ========================================
    // Cache Eviction Metrics (3)
    // ========================================

    /**
     * Patterns evicted due to LRU cache overflow.
     * <p><b>Type:</b> Counter
     * <p><b>Incremented:</b> When cache exceeds maxCacheSize and LRU pattern evicted
     * <p><b>Interpretation:</b> High values indicate cache too small or working set exceeds limit
     */
    public static final String CACHE_EVICTIONS_LRU = "cache.evictions.lru.total.count";

    /**
     * Patterns evicted due to idle timeout.
     * <p><b>Type:</b> Counter
     * <p><b>Incremented:</b> When background task evicts pattern unused for idleTimeoutSeconds
     * <p><b>Interpretation:</b> High values indicate many patterns accessed once then abandoned
     */
    public static final String CACHE_EVICTIONS_IDLE = "cache.evictions.idle.total.count";

    /**
     * Patterns freed from deferred cleanup queue (were in use when eviction attempted).
     * <p><b>Type:</b> Counter
     * <p><b>Incremented:</b> When deferred cleanup task successfully frees pattern after matchers closed
     * <p><b>Interpretation:</b> Normal during concurrent workloads; see deferred metrics for backlog
     */
    public static final String CACHE_EVICTIONS_DEFERRED = "cache.evictions.deferred.total.count";

    // ========================================
    // Deferred Cleanup Metrics (4)
    // ========================================

    /**
     * Current number of patterns awaiting deferred cleanup.
     * <p><b>Type:</b> Gauge (count)
     * <p><b>Updated:</b> When patterns moved to deferred queue or freed
     * <p><b>Interpretation:</b> Should be low; high values indicate matchers not closed promptly
     */
    public static final String CACHE_DEFERRED_PATTERNS_COUNT = "cache.deferred.patterns.current.count";

    /**
     * Peak number of patterns in deferred cleanup queue.
     * <p><b>Type:</b> Gauge (count)
     * <p><b>Updated:</b> When deferred count exceeds previous peak
     * <p><b>Interpretation:</b> High peaks indicate bursts of concurrent matcher usage
     */
    public static final String CACHE_DEFERRED_PATTERNS_PEAK = "cache.deferred.patterns.peak.count";

    /**
     * Current native memory held by deferred cleanup patterns.
     * <p><b>Type:</b> Gauge (bytes)
     * <p><b>Updated:</b> When patterns added to or freed from deferred queue
     * <p><b>Interpretation:</b> Exact memory not yet reclaimed; large values indicate matcher leak risk
     */
    public static final String CACHE_DEFERRED_MEMORY = "cache.deferred.native_memory.current.bytes";

    /**
     * Peak native memory held by deferred cleanup patterns.
     * <p><b>Type:</b> Gauge (bytes)
     * <p><b>Updated:</b> When deferred memory exceeds previous peak
     * <p><b>Interpretation:</b> Maximum exact memory from in-use patterns
     */
    public static final String CACHE_DEFERRED_MEMORY_PEAK = "cache.deferred.native_memory.peak.bytes";

    // ========================================
    // Resource Management Metrics (4)
    // ========================================

    /**
     * Current number of active (compiled) patterns across all caches.
     * <p><b>Type:</b> Gauge (count)
     * <p><b>Updated:</b> On pattern compilation and cleanup
     * <p><b>Interpretation:</b> Should stay below maxSimultaneousCompiledPatterns limit
     */
    public static final String RESOURCES_PATTERNS_ACTIVE = "resources.patterns.active.current.count";

    /**
     * Current number of active matchers.
     * <p><b>Type:</b> Gauge (count)
     * <p><b>Updated:</b> On matcher creation and close
     * <p><b>Interpretation:</b> High values indicate many concurrent matching operations
     */
    public static final String RESOURCES_MATCHERS_ACTIVE = "resources.matchers.active.current.count";

    /**
     * Total patterns freed (native memory deallocated).
     * <p><b>Type:</b> Counter
     * <p><b>Incremented:</b> When pattern's native handle freed via freePattern()
     * <p><b>Interpretation:</b> Should approximately equal PATTERNS_COMPILED over time
     */
    public static final String RESOURCES_PATTERNS_FREED = "resources.patterns.freed.total.count";

    /**
     * Total matchers freed.
     * <p><b>Type:</b> Counter
     * <p><b>Incremented:</b> When Matcher.close() completes
     * <p><b>Interpretation:</b> Tracks matcher lifecycle; useful for leak detection
     */
    public static final String RESOURCES_MATCHERS_FREED = "resources.matchers.freed.total.count";

    // ========================================
    // Performance Metrics (3)
    // ========================================

    /**
     * Full match operation latency histogram (Matcher.matches()).
     * <p><b>Type:</b> Timer (nanoseconds)
     * <p><b>Recorded:</b> For each matches() call (exact string match)
     * <p><b>Provides:</b> min, max, mean, p50, p75, p95, p98, p99, p99.9, rates
     * <p><b>Interpretation:</b> RE2 guarantees linear time; high latencies indicate long input strings
     */
    public static final String MATCHING_FULL_MATCH_LATENCY = "matching.full_match.latency";

    /**
     * Partial match operation latency histogram (Matcher.find()).
     * <p><b>Type:</b> Timer (nanoseconds)
     * <p><b>Recorded:</b> For each find() call (substring match)
     * <p><b>Provides:</b> min, max, mean, p50, p75, p95, p98, p99, p99.9, rates
     * <p><b>Interpretation:</b> Typically faster than full match; measures search performance
     */
    public static final String MATCHING_PARTIAL_MATCH_LATENCY = "matching.partial_match.latency";

    /**
     * Total matching operations (matches() + find()).
     * <p><b>Type:</b> Counter
     * <p><b>Incremented:</b> For each matches() or find() call
     * <p><b>Interpretation:</b> Total workload; compare to compilation count for reuse ratio
     */
    public static final String MATCHING_OPERATIONS = "matching.operations.total.count";

    // ========================================
    // Error Metrics (3)
    // ========================================

    /**
     * Pattern compilation failures (invalid regex syntax).
     * <p><b>Type:</b> Counter
     * <p><b>Incremented:</b> When RE2 rejects pattern as invalid
     * <p><b>Interpretation:</b> User error (bad regex); check logs for pattern details
     */
    public static final String ERRORS_COMPILATION_FAILED = "errors.compilation.failed.total.count";

    /**
     * Native library load failures.
     * <p><b>Type:</b> Counter
     * <p><b>Incremented:</b> When RE2 native library fails to load at startup
     * <p><b>Interpretation:</b> Critical error; check platform detection and library bundle
     */
    public static final String ERRORS_NATIVE_LIBRARY = "errors.native_library.total.count";

    /**
     * Resource limit exceeded (too many patterns or matchers).
     * <p><b>Type:</b> Counter
     * <p><b>Incremented:</b> When maxSimultaneousCompiledPatterns exceeded
     * <p><b>Interpretation:</b> Safety limit hit; increase limit or reduce concurrency
     */
    public static final String ERRORS_RESOURCE_EXHAUSTED = "errors.resource.exhausted.total.count";
}
