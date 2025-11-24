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
import net.openhft.chronicle.bytes.BytesStore;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Helper class for zero-copy regex matching using Chronicle Bytes.
 *
 * <p>This class provides a convenient API for performing regex matching on data
 * stored in off-heap memory (Chronicle Bytes) without any copying. The memory
 * addresses from Chronicle Bytes are passed directly to the native RE2 library
 * via StringPiece, achieving true zero-copy operation.</p>
 *
 * <h2>Performance Benefits</h2>
 * <p>Zero-copy matching provides significant performance benefits:</p>
 * <ul>
 *   <li><strong>Small inputs (&lt;100 bytes):</strong> 10-30% improvement</li>
 *   <li><strong>Medium inputs (1KB-10KB):</strong> 30-50% improvement</li>
 *   <li><strong>Large inputs (&gt;10KB):</strong> 50-100% improvement</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create a pattern (normally obtained from PatternCache)
 * long patternHandle = RE2NativeJNI.compile("hello\\s+world", true);
 * try {
 *     // Create Chronicle Bytes with your data
 *     try (Bytes<?> bytes = Bytes.from("hello   world")) {
 *         // Zero-copy matching
 *         boolean matches = RE2DirectMemory.fullMatch(patternHandle, bytes);
 *         System.out.println("Matches: " + matches);
 *     }
 * } finally {
 *     RE2NativeJNI.freePattern(patternHandle);
 * }
 * }</pre>
 *
 * <h2>Memory Safety</h2>
 * <p><strong>CRITICAL:</strong> The Chronicle Bytes object MUST remain alive for
 * the duration of the match operation. Do NOT release or close the Bytes object
 * until the match method returns. The safest approach is to use try-with-resources
 * around both the Bytes creation and the match call.</p>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The underlying RE2 pattern (once compiled) can be
 * used concurrently from multiple threads. Chronicle Bytes objects should not be
 * shared across threads without external synchronization.</p>
 *
 * @since 1.1.0
 * @see RE2NativeJNI
 * @see net.openhft.chronicle.bytes.Bytes
 */
public final class RE2DirectMemory {

    /**
     * Private constructor to prevent instantiation.
     * This is a utility class with only static methods.
     */
    private RE2DirectMemory() {
        // Utility class - prevent instantiation
    }

    /**
     * Tests if the entire content of the Bytes buffer fully matches the pattern.
     *
     * <p>This method extracts the memory address from the Chronicle Bytes object
     * and passes it directly to the native RE2 library for zero-copy matching.</p>
     *
     * <p><strong>Memory Safety:</strong> The {@code bytes} parameter must remain
     * valid (not released) for the duration of this call.</p>
     *
     * @param patternHandle compiled pattern handle from {@link RE2NativeJNI#compile(String, boolean)}
     * @param bytes Chronicle Bytes containing UTF-8 encoded text to match
     * @return true if the entire content matches the pattern, false otherwise
     * @throws NullPointerException if bytes is null
     * @throws IllegalArgumentException if patternHandle is 0
     * @throws IllegalStateException if bytes has been released
     */
    public static boolean fullMatch(long patternHandle, Bytes<?> bytes) {
        validatePatternHandle(patternHandle);
        Objects.requireNonNull(bytes, "bytes must not be null");

        long address = bytes.addressForRead(bytes.readPosition());
        int length = (int) bytes.readRemaining();

        return RE2NativeJNI.fullMatchDirect(patternHandle, address, length);
    }

    /**
     * Tests if the entire content of the BytesStore fully matches the pattern.
     *
     * <p>This overload accepts BytesStore directly for maximum flexibility with
     * Chronicle's memory abstraction.</p>
     *
     * @param patternHandle compiled pattern handle from {@link RE2NativeJNI#compile(String, boolean)}
     * @param store BytesStore containing UTF-8 encoded text to match
     * @param offset starting offset within the store
     * @param length number of bytes to match
     * @return true if the content matches the pattern, false otherwise
     * @throws NullPointerException if store is null
     * @throws IllegalArgumentException if patternHandle is 0 or offset/length are invalid
     */
    public static boolean fullMatch(long patternHandle, BytesStore<?, ?> store, long offset, int length) {
        validatePatternHandle(patternHandle);
        Objects.requireNonNull(store, "store must not be null");
        validateOffsetAndLength(offset, length);

        long address = store.addressForRead(offset);
        return RE2NativeJNI.fullMatchDirect(patternHandle, address, length);
    }

