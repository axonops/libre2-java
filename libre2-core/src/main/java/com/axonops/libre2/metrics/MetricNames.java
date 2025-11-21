/*
 * Copyright 2025 AxonOps
 */
package com.axonops.libre2.metrics;

public final class MetricNames {
    private MetricNames() {}

    // Pattern Compilation (5)
    public static final String PATTERNS_COMPILED = "patterns.compiled.total.count";
    public static final String PATTERNS_CACHE_HITS = "patterns.cache.hits.total.count";
    public static final String PATTERNS_CACHE_MISSES = "patterns.cache.misses.total.count";
    public static final String PATTERNS_COMPILATION_LATENCY = "patterns.compilation.latency";
    public static final String PATTERNS_INVALID_RECOMPILED = "patterns.invalid.recompiled.total.count";

    // Cache State (3)
    public static final String CACHE_PATTERNS_COUNT = "cache.patterns.current.count";
    public static final String CACHE_NATIVE_MEMORY = "cache.native_memory.current.bytes";
    public static final String CACHE_NATIVE_MEMORY_PEAK = "cache.native_memory.peak.bytes";

    // Cache Evictions (3)
    public static final String CACHE_EVICTIONS_LRU = "cache.evictions.lru.total.count";
    public static final String CACHE_EVICTIONS_IDLE = "cache.evictions.idle.total.count";
    public static final String CACHE_EVICTIONS_DEFERRED = "cache.evictions.deferred.total.count";

    // Deferred Cleanup (4)
    public static final String CACHE_DEFERRED_PATTERNS_COUNT = "cache.deferred.patterns.current.count";
    public static final String CACHE_DEFERRED_PATTERNS_PEAK = "cache.deferred.patterns.peak.count";
    public static final String CACHE_DEFERRED_MEMORY = "cache.deferred.native_memory.current.bytes";
    public static final String CACHE_DEFERRED_MEMORY_PEAK = "cache.deferred.native_memory.peak.bytes";

    // Resource Management (4)
    public static final String RESOURCES_PATTERNS_ACTIVE = "resources.patterns.active.current.count";
    public static final String RESOURCES_MATCHERS_ACTIVE = "resources.matchers.active.current.count";
    public static final String RESOURCES_PATTERNS_FREED = "resources.patterns.freed.total.count";
    public static final String RESOURCES_MATCHERS_FREED = "resources.matchers.freed.total.count";

    // Performance (3)
    public static final String MATCHING_FULL_MATCH_LATENCY = "matching.full_match.latency";
    public static final String MATCHING_PARTIAL_MATCH_LATENCY = "matching.partial_match.latency";
    public static final String MATCHING_OPERATIONS = "matching.operations.total.count";

    // Errors (3)
    public static final String ERRORS_COMPILATION_FAILED = "errors.compilation.failed.total.count";
    public static final String ERRORS_NATIVE_LIBRARY = "errors.native_library.total.count";
    public static final String ERRORS_RESOURCE_EXHAUSTED = "errors.resource.exhausted.total.count";
}
