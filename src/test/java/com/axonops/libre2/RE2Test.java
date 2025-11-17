package com.axonops.libre2;

import com.axonops.libre2.api.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive integration tests for RE2.
 */
class RE2Test {

    // ===== Basic Matching Tests =====

    @Test
    void testSimpleExactMatch() {
        assertThat(RE2.matches("hello", "hello")).isTrue();
        assertThat(RE2.matches("hello", "world")).isFalse();
    }

    @Test
    void testEmptyPattern() {
        // Empty patterns are rejected by RE2 wrapper
        assertThatThrownBy(() -> RE2.matches("", ""))
            .isInstanceOf(PatternCompilationException.class);
    }

    @Test
    void testEmptyInput() {
        try (Pattern p = RE2.compile(".*")) {
            assertThat(p.matches("")).isTrue();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "hello, hello, true",
        "hello, HELLO, false",
        "hello, hello world, false",  // Full match requires entire string
        "^hello, hello world, false",  // Full match
        "world$, hello world, false"   // Full match
    })
    void testFullMatchBehavior(String pattern, String input, boolean shouldMatch) {
        try (Pattern p = RE2.compile(pattern)) {
            assertThat(p.matches(input)).isEqualTo(shouldMatch);
        }
    }

    @ParameterizedTest
    @CsvSource({
        "hello, hello world, true",
        "world, hello world, true",
        "goodbye, hello world, false",
        "^hello, hello world, true",
        "world$, hello world, true"
    })
    void testPartialMatchBehavior(String pattern, String input, boolean shouldMatch) {
        try (Pattern p = RE2.compile(pattern)) {
            try (Matcher m = p.matcher(input)) {
                assertThat(m.find()).isEqualTo(shouldMatch);
            }
        }
    }

    // ===== Case Sensitivity Tests =====

    @Test
    void testCaseSensitiveMatching() {
        try (Pattern p = RE2.compile("HELLO", true)) {
            assertThat(p.matches("HELLO")).isTrue();
            assertThat(p.matches("hello")).isFalse();
            assertThat(p.matches("HeLLo")).isFalse();
        }
    }

    @Test
    void testCaseInsensitiveMatching() {
        try (Pattern p = RE2.compile("HELLO", false)) {
            assertThat(p.matches("HELLO")).isTrue();
            assertThat(p.matches("hello")).isTrue();
            assertThat(p.matches("HeLLo")).isTrue();
            assertThat(p.matches("hElLo")).isTrue();
        }
    }

    // ===== Regex Feature Tests =====

    @ParameterizedTest
    @CsvSource({
        "\\d+, 123, true",
        "\\d+, abc, false",
        "\\w+, hello123, true",
        "\\w+, !, false",
        "\\s+, '   ', true",
        "\\s+, text, false",
        "[a-z]+, abc, true",
        "[a-z]+, ABC, false",
        "[0-9]{3}, 123, true",
        "[0-9]{3}, 12, false"
    })
    void testCharacterClasses(String pattern, String input, boolean shouldMatch) {
        try (Pattern p = RE2.compile(pattern)) {
            assertThat(p.matches(input)).isEqualTo(shouldMatch);
        }
    }

    @Test
    void testRepetitionZeroOrMore() {
        try (Pattern p = RE2.compile("a*")) {
            assertThat(p.matches("")).isTrue();
            assertThat(p.matches("a")).isTrue();
            assertThat(p.matches("aaa")).isTrue();
        }
    }

    @Test
    void testRepetitionOneOrMore() {
        try (Pattern p = RE2.compile("a+")) {
            assertThat(p.matches("")).isFalse();
            assertThat(p.matches("a")).isTrue();
            assertThat(p.matches("aaa")).isTrue();
        }
    }

    @Test
    void testRepetitionOptional() {
        try (Pattern p = RE2.compile("a?")) {
            assertThat(p.matches("")).isTrue();
            assertThat(p.matches("a")).isTrue();
            assertThat(p.matches("aa")).isFalse();
        }
    }

    @Test
    void testRepetitionExactCount() {
        try (Pattern p = RE2.compile("a{2}")) {
            assertThat(p.matches("aa")).isTrue();
            assertThat(p.matches("a")).isFalse();
            assertThat(p.matches("aaa")).isFalse();
        }
    }

