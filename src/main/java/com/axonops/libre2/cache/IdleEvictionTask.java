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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background thread that periodically evicts idle patterns from cache.
 *
 * Runs as daemon thread at low priority to avoid interfering with
 * query execution in Cassandra.
 *
 * @since 1.0.0
 */
final class IdleEvictionTask {
    private static final Logger logger = LoggerFactory.getLogger(IdleEvictionTask.class);

    private final PatternCache cache;
    private final RE2Config config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread thread;

    IdleEvictionTask(PatternCache cache, RE2Config config) {
        this.cache = cache;
        this.config = config;
    }

    /**
     * Starts the eviction thread.
     */
    void start() {
        if (running.compareAndSet(false, true)) {
            thread = new Thread(this::run, "RE2-IdleEviction");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY); // Low priority - don't interfere with queries
            thread.start();

            logger.info("RE2: Idle eviction thread started - interval: {}s",
                config.evictionScanIntervalSeconds());
        }
    }

    /**
     * Stops the eviction thread gracefully.
     */
    void stop() {
        if (running.compareAndSet(true, false)) {
            logger.info("RE2: Stopping idle eviction thread");

            Thread t = thread;
            if (t != null) {
                t.interrupt();
                try {
                    t.join(5000); // Wait up to 5 seconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            logger.info("RE2: Idle eviction thread stopped");
        }
    }

    /**
     * Main eviction loop.
     *
     * Wakes frequently (every 5s) to cleanup deferred patterns.
     * Runs idle eviction less frequently (every 60s by default).
     */
    private void run() {
        logger.debug("RE2: Idle eviction thread running");

        long lastIdleScan = System.currentTimeMillis();
        long idleScanIntervalMs = config.evictionScanIntervalSeconds() * 1000;
        long deferredCleanupIntervalMs = config.deferredCleanupIntervalSeconds() * 1000;

        while (running.get()) {
            try {
                // Sleep for deferred cleanup interval (5 seconds)
                Thread.sleep(deferredCleanupIntervalMs);

                long now = System.currentTimeMillis();

                // Check if time for idle eviction scan
                if (now - lastIdleScan >= idleScanIntervalMs) {
                    // Full scan: idle eviction + deferred cleanup
                    int evicted = cache.evictIdlePatterns();
                    logger.debug("RE2: Idle eviction scan complete - evicted: {}", evicted);
                    lastIdleScan = now;
                } else {
                    // Quick scan: just deferred cleanup
                    int cleaned = cache.cleanupDeferredPatterns();
                    if (cleaned > 0) {
                        logger.debug("RE2: Deferred cleanup - freed {} patterns", cleaned);
                    }
                }

            } catch (InterruptedException e) {
                logger.debug("RE2: Idle eviction thread interrupted");
                break;
            } catch (Exception e) {
                logger.error("RE2: Error in idle eviction thread", e);
                // Continue running despite errors
            }
        }

        logger.debug("RE2: Idle eviction thread exiting");
    }

    /**
     * Checks if eviction thread is running.
     */
    boolean isRunning() {
        return running.get() && thread != null && thread.isAlive();
    }
}
