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
}
