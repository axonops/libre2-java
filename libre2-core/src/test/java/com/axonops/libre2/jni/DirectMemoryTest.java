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

package com.axonops.libre2.jni;

import net.openhft.chronicle.bytes.Bytes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for zero-copy direct memory matching functionality.
 *
 * <p>These tests verify that the zero-copy Direct API produces the same results
 * as the String-based API, ensuring correctness of the zero-copy implementation.</p>
 */
@DisplayName("Zero-Copy Direct Memory Tests")
class DirectMemoryTest {

    private long patternHandle;

    @BeforeAll
    static void loadNativeLibrary() {
        RE2LibraryLoader.loadLibrary();
    }

    @BeforeEach
    void setUp() {
        // Pattern will be set up in individual tests
        patternHandle = 0;
    }

    @AfterEach
    void tearDown() {
        if (patternHandle != 0) {
            RE2NativeJNI.freePattern(patternHandle);
            patternHandle = 0;
        }
    }

    // ========== Full Match Tests ==========

    @Test
    @DisplayName("fullMatchDirect should match simple pattern")
    void fullMatchDirect_simplePattern_matches() {
        patternHandle = RE2NativeJNI.compile("hello", true);
        assertThat(patternHandle).isNotZero();

        try (Bytes<?> bytes = Bytes.from("hello")) {
            boolean result = RE2DirectMemory.fullMatch(patternHandle, bytes);
            assertThat(result).isTrue();
        }
    }

    @Test
    @DisplayName("fullMatchDirect should not match partial content")
    void fullMatchDirect_partialContent_noMatch() {
        patternHandle = RE2NativeJNI.compile("hello", true);

        try (Bytes<?> bytes = Bytes.from("hello world")) {
            boolean result = RE2DirectMemory.fullMatch(patternHandle, bytes);
            assertThat(result).isFalse();
        }
    }

    @Test
    @DisplayName("fullMatchDirect should match regex pattern")
    void fullMatchDirect_regexPattern_matches() {
        patternHandle = RE2NativeJNI.compile("hello\\s+world", true);

        try (Bytes<?> bytes = Bytes.from("hello   world")) {
            boolean result = RE2DirectMemory.fullMatch(patternHandle, bytes);
            assertThat(result).isTrue();
        }
    }

    @Test
    @DisplayName("fullMatchDirect should respect case sensitivity")
    void fullMatchDirect_caseSensitive_respected() {
        // Case sensitive
        patternHandle = RE2NativeJNI.compile("Hello", true);
        try (Bytes<?> bytes = Bytes.from("hello")) {
            assertThat(RE2DirectMemory.fullMatch(patternHandle, bytes)).isFalse();
        }
        try (Bytes<?> bytes = Bytes.from("Hello")) {
            assertThat(RE2DirectMemory.fullMatch(patternHandle, bytes)).isTrue();
        }
        RE2NativeJNI.freePattern(patternHandle);

        // Case insensitive
        patternHandle = RE2NativeJNI.compile("Hello", false);
        try (Bytes<?> bytes = Bytes.from("hello")) {
            assertThat(RE2DirectMemory.fullMatch(patternHandle, bytes)).isTrue();
        }
        try (Bytes<?> bytes = Bytes.from("HELLO")) {
            assertThat(RE2DirectMemory.fullMatch(patternHandle, bytes)).isTrue();
        }
    }

    // ========== Partial Match Tests ==========

    @Test
    @DisplayName("partialMatchDirect should match substring")
    void partialMatchDirect_substring_matches() {
        patternHandle = RE2NativeJNI.compile("world", true);

        try (Bytes<?> bytes = Bytes.from("hello world")) {
            boolean result = RE2DirectMemory.partialMatch(patternHandle, bytes);
            assertThat(result).isTrue();
        }
    }

    @Test
    @DisplayName("partialMatchDirect should not match non-existent pattern")
    void partialMatchDirect_nonExistent_noMatch() {
        patternHandle = RE2NativeJNI.compile("xyz", true);

        try (Bytes<?> bytes = Bytes.from("hello world")) {
            boolean result = RE2DirectMemory.partialMatch(patternHandle, bytes);
            assertThat(result).isFalse();
        }
    }

    @Test
    @DisplayName("partialMatchDirect should match regex pattern")
    void partialMatchDirect_regexPattern_matches() {
        patternHandle = RE2NativeJNI.compile("\\d{4}-\\d{2}-\\d{2}", true);

        try (Bytes<?> bytes = Bytes.from("Date: 2025-11-24 is today")) {
            boolean result = RE2DirectMemory.partialMatch(patternHandle, bytes);
            assertThat(result).isTrue();
        }
    }

    // ========== Consistency Tests (Direct vs String API) ==========