    @Test
    void testRepetitionRange() {
        try (Pattern p = RE2.compile("a{2,4}")) {
            assertThat(p.matches("a")).isFalse();
            assertThat(p.matches("aa")).isTrue();
            assertThat(p.matches("aaa")).isTrue();
            assertThat(p.matches("aaaa")).isTrue();
            assertThat(p.matches("aaaaa")).isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "abc|def",
        "(hello|world)",
        "cat|dog|bird",
        "\\d+|\\w+"
    })
    void testAlternation(String pattern) {
        try (Pattern p = RE2.compile(pattern)) {
            assertThat(p).isNotNull();
        }
    }

    @Test
    void testDotMetacharacter() {
        try (Pattern p = RE2.compile("a.c")) {
            assertThat(p.matches("abc")).isTrue();
            assertThat(p.matches("axc")).isTrue();
            assertThat(p.matches("ac")).isFalse();
        }
    }

    @Test
    void testAnchors() {
        try (Pattern start = RE2.compile("^hello")) {
            try (Matcher m = start.matcher("hello world")) {
                assertThat(m.find()).isTrue();
            }
            try (Matcher m = start.matcher("say hello")) {
                assertThat(m.find()).isFalse();
            }
        }

        try (Pattern end = RE2.compile("world$")) {
            try (Matcher m = end.matcher("hello world")) {
                assertThat(m.find()).isTrue();
            }
            try (Matcher m = end.matcher("world hello")) {
                assertThat(m.find()).isFalse();
            }
        }
    }

    // ===== UTF-8 and Special Characters =====

    @Test
    void testUTF8Characters() {
        try (Pattern p = RE2.compile("hello")) {
            assertThat(p.matches("hello")).isTrue();
        }

        try (Pattern p = RE2.compile("café")) {
            assertThat(p.matches("café")).isTrue();
        }

        try (Pattern p = RE2.compile("日本語")) {
            assertThat(p.matches("日本語")).isTrue();
        }

        try (Pattern p = RE2.compile("emoji😀test")) {
            assertThat(p.matches("emoji😀test")).isTrue();
        }
    }

    @Test
    void testSpecialRegexCharacters() {
        try (Pattern p = RE2.compile("\\(\\)\\[\\]\\{\\}")) {
            assertThat(p.matches("()[]{}")).isTrue();
        }

        try (Pattern p = RE2.compile("\\.\\*\\+\\?")) {
            assertThat(p.matches(".*+?")).isTrue();
        }
    }

    // ===== Email and URL Pattern Tests =====

    @Test
    void testEmailPattern() {
        String emailPattern = "\\w+@\\w+\\.\\w+";
        try (Pattern p = RE2.compile(emailPattern)) {
            assertThat(p.matches("user@example.com")).isTrue();
            assertThat(p.matches("invalid.email")).isFalse();
            assertThat(p.matches("@example.com")).isFalse();
        }
    }

    @Test
    void testURLPattern() {
        String urlPattern = "https?://[\\w.]+(/.*)?";
        try (Pattern p = RE2.compile(urlPattern)) {
            try (Matcher m = p.matcher("https://example.com/path")) {
                assertThat(m.find()).isTrue();
            }
            try (Matcher m = p.matcher("http://test.org")) {
                assertThat(m.find()).isTrue();
            }
            try (Matcher m = p.matcher("ftp://example.com")) {
                assertThat(m.find()).isFalse();
            }
        }
    }

    // ===== Error Handling Tests =====

