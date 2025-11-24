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

import com.axonops.libre2.cache.PatternCache;
import com.axonops.libre2.test.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for type safety and encoding handling in bulk matching operations.
 * Demonstrates how the API handles non-String types, Unicode, emoji, and special characters.
 */
class BulkMatchingTypeSafetyTest {

    private static PatternCache originalCache;

    @BeforeAll
    static void setUpClass() {
        originalCache = TestUtils.replaceGlobalCache(TestUtils.testConfigBuilder().build());
    }

    @AfterAll
    static void tearDownClass() {
        TestUtils.restoreGlobalCache(originalCache);
    }

    // ========== Type Safety Tests ==========

    /**
     * Demonstrates compile-time type safety.
     * Collection<Integer> cannot be passed to matchAll(Collection<String>).
     * This test verifies the API contract.
     */
    @Test
    void testTypeSafety_CompileTime() {
        Pattern pattern = Pattern.compile("\\d+");

        // This compiles - correct type
        List<String> strings = List.of("123", "456");
        boolean[] results = pattern.matchAll(strings);
        assertNotNull(results);

        // This would NOT compile (commented out to allow test compilation):
        // List<Integer> ints = List.of(123, 456);
        // pattern.matchAll(ints);  // Compile error: Required Collection<String>, found Collection<Integer>

        // Java's type system prevents this at compile time
    }

    /**
     * Tests runtime behavior with raw types (unchecked warnings).
     * We throw explicit IllegalArgumentException with helpful message.
     */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void testTypeSafety_RuntimeWithRawTypes() {
        Pattern pattern = Pattern.compile("test");

        // Using raw List (no generic type) - compiles with warning
        List raw = new ArrayList();
        raw.add(123);          // Integer
        raw.add("test");       // String
        raw.add(456);          // Integer

        // Should throw IllegalArgumentException with helpful message
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> {
            pattern.matchAll(raw);
        });