    @ParameterizedTest
    @DisplayName("Direct API should match String API for full match")
    @CsvSource({
        "hello, hello, true",
        "hello, world, false",
        "a+b+, aaabbb, true",
        "^test$, test, true",
        "^test$, testing, false",
        "[0-9]+, 12345, true",
        "[a-z]+, ABC, false"
    })
    void directApiMatchesStringApi_fullMatch(String pattern, String text, boolean expected) {
        patternHandle = RE2NativeJNI.compile(pattern, true);

        // String API
        boolean stringResult = RE2NativeJNI.fullMatch(patternHandle, text);

        // Direct API
        try (Bytes<?> bytes = Bytes.from(text)) {
            boolean directResult = RE2DirectMemory.fullMatch(patternHandle, bytes);

            assertThat(directResult)
                .as("Direct API should match String API for pattern '%s' and text '%s'", pattern, text)
                .isEqualTo(stringResult)
                .isEqualTo(expected);
        }
    }

    @ParameterizedTest
    @DisplayName("Direct API should match String API for partial match")
    @CsvSource({
        "world, hello world, true",
        "xyz, hello world, false",
        "\\d+, abc123def, true",
        "@, user@example.com, true",
        "^start, starthere, true",
        "^start, nostart, false"
    })
    void directApiMatchesStringApi_partialMatch(String pattern, String text, boolean expected) {
        patternHandle = RE2NativeJNI.compile(pattern, true);

        // String API
        boolean stringResult = RE2NativeJNI.partialMatch(patternHandle, text);

        // Direct API
        try (Bytes<?> bytes = Bytes.from(text)) {
            boolean directResult = RE2DirectMemory.partialMatch(patternHandle, bytes);

            assertThat(directResult)
                .as("Direct API should match String API for pattern '%s' and text '%s'", pattern, text)
                .isEqualTo(stringResult)
                .isEqualTo(expected);
        }
    }

    // ========== Bulk Operations Tests ==========

    @Test
    @DisplayName("fullMatchDirectBulk should match multiple inputs")
    void fullMatchDirectBulk_multipleInputs_works() {
        patternHandle = RE2NativeJNI.compile("test", true);

        Bytes<?>[] inputs = new Bytes<?>[5];
        try {
            inputs[0] = Bytes.from("test");     // match
            inputs[1] = Bytes.from("testing");  // no match (not full)
            inputs[2] = Bytes.from("test");     // match
            inputs[3] = Bytes.from("other");    // no match
            inputs[4] = Bytes.from("test");     // match

            boolean[] results = RE2DirectMemory.fullMatchBulk(patternHandle, inputs);

            assertThat(results).containsExactly(true, false, true, false, true);
        } finally {
            for (Bytes<?> b : inputs) {
                if (b != null) b.releaseLast();
            }
        }
    }

    @Test
    @DisplayName("partialMatchDirectBulk should match multiple inputs")
    void partialMatchDirectBulk_multipleInputs_works() {
        patternHandle = RE2NativeJNI.compile("test", true);

        Bytes<?>[] inputs = new Bytes<?>[5];
        try {
            inputs[0] = Bytes.from("test");       // match
            inputs[1] = Bytes.from("testing");    // match (partial)
            inputs[2] = Bytes.from("a test");     // match
            inputs[3] = Bytes.from("other");      // no match
            inputs[4] = Bytes.from("atestb");     // match

            boolean[] results = RE2DirectMemory.partialMatchBulk(patternHandle, inputs);

            assertThat(results).containsExactly(true, true, true, false, true);
        } finally {
            for (Bytes<?> b : inputs) {
                if (b != null) b.releaseLast();
            }
        }
    }

    @Test
    @DisplayName("Bulk operations should match String bulk API results")
    void bulkOperations_matchStringBulkApi() {
        patternHandle = RE2NativeJNI.compile("\\d+", true);

        String[] texts = {"123", "abc", "456", "def789ghi", "no digits"};

        // String bulk API
        boolean[] stringResults = RE2NativeJNI.partialMatchBulk(patternHandle, texts);

        // Direct bulk API
        Bytes<?>[] inputs = new Bytes<?>[texts.length];
        try {
            for (int i = 0; i < texts.length; i++) {
                inputs[i] = Bytes.from(texts[i]);
            }

            boolean[] directResults = RE2DirectMemory.partialMatchBulk(patternHandle, inputs);

            assertThat(directResults)
                .as("Direct bulk API should match String bulk API")
                .containsExactly(stringResults);
        } finally {
            for (Bytes<?> b : inputs) {
                if (b != null) b.releaseLast();
            }
        }
    }

    // ========== Extract Groups Tests ==========

    @Test
    @DisplayName("extractGroupsDirect should extract capture groups")
    void extractGroupsDirect_captureGroups_extracted() {
        patternHandle = RE2NativeJNI.compile("(\\d{4})-(\\d{2})-(\\d{2})", true);

        try (Bytes<?> bytes = Bytes.from("2025-11-24")) {
            String[] groups = RE2DirectMemory.extractGroups(patternHandle, bytes);

            assertThat(groups).containsExactly("2025-11-24", "2025", "11", "24");
        }
    }

