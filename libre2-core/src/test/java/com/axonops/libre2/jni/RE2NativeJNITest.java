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

import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.cache.PatternCache;
import com.axonops.libre2.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct tests of the JNI layer (RE2NativeJNI) without Java wrapper.
 *
 * <p>These tests help isolate issues to either the native code or Java wrapper.
 * When debugging failures, check if the JNI method works directly before
 * investigating the Java wrapper layer.
 *
 * <p><b>IMPORTANT:</b> These tests directly manage native handles.
 * Always free handles in @AfterEach to prevent memory leaks.
 */
class RE2NativeJNITest {

    private static PatternCache originalCache;

    @BeforeAll
    static void setUpClass() {
        // Replace global cache with test config (disables JMX to prevent InstanceAlreadyExistsException)
        originalCache = TestUtils.replaceGlobalCache(TestUtils.testConfigBuilder().build());
    }

    @AfterAll
    static void tearDownClass() {
        // Restore original cache
        TestUtils.restoreGlobalCache(originalCache);
    }

    private long handle;

    @BeforeEach
    void setUp() {
        // Compile a test pattern
        handle = RE2NativeJNI.compile("test\\d+", true);
        assertTrue(handle != 0, "Pattern compilation should succeed");
    }

    @AfterEach
    void tearDown() {
        // Always free handle to prevent leaks
        if (handle != 0) {
            RE2NativeJNI.freePattern(handle);
            handle = 0;
        }
    }

    // ========== Basic Compilation and Lifecycle ==========

