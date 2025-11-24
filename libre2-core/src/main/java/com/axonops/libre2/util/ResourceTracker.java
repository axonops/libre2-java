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

package com.axonops.libre2.util;

import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.metrics.RE2MetricsRegistry;
import com.axonops.libre2.metrics.MetricNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks pattern resource usage for enforcing limits and monitoring.
 *
 * CRITICAL: Tracks ACTIVE (simultaneous) patterns, not cumulative total.
 * Instance-level (per-cache) to avoid conflicts when multiple caches exist.
 *
 * @since 1.0.0
 */
public final class ResourceTracker {
    private final Logger logger = LoggerFactory.getLogger(ResourceTracker.class);

    // ACTIVE counts (current simultaneous) - keep as AtomicInteger for fast limit checks on hot path
    private final AtomicInteger activePatternsCount = new AtomicInteger(0);
    private final AtomicInteger activeMatchersCount = new AtomicInteger(0);

    // Cumulative counters (lifetime, for metrics only) - use LongAdder for better write performance
    private final java.util.concurrent.atomic.LongAdder totalPatternsCompiled = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder totalPatternsClosed = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder totalMatchersCreated = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder totalMatchersClosed = new java.util.concurrent.atomic.LongAdder();

    // Rejection counters (rare, but use LongAdder for consistency with other counters)
    private final java.util.concurrent.atomic.LongAdder patternLimitRejections = new java.util.concurrent.atomic.LongAdder();
    private final java.util.concurrent.atomic.LongAdder matcherLimitRejections = new java.util.concurrent.atomic.LongAdder();

    public ResourceTracker() {
        // Instance per cache
    }

    /**
     * Tracks a new pattern allocation (called when pattern compiled).
     *
     * @param maxSimultaneous maximum allowed simultaneous patterns
     * @param metricsRegistry optional metrics registry to record errors
     * @throws ResourceException if simultaneous limit exceeded
     */
    public void trackPatternAllocated(int maxSimultaneous, RE2MetricsRegistry metricsRegistry) {
        int current = activePatternsCount.incrementAndGet();
        totalPatternsCompiled.increment();

        if (current > maxSimultaneous) {
            activePatternsCount.decrementAndGet(); // Roll back
            patternLimitRejections.increment();

            // Record resource exhausted error
            if (metricsRegistry != null) {
                metricsRegistry.incrementCounter(MetricNames.ERRORS_RESOURCE_EXHAUSTED);
            }

            throw new com.axonops.libre2.api.ResourceException(
                "Maximum simultaneous compiled patterns exceeded: " + maxSimultaneous +
                " (this is ACTIVE count, not cumulative - patterns can be freed and recompiled)");
        }

        logger.trace("RE2: Pattern allocated - active: {}, cumulative: {}", current, totalPatternsCompiled.sum());
    }

    /**
     * Tracks a pattern being freed (called when pattern closed).
     *
     * @param metricsRegistry optional metrics registry to record freed count
     */
    public void trackPatternFreed(RE2MetricsRegistry metricsRegistry) {
        int currentBefore = activePatternsCount.get();
        int current = activePatternsCount.decrementAndGet();
        totalPatternsClosed.increment();

        // Record pattern freed metric (Counter, not Gauge)
        if (metricsRegistry != null) {
            metricsRegistry.incrementCounter(MetricNames.RESOURCES_PATTERNS_FREED);
        }

        if (current < 0) {
            // Get stack trace to see WHO is calling this incorrectly
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            StringBuilder stackStr = new StringBuilder();
            for (int i = 2; i < Math.min(10, stack.length); i++) {
                stackStr.append("\n  at ").append(stack[i]);
            }
            logger.error("RE2: Pattern count went negative! before={}, after={}, Stack trace:{}",
                currentBefore, current, stackStr.toString());
            activePatternsCount.set(0);
        }

        logger.trace("RE2: Pattern freed - active: {}, cumulative closed: {}", current, totalPatternsClosed.sum());
    }

    /**
     * Gets current ACTIVE (simultaneous) pattern count.
     *
     * @return number of patterns currently active
     */
    public int getActivePatternCount() {
        return activePatternsCount.get();
    }

    /**
     * Gets total patterns compiled over library lifetime (cumulative).
     */
    public long getTotalPatternsCompiled() {
        return totalPatternsCompiled.sum();
    }

    /**
     * Gets total patterns closed over library lifetime (cumulative).
     */
    public long getTotalPatternsClosed() {
        return totalPatternsClosed.sum();
    }

    /**
     * Gets rejection count for pattern limit.
     */
    public long getPatternLimitRejections() {
        return patternLimitRejections.sum();
    }

    /**
     * Tracks a new matcher allocation.
     */
    public void trackMatcherAllocated() {
        activeMatchersCount.incrementAndGet();
        totalMatchersCreated.increment();
    }

    /**
     * Tracks a matcher being freed.
     *
     * @param metricsRegistry optional metrics registry to record freed count
     */
    public void trackMatcherFreed(RE2MetricsRegistry metricsRegistry) {
        int current = activeMatchersCount.decrementAndGet();
        totalMatchersClosed.increment();

        // Record matcher freed metric (Counter, not Gauge)
        if (metricsRegistry != null) {
            metricsRegistry.incrementCounter(MetricNames.RESOURCES_MATCHERS_FREED);
        }

        if (current < 0) {
            logger.error("RE2: Matcher count went negative! This is a bug.");
            activeMatchersCount.set(0);
        }
    }

    /**
     * Gets current ACTIVE (simultaneous) matcher count.
     */
    public int getActiveMatcherCount() {
        return activeMatchersCount.get();
    }

    /**
     * Gets total matchers created over library lifetime.
     */
    public long getTotalMatchersCreated() {
        return totalMatchersCreated.sum();
    }

    /**
     * Gets total matchers closed over library lifetime.
     */
    public long getTotalMatchersClosed() {
        return totalMatchersClosed.sum();
    }

    /**
     * Gets rejection count for matcher limit.
     */
    public long getMatcherLimitRejections() {
        return matcherLimitRejections.sum();
    }

    /**
     * Resets all counters (for testing only).
     */
    public void reset() {
        activePatternsCount.set(0);
        activeMatchersCount.set(0);
        totalPatternsCompiled.reset();
        totalPatternsClosed.reset();
        totalMatchersCreated.reset();
        totalMatchersClosed.reset();
        patternLimitRejections.reset();
        matcherLimitRejections.reset();
        logger.trace("RE2: ResourceTracker reset");
    }

    /**
     * Gets statistics snapshot.
     */
    public ResourceStatistics getStatistics() {
        return new ResourceStatistics(
            activePatternsCount.get(),
            totalPatternsCompiled.sum(),
            totalPatternsClosed.sum(),
            patternLimitRejections.sum(),
            matcherLimitRejections.sum()
        );
    }

    public record ResourceStatistics(
        int activePatterns,
        long totalCompiled,
        long totalClosed,
        long patternLimitRejections,
        long matcherLimitRejections
    ) {
        public boolean hasPotentialLeaks() {
            // If cumulative compiled > cumulative closed + active, we have leaks
            return totalCompiled > (totalClosed + activePatterns);
        }
    }
}