    @Test
    @DisplayName("extractGroupsDirect should return null for no match")
    void extractGroupsDirect_noMatch_returnsNull() {
        patternHandle = RE2NativeJNI.compile("(\\d+)", true);

        try (Bytes<?> bytes = Bytes.from("no digits here")) {
            String[] groups = RE2DirectMemory.extractGroups(patternHandle, bytes);

            assertThat(groups).isNull();
        }
    }

    @Test
    @DisplayName("extractGroupsDirect should match String API")
    void extractGroupsDirect_matchesStringApi() {
        patternHandle = RE2NativeJNI.compile("([a-z]+)@([a-z]+)\\.([a-z]+)", true);
        String text = "user@example.com";

        // String API
        String[] stringGroups = RE2NativeJNI.extractGroups(patternHandle, text);

        // Direct API
        try (Bytes<?> bytes = Bytes.from(text)) {
            String[] directGroups = RE2DirectMemory.extractGroups(patternHandle, bytes);

            assertThat(directGroups)
                .as("Direct extractGroups should match String extractGroups")
                .containsExactly(stringGroups);
        }
    }

    // ========== Find All Matches Tests ==========

    @Test
    @DisplayName("findAllMatchesDirect should find all occurrences")
    void findAllMatchesDirect_multipleOccurrences_found() {
        patternHandle = RE2NativeJNI.compile("\\d+", true);

        try (Bytes<?> bytes = Bytes.from("a1b22c333d4444")) {
            String[][] matches = RE2DirectMemory.findAllMatches(patternHandle, bytes);

            assertThat(matches).hasSize(4);
            assertThat(matches[0][0]).isEqualTo("1");
            assertThat(matches[1][0]).isEqualTo("22");
            assertThat(matches[2][0]).isEqualTo("333");
            assertThat(matches[3][0]).isEqualTo("4444");
        }
    }

    @Test
    @DisplayName("findAllMatchesDirect should return null for no matches")
    void findAllMatchesDirect_noMatches_returnsNull() {
        patternHandle = RE2NativeJNI.compile("\\d+", true);

        try (Bytes<?> bytes = Bytes.from("no digits")) {
            String[][] matches = RE2DirectMemory.findAllMatches(patternHandle, bytes);

            assertThat(matches).isNull();
        }
    }

    // ========== Edge Cases ==========

    @Test
    @DisplayName("Should handle empty string")
    void directMatch_emptyString_handles() {
        patternHandle = RE2NativeJNI.compile("", true);

        try (Bytes<?> bytes = Bytes.from("")) {
            boolean fullMatch = RE2DirectMemory.fullMatch(patternHandle, bytes);
            assertThat(fullMatch).isTrue();
        }
    }

    @Test
    @DisplayName("Should handle Unicode content")
    void directMatch_unicode_handles() {
        patternHandle = RE2NativeJNI.compile("\\p{L}+", true);

        try (Bytes<?> bytes = Bytes.from("Hello")) {
            boolean result = RE2DirectMemory.fullMatch(patternHandle, bytes);
            assertThat(result).isTrue();
        }
    }

    @ParameterizedTest
    @DisplayName("Should handle various input sizes")
    @ValueSource(ints = {1, 10, 100, 1000, 10000})
    void directMatch_variousSizes_handles(int size) {
        patternHandle = RE2NativeJNI.compile("a+", true);

        String text = "a".repeat(size);
        try (Bytes<?> bytes = Bytes.from(text)) {
            boolean directResult = RE2DirectMemory.fullMatch(patternHandle, bytes);
            boolean stringResult = RE2NativeJNI.fullMatch(patternHandle, text);

            assertThat(directResult).isEqualTo(stringResult).isTrue();
        }
    }

    // ========== Validation Tests ==========

    @Test
    @DisplayName("Should throw on null bytes")
    void fullMatch_nullBytes_throws() {
        patternHandle = RE2NativeJNI.compile("test", true);

        assertThatNullPointerException()
            .isThrownBy(() -> RE2DirectMemory.fullMatch(patternHandle, (Bytes<?>) null))
            .withMessageContaining("null");
    }

    @Test
    @DisplayName("Should throw on zero pattern handle")
    void fullMatch_zeroHandle_throws() {
        try (Bytes<?> bytes = Bytes.from("test")) {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> RE2DirectMemory.fullMatch(0, bytes))
                .withMessageContaining("0");
        }
    }

    // ========== toBytes Utility Test ==========

    @Test
    @DisplayName("toBytes should create usable Bytes from String")
    void toBytes_createsUsableBytes() {
        patternHandle = RE2NativeJNI.compile("hello", true);

        try (Bytes<?> bytes = RE2DirectMemory.toBytes("hello")) {
            boolean result = RE2DirectMemory.fullMatch(patternHandle, bytes);
            assertThat(result).isTrue();
        }
    }
}
