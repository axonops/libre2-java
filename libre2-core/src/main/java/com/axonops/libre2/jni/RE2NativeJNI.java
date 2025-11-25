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
 * <p>Maps directly to the C functions in re2_jni.cpp.
 * All methods are native calls executing off-heap.</p>
 *
 * <p>This class uses JNI for maximum performance, avoiding the overhead
 * of JNA marshalling on every call.</p>
 *
 * <h2>Zero-Copy Direct Memory API</h2>
 * <p>This class provides two categories of methods:</p>
 * <ul>
 *   <li><strong>String-based methods</strong> - Accept Java Strings, involve UTF-8 copy</li>
 *   <li><strong>Direct methods (*Direct suffix)</strong> - Accept memory addresses for zero-copy operation</li>
 * </ul>
 *
 * <p>The Direct methods are designed for use with Chronicle Bytes or other off-heap memory
 * systems that can provide stable native memory addresses via {@code addressForRead()}.</p>
 *
 * <h2>CRITICAL SAFETY</h2>
 * <ul>
 *   <li>All long handles MUST be freed via {@link #freePattern(long)}</li>
 *   <li>Never call methods with 0 handles (will return error/false)</li>
 *   <li>All strings are UTF-8 encoded</li>
 *   <li>For Direct methods: The memory at the provided address MUST remain valid
 *       for the duration of the call. Do NOT release the backing memory (e.g.,
 *       Chronicle Bytes) until the method returns.</li>
 * </ul>
 *
 * @since 1.0.0
 * @see com.axonops.libre2.jni.RE2DirectMemory
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

    /**
     * Replaces first match using zero-copy memory access (off-heap memory).
     * Accesses memory directly via native address without UTF-8 conversion.
     *
     * @param handle compiled pattern handle
     * @param textAddress native memory address (from DirectByteBuffer or native allocator)
     * @param textLength number of bytes to process
     * @param replacement replacement string (supports $1, $2 backreferences)
     * @return text with first match replaced
     */
    public static native String replaceFirstDirect(long handle, long textAddress, int textLength, String replacement);

    /**
     * Replaces all matches using zero-copy memory access (off-heap memory).
     * Accesses memory directly via native address without UTF-8 conversion.
     *
     * @param handle compiled pattern handle
     * @param textAddress native memory address (from DirectByteBuffer or native allocator)
     * @param textLength number of bytes to process
     * @param replacement replacement string (supports $1, $2 backreferences)
     * @return text with all matches replaced
     */
    public static native String replaceAllDirect(long handle, long textAddress, int textLength, String replacement);

    /**
     * Replaces all matches in multiple off-heap buffers (bulk zero-copy operation).
     * Processes all buffers in a single JNI call for better performance.
     *
     * @param handle compiled pattern handle
     * @param textAddresses native memory addresses (from DirectByteBuffer or native allocator)
     * @param textLengths number of bytes for each address
     * @param replacement replacement string (supports $1, $2 backreferences)
     * @return array of strings with all matches replaced (parallel to inputs)
     */
    public static native String[] replaceAllDirectBulk(long handle, long[] textAddresses, int[] textLengths, String replacement);

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
     * Returns histogram array where index is fanout value and element is count.
     *
     * @param handle compiled pattern handle
     * @return histogram array, or null on error
     */
    public static native int[] programFanout(long handle);

    // ========== Zero-Copy Direct Memory Operations ==========
    //
    // These methods accept raw memory addresses instead of Java Strings,
    // enabling true zero-copy regex matching with Chronicle Bytes or
    // other off-heap memory systems.
    //
    // The memory at the provided address is passed directly to RE2 via
    // StringPiece, eliminating all copy overhead.
    //
    // CRITICAL: The caller MUST ensure the memory remains valid for
    // the duration of the call. Do NOT release Chronicle Bytes or other
    // backing memory until the method returns.

    /**
     * Tests if text fully matches the pattern using direct memory access (zero-copy).
     *
     * <p>This method accepts a native memory address and length, passing them directly
     * to RE2 via StringPiece without any intermediate copying. This is ideal for use
     * with Chronicle Bytes where data is already in off-heap memory.</p>
     *
     * <p><strong>Memory Safety:</strong> The memory at {@code textAddress} must remain
     * valid and unchanged for the duration of this call. The caller is responsible for
     * ensuring the backing memory (e.g., Chronicle Bytes object) is not released until
     * this method returns.</p>
     *
     * <p><strong>Usage with Chronicle Bytes:</strong></p>
     * <pre>{@code
     * try (Bytes<?> bytes = Bytes.from("Hello World")) {
     *     long address = bytes.addressForRead(0);
     *     int length = (int) bytes.readRemaining();
     *     boolean matches = RE2NativeJNI.fullMatchDirect(patternHandle, address, length);
     * }
     * }</pre>
     *
     * @param handle compiled pattern handle (from {@link #compile(String, boolean)})
     * @param textAddress native memory address of UTF-8 encoded text
     *                    (e.g., from Chronicle Bytes {@code addressForRead()})
     * @param textLength number of bytes to read from the address
     * @return true if the entire text matches the pattern, false if no match or error
     * @throws IllegalArgumentException if handle is 0 or textAddress is 0
     * @since 1.1.0
     */
    public static native boolean fullMatchDirect(long handle, long textAddress, int textLength);

    /**
     * Tests if pattern matches anywhere in text using direct memory access (zero-copy).
     *
     * <p>This method accepts a native memory address and length, passing them directly
     * to RE2 via StringPiece without any intermediate copying. This is ideal for use
     * with Chronicle Bytes where data is already in off-heap memory.</p>
     *
     * <p><strong>Memory Safety:</strong> The memory at {@code textAddress} must remain
     * valid and unchanged for the duration of this call. The caller is responsible for
     * ensuring the backing memory (e.g., Chronicle Bytes object) is not released until
     * this method returns.</p>
     *
     * <p><strong>Usage with Chronicle Bytes:</strong></p>
     * <pre>{@code
     * try (Bytes<?> bytes = Bytes.from("Hello World")) {
     *     long address = bytes.addressForRead(0);
     *     int length = (int) bytes.readRemaining();
     *     boolean matches = RE2NativeJNI.partialMatchDirect(patternHandle, address, length);
     * }
     * }</pre>
     *
     * @param handle compiled pattern handle (from {@link #compile(String, boolean)})
     * @param textAddress native memory address of UTF-8 encoded text
     *                    (e.g., from Chronicle Bytes {@code addressForRead()})
     * @param textLength number of bytes to read from the address
     * @return true if the pattern matches anywhere in text, false if no match or error
     * @throws IllegalArgumentException if handle is 0 or textAddress is 0
     * @since 1.1.0
     */
    public static native boolean partialMatchDirect(long handle, long textAddress, int textLength);

    /**
     * Performs full match on multiple memory regions in a single JNI call (zero-copy bulk).
     *
     * <p>This method accepts arrays of memory addresses and lengths, enabling efficient
     * bulk matching without any copying. Each address/length pair is matched independently
     * against the pattern.</p>
     *
     * <p><strong>Memory Safety:</strong> All memory regions specified by the address/length
     * pairs must remain valid for the duration of this call. This is particularly important
     * for Chronicle Bytes - ensure all Bytes objects remain alive until this method returns.</p>
     *
     * <p><strong>Performance:</strong> This method minimizes JNI crossing overhead by
     * processing all inputs in a single native call. Combined with zero-copy memory access,
     * this provides maximum throughput for batch processing scenarios.</p>
     *
     * @param handle compiled pattern handle (from {@link #compile(String, boolean)})
     * @param textAddresses array of native memory addresses (e.g., from Chronicle Bytes)
     * @param textLengths array of byte lengths (must be same length as textAddresses)
     * @return boolean array (parallel to inputs) indicating matches, or null on error
     * @throws IllegalArgumentException if arrays are null or have different lengths
     * @since 1.1.0
     */
    public static native boolean[] fullMatchDirectBulk(long handle, long[] textAddresses, int[] textLengths);

    /**
     * Performs partial match on multiple memory regions in a single JNI call (zero-copy bulk).
     *
     * <p>This method accepts arrays of memory addresses and lengths, enabling efficient
     * bulk matching without any copying. Each address/length pair is matched independently
     * against the pattern.</p>
     *
     * <p><strong>Memory Safety:</strong> All memory regions specified by the address/length
     * pairs must remain valid for the duration of this call. This is particularly important
     * for Chronicle Bytes - ensure all Bytes objects remain alive until this method returns.</p>
     *
     * <p><strong>Performance:</strong> This method minimizes JNI crossing overhead by
     * processing all inputs in a single native call. Combined with zero-copy memory access,
     * this provides maximum throughput for batch processing scenarios.</p>
     *
     * @param handle compiled pattern handle (from {@link #compile(String, boolean)})
     * @param textAddresses array of native memory addresses (e.g., from Chronicle Bytes)
     * @param textLengths array of byte lengths (must be same length as textAddresses)
     * @return boolean array (parallel to inputs) indicating matches, or null on error
     * @throws IllegalArgumentException if arrays are null or have different lengths
     * @since 1.1.0
     */
    public static native boolean[] partialMatchDirectBulk(long handle, long[] textAddresses, int[] textLengths);

    /**
     * Extracts capture groups from text using direct memory access (zero-copy).
     *
     * <p>This method reads text directly from the provided memory address, extracts
     * all capture groups, and returns them as a String array. The input is zero-copy,
     * but the output necessarily creates new Java Strings for the captured groups.</p>
     *
     * <p><strong>Memory Safety:</strong> The memory at {@code textAddress} must remain
     * valid for the duration of this call.</p>
     *
     * @param handle compiled pattern handle (from {@link #compile(String, boolean)})
     * @param textAddress native memory address of UTF-8 encoded text
     * @param textLength number of bytes to read from the address
     * @return String array where [0] = full match, [1+] = capturing groups, or null if no match
     * @since 1.1.0
     */
    public static native String[] extractGroupsDirect(long handle, long textAddress, int textLength);

    /**
     * Finds all non-overlapping matches in text using direct memory access (zero-copy).
     *
     * <p>This method reads text directly from the provided memory address and finds
     * all non-overlapping matches. The input is zero-copy, but the output necessarily
     * creates new Java Strings for the matches.</p>
     *
     * <p><strong>Memory Safety:</strong> The memory at {@code textAddress} must remain
     * valid for the duration of this call.</p>
     *
     * @param handle compiled pattern handle (from {@link #compile(String, boolean)})
     * @param textAddress native memory address of UTF-8 encoded text
     * @param textLength number of bytes to read from the address
     * @return array of match results with capture groups, or null if no matches
     * @since 1.1.0
     */
    public static native String[][] findAllMatchesDirect(long handle, long textAddress, int textLength);
}

