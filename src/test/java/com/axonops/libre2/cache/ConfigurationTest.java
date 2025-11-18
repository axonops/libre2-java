package com.axonops.libre2.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for RE2Config validation and defaults.
 */
class ConfigurationTest {

    @Test
    void testDefaultConfiguration() {
        RE2Config config = RE2Config.DEFAULT;

        assertThat(config.cacheEnabled()).isTrue();
        assertThat(config.maxCacheSize()).isEqualTo(50000);
        assertThat(config.idleTimeoutSeconds()).isEqualTo(300);
        assertThat(config.evictionScanIntervalSeconds()).isEqualTo(60);
        assertThat(config.maxSimultaneousCompiledPatterns()).isEqualTo(100000);
        assertThat(config.maxMatchersPerPattern()).isEqualTo(10000);
    }

    @Test
    void testNoCacheConfiguration() {
        RE2Config config = RE2Config.NO_CACHE;

        assertThat(config.cacheEnabled()).isFalse();
        assertThat(config.maxSimultaneousCompiledPatterns()).isEqualTo(100000);
        assertThat(config.maxMatchersPerPattern()).isEqualTo(10000);
    }

    @Test
    void testBuilderWithAllCustomValues() {
        RE2Config config = RE2Config.builder()
            .cacheEnabled(true)
            .maxCacheSize(10000)
            .idleTimeoutSeconds(600)
            .evictionScanIntervalSeconds(120)
            .maxSimultaneousCompiledPatterns(200000)
            .maxMatchersPerPattern(20000)
            .build();

        assertThat(config.cacheEnabled()).isTrue();
        assertThat(config.maxCacheSize()).isEqualTo(10000);
        assertThat(config.idleTimeoutSeconds()).isEqualTo(600);
        assertThat(config.evictionScanIntervalSeconds()).isEqualTo(120);
        assertThat(config.maxSimultaneousCompiledPatterns()).isEqualTo(200000);
        assertThat(config.maxMatchersPerPattern()).isEqualTo(20000);
    }

    @Test
    void testValidation_InvalidMaxCacheSize_Zero() {
        assertThatThrownBy(() -> new RE2Config(true, 0, 300, 60, 5, 100000, 10000, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxCacheSize must be positive");
    }

    @Test
    void testValidation_InvalidMaxCacheSize_Negative() {
        assertThatThrownBy(() -> new RE2Config(true, -1, 300, 60, 5, 100000, 10000, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxCacheSize must be positive");
    }

    @Test
    void testValidation_InvalidIdleTimeout_Zero() {
        assertThatThrownBy(() -> new RE2Config(true, 1000, 0, 60, 5, 100000, 10000, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("idleTimeoutSeconds must be positive");
    }

    @Test
    void testValidation_InvalidScanInterval_Negative() {
        assertThatThrownBy(() -> new RE2Config(true, 1000, 300, -1, 5, 100000, 10000, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("evictionScanIntervalSeconds must be positive");
    }

    @Test
    void testValidation_InvalidMaxSimultaneousPatterns_Zero() {
        assertThatThrownBy(() -> new RE2Config(true, 1000, 300, 60, 5, 0, 10000, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxSimultaneousCompiledPatterns must be positive");
    }

    @Test
    void testValidation_InvalidMaxMatchers_Zero() {
        assertThatThrownBy(() -> new RE2Config(true, 1000, 300, 60, 5, 100000, 0, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxMatchersPerPattern must be positive");
    }

    @Test
    void testValidation_CacheLargerThanSimultaneousLimit() {
        assertThatThrownBy(() -> new RE2Config(true, 100000, 300, 60, 5, 50000, 10000, true))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxCacheSize")
            .hasMessageContaining("maxSimultaneousCompiledPatterns");
    }

    @Test
    void testValidation_IgnoresInvalidCacheParams_IfCacheDisabled() {
        // Should NOT throw - cache params ignored when disabled
        RE2Config config = new RE2Config(false, -999, -999, -999, -999, 100000, 10000, false);

        assertThat(config.cacheEnabled()).isFalse();
    }

    @Test
    void testValidation_VerySmallCache() {
        // Should work - cache size = 1
        RE2Config config = new RE2Config(true, 1, 300, 60, 5, 100000, 10000, true);

        assertThat(config.maxCacheSize()).isEqualTo(1);
    }

    @Test
    void testValidation_VeryLargeCache() {
        // Should work - cache size = 500K
        RE2Config config = new RE2Config(true, 500000, 300, 60, 5, 1000000, 10000, true);

        assertThat(config.maxCacheSize()).isEqualTo(500000);
    }

    @Test
    void testBuilder_DefaultValues() {
        RE2Config config = RE2Config.builder().build();

        // Should have production defaults
        assertThat(config.cacheEnabled()).isTrue();
        assertThat(config.maxCacheSize()).isEqualTo(50000);
        assertThat(config.maxSimultaneousCompiledPatterns()).isEqualTo(100000);
    }
}