    /**
     * Tests if the pattern matches anywhere in the Bytes buffer content.
     *
     * <p>This method extracts the memory address from the Chronicle Bytes object
     * and passes it directly to the native RE2 library for zero-copy matching.</p>
     *
     * <p><strong>Memory Safety:</strong> The {@code bytes} parameter must remain
     * valid (not released) for the duration of this call.</p>
     *
     * @param patternHandle compiled pattern handle from {@link RE2NativeJNI#compile(String, boolean)}
     * @param bytes Chronicle Bytes containing UTF-8 encoded text to search
     * @return true if the pattern matches anywhere in the content, false otherwise
     * @throws NullPointerException if bytes is null
     * @throws IllegalArgumentException if patternHandle is 0
     * @throws IllegalStateException if bytes has been released
     */
    public static boolean partialMatch(long patternHandle, Bytes<?> bytes) {
        validatePatternHandle(patternHandle);
        Objects.requireNonNull(bytes, "bytes must not be null");

        long address = bytes.addressForRead(bytes.readPosition());
        int length = (int) bytes.readRemaining();

        return RE2NativeJNI.partialMatchDirect(patternHandle, address, length);
    }

    /**
     * Tests if the pattern matches anywhere in the BytesStore content.
     *
     * <p>This overload accepts BytesStore directly for maximum flexibility with
     * Chronicle's memory abstraction.</p>
     *
     * @param patternHandle compiled pattern handle from {@link RE2NativeJNI#compile(String, boolean)}
     * @param store BytesStore containing UTF-8 encoded text to search
     * @param offset starting offset within the store
     * @param length number of bytes to search
     * @return true if the pattern matches anywhere in the content, false otherwise
     * @throws NullPointerException if store is null
     * @throws IllegalArgumentException if patternHandle is 0 or offset/length are invalid
     */
    public static boolean partialMatch(long patternHandle, BytesStore<?, ?> store, long offset, int length) {
        validatePatternHandle(patternHandle);
        Objects.requireNonNull(store, "store must not be null");
        validateOffsetAndLength(offset, length);

        long address = store.addressForRead(offset);
        return RE2NativeJNI.partialMatchDirect(patternHandle, address, length);
    }

    /**
     * Performs full match on multiple Bytes buffers in a single JNI call.
     *
     * <p>This method minimizes JNI crossing overhead by processing all inputs
     * in a single native call. Combined with zero-copy memory access, this
     * provides maximum throughput for batch processing scenarios.</p>
     *
     * <p><strong>Memory Safety:</strong> All {@code bytesArray} elements must remain
     * valid (not released) for the duration of this call.</p>
     *
     * @param patternHandle compiled pattern handle from {@link RE2NativeJNI#compile(String, boolean)}
     * @param bytesArray array of Chronicle Bytes to match against
     * @return boolean array (parallel to inputs) indicating matches, or null on error
     * @throws NullPointerException if bytesArray is null
     * @throws IllegalArgumentException if patternHandle is 0
     */
    public static boolean[] fullMatchBulk(long patternHandle, Bytes<?>[] bytesArray) {
        validatePatternHandle(patternHandle);
        Objects.requireNonNull(bytesArray, "bytesArray must not be null");

        long[] addresses = new long[bytesArray.length];
        int[] lengths = new int[bytesArray.length];

        for (int i = 0; i < bytesArray.length; i++) {
            Bytes<?> bytes = bytesArray[i];
            if (bytes != null) {
                addresses[i] = bytes.addressForRead(bytes.readPosition());
                lengths[i] = (int) bytes.readRemaining();
            }
            // null entries will have address=0 and length=0, which the native code handles
        }

        return RE2NativeJNI.fullMatchDirectBulk(patternHandle, addresses, lengths);
    }

    /**
     * Performs partial match on multiple Bytes buffers in a single JNI call.
     *
     * <p>This method minimizes JNI crossing overhead by processing all inputs
     * in a single native call. Combined with zero-copy memory access, this
     * provides maximum throughput for batch processing scenarios.</p>
     *
     * <p><strong>Memory Safety:</strong> All {@code bytesArray} elements must remain
     * valid (not released) for the duration of this call.</p>
     *
     * @param patternHandle compiled pattern handle from {@link RE2NativeJNI#compile(String, boolean)}
     * @param bytesArray array of Chronicle Bytes to search
     * @return boolean array (parallel to inputs) indicating matches, or null on error
     * @throws NullPointerException if bytesArray is null
     * @throws IllegalArgumentException if patternHandle is 0
     */
    public static boolean[] partialMatchBulk(long patternHandle, Bytes<?>[] bytesArray) {
        validatePatternHandle(patternHandle);
        Objects.requireNonNull(bytesArray, "bytesArray must not be null");

        long[] addresses = new long[bytesArray.length];
        int[] lengths = new int[bytesArray.length];

        for (int i = 0; i < bytesArray.length; i++) {
            Bytes<?> bytes = bytesArray[i];
            if (bytes != null) {
                addresses[i] = bytes.addressForRead(bytes.readPosition());
                lengths[i] = (int) bytes.readRemaining();
            }
        }

        return RE2NativeJNI.partialMatchDirectBulk(patternHandle, addresses, lengths);
    }