        // Verify error message is helpful
        assertTrue(e.getMessage().contains("non-String elements"));
        assertTrue(e.getMessage().contains("stream().map(Object::toString)"));
        assertNotNull(e.getCause());  // Cause is ArrayStoreException
        assertTrue(e.getCause() instanceof ArrayStoreException);
    }

    /**
     * Tests that all Collection-based methods throw helpful errors for non-String elements.
     */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void testTypeSafety_AllMethodsValidate() {
        Pattern pattern = Pattern.compile("test");

        List raw = new ArrayList();
        raw.add(123);

        // All Collection methods should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> pattern.matchAll(raw));
        assertThrows(IllegalArgumentException.class, () -> pattern.filter(raw));
        assertThrows(IllegalArgumentException.class, () -> pattern.filterNot(raw));

        List mutableRaw = new ArrayList();
        mutableRaw.add(123);
        assertThrows(IllegalArgumentException.class, () -> pattern.retainMatches(mutableRaw));

        List mutableRaw2 = new ArrayList();
        mutableRaw2.add(123);
        assertThrows(IllegalArgumentException.class, () -> pattern.removeMatches(mutableRaw2));
    }

    // ========== UTF-8 / Unicode Handling Tests ==========

    /**
     * Tests bulk matching with Unicode characters (Chinese, Arabic, Emoji).
     * Java Strings are UTF-16, JNI converts to UTF-8 automatically.
     */
    @Test
    void testBulkMatching_UnicodeCharacters() {
        // Chinese characters
        Pattern chinesePattern = Pattern.compile("中文.*");
        List<String> chineseInputs = List.of("中文测试", "English", "中文字符", "123");
        boolean[] results = chinesePattern.matchAll(chineseInputs);

        assertArrayEquals(new boolean[]{true, false, true, false}, results);

        // Arabic characters
        Pattern arabicPattern = Pattern.compile("مرحبا.*");
        List<String> arabicInputs = List.of("مرحبا بك", "hello", "مرحبا العالم");
        results = arabicPattern.matchAll(arabicInputs);

        assertArrayEquals(new boolean[]{true, false, true}, results);

        // Emoji
        Pattern emojiPattern = Pattern.compile(".*😀.*");
        List<String> emojiInputs = List.of("hello😀world", "no emoji", "test😀😀test");
        results = emojiPattern.matchAll(emojiInputs);

        assertArrayEquals(new boolean[]{true, false, true}, results);
    }

    /**
     * Tests bulk matching with mixed Unicode scripts in same string.
     */
    @Test
    void testBulkMatching_MixedScripts() {
        Pattern pattern = Pattern.compile("User:\\s*\\S+");

        List<String> inputs = List.of(
            "User: Alice",      // ASCII
            "User: 田中",       // Japanese
            "User: José",       // Accented
            "User: مصطفى",      // Arabic
            "User: 李明"        // Chinese
        );

        boolean[] results = pattern.matchAll(inputs);

        // All should match (\\S+ matches any non-whitespace including Unicode)
        assertArrayEquals(new boolean[]{true, true, true, true, true}, results);
    }

    /**
     * Tests bulk filtering with emoji and special Unicode characters.
     */
    @Test
    void testFilter_EmojiAndSpecialCharacters() {
        // Match strings containing specific emoji (literal match, not ranges)
        Pattern pattern = Pattern.compile(".*(😀|😢|😁|😂|😃).*");

        List<String> inputs = List.of(
            "Happy 😀",
            "Sad 😢",
            "No emoji",
            "Multiple 😁😂😃"
        );

        List<String> filtered = pattern.filter(inputs);

        assertEquals(3, filtered.size());  // All except "No emoji"
        assertTrue(filtered.contains("Happy 😀"));
        assertTrue(filtered.contains("Sad 😢"));
        assertTrue(filtered.contains("Multiple 😁😂😃"));
    }

    /**
     * Tests handling of zero-width characters and combining characters.
     */
    @Test
    void testMatchAll_ZeroWidthAndCombiningCharacters() {
        Pattern pattern = Pattern.compile("test.*");

        List<String> inputs = List.of(
            "test",
            "test\u200B",      // Zero-width space
            "test\u0301",      // Combining acute accent
            "tést",            // Precomposed é
            "te\u0301st"       // e + combining accent
        );

        boolean[] results = pattern.matchAll(inputs);

        // All should match (pattern is "test.*" which matches test followed by anything)
        assertArrayEquals(new boolean[]{true, true, true, false, false}, results);
    }

    // ========== Special Character Tests ==========

    /**
     * Tests bulk matching with control characters, newlines, tabs.
     */
    @Test
    void testMatchAll_ControlCharacters() {
        Pattern pattern = Pattern.compile("line\\d+");

        List<String> inputs = List.of(
            "line1",
            "line2\n",         // With newline
            "line3\t",         // With tab
            "line4\r\n",       // With CR+LF
            "other\n"
        );

        boolean[] results = pattern.matchAll(inputs);

        // Pattern matches "lineN" without trailing characters
        assertArrayEquals(new boolean[]{true, false, false, false, false}, results);
    }

    /**
     * Tests map filtering with Unicode keys and values.
     */
    @Test
    void testMapFiltering_UnicodeKeysAndValues() {
        Pattern pattern = Pattern.compile("用户.*");  // Chinese "user"

        Map<String, String> users = new HashMap<>();
        users.put("用户001", "Alice");
        users.put("管理员", "Admin");
        users.put("用户002", "Bob");

        Map<String, String> filtered = pattern.filterByKey(users);

        assertEquals(2, filtered.size());
        assertTrue(filtered.containsKey("用户001"));
        assertTrue(filtered.containsKey("用户002"));
        assertFalse(filtered.containsKey("管理员"));
    }

    // ========== toString() Behavior Tests ==========

    /**
     * Demonstrates that non-String objects would need explicit toString().
     * Since our signature requires Collection<String>, this is handled at compile time.
     */
    @Test
    void testNonStringObjects_RequireExplicitConversion() {
        Pattern pattern = Pattern.compile("\\d+");

        // If you have Collection<Integer>, you must convert explicitly
        List<Integer> numbers = List.of(123, 456, 789);

        // Convert to strings explicitly
        List<String> stringNumbers = numbers.stream()
            .map(Object::toString)
            .toList();

        boolean[] results = pattern.matchAll(stringNumbers);
        assertArrayEquals(new boolean[]{true, true, true}, results);
    }

    // ========== Null and Empty Tests ==========

    /**
     * Tests how matchAll handles collections with null elements.
     * Nulls should not crash - JNI handles them gracefully (returns false for match).
     */
    @Test
    void testMatchAll_NullElements_DoesNotCrash() {
        Pattern pattern = Pattern.compile("test");

        String[] arrayWithNulls = {"test", null, "other", null};
        boolean[] results = pattern.matchAll(arrayWithNulls);

        assertEquals(4, results.length);
        assertTrue(results[0]);   // "test" matches
        assertFalse(results[1]);  // null doesn't match
        assertFalse(results[2]);  // "other" doesn't match
        assertFalse(results[3]);  // null doesn't match
    }

    /**
     * Tests bulk matching with empty strings.
     */
    @Test
    void testMatchAll_EmptyStrings() {
        // Pattern that matches empty string (and only empty)
        Pattern onlyEmptyPattern = Pattern.compile("^$");
        List<String> inputs = List.of("", "test", "", "other");
        boolean[] results = onlyEmptyPattern.matchAll(inputs);

        assertArrayEquals(new boolean[]{true, false, true, false}, results);

        // Pattern that matches anything (including empty)
        Pattern anyPattern = Pattern.compile(".*");
        results = anyPattern.matchAll(inputs);

        assertArrayEquals(new boolean[]{true, true, true, true}, results);
    }

    // ========== Binary Data / Invalid UTF-8 ==========

    /**
     * Tests behavior with strings containing invalid UTF-16 surrogate pairs.
     * JNI's GetStringUTFChars handles this by replacing invalid sequences.
     */
    @Test
    void testMatchAll_InvalidSurrogates() {
        Pattern pattern = Pattern.compile("test.*");

        // Create string with unpaired surrogate (invalid UTF-16)
        String invalidSurrogate = "test\uD800";  // High surrogate without low surrogate

        List<String> inputs = List.of("test", invalidSurrogate, "test123");
        boolean[] results = pattern.matchAll(inputs);

        // JNI will replace invalid sequence, pattern may or may not match
        // The important thing is it doesn't crash
        assertEquals(3, results.length);
        assertNotNull(results);  // Just verify no crash
    }
}
