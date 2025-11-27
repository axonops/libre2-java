package com.axonops.libre2.metrics;

import static org.assertj.core.api.Assertions.*;

import com.axonops.libre2.api.Matcher;
import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.cache.PatternCache;
import com.axonops.libre2.cache.RE2Config;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests verifying that Timer metrics provide comprehensive histogram statistics.
 *
 * <p>Dropwizard Timers automatically track: - Count, min, max, mean, median - Percentiles: 75th,
 * 95th, 98th, 99th, 99.9th - Rates: 1-min, 5-min, 15-min moving averages
 */
class TimerHistogramIT {

  private MetricRegistry registry;
  private PatternCache originalCache;

  @BeforeEach
  void setup() {
    originalCache = Pattern.getGlobalCache();
    registry = new MetricRegistry();

    RE2Config config =
        RE2Config.builder()
            .metricsRegistry(new DropwizardMetricsAdapter(registry, "histogram.test"))
            .build();

    Pattern.setGlobalCache(new PatternCache(config));
  }

  @AfterEach
  void cleanup() {
    Pattern.setGlobalCache(originalCache);
  }

  @Test
  void testCompilationLatency_ProvidesHistogramStats() {
    // Compile multiple patterns to generate latency data
    for (int i = 0; i < 100; i++) {
      Pattern.compile("pattern_" + i);
    }

    Timer compilationLatency = registry.timer("histogram.test.patterns.compilation.latency");

    // Verify count
    assertThat(compilationLatency.getCount()).as("Timer should track count").isEqualTo(100);

    // Verify rates (1/5/15 minute moving averages)
    assertThat(compilationLatency.getOneMinuteRate())
        .as("Timer should provide 1-minute rate")
        .isGreaterThanOrEqualTo(0.0);

    assertThat(compilationLatency.getFiveMinuteRate())
        .as("Timer should provide 5-minute rate")
        .isGreaterThanOrEqualTo(0.0);

    assertThat(compilationLatency.getFifteenMinuteRate())
        .as("Timer should provide 15-minute rate")
        .isGreaterThanOrEqualTo(0.0);

    // Verify snapshot provides histogram statistics
    Snapshot snapshot = compilationLatency.getSnapshot();

    assertThat(snapshot.getMin()).as("Timer should track minimum latency").isGreaterThan(0L);

    assertThat(snapshot.getMax()).as("Timer should track maximum latency").isGreaterThan(0L);

    assertThat(snapshot.getMean()).as("Timer should track mean latency").isGreaterThan(0.0);

    assertThat(snapshot.getMedian())
        .as("Timer should track median (50th percentile)")
        .isGreaterThan(0.0);

    // Verify percentiles
    assertThat(snapshot.get75thPercentile())
        .as("Timer should provide 75th percentile")
        .isGreaterThan(0.0);

    assertThat(snapshot.get95thPercentile())
        .as("Timer should provide 95th percentile")
        .isGreaterThan(0.0);

    assertThat(snapshot.get98thPercentile())
        .as("Timer should provide 98th percentile")
        .isGreaterThan(0.0);

    assertThat(snapshot.get99thPercentile())
        .as("Timer should provide 99th percentile")
        .isGreaterThan(0.0);

    assertThat(snapshot.get999thPercentile())
        .as("Timer should provide 99.9th percentile")
        .isGreaterThan(0.0);

    // Verify min <= mean <= max (sanity check)
    assertThat(snapshot.getMean())
        .as("Mean should be between min and max")
        .isBetween((double) snapshot.getMin(), (double) snapshot.getMax());
  }

  @Test
  void testMatchingLatency_ProvidesHistogramStats() {
    Pattern pattern = Pattern.compile("test.*");

    // Perform 50 matches to generate latency data
    for (int i = 0; i < 50; i++) {
      try (Matcher m = pattern.matcher("test" + i)) {
        m.matches();
      }
    }

    Timer fullMatchLatency = registry.timer("histogram.test.matching.full_match.latency");

    // Verify count
    assertThat(fullMatchLatency.getCount())
        .as("Should have 50 full match operations")
        .isEqualTo(50);

    // Verify histogram stats available
    Snapshot snapshot = fullMatchLatency.getSnapshot();

    assertThat(snapshot.getMin()).as("Should track minimum match latency").isGreaterThan(0L);

    assertThat(snapshot.getMax()).as("Should track maximum match latency").isGreaterThan(0L);

    assertThat(snapshot.get99thPercentile())
        .as("Should provide 99th percentile match latency")
        .isGreaterThan(0.0);

    // Verify max >= mean >= min
    assertThat(snapshot.getMax())
        .as("Max should be >= mean")
        .isGreaterThanOrEqualTo((long) snapshot.getMean());

    assertThat(snapshot.getMean())
        .as("Mean should be >= min")
        .isGreaterThanOrEqualTo((double) snapshot.getMin());
  }

  @Test
  void testPartialMatchLatency_TrackedSeparately() {
    Pattern pattern = Pattern.compile("test.*");

    // Perform partial matches
    for (int i = 0; i < 30; i++) {
      try (Matcher m = pattern.matcher("test" + i)) {
        m.find();
      }
    }

    Timer partialMatchLatency = registry.timer("histogram.test.matching.partial_match.latency");

    // Verify tracked separately from full match
    assertThat(partialMatchLatency.getCount())
        .as("Partial match should be tracked separately")
        .isEqualTo(30);

    // Verify histogram available
    Snapshot snapshot = partialMatchLatency.getSnapshot();
    assertThat(snapshot.get95thPercentile())
        .as("95th percentile should be available for partial match")
        .isGreaterThan(0.0);
  }

  @Test
  void testTimerUnits_Nanoseconds() {
    // Compile a pattern
    Pattern.compile("test.*");

    Timer compilationLatency = registry.timer("histogram.test.patterns.compilation.latency");

    // Verify latency is in nanoseconds (should be small values)
    Snapshot snapshot = compilationLatency.getSnapshot();

    // Typical compilation: 10,000ns - 10,000,000ns (10μs - 10ms)
    assertThat(snapshot.getMean())
        .as("Latency should be in nanoseconds (10μs - 100ms range)")
        .isBetween(1000.0, 100_000_000.0);
  }
}
