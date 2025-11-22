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

/**
 * JNI interface to the native RE2 library.
 *
 * Maps directly to the C functions in re2_jni.cpp.
 * All methods are native calls executing off-heap.
 *
 * This class uses JNI for maximum performance, avoiding the overhead
 * of JNA marshalling on every call.
 *
 * CRITICAL SAFETY:
 * - All long handles MUST be freed via freePattern()
 * - Never call methods with 0 handles (will return error/false)
 * - All strings are UTF-8 encoded
 *
 * @since 1.0.0
 */
public final class RE2NativeJNI {

    private RE2NativeJNI() {
        // Utility class - prevent instantiation
    }

    /**
     * Compiles a regular expression pattern.
     *
     * @param pattern regex pattern string (UTF-8)
     * @param caseSensitive true for case-sensitive, false for case-insensitive
     * @return native handle to compiled pattern, or 0 on error (MUST be freed)
     */
    public static native long compile(String pattern, boolean caseSensitive);

    /**
     * Frees a compiled pattern.
     * Safe to call with 0 handle (no-op).
     *
     * @param handle native handle from compile()
     */
    public static native void freePattern(long handle);

    /**
     * Tests if text fully matches the pattern.
     *
     * @param handle compiled pattern handle
     * @param text text to match (UTF-8)
     * @return true if matches, false if no match or error
     */
    public static native boolean fullMatch(long handle, String text);

    /**
     * Tests if pattern matches anywhere in text.
     *
     * @param handle compiled pattern handle
     * @param text text to search (UTF-8)
     * @return true if matches, false if no match or error
     */
    public static native boolean partialMatch(long handle, String text);

    /**
     * Gets the last error message.
     *
     * @return error message, or null if no error
     */
    public static native String getError();

    /**
     * Gets the pattern string from a compiled pattern.
     *
     * @param handle compiled pattern handle
     * @return pattern string, or null if invalid
     */
    public static native String getPattern(long handle);

    /**
     * Gets the number of capturing groups.
     *
     * @param handle compiled pattern handle
     * @return number of capturing groups, or -1 on error
     */
    public static native int numCapturingGroups(long handle);

    /**
     * Checks if pattern is valid.
     *
     * @param handle compiled pattern handle
     * @return true if valid, false if invalid/null
     */
    public static native boolean patternOk(long handle);

    /**
     * Gets the native memory size of a compiled pattern.
     *
     * Returns the size of the compiled DFA/NFA program in bytes.
     * This represents the off-heap memory consumed by this pattern.
     *
     * @param handle compiled pattern handle
     * @return size in bytes, or 0 if handle is 0
     */
    public static native long patternMemory(long handle);

    // ========== Bulk Matching Operations ==========

    /**
     * Performs full match on multiple strings in single JNI call.
     * Minimizes JNI overhead for high-throughput scenarios.
     *
     * @param handle compiled pattern handle
     * @param texts array of strings to match
     * @return boolean array (parallel to texts) indicating matches, or null on error
     */
    public static native boolean[] fullMatchBulk(long handle, String[] texts);

    /**
     * Performs partial match on multiple strings in single JNI call.
     * Minimizes JNI overhead for high-throughput scenarios.
     *
     * @param handle compiled pattern handle
     * @param texts array of strings to match
     * @return boolean array (parallel to texts) indicating matches, or null on error
     */
    public static native boolean[] partialMatchBulk(long handle, String[] texts);

    // ========== Capture Group Operations ==========

    /**
     * Extracts capture groups from a single match.
     * Returns array where [0] = full match, [1+] = capturing groups.
     *
     * @param handle compiled pattern handle
     * @param text text to match
     * @return string array of groups, or null if no match
     */
    public static native String[] extractGroups(long handle, String text);

    /**
     * Extracts capture groups from multiple strings in single JNI call.
     *
     * @param handle compiled pattern handle
     * @param texts array of strings to match
     * @return array of string arrays (groups per input), or null on error
     */
    public static native String[][] extractGroupsBulk(long handle, String[] texts);

    /**
     * Finds all non-overlapping matches in text with capture groups.
     * Returns array of match results, each containing groups.
     *
     * @param handle compiled pattern handle
     * @param text text to search
     * @return array of match data (flattened: [match1_groups..., match2_groups...]), or null on error
     */
    public static native String[][] findAllMatches(long handle, String text);

    /**
     * Gets map of named capturing groups to their indices.
     * Returns flattened array: [name1, index1, name2, index2, ...]
     *
     * @param handle compiled pattern handle
     * @return flattened name-index pairs, or null if no named groups
     */
    public static native String[] getNamedGroups(long handle);

    // ========== Replace Operations ==========

    /**
     * Replaces first match with replacement string.
     * Supports backreferences ($1, $2, etc.) via RE2::Rewrite.
     *
     * @param handle compiled pattern handle
     * @param text input text
     * @param replacement replacement string (supports $1, $2 backreferences)
     * @return text with first match replaced, or original text if no match
     */
    public static native String replaceFirst(long handle, String text, String replacement);

    /**
     * Replaces all non-overlapping matches with replacement string.
     * Supports backreferences ($1, $2, etc.) via RE2::Rewrite.
     *
     * @param handle compiled pattern handle
     * @param text input text
     * @param replacement replacement string (supports $1, $2 backreferences)
     * @return text with all matches replaced, or original text if no matches
     */
    public static native String replaceAll(long handle, String text, String replacement);

    /**
     * Replaces all matches in multiple strings in single JNI call.
     *
     * @param handle compiled pattern handle
     * @param texts array of input texts
     * @param replacement replacement string (supports $1, $2 backreferences)
     * @return array of replaced strings (parallel to texts), or null on error
     */
    public static native String[] replaceAllBulk(long handle, String[] texts, String replacement);

    // ========== Utility Operations ==========

    /**
     * Escapes special regex characters for literal matching.
     * Static method - no pattern handle required.
     *
     * @param text text to escape
     * @return escaped text safe for use in regex patterns
     */
    public static native String quoteMeta(String text);

    /**
     * Gets pattern complexity histogram (DFA branching factor).
     * Returns flattened array: [fanout1, count1, fanout2, count2, ...]
     *
     * @param handle compiled pattern handle
     * @return flattened fanout-count pairs, or null on error
     */
    public static native int[] programFanout(long handle);
}
