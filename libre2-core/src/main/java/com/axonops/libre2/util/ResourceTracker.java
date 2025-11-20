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

    // ACTIVE counts (current simultaneous)
    private final AtomicInteger activePatternsCount = new AtomicInteger(0);
    private final AtomicInteger activeMatchersCount = new AtomicInteger(0);

    // Cumulative counters (lifetime, for metrics only)
    private final AtomicLong totalPatternsCompiled = new AtomicLong(0);
    private final AtomicLong totalPatternsClosed = new AtomicLong(0);
    private final AtomicLong totalMatchersCreated = new AtomicLong(0);
    private final AtomicLong totalMatchersClosed = new AtomicLong(0);

    // Rejection counters
    private final AtomicLong patternLimitRejections = new AtomicLong(0);
    private final AtomicLong matcherLimitRejections = new AtomicLong(0);

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
        totalPatternsCompiled.incrementAndGet();

        if (current > maxSimultaneous) {
            activePatternsCount.decrementAndGet(); // Roll back
            patternLimitRejections.incrementAndGet();

            // Record resource exhausted error
            if (metricsRegistry != null) {
                metricsRegistry.incrementCounter("errors.resource.exhausted.total.count");
            }

            throw new com.axonops.libre2.api.ResourceException(
                "Maximum simultaneous compiled patterns exceeded: " + maxSimultaneous +
                " (this is ACTIVE count, not cumulative - patterns can be freed and recompiled)");
        }

        logger.trace("RE2: Pattern allocated - active: {}, cumulative: {}", current, totalPatternsCompiled.get());
    }

    /**
     * Tracks a pattern being freed (called when pattern closed).
     *
     * @param metricsRegistry optional metrics registry to record freed count
     */
    public void trackPatternFreed(RE2MetricsRegistry metricsRegistry) {
        int currentBefore = activePatternsCount.get();
        int current = activePatternsCount.decrementAndGet();
        totalPatternsClosed.incrementAndGet();

        // Record pattern freed metric (Counter, not Gauge)
        if (metricsRegistry != null) {
            metricsRegistry.incrementCounter("resources.patterns.freed.total.count");
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

        logger.trace("RE2: Pattern freed - active: {}, cumulative closed: {}", current, totalPatternsClosed.get());
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
        return totalPatternsCompiled.get();
    }

    /**
     * Gets total patterns closed over library lifetime (cumulative).
     */
    public long getTotalPatternsClosed() {
        return totalPatternsClosed.get();
    }

    /**
     * Gets rejection count for pattern limit.
     */
    public long getPatternLimitRejections() {
        return patternLimitRejections.get();
    }

    /**
     * Tracks a new matcher allocation.
     */
    public void trackMatcherAllocated() {
        activeMatchersCount.incrementAndGet();
        totalMatchersCreated.incrementAndGet();
    }

    /**
     * Tracks a matcher being freed.
     *
     * @param metricsRegistry optional metrics registry to record freed count
     */
    public void trackMatcherFreed(RE2MetricsRegistry metricsRegistry) {
        int current = activeMatchersCount.decrementAndGet();
        totalMatchersClosed.incrementAndGet();

        // Record matcher freed metric (Counter, not Gauge)
        if (metricsRegistry != null) {
            metricsRegistry.incrementCounter("resources.matchers.freed.total.count");
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
        return totalMatchersCreated.get();
    }

    /**
     * Gets total matchers closed over library lifetime.
     */
    public long getTotalMatchersClosed() {
        return totalMatchersClosed.get();
    }

    /**
     * Gets rejection count for matcher limit.
     */
    public long getMatcherLimitRejections() {
        return matcherLimitRejections.get();
    }

    /**
     * Resets all counters (for testing only).
     */
    public void reset() {
        activePatternsCount.set(0);
        activeMatchersCount.set(0);
        totalPatternsCompiled.set(0);
        totalPatternsClosed.set(0);
        totalMatchersCreated.set(0);
        totalMatchersClosed.set(0);
        patternLimitRejections.set(0);
        matcherLimitRejections.set(0);
        logger.trace("RE2: ResourceTracker reset");
    }

    /**
     * Gets statistics snapshot.
     */
    public ResourceStatistics getStatistics() {
        return new ResourceStatistics(
            activePatternsCount.get(),
            totalPatternsCompiled.get(),
            totalPatternsClosed.get(),
            patternLimitRejections.get(),
            matcherLimitRejections.get()
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