    @Test
    void testNullPatternThrows() {
        assertThatThrownBy(() -> RE2.compile(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullInputThrows() {
        try (Pattern p = RE2.compile("test")) {
            assertThatThrownBy(() -> p.matcher(null))
                .isInstanceOf(NullPointerException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "(unclosed",
        "(?P<incomplete",
        "*invalid",
        "(?P<>empty)",
        "[[invalid"
    })
    void testInvalidPatternThrows(String invalidPattern) {
        assertThatThrownBy(() -> RE2.compile(invalidPattern))
            .isInstanceOf(PatternCompilationException.class)
            .hasMessageContaining("compilation failed");
    }

    @Test
    void testPatternCompilationExceptionContainsPattern() {
        try {
            RE2.compile("(unclosed");
            fail("Should have thrown PatternCompilationException");
        } catch (PatternCompilationException e) {
            assertThat(e.getPattern()).isEqualTo("(unclosed");
            assertThat(e.getMessage()).contains("unclosed");
        }
    }

    // ===== Resource Management Tests =====

    @Test
    void testPatternClose() {
        Pattern p = RE2.compile("test");
        assertThat(p.isClosed()).isFalse();

        p.close();
        assertThat(p.isClosed()).isTrue();
    }

    @Test
    void testUseAfterClose() {
        Pattern p = RE2.compile("test");
        p.close();

        assertThatThrownBy(() -> p.matcher("input"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("closed");
    }

    @Test
    void testDoubleClose() {
        Pattern p = RE2.compile("test");
        p.close();

        // Second close should be idempotent (not throw)
        assertThatCode(p::close).doesNotThrowAnyException();
    }

    @Test
    void testTryWithResources() {
        // Verify AutoCloseable works correctly
        Pattern[] holder = new Pattern[1];

        try (Pattern p = RE2.compile("test")) {
            holder[0] = p;
            assertThat(p.isClosed()).isFalse();
        }

        assertThat(holder[0].isClosed()).isTrue();
    }

    @Test
    void testNestedTryWithResources() {
        try (Pattern p = RE2.compile("test")) {
            try (Matcher m = p.matcher("test")) {
                assertThat(m.matches()).isTrue();
            }
        }
    }

    @Test
    void testMultiplePatternsIndependent() {
        try (Pattern p1 = RE2.compile("pattern1");
             Pattern p2 = RE2.compile("pattern2");
             Pattern p3 = RE2.compile("pattern3")) {

            assertThat(p1.matches("pattern1")).isTrue();
            assertThat(p2.matches("pattern2")).isTrue();
            assertThat(p3.matches("pattern3")).isTrue();

            assertThat(p1.matches("pattern2")).isFalse();
        }
    }

    // ===== Complex Pattern Tests =====

    @Test
    void testIPv4Pattern() {
        String ipPattern = "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}";
        try (Pattern p = RE2.compile(ipPattern)) {
            assertThat(p.matches("192.168.1.1")).isTrue();
            assertThat(p.matches("10.0.0.1")).isTrue();
            assertThat(p.matches("999.999.999.999")).isTrue(); // Matches pattern, not valid IP
            assertThat(p.matches("192.168.1")).isFalse();
        }
    }

    @Test
    void testDatePattern() {
        String datePattern = "\\d{4}-\\d{2}-\\d{2}";
        try (Pattern p = RE2.compile(datePattern)) {
            assertThat(p.matches("2025-11-17")).isTrue();
            assertThat(p.matches("2025-1-17")).isFalse();
            assertThat(p.matches("25-11-17")).isFalse();
        }
    }

    @Test
    void testPhoneNumberPattern() {
        String phonePattern = "\\(?\\d{3}\\)?[- ]?\\d{3}[- ]?\\d{4}";
        try (Pattern p = RE2.compile(phonePattern)) {
            try (Matcher m = p.matcher("(555) 123-4567")) {
                assertThat(m.find()).isTrue();
            }
            try (Matcher m = p.matcher("555-123-4567")) {
                assertThat(m.find()).isTrue();
            }
            try (Matcher m = p.matcher("5551234567")) {
                assertThat(m.find()).isTrue();
            }
        }
    }

    @Test
    void testComplexAlternation() {
        try (Pattern p = RE2.compile("(cat|dog|bird|fish)")) {
            assertThat(p.matches("cat")).isTrue();
            assertThat(p.matches("dog")).isTrue();
            assertThat(p.matches("fish")).isTrue();
            assertThat(p.matches("cow")).isFalse();
        }
    }

    @Test
    void testNestedGroups() {
        try (Pattern p = RE2.compile("((a|b)(c|d))")) {
            assertThat(p.matches("ac")).isTrue();
            assertThat(p.matches("ad")).isTrue();
            assertThat(p.matches("bc")).isTrue();
            assertThat(p.matches("bd")).isTrue();
            assertThat(p.matches("ab")).isFalse();
        }
    }

    // ===== Edge Cases =====

    @Test
    void testVeryLongPattern() {
        String longPattern = "a".repeat(1000);
        try (Pattern p = RE2.compile(longPattern)) {
            assertThat(p.matches(longPattern)).isTrue();
            assertThat(p.matches("a".repeat(999))).isFalse();
        }
    }

    @Test
    void testVeryLongInput() {
        String longInput = "x".repeat(10000) + "needle" + "y".repeat(10000);
        try (Pattern p = RE2.compile("needle")) {
            try (Matcher m = p.matcher(longInput)) {
                assertThat(m.find()).isTrue();
            }
        }
    }

    @Test
    void testPatternWithManyAlternatives() {
        StringBuilder pattern = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            if (i > 0) pattern.append("|");
            pattern.append("word").append(i);
        }

        try (Pattern p = RE2.compile(pattern.toString())) {
            assertThat(p.matches("word50")).isTrue();
            assertThat(p.matches("word99")).isTrue();
            assertThat(p.matches("word100")).isFalse();
        }
    }

    @Test
    void testDeepNesting() {
        String pattern = "((((((((((a))))))))))";
        try (Pattern p = RE2.compile(pattern)) {
            assertThat(p.matches("a")).isTrue();
        }
    }

    // ===== Whitespace and Special Input Tests =====

    @Test
    void testWhitespaceMatching() {
        try (Pattern p = RE2.compile("\\s+")) {
            assertThat(p.matches("   ")).isTrue();
            assertThat(p.matches("\t\n")).isTrue();
            assertThat(p.matches("text")).isFalse();
        }
    }

    @Test
    void testNewlinesInInput() {
        try (Pattern p = RE2.compile("hello")) {
            try (Matcher m = p.matcher("hello\nworld")) {
                assertThat(m.find()).isTrue();
            }
        }
    }

    @Test
    void testTabsInInput() {
        try (Pattern p = RE2.compile("hello\tworld")) {
            assertThat(p.matches("hello\tworld")).isTrue();
        }
    }

    // ===== Resource Leak Tests =====

    @Test
    void testManyPatternsNoLeak() {
        // Compile and close many patterns (tests resource cleanup)
        for (int i = 0; i < 1000; i++) {
            try (Pattern p = RE2.compile("pattern" + i)) {
                assertThat(p).isNotNull();
            }
        }
    }

    @Test
    void testManyMatchersNoLeak() {
        try (Pattern p = RE2.compile("test")) {
            for (int i = 0; i < 1000; i++) {
                try (Matcher m = p.matcher("test" + i)) {
                    m.matches();
                }
            }
        }
    }

    // ===== Concurrent Access Tests =====

    @Test
    void testConcurrentPatternCompilation() throws InterruptedException {
        int threadCount = 10;
        int patternsPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            Thread thread = new Thread(() -> {
                try {
                    for (int i = 0; i < patternsPerThread; i++) {
                        try (Pattern p = RE2.compile("thread" + threadId + "pattern" + i)) {
                            p.matches("thread" + threadId + "pattern" + i);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
            threads.add(thread);
            thread.start();
        }

        latch.await();
        assertThat(errors.get()).isEqualTo(0);
    }

    // ===== Pattern Properties Tests =====

    @Test
    void testPatternProperties() {
        try (Pattern p = RE2.compile("test.*pattern", false)) {
            assertThat(p.pattern()).isEqualTo("test.*pattern");
            assertThat(p.isCaseSensitive()).isFalse();
            assertThat(p.isClosed()).isFalse();
        }
    }

    @Test
    void testMatcherProperties() {
        try (Pattern p = RE2.compile("test")) {
            try (Matcher m = p.matcher("input")) {
                assertThat(m.pattern()).isSameAs(p);
                assertThat(m.input()).isEqualTo("input");
            }
        }
    }

    // ===== ReDoS Safety Tests =====

    @Test
    void testReDoSSafePatterns() {
        // Patterns that would cause catastrophic backtracking in Java regex
        // RE2 handles these in linear time

        String[] redosPatterns = {
            "(a+)+b",
            "(a*)*b",
            "(a|a)*b",
            "(a|ab)*c"
        };

        for (String pattern : redosPatterns) {
            try (Pattern p = RE2.compile(pattern)) {
                // These should complete quickly (RE2 is linear time)
                // In Java regex, these would hang on long non-matching input
                String input = "a".repeat(100) + "x";

                try (Matcher m = p.matcher(input)) {
                    long start = System.currentTimeMillis();
                    boolean matches = m.find();
                    long duration = System.currentTimeMillis() - start;

                    // Should complete in milliseconds, not seconds
                    assertThat(duration).isLessThan(100);
                }
            }
        }
    }

    // ===== toString() Tests =====

    @Test
    void testToStringDoesNotThrow() {
        try (Pattern p = RE2.compile("test")) {
            assertThat(p.toString()).isNotNull();

            try (Matcher m = p.matcher("input")) {
                assertThat(m.toString()).isNotNull();
            }
        }
    }

    // ===== Large Scale Tests =====

    @Test
    void testManyDifferentPatterns() {
        // Test each pattern with matching and non-matching input
        try (Pattern p = RE2.compile("\\d+")) {
            assertThat(p.matches("123")).isTrue();
            assertThat(p.matches("abc")).isFalse();
        }

        try (Pattern p = RE2.compile("\\w+")) {
            assertThat(p.matches("hello123")).isTrue();
            assertThat(p.matches("!!!")).isFalse();
        }

        try (Pattern p = RE2.compile("\\s+")) {
            assertThat(p.matches("   ")).isTrue();
            assertThat(p.matches("text")).isFalse();
        }

        try (Pattern p = RE2.compile("[a-z]+")) {
            assertThat(p.matches("abc")).isTrue();
            assertThat(p.matches("ABC")).isFalse();
        }

        try (Pattern p = RE2.compile("[A-Z]+")) {
            assertThat(p.matches("ABC")).isTrue();
            assertThat(p.matches("abc")).isFalse();
        }

        try (Pattern p = RE2.compile(".*")) {
            assertThat(p.matches("anything")).isTrue();
            assertThat(p.matches("")).isTrue();
        }

        try (Pattern p = RE2.compile(".+")) {
            assertThat(p.matches("something")).isTrue();
            assertThat(p.matches("")).isFalse();
        }

        try (Pattern p = RE2.compile("a*")) {
            assertThat(p.matches("aaa")).isTrue();
            assertThat(p.matches("")).isTrue();
            assertThat(p.matches("b")).isFalse();
        }

        try (Pattern p = RE2.compile("a+")) {
            assertThat(p.matches("aaa")).isTrue();
            assertThat(p.matches("")).isFalse();
        }

        try (Pattern p = RE2.compile("^start")) {
            try (Matcher m = p.matcher("start here")) {
                assertThat(m.find()).isTrue();
            }
            try (Matcher m = p.matcher("here start")) {
                assertThat(m.find()).isFalse();
            }
        }

        try (Pattern p = RE2.compile("end$")) {
            try (Matcher m = p.matcher("at the end")) {
                assertThat(m.find()).isTrue();
            }
            try (Matcher m = p.matcher("end here")) {
                assertThat(m.find()).isFalse();
            }
        }

        try (Pattern p = RE2.compile("^exact$")) {
            assertThat(p.matches("exact")).isTrue();
            assertThat(p.matches("exact ")).isFalse();
            assertThat(p.matches(" exact")).isFalse();
        }

        try (Pattern p = RE2.compile("hello|world")) {
            assertThat(p.matches("hello")).isTrue();
            assertThat(p.matches("world")).isTrue();
            assertThat(p.matches("goodbye")).isFalse();
        }

        try (Pattern p = RE2.compile("(cat|dog)")) {
            assertThat(p.matches("cat")).isTrue();
            assertThat(p.matches("dog")).isTrue();
            assertThat(p.matches("bird")).isFalse();
        }

        try (Pattern p = RE2.compile("\\d{3}-\\d{4}")) {
            assertThat(p.matches("123-4567")).isTrue();
            assertThat(p.matches("12-4567")).isFalse();
        }

        try (Pattern p = RE2.compile("\\w+@\\w+\\.\\w+")) {
            assertThat(p.matches("user@example.com")).isTrue();
            assertThat(p.matches("invalid")).isFalse();
        }
    }

    @Test
    void testQuickSuccessiveOperations() {
        // Test rapid pattern creation and matching (stress test)
        for (int i = 0; i < 100; i++) {
            boolean matches = RE2.matches("test" + i, "test" + i);
            assertThat(matches).isTrue();

            // Also verify non-match
            matches = RE2.matches("test" + i, "different" + i);
            assertThat(matches).isFalse();
        }
    }

    @Test
    void testPatternReuseManyTimes() {
        // Test a single pattern used many times
        try (Pattern p = RE2.compile("\\d+")) {
            for (int i = 0; i < 1000; i++) {
                assertThat(p.matches(String.valueOf(i))).isTrue();
                assertThat(p.matches("text" + i)).isFalse();
            }
        }
    }
}

