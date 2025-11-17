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
        // Use compileWithoutCache() to test actual closing
        Pattern p = Pattern.compileWithoutCache("test", true);
        assertThat(p.isClosed()).isFalse();

        p.close();
        assertThat(p.isClosed()).isTrue();
    }

    @Test
    void testUseAfterClose() {
        // Use compileWithoutCache() to test actual closing
        Pattern p = Pattern.compileWithoutCache("test", true);
        p.close();

        assertThatThrownBy(() -> p.matcher("input"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("closed");
    }

    @Test
    void testDoubleClose() {
        // Use compileWithoutCache() to test actual closing
        Pattern p = Pattern.compileWithoutCache("test", true);
        p.close();

        // Second close should be idempotent (not throw)
        assertThatCode(p::close).doesNotThrowAnyException();
    }

    @Test
    void testTryWithResources() {
        // Verify AutoCloseable works correctly with uncached patterns
        Pattern[] holder = new Pattern[1];

        try (Pattern p = Pattern.compileWithoutCache("test", true)) {
            holder[0] = p;
            assertThat(p.isClosed()).isFalse();
        }

        assertThat(holder[0].isClosed()).isTrue();
    }

    @Test
    void testCachedPatternNotClosedOnClose() {
        // Cached patterns should NOT actually close when close() is called
        Pattern p = RE2.compile("test");
        assertThat(p.isClosed()).isFalse();

        p.close(); // This should be a no-op for cached patterns

        // Pattern should still not be closed (cache manages it)
        assertThat(p.isClosed()).isFalse();
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

    // ===== Log Processing Tests (Real-World Use Case) =====

    @Test
    void testTypicalLogEntry() {
        String logEntry = "2025-11-17 10:30:45.123 [INFO] com.example.Service - Processing request id=12345 user=admin@example.com duration=250ms status=200";

        // Find timestamp
        try (Pattern p = RE2.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}")) {
            try (Matcher m = p.matcher(logEntry)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Find log level
        try (Pattern p = RE2.compile("\\[(INFO|WARN|ERROR|DEBUG)\\]")) {
            try (Matcher m = p.matcher(logEntry)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Find user email
        try (Pattern p = RE2.compile("user=\\w+@[\\w.]+")) {
            try (Matcher m = p.matcher(logEntry)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Find duration
        try (Pattern p = RE2.compile("duration=\\d+ms")) {
            try (Matcher m = p.matcher(logEntry)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Find request ID
        try (Pattern p = RE2.compile("id=\\d+")) {
            try (Matcher m = p.matcher(logEntry)) {
                assertThat(m.find()).isTrue();
            }
        }
    }

    @Test
    void testMultiLineLogEntry() {
        String multiLineLog = """
            2025-11-17 10:30:45.123 [ERROR] com.example.Service - Request failed
            java.lang.NullPointerException: Cannot invoke method on null object
                at com.example.Service.processRequest(Service.java:123)
                at com.example.Handler.handle(Handler.java:45)
                at java.base/java.lang.Thread.run(Thread.java:1583)
            Caused by: java.lang.IllegalStateException: Invalid state
                at com.example.Core.validate(Core.java:89)
                ... 10 more
            """;

        // Find error class
        try (Pattern p = RE2.compile("java\\.lang\\.\\w+Exception")) {
            try (Matcher m = p.matcher(multiLineLog)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Find stack trace lines
        try (Pattern p = RE2.compile("at [\\w.$]+\\([\\w.]+:\\d+\\)")) {
            try (Matcher m = p.matcher(multiLineLog)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Find file and line number
        try (Pattern p = RE2.compile("Service\\.java:\\d+")) {
            try (Matcher m = p.matcher(multiLineLog)) {
                assertThat(m.find()).isTrue();
            }
        }
    }

    @Test
    void testLargeLogChunk() {
        // Simulate processing a large log file (10,000 lines)
        StringBuilder largeLog = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            largeLog.append(String.format("2025-11-17 10:%02d:%02d [INFO] Request %d processed successfully%n",
                i / 60, i % 60, i));
        }

        String logText = largeLog.toString();

        // Find all INFO entries
        try (Pattern p = RE2.compile("\\[INFO\\]")) {
            try (Matcher m = p.matcher(logText)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Find specific request ID in large log
        try (Pattern p = RE2.compile("Request 5000 processed")) {
            try (Matcher m = p.matcher(logText)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Pattern that doesn't exist
        try (Pattern p = RE2.compile("\\[ERROR\\]")) {
            try (Matcher m = p.matcher(logText)) {
                assertThat(m.find()).isFalse();
            }
        }
    }

    @Test
    void testApacheAccessLog() {
        String accessLog = "192.168.1.100 - - [17/Nov/2025:10:30:45 +0000] \"GET /api/users?id=123 HTTP/1.1\" 200 1234 \"https://example.com/\" \"Mozilla/5.0\"";

        // Find IP address
        try (Pattern p = RE2.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) {
            try (Matcher m = p.matcher(accessLog)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Find HTTP method
        try (Pattern p = RE2.compile("(GET|POST|PUT|DELETE|PATCH)")) {
            try (Matcher m = p.matcher(accessLog)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Find HTTP status
        try (Pattern p = RE2.compile("\" \\d{3} ")) {
            try (Matcher m = p.matcher(accessLog)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Find URL path
        try (Pattern p = RE2.compile("/api/\\w+")) {
            try (Matcher m = p.matcher(accessLog)) {
                assertThat(m.find()).isTrue();
            }
        }
    }

    @Test
    void testJSONLogEntry() {
        String jsonLog = "{\"timestamp\":\"2025-11-17T10:30:45.123Z\",\"level\":\"ERROR\",\"service\":\"api-gateway\",\"message\":\"Connection timeout\",\"userId\":12345,\"requestId\":\"req-abc-123\",\"duration\":5000}";

        // Find timestamp
        try (Pattern p = RE2.compile("\"timestamp\":\"[^\"]+\"")) {
            try (Matcher m = p.matcher(jsonLog)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Find error level
        try (Pattern p = RE2.compile("\"level\":\"ERROR\"")) {
            try (Matcher m = p.matcher(jsonLog)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Find user ID
        try (Pattern p = RE2.compile("\"userId\":\\d+")) {
            try (Matcher m = p.matcher(jsonLog)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Find request ID pattern
        try (Pattern p = RE2.compile("\"requestId\":\"req-[a-z0-9-]+\"")) {
            try (Matcher m = p.matcher(jsonLog)) {
                assertThat(m.find()).isTrue();
            }
        }
    }

    @Test
    void testSearchInVeryLargeLogFile() {
        // Simulate searching in a 1MB log file
        StringBuilder hugeLog = new StringBuilder();

        // Add 50,000 normal log entries
        for (int i = 0; i < 50000; i++) {
            hugeLog.append(String.format("[INFO] %d - Normal operation%n", i));
        }

        // Add a few error entries in the middle
        hugeLog.append("[ERROR] Database connection failed - retry attempt 1\n");
        hugeLog.append("[ERROR] Database connection failed - retry attempt 2\n");

        // Add more normal entries
        for (int i = 50000; i < 100000; i++) {
            hugeLog.append(String.format("[INFO] %d - Normal operation%n", i));
        }

        String logText = hugeLog.toString();
        assertThat(logText.length()).isGreaterThan(1_000_000); // Over 1MB

        // Search for ERROR entries in huge log
        try (Pattern p = RE2.compile("\\[ERROR\\].*")) {
            try (Matcher m = p.matcher(logText)) {
                long start = System.currentTimeMillis();
                boolean found = m.find();
                long duration = System.currentTimeMillis() - start;

                assertThat(found).isTrue();
                // Should be fast even on huge input (RE2 is linear time)
                assertThat(duration).isLessThan(500);
            }
        }

        // Search for pattern that doesn't exist
        try (Pattern p = RE2.compile("\\[CRITICAL\\]")) {
            try (Matcher m = p.matcher(logText)) {
                long start = System.currentTimeMillis();
                boolean found = m.find();
                long duration = System.currentTimeMillis() - start;

                assertThat(found).isFalse();
                // Should still be fast even when scanning entire 1MB
                assertThat(duration).isLessThan(500);
            }
        }
    }

    @Test
    void testCassandraQueryLog() {
        String cassandraLog = "INFO  [Native-Transport-Requests-1] 2025-11-17 10:30:45,123 QueryProcessor.java:169 - Execute CQL3 query: SELECT * FROM keyspace.table WHERE partition_key = 'abc123' AND clustering_key > 100 ALLOW FILTERING";

        // Find CQL query
        try (Pattern p = RE2.compile("SELECT .* FROM [\\w.]+")) {
            try (Matcher m = p.matcher(cassandraLog)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Find keyspace.table
        try (Pattern p = RE2.compile("FROM [\\w]+\\.[\\w]+")) {
            try (Matcher m = p.matcher(cassandraLog)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Find ALLOW FILTERING
        try (Pattern p = RE2.compile("ALLOW FILTERING")) {
            try (Matcher m = p.matcher(cassandraLog)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Find thread name
        try (Pattern p = RE2.compile("\\[Native-Transport-Requests-\\d+\\]")) {
            try (Matcher m = p.matcher(cassandraLog)) {
                assertThat(m.find()).isTrue();
            }
        }
    }

    @Test
    void testSearchMultiplePatternsInLargeText() {
        // Simulate Cassandra SAI scanning large partition with multiple filter terms
        StringBuilder partition = new StringBuilder();

        // 10,000 rows in partition
        for (int i = 0; i < 10000; i++) {
            partition.append(String.format("row_%d|user_%d@example.com|status_%s|value_%d|timestamp_%d%n",
                i, i % 100, i % 2 == 0 ? "active" : "inactive", i * 10, System.currentTimeMillis() + i));
        }

        String data = partition.toString();
        assertThat(data.length()).isGreaterThan(500_000); // Over 500KB

        // Pattern 1: Find rows with specific user pattern
        try (Pattern p = RE2.compile("user_42@example\\.com")) {
            try (Matcher m = p.matcher(data)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Pattern 2: Find active status
        try (Pattern p = RE2.compile("status_active")) {
            try (Matcher m = p.matcher(data)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Pattern 3: Find specific row range
        try (Pattern p = RE2.compile("row_500\\d")) {
            try (Matcher m = p.matcher(data)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Pattern 4: Complex pattern combining multiple fields
        try (Pattern p = RE2.compile("row_\\d+\\|.*@example\\.com\\|status_active")) {
            try (Matcher m = p.matcher(data)) {
                long start = System.currentTimeMillis();
                boolean found = m.find();
                long duration = System.currentTimeMillis() - start;

                assertThat(found).isTrue();
                // Should be fast even on 500KB+ data
                assertThat(duration).isLessThan(200);
            }
        }
    }

    @Test
    void testRealisticDatabaseTextSearch() {
        // Simulate searching through Cassandra text column with large values
        String[] largeTextSamples = {
            // Sample 1: Large JSON document
            "{\"user\":{\"name\":\"John Doe\",\"email\":\"john@example.com\",\"address\":{\"street\":\"123 Main St\",\"city\":\"Springfield\"},\"orders\":[" +
            "{\"id\":1,\"product\":\"Widget\",\"price\":29.99},{\"id\":2,\"product\":\"Gadget\",\"price\":49.99}]},\"metadata\":{\"source\":\"web\",\"timestamp\":\"2025-11-17T10:30:45Z\"}}",

            // Sample 2: Large log blob
            "[ERROR] Connection timeout to 192.168.1.100:9042 after 5000ms\n" +
            "[ERROR] Retry attempt 1/3\n" +
            "[ERROR] Connection timeout to 192.168.1.100:9042 after 5000ms\n" +
            "[ERROR] Retry attempt 2/3\n" +
            "[ERROR] Connection timeout to 192.168.1.100:9042 after 5000ms\n" +
            "[ERROR] Retry attempt 3/3\n" +
            "[ERROR] All retry attempts exhausted, marking node as DOWN",

            // Sample 3: XML document
            "<?xml version=\"1.0\"?><document><header><title>Important Document</title><date>2025-11-17</date></header>" +
            "<body><section id=\"1\"><content>This is a large amount of text that might be stored in a database column.</content></section>" +
            "<section id=\"2\"><content>More content with various special characters: @#$%^&*(){}[]</content></section></body></document>"
        };

        for (String text : largeTextSamples) {
            // Search for email pattern
            try (Pattern p = RE2.compile("\\w+@[\\w.]+")) {
                try (Matcher m = p.matcher(text)) {
                    // May or may not find depending on sample
                    m.find();
                }
            }

            // Search for number pattern
            try (Pattern p = RE2.compile("\\d+")) {
                try (Matcher m = p.matcher(text)) {
                    assertThat(m.find()).isTrue(); // All samples have numbers
                }
            }

            // Search for ERROR keyword
            try (Pattern p = RE2.compile("ERROR")) {
                try (Matcher m = p.matcher(text)) {
                    m.find(); // May or may not find
                }
            }
        }
    }

    @Test
    void testConcurrentLogSearching() throws InterruptedException {
        // Simulate multiple Cassandra query threads searching logs concurrently
        String largeLog = generateLargeLogData(50000);

        int threadCount = 20;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);

        String[] searchPatterns = {
            "\\[ERROR\\]",
            "user_\\d+@example\\.com",
            "duration=\\d+ms",
            "status=\\d{3}",
            "Exception",
            "192\\.168\\.\\d+\\.\\d+",
            "Request \\d+",
            "Thread-\\d+"
        };

        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            new Thread(() -> {
                try {
                    String pattern = searchPatterns[threadId % searchPatterns.length];
                    try (Pattern p = RE2.compile(pattern)) {
                        try (Matcher m = p.matcher(largeLog)) {
                            if (m.find()) {
                                successCount.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assertThat(errors.get()).isEqualTo(0);
        assertThat(successCount.get()).isGreaterThan(0);
    }

    @Test
    void testSQLInjectionPatternDetection() {
        // Test patterns for detecting SQL injection in logs
        String[] suspiciousInputs = {
            "admin' OR '1'='1",
            "' UNION SELECT * FROM users--",
            "1; DROP TABLE users;--",
            "admin'--",
            "' OR 1=1--"
        };

        // Pattern to detect SQL injection attempts
        try (Pattern p = RE2.compile("('.*(OR|UNION|DROP|SELECT|--|;).*)|(--)")) {
            for (String input : suspiciousInputs) {
                try (Matcher m = p.matcher(input)) {
                    assertThat(m.find()).isTrue();
                }
            }

            // Safe inputs should not match
            try (Matcher m = p.matcher("normal_username")) {
                assertThat(m.find()).isFalse();
            }
        }
    }

    @Test
    void testSearchWithLineBreaks() {
        String multiLineData = "Line 1: Some data\n" +
                               "Line 2: ERROR - Something failed\n" +
                               "Line 3: More data\n" +
                               "Line 4: WARNING - Check this\n" +
                               "Line 5: Final line";

        // Find lines with ERROR
        try (Pattern p = RE2.compile("ERROR")) {
            try (Matcher m = p.matcher(multiLineData)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Find lines with WARNING
        try (Pattern p = RE2.compile("WARNING")) {
            try (Matcher m = p.matcher(multiLineData)) {
                assertThat(m.find()).isTrue();
            }
        }

        // Pattern that spans multiple lines won't match (RE2 default behavior)
        try (Pattern p = RE2.compile("Line 2.*Line 3")) {
            try (Matcher m = p.matcher(multiLineData)) {
                assertThat(m.find()).isFalse(); // . doesn't match \n by default
            }
        }
    }

    // ===== Helper Methods =====

    private String generateLargeLogData(int lineCount) {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < lineCount; i++) {
            String level = i % 100 == 0 ? "ERROR" : (i % 20 == 0 ? "WARN" : "INFO");
            log.append(String.format("%s [Thread-%d] 2025-11-17 10:%02d:%02d Request %d from user_%d@example.com - duration=%dms status=%d%n",
                level, i % 10, i / 3600, (i / 60) % 60, i, i % 1000, i % 500, 200 + (i % 5)));
        }
        return log.toString();
    }
}

