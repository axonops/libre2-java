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

import com.axonops.libre2.jni.RE2DirectMemory;
import net.openhft.chronicle.bytes.Bytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for off-heap memory matching using Pattern address/length overloads.
 *
 * <p>These tests demonstrate mixed usage: same Pattern used with both String
 * and off-heap memory (Chronicle Bytes) in the same application.</p>
 */
@DisplayName("Off-Heap Memory Matching")
class OffHeapMatchingTest {

    /**
     * Helper to create direct Bytes from String.
     */
    private Bytes<?> createDirectBytes(String text) {
        Bytes<?> bytes = Bytes.allocateElasticDirect();
        bytes.write(text.getBytes(StandardCharsets.UTF_8));
        return bytes;
    }

    // ========== Basic Functionality Tests ==========

    @Test
    @DisplayName("Pattern.matches(address, length) should match content")
    void patternMatches_addressLength_works() {
        Pattern pattern = Pattern.compile("hello");

        Bytes<?> bytes = createDirectBytes("hello");
        try {
            long address = bytes.addressForRead(0);
            int length = (int) bytes.readRemaining();

            boolean matches = pattern.matches(address, length);

            assertThat(matches).isTrue();
        } finally {
            bytes.releaseLast();
        }
    }

    @Test
    @DisplayName("Pattern.find(address, length) should find substring")
    void patternFind_addressLength_works() {
        Pattern pattern = Pattern.compile("world");

        Bytes<?> bytes = createDirectBytes("hello world");
        try {
            long address = bytes.addressForRead(0);
            int length = (int) bytes.readRemaining();

            boolean found = pattern.find(address, length);

            assertThat(found).isTrue();
        } finally {
            bytes.releaseLast();
        }
    }

    // ========== Mixed Usage Tests (String + Off-Heap) ==========

    @Test
    @DisplayName("Pattern can mix String and off-heap matching in same app")
    void pattern_mixedUsage_works() {
        Pattern pattern = Pattern.compile("\\d+");

        // Use with String
        assertThat(pattern.matches("123")).isTrue();
        assertThat(pattern.matches("abc")).isFalse();

        // Use with off-heap (Chronicle Bytes)
        Bytes<?> bytes1 = createDirectBytes("456");
        Bytes<?> bytes2 = createDirectBytes("def");
        try {
            assertThat(pattern.matches(bytes1.addressForRead(0), (int) bytes1.readRemaining())).isTrue();
            assertThat(pattern.matches(bytes2.addressForRead(0), (int) bytes2.readRemaining())).isFalse();
        } finally {
            bytes1.releaseLast();
            bytes2.releaseLast();
        }

        // Mix them in same method
        assertThat(pattern.matches("789")).isTrue();

        Bytes<?> bytes3 = createDirectBytes("xyz");
        try {
            assertThat(pattern.matches(bytes3.addressForRead(0), (int) bytes3.readRemaining())).isFalse();
        } finally {
            bytes3.releaseLast();
        }
    }

    @ParameterizedTest
    @DisplayName("Off-heap API should produce same results as String API")
    @CsvSource({
        "\\d+, 12345, true",
        "\\d+, abc, false",
        "[a-z]+, hello, true",
        "[a-z]+, HELLO, false",
        "test, test, true",
        "test, testing, false"
    })
    void offHeapApi_matchesStringApi(String patternStr, String input, boolean expected) {
        Pattern pattern = Pattern.compile(patternStr);

        // String API
        boolean stringResult = pattern.matches(input);

        // Off-heap API
        Bytes<?> bytes = createDirectBytes(input);
        try {
            boolean offHeapResult = pattern.matches(bytes.addressForRead(0), (int) bytes.readRemaining());
            assertThat(offHeapResult).isEqualTo(stringResult).isEqualTo(expected);
        } finally {
            bytes.releaseLast();
        }
    }

    // ========== Bulk Operations Tests ==========

    @Test
    @DisplayName("Pattern.matchAll(addresses[], lengths[]) should work")
    void patternMatchAll_addressesLengths_works() {
        Pattern pattern = Pattern.compile("test");

        Bytes<?>[] bytesArray = new Bytes<?>[3];
        try {
            bytesArray[0] = createDirectBytes("test");
            bytesArray[1] = createDirectBytes("testing");  // Not full match
            bytesArray[2] = createDirectBytes("test");

            // Extract addresses and lengths
            long[] addresses = new long[bytesArray.length];
            int[] lengths = new int[bytesArray.length];
            for (int i = 0; i < bytesArray.length; i++) {
                addresses[i] = bytesArray[i].addressForRead(0);
                lengths[i] = (int) bytesArray[i].readRemaining();
            }

            boolean[] results = pattern.matchAll(addresses, lengths);

            assertThat(results).containsExactly(true, false, true);
        } finally {
            for (Bytes<?> b : bytesArray) {
                if (b != null) b.releaseLast();
            }
        }
    }