    @Test
    void testCompile_Success() {
        long h = RE2NativeJNI.compile("simple", true);
        assertTrue(h != 0);
        assertTrue(RE2NativeJNI.patternOk(h));
        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testCompile_InvalidPattern() {
        long h = RE2NativeJNI.compile("[invalid(", true);
        assertEquals(0, h);  // Should return 0 on error

        String error = RE2NativeJNI.getError();
        assertNotNull(error);
        assertTrue(error.contains("missing") || error.contains("unclosed"));
    }

    @Test
    void testCompile_CaseSensitive_vs_Insensitive() {
        long caseSensitive = RE2NativeJNI.compile("Test", true);
        long caseInsensitive = RE2NativeJNI.compile("Test", false);

        // Case sensitive: only "Test" matches
        assertTrue(RE2NativeJNI.fullMatch(caseSensitive, "Test"));
        assertFalse(RE2NativeJNI.fullMatch(caseSensitive, "test"));

        // Case insensitive: both match
        assertTrue(RE2NativeJNI.fullMatch(caseInsensitive, "Test"));
        assertTrue(RE2NativeJNI.fullMatch(caseInsensitive, "test"));

        RE2NativeJNI.freePattern(caseSensitive);
        RE2NativeJNI.freePattern(caseInsensitive);
    }

    @Test
    void testFreePattern_ZeroHandle() {
        // Should not crash
        RE2NativeJNI.freePattern(0);
    }

    // ========== Matching Operations (Single) ==========

    @Test
    void testFullMatch_Basic() {
        assertTrue(RE2NativeJNI.fullMatch(handle, "test123"));
        assertTrue(RE2NativeJNI.fullMatch(handle, "test0"));
        assertFalse(RE2NativeJNI.fullMatch(handle, "test"));     // No digit
        assertFalse(RE2NativeJNI.fullMatch(handle, "test123x")); // Extra char
    }

    @Test
    void testPartialMatch_Basic() {
        assertTrue(RE2NativeJNI.partialMatch(handle, "prefix test123 suffix"));
        assertTrue(RE2NativeJNI.partialMatch(handle, "test0"));
        assertFalse(RE2NativeJNI.partialMatch(handle, "no match here"));
    }

    @Test
    void testFullMatch_NullText() {
        assertFalse(RE2NativeJNI.fullMatch(handle, null));
    }

    @Test
    void testFullMatch_ZeroHandle() {
        assertFalse(RE2NativeJNI.fullMatch(0, "test"));
    }

    // ========== Bulk Matching Operations (NEW) ==========

    @Test
    void testFullMatchBulk_Basic() {
        String[] texts = {"test1", "no match", "test999", "test"};
        boolean[] results = RE2NativeJNI.fullMatchBulk(handle, texts);

        assertNotNull(results);
        assertEquals(4, results.length);
        assertTrue(results[0]);   // "test1" matches
        assertFalse(results[1]);  // "no match" doesn't
        assertTrue(results[2]);   // "test999" matches
        assertFalse(results[3]);  // "test" doesn't (no digit)
    }

    @Test
    void testPartialMatchBulk_Basic() {
        String[] texts = {"prefix test1 suffix", "no match", "test999"};
        boolean[] results = RE2NativeJNI.partialMatchBulk(handle, texts);

        assertNotNull(results);
        assertEquals(3, results.length);
        assertTrue(results[0]);
        assertFalse(results[1]);
        assertTrue(results[2]);
    }

    @Test
    void testFullMatchBulk_EmptyArray() {
        String[] empty = {};
        boolean[] results = RE2NativeJNI.fullMatchBulk(handle, empty);

        assertNotNull(results);
        assertEquals(0, results.length);
    }

    @Test
    void testFullMatchBulk_WithNullElements() {
        String[] texts = {"test1", null, "test2", null};
        boolean[] results = RE2NativeJNI.fullMatchBulk(handle, texts);

        assertNotNull(results);
        assertEquals(4, results.length);
        assertTrue(results[0]);
        assertFalse(results[1]);  // null should not match
        assertTrue(results[2]);
        assertFalse(results[3]);  // null should not match
    }

    @Test
    void testFullMatchBulk_NullArray() {
        boolean[] results = RE2NativeJNI.fullMatchBulk(handle, null);
        assertNull(results);
    }

    @Test
    void testFullMatchBulk_ZeroHandle() {
        String[] texts = {"test"};
        boolean[] results = RE2NativeJNI.fullMatchBulk(0, texts);
        assertNull(results);
    }

    // ========== Capture Group Operations (NEW) ==========

    @Test
    void testExtractGroups_NoGroups() {
        long h = RE2NativeJNI.compile("test\\d+", true);

        String[] groups = RE2NativeJNI.extractGroups(h, "test123");

        assertNotNull(groups);
        assertEquals(1, groups.length);  // Just group 0 (full match)
        assertEquals("test123", groups[0]);

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testExtractGroups_WithGroups() {
        long h = RE2NativeJNI.compile("(\\d{3})-(\\d{4})", true);

        String[] groups = RE2NativeJNI.extractGroups(h, "123-4567");

        assertNotNull(groups);
        assertEquals(3, groups.length);  // group 0 + 2 capturing groups
        assertEquals("123-4567", groups[0]);  // Full match
        assertEquals("123", groups[1]);       // First group
        assertEquals("4567", groups[2]);      // Second group

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testExtractGroups_NoMatch() {
        long h = RE2NativeJNI.compile("(\\d+)", true);

        String[] groups = RE2NativeJNI.extractGroups(h, "no digits");

        assertNull(groups);  // No match returns null

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testExtractGroupsBulk_Basic() {
        long h = RE2NativeJNI.compile("(\\d{3})-(\\d{4})", true);

        String[] texts = {"123-4567", "invalid", "999-8888"};
        String[][] results = RE2NativeJNI.extractGroupsBulk(h, texts);

        assertNotNull(results);
        assertEquals(3, results.length);

        // First input matches
        assertNotNull(results[0]);
        assertEquals(3, results[0].length);
        assertEquals("123-4567", results[0][0]);
        assertEquals("123", results[0][1]);
        assertEquals("4567", results[0][2]);

        // Second input doesn't match
        assertNull(results[1]);

        // Third input matches
        assertNotNull(results[2]);
        assertEquals("999-8888", results[2][0]);

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testFindAllMatches_Multiple() {
        long h = RE2NativeJNI.compile("(\\d+)", true);

        String[][] results = RE2NativeJNI.findAllMatches(h, "Found 123 and 456 and 789");

        assertNotNull(results);
        assertEquals(3, results.length);  // 3 matches

        assertEquals("123", results[0][0]);
        assertEquals("123", results[0][1]);

        assertEquals("456", results[1][0]);
        assertEquals("789", results[2][0]);

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testFindAllMatches_NoMatches() {
        long h = RE2NativeJNI.compile("(\\d+)", true);

        String[][] results = RE2NativeJNI.findAllMatches(h, "no numbers here");

        assertNull(results);  // No matches returns null

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testGetNamedGroups_NoNamedGroups() {
        long h = RE2NativeJNI.compile("(\\d+)", true);

        String[] namedGroups = RE2NativeJNI.getNamedGroups(h);

        assertNull(namedGroups);  // No named groups returns null

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testGetNamedGroups_WithNamedGroups() {
        long h = RE2NativeJNI.compile("(?P<area>\\d{3})-(?P<number>\\d{4})", true);

        String[] namedGroups = RE2NativeJNI.getNamedGroups(h);

        assertNotNull(namedGroups);
        // Flattened: [name1, index1_as_string, name2, index2_as_string, ...]
        assertEquals(4, namedGroups.length);

        // Find indices
        int areaIndex = -1;
        int numberIndex = -1;
        for (int i = 0; i < namedGroups.length; i += 2) {
            if ("area".equals(namedGroups[i])) {
                areaIndex = Integer.parseInt(namedGroups[i + 1]);
            } else if ("number".equals(namedGroups[i])) {
                numberIndex = Integer.parseInt(namedGroups[i + 1]);
            }
        }

        assertEquals(1, areaIndex);
        assertEquals(2, numberIndex);

        RE2NativeJNI.freePattern(h);
    }

    // ========== Replace Operations (NEW) ==========

    @Test
    void testReplaceFirst_Basic() {
        long h = RE2NativeJNI.compile("\\d+", true);

        String result = RE2NativeJNI.replaceFirst(h, "Found 123 and 456", "XXX");

        assertEquals("Found XXX and 456", result);  // Only first replaced

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testReplaceFirst_NoMatch() {
        long h = RE2NativeJNI.compile("\\d+", true);

        String result = RE2NativeJNI.replaceFirst(h, "no numbers", "XXX");

        assertEquals("no numbers", result);  // Unchanged if no match

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testReplaceAll_Basic() {
        long h = RE2NativeJNI.compile("\\d+", true);

        String result = RE2NativeJNI.replaceAll(h, "Found 123 and 456 and 789", "XXX");

        assertEquals("Found XXX and XXX and XXX", result);

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testReplaceAll_Backreferences() {
        long h = RE2NativeJNI.compile("(\\d{3})-(\\d{4})", true);

        // RE2 uses \\1, \\2 syntax for backreferences (not $1, $2)
        String result = RE2NativeJNI.replaceAll(h, "Call 123-4567 or 999-8888", "(\\1) \\2");

        assertEquals("Call (123) 4567 or (999) 8888", result);

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testReplaceAllBulk_Basic() {
        long h = RE2NativeJNI.compile("\\d+", true);

        String[] texts = {"Found 123", "No match", "Has 456 and 789"};
        String[] results = RE2NativeJNI.replaceAllBulk(h, texts, "XXX");

        assertNotNull(results);
        assertEquals(3, results.length);
        assertEquals("Found XXX", results[0]);
        assertEquals("No match", results[1]);
        assertEquals("Has XXX and XXX", results[2]);

        RE2NativeJNI.freePattern(h);
    }

    // ========== Utility Operations (NEW) ==========

    @Test
    void testQuoteMeta_Basic() {
        String escaped = RE2NativeJNI.quoteMeta("price: $100 (special)");

        assertNotNull(escaped);
        // RE2::QuoteMeta escapes ALL regex special chars including space, colon
        assertTrue(escaped.contains("\\$"));
        assertTrue(escaped.contains("\\("));
        assertTrue(escaped.contains("\\)"));
    }

    @Test
    void testQuoteMeta_NoSpecialChars() {
        String escaped = RE2NativeJNI.quoteMeta("simple");

        assertEquals("simple", escaped);
    }

    @Test
    void testQuoteMeta_Null() {
        String result = RE2NativeJNI.quoteMeta(null);

        assertNull(result);
    }

    @Test
    void testProgramFanout_Basic() {
        long h = RE2NativeJNI.compile("(a|b|c)+", true);

        int[] fanout = RE2NativeJNI.programFanout(h);

        // May return null for simple patterns (no meaningful fanout)
        // Just verify it doesn't crash
        // If not null, should be a valid array
        if (fanout != null) {
            assertTrue(fanout.length >= 0);
        }

        RE2NativeJNI.freePattern(h);
    }

    // ========== Pattern Info Methods ==========

    @Test
    void testGetPattern() {
        String pattern = RE2NativeJNI.getPattern(handle);

        assertEquals("test\\d+", pattern);
    }

    @Test
    void testNumCapturingGroups() {
        long h = RE2NativeJNI.compile("(\\d{3})-(\\d{4})", true);

        int numGroups = RE2NativeJNI.numCapturingGroups(h);

        assertEquals(2, numGroups);

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testPatternOk() {
        assertTrue(RE2NativeJNI.patternOk(handle));
        assertFalse(RE2NativeJNI.patternOk(0));
    }

    @Test
    void testPatternMemory() {
        long memory = RE2NativeJNI.patternMemory(handle);

        assertTrue(memory > 0);  // Should report some memory usage
        assertTrue(memory < 10_000);  // Simple pattern should be < 10KB
    }

    // ========== Edge Cases and Error Handling ==========

    @Test
    void testUnicodeHandling() {
        long h = RE2NativeJNI.compile("中文\\d+", true);

        assertTrue(RE2NativeJNI.fullMatch(h, "中文123"));
        assertFalse(RE2NativeJNI.fullMatch(h, "中文"));

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testEmojiHandling() {
        long h = RE2NativeJNI.compile(".*😀.*", true);

        assertTrue(RE2NativeJNI.fullMatch(h, "Hello 😀 World"));
        assertFalse(RE2NativeJNI.fullMatch(h, "No emoji"));

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testEmptyPattern_Allowed() {
        long h = RE2NativeJNI.compile("", true);

        // RE2 allows empty patterns (they match empty strings)
        assertTrue(h != 0);
        assertTrue(RE2NativeJNI.fullMatch(h, ""));
        assertFalse(RE2NativeJNI.fullMatch(h, "test"));

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testVeryLongPattern() {
        // Create pattern with 1000 alternations
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            if (i > 0) sb.append("|");
            sb.append("item").append(i);
        }

        long h = RE2NativeJNI.compile(sb.toString(), true);

        assertTrue(h != 0);  // Should compile successfully
        assertTrue(RE2NativeJNI.fullMatch(h, "item500"));
        assertFalse(RE2NativeJNI.fullMatch(h, "item1000"));

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testBulkMatching_LargeArray() {
        // Test with 1000 strings
        String[] texts = new String[1000];
        for (int i = 0; i < 1000; i++) {
            texts[i] = "test" + i;
        }

        boolean[] results = RE2NativeJNI.fullMatchBulk(handle, texts);

        assertNotNull(results);
        assertEquals(1000, results.length);
        assertTrue(results[0]);    // test0 matches
        assertTrue(results[999]);  // test999 matches
    }

    // ========== Zero-Copy Direct Memory Operations ==========

    @Test
    void testFullMatchDirect_Success() {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(20);
        buffer.put("test123".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        buffer.flip();

        long address = ((sun.nio.ch.DirectBuffer) buffer).address();
        int length = buffer.remaining();

        boolean result = RE2NativeJNI.fullMatchDirect(handle, address, length);

        assertTrue(result);
    }

    @Test
    void testPartialMatchDirect_Success() {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(20);
        buffer.put("before test456 after".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        buffer.flip();

        long address = ((sun.nio.ch.DirectBuffer) buffer).address();
        int length = buffer.remaining();

        boolean result = RE2NativeJNI.partialMatchDirect(handle, address, length);

        assertTrue(result);
    }

    @Test
    void testFullMatchDirectBulk_Success() {
        // Create 3 direct buffers
        java.nio.ByteBuffer[] buffers = new java.nio.ByteBuffer[3];
        long[] addresses = new long[3];
        int[] lengths = new int[3];

        String[] texts = {"test123", "test456", "nomatch"};
        for (int i = 0; i < 3; i++) {
            buffers[i] = java.nio.ByteBuffer.allocateDirect(20);
            buffers[i].put(texts[i].getBytes(java.nio.charset.StandardCharsets.UTF_8));
            buffers[i].flip();
            addresses[i] = ((sun.nio.ch.DirectBuffer) buffers[i]).address();
            lengths[i] = buffers[i].remaining();
        }

        boolean[] results = RE2NativeJNI.fullMatchDirectBulk(handle, addresses, lengths);

        assertNotNull(results);
        assertEquals(3, results.length);
        assertTrue(results[0]);   // test123 matches
        assertTrue(results[1]);   // test456 matches
        assertFalse(results[2]);  // nomatch doesn't match
    }

    @Test
    void testExtractGroupsDirect_Success() {
        long h = RE2NativeJNI.compile("(\\d+)-(\\d+)", true);

        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(20);
        buffer.put("123-456".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        buffer.flip();

        long address = ((sun.nio.ch.DirectBuffer) buffer).address();
        int length = buffer.remaining();

        String[] groups = RE2NativeJNI.extractGroupsDirect(h, address, length);

        assertNotNull(groups);
        assertEquals(3, groups.length);
        assertEquals("123-456", groups[0]);  // Full match
        assertEquals("123", groups[1]);      // First group
        assertEquals("456", groups[2]);      // Second group

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testFindAllMatchesDirect_Success() {
        long h = RE2NativeJNI.compile("(\\d+)", true);

        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(30);
        buffer.put("a1b22c333".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        buffer.flip();

        long address = ((sun.nio.ch.DirectBuffer) buffer).address();
        int length = buffer.remaining();

        String[][] matches = RE2NativeJNI.findAllMatchesDirect(h, address, length);

        assertNotNull(matches);
        assertEquals(3, matches.length);
        assertEquals("1", matches[0][0]);
        assertEquals("22", matches[1][0]);
        assertEquals("333", matches[2][0]);

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testReplaceFirstDirect_Success() {
        long h = RE2NativeJNI.compile("\\d+", true);

        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(30);
        buffer.put("Item 123 costs $456".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        buffer.flip();

        long address = ((sun.nio.ch.DirectBuffer) buffer).address();
        int length = buffer.remaining();

        String result = RE2NativeJNI.replaceFirstDirect(h, address, length, "XXX");

        assertEquals("Item XXX costs $456", result);

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testReplaceAllDirect_Success() {
        long h = RE2NativeJNI.compile("\\d+", true);

        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocateDirect(30);
        buffer.put("Item 123 costs $456".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        buffer.flip();

        long address = ((sun.nio.ch.DirectBuffer) buffer).address();
        int length = buffer.remaining();

        String result = RE2NativeJNI.replaceAllDirect(h, address, length, "XXX");

        assertEquals("Item XXX costs $XXX", result);

        RE2NativeJNI.freePattern(h);
    }

    @Test
    void testReplaceAllDirectBulk_Success() {
        long h = RE2NativeJNI.compile("\\d+", true);

        // Create 3 direct buffers
        java.nio.ByteBuffer[] buffers = new java.nio.ByteBuffer[3];
        long[] addresses = new long[3];
        int[] lengths = new int[3];

        String[] texts = {"Found 123", "No match", "Has 456 and 789"};
        for (int i = 0; i < 3; i++) {
            buffers[i] = java.nio.ByteBuffer.allocateDirect(30);
            buffers[i].put(texts[i].getBytes(java.nio.charset.StandardCharsets.UTF_8));
            buffers[i].flip();
            addresses[i] = ((sun.nio.ch.DirectBuffer) buffers[i]).address();
            lengths[i] = buffers[i].remaining();
        }

        String[] results = RE2NativeJNI.replaceAllDirectBulk(h, addresses, lengths, "XXX");

        assertNotNull(results);
        assertEquals(3, results.length);
        assertEquals("Found XXX", results[0]);
        assertEquals("No match", results[1]);
        assertEquals("Has XXX and XXX", results[2]);

        RE2NativeJNI.freePattern(h);
    }
}
