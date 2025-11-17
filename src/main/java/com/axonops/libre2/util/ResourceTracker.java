package com.axonops.libre2.util;

import com.axonops.libre2.api.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks pattern resource usage for enforcing limits and monitoring.
 *
 * CRITICAL: Tracks ACTIVE (simultaneous) patterns, not cumulative total.
 *
 * @since 1.0.0
 */
public final class ResourceTracker {
    private static final Logger logger = LoggerFactory.getLogger(ResourceTracker.class);

    // ACTIVE counts (current simultaneous)
    private static final AtomicInteger activePatternsCount = new AtomicInteger(0);

    // Cumulative counters (lifetime, for metrics only)
    private static final AtomicLong totalPatternsCompiled = new AtomicLong(0);
    private static final AtomicLong totalPatternsClosed = new AtomicLong(0);

    // Rejection counters
    private static final AtomicLong patternLimitRejections = new AtomicLong(0);
    private static final AtomicLong matcherLimitRejections = new AtomicLong(0);

    private ResourceTracker() {
        // Utility class
    }

    /**
     * Tracks a new pattern allocation (called when pattern compiled).
     *
     * @param maxSimultaneous maximum allowed simultaneous patterns
     * @throws ResourceException if simultaneous limit exceeded
     */
    public static void trackPatternAllocated(int maxSimultaneous) {
        int current = activePatternsCount.incrementAndGet();
        totalPatternsCompiled.incrementAndGet();

        if (current > maxSimultaneous) {
            activePatternsCount.decrementAndGet(); // Roll back
            patternLimitRejections.incrementAndGet();

            throw new com.axonops.libre2.api.ResourceException(
                "Maximum simultaneous compiled patterns exceeded: " + maxSimultaneous +
                " (this is ACTIVE count, not cumulative - patterns can be freed and recompiled)");
        }

        logger.debug("RE2: Pattern allocated - active: {}, cumulative: {}", current, totalPatternsCompiled.get());
    }

    /**
     * Tracks a pattern being freed (called when pattern closed).
     */
    public static void trackPatternFreed() {
        int current = activePatternsCount.decrementAndGet();
        totalPatternsClosed.incrementAndGet();

        if (current < 0) {
            logger.error("RE2: Pattern count went negative! This is a bug.");
            activePatternsCount.set(0);
        }

        logger.debug("RE2: Pattern freed - active: {}, cumulative closed: {}", current, totalPatternsClosed.get());
    }

    /**
     * Gets current ACTIVE (simultaneous) pattern count.
     *
     * @return number of patterns currently active
     */
    public static int getActivePatternCount() {
        return activePatternsCount.get();
    }

    /**
     * Gets total patterns compiled over library lifetime (cumulative).
     */
    public static long getTotalPatternsCompiled() {
        return totalPatternsCompiled.get();
    }

    /**
     * Gets total patterns closed over library lifetime (cumulative).
     */
    public static long getTotalPatternsClosed() {
        return totalPatternsClosed.get();
    }

    /**
     * Gets rejection count for pattern limit.
     */
    public static long getPatternLimitRejections() {
        return patternLimitRejections.get();
    }

    /**
     * Gets rejection count for matcher limit.
     */
    public static long getMatcherLimitRejections() {
        return matcherLimitRejections.get();
    }

    /**
     * Resets all counters (for testing only).
     */
    public static void reset() {
        activePatternsCount.set(0);
        totalPatternsCompiled.set(0);
        totalPatternsClosed.set(0);
        patternLimitRejections.set(0);
        matcherLimitRejections.set(0);
        logger.debug("RE2: ResourceTracker reset");
    }

    /**
     * Gets statistics snapshot.
     */
    public static ResourceStatistics getStatistics() {
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
