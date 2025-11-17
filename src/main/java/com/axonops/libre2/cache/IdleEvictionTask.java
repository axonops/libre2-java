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
                config.evictionScanInterval().toSeconds());
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
     */
    private void run() {
        logger.debug("RE2: Idle eviction thread running");

        while (running.get()) {
            try {
                // Sleep for scan interval
                Thread.sleep(config.evictionScanInterval().toMillis());

                // Evict idle patterns
                int evicted = cache.evictIdlePatterns();

                logger.debug("RE2: Idle eviction scan complete - evicted: {}", evicted);

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