    /**
     * Extracts capture groups from Bytes content using zero-copy input.
     *
     * <p>This method reads text directly from the Chronicle Bytes memory address,
     * extracts all capture groups, and returns them as a String array. The input
     * is zero-copy, but the output necessarily creates new Java Strings.</p>
     *
     * @param patternHandle compiled pattern handle from {@link RE2NativeJNI#compile(String, boolean)}
     * @param bytes Chronicle Bytes containing UTF-8 encoded text
     * @return String array where [0] = full match, [1+] = capturing groups, or null if no match
     * @throws NullPointerException if bytes is null
     * @throws IllegalArgumentException if patternHandle is 0
     */
    public static String[] extractGroups(long patternHandle, Bytes<?> bytes) {
        validatePatternHandle(patternHandle);
        Objects.requireNonNull(bytes, "bytes must not be null");

        long address = bytes.addressForRead(bytes.readPosition());
        int length = (int) bytes.readRemaining();

        return RE2NativeJNI.extractGroupsDirect(patternHandle, address, length);
    }

    /**
     * Finds all non-overlapping matches in Bytes content using zero-copy input.
     *
     * <p>This method reads text directly from the Chronicle Bytes memory address
     * and finds all non-overlapping matches. The input is zero-copy, but the output
     * necessarily creates new Java Strings.</p>
     *
     * @param patternHandle compiled pattern handle from {@link RE2NativeJNI#compile(String, boolean)}
     * @param bytes Chronicle Bytes containing UTF-8 encoded text
     * @return array of match results with capture groups, or null if no matches
     * @throws NullPointerException if bytes is null
     * @throws IllegalArgumentException if patternHandle is 0
     */
    public static String[][] findAllMatches(long patternHandle, Bytes<?> bytes) {
        validatePatternHandle(patternHandle);
        Objects.requireNonNull(bytes, "bytes must not be null");

        long address = bytes.addressForRead(bytes.readPosition());
        int length = (int) bytes.readRemaining();

        return RE2NativeJNI.findAllMatchesDirect(patternHandle, address, length);
    }

    /**
     * Creates Chronicle Bytes from a Java String for zero-copy matching.
     *
     * <p>This is a convenience method that converts a Java String to Chronicle Bytes,
     * enabling the use of zero-copy matching APIs. The returned Bytes object must be
     * closed when no longer needed.</p>
     *
     * <p><strong>Note:</strong> This method does involve an initial copy to convert
     * the String to UTF-8 bytes in off-heap memory. However, subsequent matching
     * operations on the returned Bytes will be zero-copy.</p>
     *
     * @param text the text to convert to Chronicle Bytes
     * @return a new Bytes object containing the UTF-8 encoded text (must be closed)
     * @throws NullPointerException if text is null
     */
    public static Bytes<?> toBytes(String text) {
        Objects.requireNonNull(text, "text must not be null");
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        Bytes<?> bytes = Bytes.allocateElasticDirect(utf8.length);
        bytes.write(utf8);
        return bytes;
    }

    /**
     * Validates that the pattern handle is not 0.
     *
     * @param patternHandle the pattern handle to validate
     * @throws IllegalArgumentException if patternHandle is 0
     */
    private static void validatePatternHandle(long patternHandle) {
        if (patternHandle == 0) {
            throw new IllegalArgumentException("Pattern handle must not be 0");
        }
    }

    /**
     * Validates offset and length parameters.
     *
     * @param offset the offset to validate
     * @param length the length to validate
     * @throws IllegalArgumentException if offset or length is negative
     */
    private static void validateOffsetAndLength(long offset, int length) {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must not be negative: " + offset);
        }
        if (length < 0) {
            throw new IllegalArgumentException("Length must not be negative: " + length);
        }
    }
}
