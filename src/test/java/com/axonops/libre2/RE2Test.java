package com.axonops.libre2;

import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.api.RE2;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Basic integration test for RE2.
 */
class RE2Test {

    @Test
    void testSimpleMatch() {
        boolean matches = RE2.matches("hello", "hello");
        assertThat(matches).isTrue();
    }

    @Test
    void testSimpleNonMatch() {
        boolean matches = RE2.matches("hello", "world");
        assertThat(matches).isFalse();
    }

    @Test
    void testPatternCompilation() {
        try (Pattern pattern = RE2.compile("test.*pattern")) {
            assertThat(pattern.pattern()).isEqualTo("test.*pattern");
            assertThat(pattern.isCaseSensitive()).isTrue();
        }
    }

    @Test
    void testCaseInsensitiveMatch() {
        try (Pattern pattern = RE2.compile("HELLO", false)) {
            assertThat(pattern.matches("hello")).isTrue();
            assertThat(pattern.matches("HELLO")).isTrue();
        }
    }

    @Test
    void testPartialMatch() {
        try (Pattern pattern = RE2.compile("world")) {
            try (var matcher = pattern.matcher("hello world")) {
                assertThat(matcher.find()).isTrue();
            }
        }
    }
}