    @Test
    @DisplayName("Bulk operations should match String bulk API")
    void bulkOperations_offHeapMatchesString() {
        Pattern pattern = Pattern.compile("\\d+");

        String[] texts = {"123", "abc", "456"};

        // String bulk API
        boolean[] stringResults = pattern.matchAll(texts);

        // Off-heap bulk API
        Bytes<?>[] bytesArray = new Bytes<?>[texts.length];
        try {
            for (int i = 0; i < texts.length; i++) {
                bytesArray[i] = createDirectBytes(texts[i]);
            }

            long[] addresses = new long[bytesArray.length];
            int[] lengths = new int[bytesArray.length];
            for (int i = 0; i < bytesArray.length; i++) {
                addresses[i] = bytesArray[i].addressForRead(0);
                lengths[i] = (int) bytesArray[i].readRemaining();
            }

            boolean[] offHeapResults = pattern.matchAll(addresses, lengths);

            assertThat(offHeapResults).containsExactly(stringResults);
        } finally {
            for (Bytes<?> b : bytesArray) {
                if (b != null) b.releaseLast();
            }
        }
    }

    // ========== Capture Groups Tests ==========

    @Test
    @DisplayName("Pattern.extractGroups(address, length) should work")
    void extractGroups_addressLength_works() {
        Pattern pattern = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

        Bytes<?> bytes = createDirectBytes("2025-11-24");
        try {
            String[] groups = pattern.extractGroups(bytes.addressForRead(0), (int) bytes.readRemaining());

            assertThat(groups).containsExactly("2025-11-24", "2025", "11", "24");
        } finally {
            bytes.releaseLast();
        }
    }

    @Test
    @DisplayName("Pattern.findAllMatches(address, length) should work")
    void findAllMatches_addressLength_works() {
        Pattern pattern = Pattern.compile("\\d+");

        Bytes<?> bytes = createDirectBytes("a1b22c333");
        try {
            String[][] matches = pattern.findAllMatches(bytes.addressForRead(0), (int) bytes.readRemaining());

            assertThat(matches).isNotNull();
            assertThat(matches.length).isEqualTo(3);
            assertThat(matches[0][0]).isEqualTo("1");
            assertThat(matches[1][0]).isEqualTo("22");
            assertThat(matches[2][0]).isEqualTo("333");
        } finally {
            bytes.releaseLast();
        }
    }

    // ========== RE2DirectMemory Helper Tests ==========
    // RE2DirectMemory is a convenience helper for Chronicle Bytes users

    @Test
    @DisplayName("RE2DirectMemory provides Chronicle Bytes convenience")
    void re2DirectMemory_providesConvenience() {
        Pattern pattern = Pattern.compile("\\d+");
        long handle = pattern.getNativeHandle();

        Bytes<?> bytes = createDirectBytes("12345");
        try {
            // Users can use RE2DirectMemory helper (accepts Bytes directly)
            boolean result1 = RE2DirectMemory.fullMatch(handle, bytes);

            // Or they can extract address/length themselves
            long address = bytes.addressForRead(0);
            int length = (int) bytes.readRemaining();
            boolean result2 = pattern.matches(address, length);

            // Both should give same result
            assertThat(result1).isEqualTo(result2).isTrue();
        } finally {
            bytes.releaseLast();
        }
    }

    // ========== Validation Tests ==========

    @Test
    @DisplayName("Should throw on zero address")
    void matches_zeroAddress_throws() {
        Pattern pattern = Pattern.compile("test");

        assertThatIllegalArgumentException()
            .isThrownBy(() -> pattern.matches(0, 10))
            .withMessageContaining("0");
    }

    @Test
    @DisplayName("Should throw on negative length")
    void matches_negativeLength_throws() {
        Pattern pattern = Pattern.compile("test");

        assertThatIllegalArgumentException()
            .isThrownBy(() -> pattern.matches(12345L, -1))
            .withMessageContaining("negative");
    }

    @Test
    @DisplayName("Should throw on mismatched array lengths")
    void matchAll_mismatchedArrays_throws() {
        Pattern pattern = Pattern.compile("test");

        long[] addresses = {100L, 200L, 300L};
        int[] lengths = {10, 20};  // Different length!

        assertThatIllegalArgumentException()
            .isThrownBy(() -> pattern.matchAll(addresses, lengths))
            .withMessageContaining("same size");
    }
}
