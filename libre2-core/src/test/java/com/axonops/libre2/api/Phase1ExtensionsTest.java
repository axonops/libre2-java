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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Phase 1 extensions: findAll bulk variants and ByteBuffer[] bulk.
 */
@DisplayName("Phase 1 Extensions (findAll bulk + ByteBuffer[] bulk)")
class Phase1ExtensionsTest {

    private ByteBuffer createDirectBuffer(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes.length);
        buffer.put(bytes);
        buffer.flip();
        return buffer;
    }

    private ByteBuffer createHeapBuffer(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    // ========== findAll(String[]) Tests ==========

    @Test
    @DisplayName("findAll(String[]) should find partial matches in all strings")
    void findAll_stringArray_findsPartialMatches() {
        Pattern pattern = Pattern.compile("test");
        String[] inputs = {
            "test",           // Full match - should find
            "testing",        // Partial match - should find
            "notest",         // Partial match - should find
            "other"           // No match
        };

        boolean[] results = pattern.findAll(inputs);

        assertThat(results).containsExactly(true, true, true, false);
    }

    @Test
    @DisplayName("findAll(String[]) vs matchAll(String[]) - partial vs full")
    void findAll_vs_matchAll_differentBehavior() {
        Pattern pattern = Pattern.compile("test");
        String[] inputs = {"test", "testing", "other"};

        boolean[] matchResults = pattern.matchAll(inputs);  // Full match
        boolean[] findResults = pattern.findAll(inputs);    // Partial match

        assertThat(matchResults).containsExactly(true, false, false);  // Only exact matches
        assertThat(findResults).containsExactly(true, true, false);     // Partial matches too
    }

    @Test
    @DisplayName("findAll(Collection<String>) should work")
    void findAll_collection_works() {
        Pattern pattern = Pattern.compile("\\d+");
        List<String> inputs = Arrays.asList("abc123", "def", "456ghi");

        boolean[] results = pattern.findAll(inputs);

        assertThat(results).containsExactly(true, false, true);
    }

    @Test
    @DisplayName("findAll(String[]) with empty array should return empty")
    void findAll_emptyArray_returnsEmpty() {
        Pattern pattern = Pattern.compile("test");

        boolean[] results = pattern.findAll(new String[0]);

        assertThat(results).isEmpty();
    }

    // ========== matchAll(ByteBuffer[]) Tests ==========

    @Test
    @DisplayName("matchAll(ByteBuffer[]) with all DirectByteBuffers should use zero-copy")
    void matchAll_allDirectBuffers_usesZeroCopy() {
        Pattern pattern = Pattern.compile("test");
        ByteBuffer[] buffers = {
            createDirectBuffer("test"),
            createDirectBuffer("testing"),
            createDirectBuffer("test")
        };

        boolean[] results = pattern.matchAll(buffers);

        assertThat(results).containsExactly(true, false, true);
    }

    @Test
    @DisplayName("matchAll(ByteBuffer[]) with all heap buffers should convert to String")
    void matchAll_allHeapBuffers_convertsToString() {
        Pattern pattern = Pattern.compile("test");
        ByteBuffer[] buffers = {
            createHeapBuffer("test"),
            createHeapBuffer("testing"),
            createHeapBuffer("test")
        };

        boolean[] results = pattern.matchAll(buffers);

        assertThat(results).containsExactly(true, false, true);
    }

    @Test
    @DisplayName("matchAll(ByteBuffer[]) with mixed buffers should convert all to String")
    void matchAll_mixedBuffers_convertsToString() {
        Pattern pattern = Pattern.compile("test");
        ByteBuffer[] buffers = {
            createDirectBuffer("test"),       // Direct
            createHeapBuffer("testing"),      // Heap - forces String path for all
            createDirectBuffer("test")        // Direct
        };

        boolean[] results = pattern.matchAll(buffers);

        assertThat(results).containsExactly(true, false, true);
    }

    @Test
    @DisplayName("matchAll(ByteBuffer[]) should produce same results as matchAll(String[])")
    void matchAll_byteBufferArray_matchesStringArray() {
        Pattern pattern = Pattern.compile("\\d+");
        String[] strings = {"123", "abc", "456"};

        boolean[] stringResults = pattern.matchAll(strings);

        ByteBuffer[] buffers = {
            createDirectBuffer("123"),
            createDirectBuffer("abc"),
            createDirectBuffer("456")
        };

        boolean[] bufferResults = pattern.matchAll(buffers);

        assertThat(bufferResults).containsExactly(stringResults);
    }

    // ========== findAll(ByteBuffer[]) Tests ==========

    @Test
    @DisplayName("findAll(ByteBuffer[]) with DirectByteBuffers should use zero-copy")
    void findAll_directBuffers_usesZeroCopy() {
        Pattern pattern = Pattern.compile("test");
        ByteBuffer[] buffers = {
            createDirectBuffer("test"),      // Full match - finds
            createDirectBuffer("testing"),   // Partial match - finds
            createDirectBuffer("other")      // No match
        };

        boolean[] results = pattern.findAll(buffers);

        assertThat(results).containsExactly(true, true, false);
    }

    @Test
    @DisplayName("findAll(ByteBuffer[]) should differ from matchAll(ByteBuffer[]) for partial matches")
    void findAll_vs_matchAll_byteBuffers_differentBehavior() {
        Pattern pattern = Pattern.compile("test");
        ByteBuffer[] buffers = {
            createDirectBuffer("test"),
            createDirectBuffer("testing"),
            createDirectBuffer("other")
        };

        boolean[] matchResults = pattern.matchAll(buffers);  // Full match
        boolean[] findResults = pattern.findAll(buffers);    // Partial match

        assertThat(matchResults).containsExactly(true, false, false);  // Only exact
        assertThat(findResults).containsExactly(true, true, false);     // Includes partial
    }

    @Test
    @DisplayName("findAll(ByteBuffer[]) with empty array should return empty")
    void findAll_emptyBufferArray_returnsEmpty() {
        Pattern pattern = Pattern.compile("test");

        boolean[] results = pattern.findAll(new ByteBuffer[0]);

        assertThat(results).isEmpty();
    }

    // ========== Integration Tests ==========

    @Test
    @DisplayName("ByteBuffer[] bulk should work with Cassandra-like multi-column scenario")
    void cassandraScenario_bulkByteBufferProcessing() {
        Pattern emailPattern = Pattern.compile("[a-z]+@[a-z]+\\.[a-z]+");

        // Simulate Cassandra returning ByteBuffer[] from multiple cells
        ByteBuffer[] cells = {
            createDirectBuffer("user@example.com"),
            createDirectBuffer("invalid"),
            createDirectBuffer("admin@test.org"),
            createDirectBuffer("also_invalid")
        };

        boolean[] results = emailPattern.matchAll(cells);

        assertThat(results).containsExactly(true, false, true, false);

        // Count valid emails
        long validCount = 0;
        for (boolean result : results) {
            if (result) validCount++;
        }
        assertThat(validCount).isEqualTo(2);
    }

    // ========== Null Handling ==========

    @Test
    @DisplayName("findAll(String[]) should throw on null array")
    void findAll_nullArray_throws() {
        Pattern pattern = Pattern.compile("test");

        assertThatNullPointerException()
            .isThrownBy(() -> pattern.findAll((String[]) null));
    }

    @Test
    @DisplayName("matchAll(ByteBuffer[]) should throw on null array")
    void matchAll_nullByteBufferArray_throws() {
        Pattern pattern = Pattern.compile("test");

        assertThatNullPointerException()
            .isThrownBy(() -> pattern.matchAll((ByteBuffer[]) null));
    }

    @Test
    @DisplayName("findAll(ByteBuffer[]) should throw on null array")
    void findAll_nullByteBufferArray_throws() {
        Pattern pattern = Pattern.compile("test");

        assertThatNullPointerException()
            .isThrownBy(() -> pattern.findAll((ByteBuffer[]) null));
    }
}
