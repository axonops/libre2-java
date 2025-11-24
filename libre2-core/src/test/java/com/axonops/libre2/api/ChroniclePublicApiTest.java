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

package com.axonops.libre2.api;

import net.openhft.chronicle.bytes.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration tests for Chronicle Bytes support via ZeroCopyPattern adapter.
 *
 * <p>These tests verify that the ZeroCopyPattern adapter correctly exposes zero-copy
 * functionality while keeping the main Pattern API clean.</p>
 */
@DisplayName("Zero-Copy Public API Integration")
class ChroniclePublicApiTest {

    /**
     * Helper to create direct Bytes from String.
     */
    private Bytes<?> createBytes(String text) {
        Bytes<?> bytes = Bytes.allocateElasticDirect();
        bytes.write(text.getBytes(StandardCharsets.UTF_8));
        return bytes;
    }

    /**
     * Helper to safely use Bytes with automatic cleanup.
     */
    private <T> T withBytes(String text, java.util.function.Function<Bytes<?>, T> action) {
        Bytes<?> bytes = createBytes(text);
        try {
            return action.apply(bytes);
        } finally {
            bytes.releaseLast();
        }
    }

    // ========== ZeroCopyPattern.matches(Bytes) Tests ==========

    @Test
    @DisplayName("ZeroCopyPattern.matches(Bytes) should match full content")
    void zeroCopyMatches_bytes_works() {
        ZeroCopyPattern pattern = ZeroCopyPattern.compile("hello");

        boolean result = withBytes("hello", pattern::matches);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("ZeroCopyPattern.matches(Bytes) should not match partial content")
    void zeroCopyMatches_bytes_partialContent_noMatch() {
        ZeroCopyPattern pattern = ZeroCopyPattern.compile("hello");

        boolean result = withBytes("hello world", pattern::matches);
        assertThat(result).isFalse();
    }

    @ParameterizedTest
    @DisplayName("ZeroCopyPattern.matches(Bytes) should match String API")
    @CsvSource({
        "\\d+, 12345, true",
        "\\d+, abc, false",
        "[a-z]+, hello, true",
        "[a-z]+, HELLO, false",
        "test, test, true",
        "test, testing, false"
    })
    void zeroCopyMatches_bytesMatchesString(String patternStr, String input, boolean expected) {
        Pattern pattern = Pattern.compile(patternStr);
        ZeroCopyPattern zeroCopy = ZeroCopyPattern.wrap(pattern);

        // String API
        boolean stringResult = pattern.matches(input);

        // Zero-Copy API
        boolean bytesResult = withBytes(input, zeroCopy::matches);

        assertThat(bytesResult).isEqualTo(stringResult).isEqualTo(expected);
    }

    // ========== ZeroCopyPattern.find(Bytes) Tests ==========

    @Test
    @DisplayName("ZeroCopyPattern.find(Bytes) should find substring")
    void zeroCopyFind_bytes_findsSubstring() {
        ZeroCopyPattern pattern = ZeroCopyPattern.compile("world");

        boolean result = withBytes("hello world", pattern::find);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("ZeroCopyPattern.find(Bytes) should not find non-existent pattern")
    void zeroCopyFind_bytes_notFound() {
        ZeroCopyPattern pattern = ZeroCopyPattern.compile("xyz");

        boolean result = withBytes("hello world", pattern::find);
        assertThat(result).isFalse();
    }

    @ParameterizedTest
    @DisplayName("ZeroCopyPattern.find(Bytes) should match String API")
    @CsvSource({
        "\\d+, abc123def, true",
        "\\d+, abcdef, false",
        "@, user@example.com, true",
        "@, noatsign, false",
        "world, hello world, true"
    })
    void zeroCopyFind_bytesMatchesString(String patternStr, String input, boolean expected) {
        Pattern pattern = Pattern.compile(patternStr);
        ZeroCopyPattern zeroCopy = ZeroCopyPattern.wrap(pattern);

        // String API (using Matcher.find())
        boolean stringResult;
        try (Matcher m = pattern.matcher(input)) {
            stringResult = m.find();
        }

        // Zero-Copy API
        boolean bytesResult = withBytes(input, zeroCopy::find);

        assertThat(bytesResult).isEqualTo(stringResult).isEqualTo(expected);
    }

    // ========== ZeroCopyPattern Bulk Operations Tests ==========

    @Test
    @DisplayName("ZeroCopyPattern.matchAll(Bytes[]) should match multiple inputs")
    void zeroCopyMatchAll_bytes_works() {
        ZeroCopyPattern pattern = ZeroCopyPattern.compile("test");

        Bytes<?>[] inputs = new Bytes<?>[3];
        try {
            inputs[0] = createBytes("test");
            inputs[1] = createBytes("testing");  // Not full match
            inputs[2] = createBytes("test");

            boolean[] results = pattern.matchAll(inputs);

            assertThat(results).containsExactly(true, false, true);
        } finally {
            for (Bytes<?> b : inputs) {
                if (b != null) b.releaseLast();
            }
        }
    }

    @Test
    @DisplayName("ZeroCopyPattern.matchAll(Bytes[]) should match String bulk API")
    void zeroCopyMatchAll_bytesMatchesStringBulk() {
        Pattern pattern = Pattern.compile("\\d+");
        ZeroCopyPattern zeroCopy = ZeroCopyPattern.wrap(pattern);

        String[] texts = {"123", "abc", "456"};
        boolean[] stringResults = pattern.matchAll(texts);

        Bytes<?>[] bytesInputs = new Bytes<?>[texts.length];
        try {
            for (int i = 0; i < texts.length; i++) {
                bytesInputs[i] = createBytes(texts[i]);
            }

            boolean[] bytesResults = zeroCopy.matchAll(bytesInputs);

            assertThat(bytesResults).containsExactly(stringResults);
        } finally {
            for (Bytes<?> b : bytesInputs) {
                if (b != null) b.releaseLast();
            }
        }
    }

    @Test
    @DisplayName("ZeroCopyPattern.findAll(Bytes[]) should find in multiple inputs")
    void zeroCopyFindAll_bytes_works() {
        ZeroCopyPattern pattern = ZeroCopyPattern.compile("test");

        Bytes<?>[] inputs = new Bytes<?>[3];
        try {
            inputs[0] = createBytes("test");
            inputs[1] = createBytes("testing");  // Partial match OK
            inputs[2] = createBytes("notest");   // Partial match OK

            boolean[] results = pattern.findAll(inputs);

            assertThat(results).containsExactly(true, true, true);
        } finally {
            for (Bytes<?> b : inputs) {
                if (b != null) b.releaseLast();
            }
        }
    }

    // ========== ZeroCopyPattern Capture Groups Tests ==========

    @Test
    @DisplayName("ZeroCopyPattern.extractGroups(Bytes) should extract groups")
    void zeroCopyExtractGroups_bytes_works() {
        ZeroCopyPattern pattern = ZeroCopyPattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

        String[] groups = withBytes("2025-11-24", pattern::extractGroups);

        assertThat(groups).containsExactly("2025-11-24", "2025", "11", "24");
    }

    @Test
    @DisplayName("ZeroCopyPattern.findAllMatches(Bytes) should find all occurrences")
    void zeroCopyFindAllMatches_bytes_works() {
        ZeroCopyPattern pattern = ZeroCopyPattern.compile("\\d+");

        String[][] matches = withBytes("a1b22c333", pattern::findAllMatches);

        assertThat(matches).isNotNull();
        assertThat(matches.length).isEqualTo(3);
        assertThat(matches[0][0]).isEqualTo("1");
        assertThat(matches[1][0]).isEqualTo("22");
        assertThat(matches[2][0]).isEqualTo("333");
    }

    @Test
    @DisplayName("ZeroCopyPattern.findAllMatches(Bytes) should return null for no matches")
    void zeroCopyFindAllMatches_bytes_noMatch() {
        ZeroCopyPattern pattern = ZeroCopyPattern.compile("\\d+");

        String[][] matches = withBytes("no digits", pattern::findAllMatches);

        assertThat(matches).isNull();
    }

    // ========== ZeroCopyRE2 Convenience Tests ==========

    @Test
    @DisplayName("ZeroCopyRE2.matches() should work")
    void zeroCopyRE2Matches_works() {
        boolean result = withBytes("hello", bytes -> ZeroCopyRE2.matches("hello", bytes));

        assertThat(result).isTrue();
    }

    @ParameterizedTest
    @DisplayName("ZeroCopyRE2.matches() should match String API")
    @CsvSource({
        "\\d+, 12345, true",
        "\\d+, abc, false",
        "test, test, true",
        "test, testing, false"
    })
    void zeroCopyRE2Matches_matchesStringApi(String pattern, String input, boolean expected) {
        // String API
        boolean stringResult = RE2.matches(pattern, input);

        // Zero-Copy API
        boolean bytesResult = withBytes(input, bytes -> ZeroCopyRE2.matches(pattern, bytes));

        assertThat(bytesResult).isEqualTo(stringResult).isEqualTo(expected);
    }

    @Test
    @DisplayName("ZeroCopyRE2.find() should work")
    void zeroCopyRE2Find_works() {
        boolean result = withBytes("hello world", bytes -> ZeroCopyRE2.find("world", bytes));

        assertThat(result).isTrue();
    }

    // ========== End-to-End Workflow Tests ==========

    @Test
    @DisplayName("End-to-end: Compile once, match many times with Chronicle Bytes")
    void endToEnd_reusePatternWithBytes() {
        ZeroCopyPattern pattern = ZeroCopyPattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b", false);

        // Test multiple inputs with same pattern
        boolean r1 = withBytes("user@example.com", pattern::matches);
        boolean r2 = withBytes("invalid", pattern::matches);
        boolean r3 = withBytes("admin@test.org", pattern::matches);
        assertThat(r1).isTrue();
        assertThat(r2).isFalse();
        assertThat(r3).isTrue();

        // Test with partial matching
        boolean r4 = withBytes("Contact: support@company.com", pattern::find);
        boolean r5 = withBytes("No email here", pattern::find);
        assertThat(r4).isTrue();
        assertThat(r5).isFalse();
    }

    @Test
    @DisplayName("End-to-end: Bulk matching with Chronicle Bytes")
    void endToEnd_bulkMatchingWithBytes() {
        ZeroCopyPattern pattern = ZeroCopyPattern.compile("^valid_.*");  // Anchor to start

        Bytes<?>[] inputs = new Bytes<?>[5];
        try {
            inputs[0] = createBytes("valid_key1");
            inputs[1] = createBytes("invalid_key");  // Doesn't start with "valid_"
            inputs[2] = createBytes("valid_key2");
            inputs[3] = createBytes("other");
            inputs[4] = createBytes("valid_key3");

            // Full match tests (must match entire content)
            boolean[] matchResults = pattern.matchAll(inputs);
            assertThat(matchResults).containsExactly(true, false, true, false, true);

            // Partial match tests (pattern must be found somewhere)
            boolean[] findResults = pattern.findAll(inputs);
            assertThat(findResults).containsExactly(true, false, true, false, true);  // Same due to anchor
        } finally {
            for (Bytes<?> b : inputs) {
                if (b != null) b.releaseLast();
            }
        }
    }

    @Test
    @DisplayName("End-to-end: Extract groups with Chronicle Bytes")
    void endToEnd_extractGroupsWithBytes() {
        ZeroCopyPattern pattern = ZeroCopyPattern.compile("([a-z]+)_([0-9]+)");

        String[] groups = withBytes("user_123", pattern::extractGroups);

        assertThat(groups).containsExactly("user_123", "user", "123");
    }

    @Test
    @DisplayName("End-to-end: Find all matches with Chronicle Bytes")
    void endToEnd_findAllMatchesWithBytes() {
        ZeroCopyPattern pattern = ZeroCopyPattern.compile("(\\d+)");

        String[][] matches = withBytes("a1b22c333d4444", pattern::findAllMatches);

        assertThat(matches).isNotNull();
        assertThat(matches.length).isEqualTo(4);
        assertThat(matches[0]).containsExactly("1", "1");
        assertThat(matches[1]).containsExactly("22", "22");
        assertThat(matches[2]).containsExactly("333", "333");
        assertThat(matches[3]).containsExactly("4444", "4444");
    }

    // ========== Compatibility Tests ==========

    @Test
    @DisplayName("ZeroCopyPattern should work with cached patterns")
    void zeroCopy_worksWithCachedPatterns() {
        // Compile pattern (will be cached)
        Pattern pattern = Pattern.compile("test_pattern");
        ZeroCopyPattern zeroCopy = ZeroCopyPattern.wrap(pattern);

        // Use multiple times with Chronicle Bytes
        for (int i = 0; i < 10; i++) {
            boolean result = withBytes("test_pattern", zeroCopy::matches);
            assertThat(result).isTrue();
        }

        // Verify pattern is still valid
        assertThat(pattern.isValid()).isTrue();
    }

    @Test
    @DisplayName("ZeroCopyPattern and Pattern String APIs can be mixed")
    void zeroCopy_mixedWithStringApi() {
        Pattern pattern = Pattern.compile("\\d+");
        ZeroCopyPattern zeroCopy = ZeroCopyPattern.wrap(pattern);

        // Use String API on Pattern
        assertThat(pattern.matches("123")).isTrue();
        assertThat(pattern.matches("abc")).isFalse();

        // Use Chronicle Bytes API on ZeroCopyPattern
        boolean b1 = withBytes("456", zeroCopy::matches);
        boolean b2 = withBytes("def", zeroCopy::matches);
        assertThat(b1).isTrue();
        assertThat(b2).isFalse();

        // Mix them
        assertThat(pattern.matches("789")).isTrue();
        boolean b3 = withBytes("xyz", zeroCopy::matches);
        assertThat(b3).isFalse();
    }
}


