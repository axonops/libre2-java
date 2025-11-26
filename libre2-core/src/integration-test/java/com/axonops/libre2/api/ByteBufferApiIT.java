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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for ByteBuffer API with automatic routing to zero-copy or String API.
 *
 * <p>These tests verify that Pattern correctly detects DirectByteBuffer vs
 * heap ByteBuffer and routes to the appropriate implementation.</p>
 */
@DisplayName("ByteBuffer API Tests")
class ByteBufferApiIT {

    /**
     * Creates a DirectByteBuffer (off-heap, supports zero-copy).
     */
    private ByteBuffer createDirectBuffer(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }

    /**
     * Creates a heap ByteBuffer (on-heap, falls back to String API).
     */
    private ByteBuffer createHeapBuffer(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    // ========== DirectByteBuffer Tests (Zero-Copy Path) ==========

    @Test
    @DisplayName("Pattern.matches(DirectByteBuffer) should use zero-copy")
    void patternMatches_directBuffer_usesZeroCopy() {
        Pattern pattern = Pattern.compile("hello");

        ByteBuffer buffer = createDirectBuffer("hello");
        assertThat(buffer.isDirect()).isTrue();  // Verify it's direct

        boolean matches = pattern.matches(buffer);

        assertThat(matches).isTrue();
    }

    @Test
    @DisplayName("Pattern.find(DirectByteBuffer) should use zero-copy")
    void patternFind_directBuffer_usesZeroCopy() {
        Pattern pattern = Pattern.compile("world");

        ByteBuffer buffer = createDirectBuffer("hello world");
        assertThat(buffer.isDirect()).isTrue();

        boolean found = pattern.find(buffer);

        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("Pattern.extractGroups(DirectByteBuffer) should use zero-copy")
    void extractGroups_directBuffer_usesZeroCopy() {
        Pattern pattern = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

        ByteBuffer buffer = createDirectBuffer("2025-11-24");
        assertThat(buffer.isDirect()).isTrue();

        String[] groups = pattern.extractGroups(buffer);

        assertThat(groups).containsExactly("2025-11-24", "2025", "11", "24");
    }

    @Test
    @DisplayName("Pattern.findAllMatches(DirectByteBuffer) should use zero-copy")
    void findAllMatches_directBuffer_usesZeroCopy() {
        Pattern pattern = Pattern.compile("\\d+");

        ByteBuffer buffer = createDirectBuffer("a1b22c333");
        assertThat(buffer.isDirect()).isTrue();

        String[][] matches = pattern.findAllMatches(buffer);

        assertThat(matches).isNotNull();
        assertThat(matches.length).isEqualTo(3);
        assertThat(matches[0][0]).isEqualTo("1");
        assertThat(matches[1][0]).isEqualTo("22");
        assertThat(matches[2][0]).isEqualTo("333");
    }

    // ========== Heap ByteBuffer Tests (String Fallback Path) ==========

    @Test
    @DisplayName("Pattern.matches(heap ByteBuffer) should fall back to String API")
    void patternMatches_heapBuffer_fallsBackToString() {
        Pattern pattern = Pattern.compile("hello");

        ByteBuffer buffer = createHeapBuffer("hello");
        assertThat(buffer.isDirect()).isFalse();  // Verify it's heap

        boolean matches = pattern.matches(buffer);

        assertThat(matches).isTrue();
    }

    @Test
    @DisplayName("Pattern.find(heap ByteBuffer) should fall back to String API")
    void patternFind_heapBuffer_fallsBackToString() {
        Pattern pattern = Pattern.compile("world");

        ByteBuffer buffer = createHeapBuffer("hello world");
        assertThat(buffer.isDirect()).isFalse();

        boolean found = pattern.find(buffer);

        assertThat(found).isTrue();
    }

    // ========== Consistency Tests (Direct vs Heap vs String) ==========

    @ParameterizedTest
    @DisplayName("DirectByteBuffer, heap ByteBuffer, and String should all match")
    @CsvSource({
        "\\d+, 12345, true",
        "\\d+, abc, false",
        "[a-z]+, hello, true",
        "[a-z]+, HELLO, false",
        "test, test, true",
        "test, testing, false"
    })
    void allApisProduceSameResults(String patternStr, String input, boolean expected) {
        Pattern pattern = Pattern.compile(patternStr);

        // String API
        boolean stringResult = pattern.matches(input);

        // DirectByteBuffer API (zero-copy)
        ByteBuffer directBuffer = createDirectBuffer(input);
        boolean directResult = pattern.matches(directBuffer);

        // Heap ByteBuffer API (String fallback)
        ByteBuffer heapBuffer = createHeapBuffer(input);
        boolean heapResult = pattern.matches(heapBuffer);

        // All should produce same result
        assertThat(directResult)
            .as("DirectByteBuffer should match String API")
            .isEqualTo(stringResult)
            .isEqualTo(expected);

        assertThat(heapResult)
            .as("Heap ByteBuffer should match String API")
            .isEqualTo(stringResult)
            .isEqualTo(expected);
    }

    @ParameterizedTest
    @DisplayName("find() should work consistently across all API variants")
    @CsvSource({
        "\\d+, abc123def, true",
        "\\d+, abcdef, false",
        "@, user@example.com, true",
        "@, noatsign, false"
    })
    void find_allApisConsistent(String patternStr, String input, boolean expected) {
        Pattern pattern = Pattern.compile(patternStr);

        // String API
        boolean stringResult;
        try (Matcher m = pattern.matcher(input)) {
            stringResult = m.find();
        }

        // DirectByteBuffer
        boolean directResult = pattern.find(createDirectBuffer(input));

        // Heap ByteBuffer
        boolean heapResult = pattern.find(createHeapBuffer(input));

        assertThat(directResult).isEqualTo(stringResult).isEqualTo(expected);
        assertThat(heapResult).isEqualTo(stringResult).isEqualTo(expected);
    }

    // ========== Mixed Usage Tests ==========

    @Test
    @DisplayName("Pattern can mix String, DirectByteBuffer, and heap ByteBuffer")
    void pattern_mixedUsage_allTypes() {
        Pattern pattern = Pattern.compile("\\d+");

        // Use with String
        assertThat(pattern.matches("123")).isTrue();

        // Use with DirectByteBuffer (zero-copy)
        ByteBuffer directBuffer = createDirectBuffer("456");
        assertThat(pattern.matches(directBuffer)).isTrue();

        // Use with heap ByteBuffer (String fallback)
        ByteBuffer heapBuffer = createHeapBuffer("789");
        assertThat(pattern.matches(heapBuffer)).isTrue();

        // Mix all three in same method
        assertThat(pattern.matches("abc")).isFalse();
        assertThat(pattern.matches(createDirectBuffer("def"))).isFalse();
        assertThat(pattern.matches(createHeapBuffer("ghi"))).isFalse();
    }

    // ========== Position/Limit Handling Tests ==========

    @Test
    @DisplayName("ByteBuffer position and limit should be respected")
    void byteBuffer_positionLimit_respected() {
        Pattern pattern = Pattern.compile("world");

        ByteBuffer buffer = createDirectBuffer("hello world goodbye");

        // Match full buffer - should find "world"
        assertThat(pattern.find(buffer)).isTrue();

        // Reset and set position to skip "hello "
        buffer.rewind();
        buffer.position(6);  // Start at "world"
        buffer.limit(11);    // End after "world"

        // Should match just "world"
        assertThat(pattern.matches(buffer)).isTrue();
    }

    @Test
    @DisplayName("ByteBuffer position should not be modified")
    void byteBuffer_positionNotModified() {
        Pattern pattern = Pattern.compile("test");

        ByteBuffer buffer = createDirectBuffer("test");
        int originalPosition = buffer.position();
        int originalLimit = buffer.limit();

        pattern.matches(buffer);

        // Position and limit should be unchanged
        assertThat(buffer.position()).isEqualTo(originalPosition);
        assertThat(buffer.limit()).isEqualTo(originalLimit);
    }

    // ========== Validation Tests ==========

    @Test
    @DisplayName("Should throw on null ByteBuffer")
    void matches_nullByteBuffer_throws() {
        Pattern pattern = Pattern.compile("test");

        assertThatNullPointerException()
            .isThrownBy(() -> pattern.matches((ByteBuffer) null))
            .withMessageContaining("null");
    }

    @Test
    @DisplayName("Empty ByteBuffer should work")
    void matches_emptyByteBuffer_works() {
        Pattern pattern = Pattern.compile(".*");  // Match anything (including empty)

        ByteBuffer emptyDirect = createDirectBuffer("");
        ByteBuffer emptyHeap = createHeapBuffer("");

        assertThat(pattern.matches(emptyDirect)).isTrue();
        assertThat(pattern.matches(emptyHeap)).isTrue();
    }

    // ========== Real-World Scenario Tests ==========

    @Test
    @DisplayName("Real-world: Netty-like scenario with DirectByteBuffer")
    void realWorld_nettyStyleDirectBuffer() {
        Pattern emailPattern = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b", false);

        // Simulate Netty ByteBuf-like usage (direct memory)
        ByteBuffer networkBuffer = ByteBuffer.allocateDirect(1024);
        String message = "New user registered: user@example.com";
        networkBuffer.put(message.getBytes(StandardCharsets.UTF_8));
        networkBuffer.flip();

        // Extract email using zero-copy
        boolean hasEmail = emailPattern.find(networkBuffer);

        assertThat(hasEmail).isTrue();
    }

    @Test
    @DisplayName("Real-world: Process multiple network buffers")
    void realWorld_multipleNetworkBuffers() {
        Pattern validPattern = Pattern.compile("valid_.*");

        // Simulate multiple incoming network buffers
        ByteBuffer[] buffers = {
            createDirectBuffer("valid_request_1"),
            createDirectBuffer("invalid_request"),
            createDirectBuffer("valid_request_2"),
            createHeapBuffer("other_data"),  // Mixed: some heap, some direct
            createDirectBuffer("valid_request_3")
        };

        // Process all buffers
        int validCount = 0;
        for (ByteBuffer buffer : buffers) {
            if (validPattern.matches(buffer)) {
                validCount++;
            }
        }

        assertThat(validCount).isEqualTo(3);
    }
}
