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

import com.sun.jna.Library;
import com.sun.jna.Pointer;

/**
 * JNA interface to the native RE2 library.
 *
 * Maps directly to the C functions in re2_wrapper.cpp.
 * All methods are native calls executing off-heap.
 *
 * CRITICAL SAFETY:
 * - All Pointer returns MUST be freed via re2_free_pattern
 * - Never call methods on null pointers (will crash JVM)
 * - All strings are UTF-8 encoded
 *
 * @since 1.0.0
 */
public interface RE2Native extends Library {

    /**
     * Compiles a regular expression pattern.
     *
     * @param pattern regex pattern string (UTF-8)
     * @param patternLen length of pattern in bytes
     * @param caseSensitive 1 for case-sensitive, 0 for case-insensitive
     * @return pointer to compiled pattern, or null on error (MUST be freed)
     */
    Pointer re2_compile(String pattern, int patternLen, int caseSensitive);

    /**
     * Frees a compiled pattern.
     * Safe to call with null pointer (no-op).
     *
     * @param pattern pointer from re2_compile
     */
    void re2_free_pattern(Pointer pattern);

    /**
     * Tests if text fully matches the pattern.
     *
     * @param pattern compiled pattern pointer
     * @param text text to match (UTF-8)
     * @param textLen length of text in bytes
     * @return 1 if matches, 0 if no match, -1 on error
     */
    int re2_full_match(Pointer pattern, String text, int textLen);

    /**
     * Tests if pattern matches anywhere in text.
     *
     * @param pattern compiled pattern pointer
     * @param text text to search (UTF-8)
     * @param textLen length of text in bytes
     * @return 1 if matches, 0 if no match, -1 on error
     */
    int re2_partial_match(Pointer pattern, String text, int textLen);

    /**
     * Gets the last error message.
     *
     * @return error message, or null if no error
     *         String is managed by native library, do not free
     */
    String re2_get_error();

    /**
     * Gets the pattern string from a compiled pattern.
     *
     * @param pattern compiled pattern pointer
     * @return pattern string, or null if invalid
     *         String is managed by native library, do not free
     */
    String re2_get_pattern(Pointer pattern);

    /**
     * Gets the number of capturing groups.
     *
     * @param pattern compiled pattern pointer
     * @return number of capturing groups, or -1 on error
     */
    int re2_num_capturing_groups(Pointer pattern);

    /**
     * Checks if pattern is valid.
     *
     * @param pattern compiled pattern pointer
     * @return 1 if valid, 0 if invalid/null
     */
    int re2_pattern_ok(Pointer pattern);

    /**
     * Gets the native memory size of a compiled pattern.
     *
     * Returns the size of the compiled DFA/NFA program in bytes.
     * This represents the off-heap memory consumed by this pattern.
     *
     * @param pattern compiled pattern pointer
     * @return size in bytes, or 0 if pattern is null
     */
    long re2_pattern_memory(Pointer pattern);
}
