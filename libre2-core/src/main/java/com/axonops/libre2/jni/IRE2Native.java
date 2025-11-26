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
 * Adapter interface for RE2 JNI operations.
 * Enables mocking for unit tests while maintaining production performance.
 *
 * <p>Production implementation (DirectJniAdapter) delegates directly to RE2NativeJNI.
 * Test implementations can mock native calls to verify correct parameters and behavior.</p>
 *
 * <p><b>Internal API:</b> Not part of public API contract. Used internally by Pattern/Matcher/RE2.
 * Public visibility required for cross-package access from api package.</p>
 */
public interface IRE2Native {

    // Pattern lifecycle
    long compile(String pattern, boolean caseSensitive);
    void freePattern(long handle);
    boolean patternOk(long handle);
    String getError();
    String getPattern(long handle);
    int numCapturingGroups(long handle);
    long patternMemory(long handle);

    // Matching operations
    boolean fullMatch(long handle, String text);
    boolean partialMatch(long handle, String text);
    boolean fullMatchDirect(long handle, long address, int length);
    boolean partialMatchDirect(long handle, long address, int length);

    // Bulk operations
    boolean[] fullMatchBulk(long handle, String[] texts);
    boolean[] partialMatchBulk(long handle, String[] texts);
    boolean[] fullMatchDirectBulk(long handle, long[] addresses, int[] lengths);
    boolean[] partialMatchDirectBulk(long handle, long[] addresses, int[] lengths);

    // Capture groups
    String[] extractGroups(long handle, String text);
    String[][] extractGroupsBulk(long handle, String[] texts);
    String[] extractGroupsDirect(long handle, long address, int length);
    String[][] findAllMatches(long handle, String text);
    String[][] findAllMatchesDirect(long handle, long address, int length);
    String[] getNamedGroups(long handle);

    // Replace operations
    String replaceFirst(long handle, String text, String replacement);
    String replaceAll(long handle, String text, String replacement);
    String[] replaceAllBulk(long handle, String[] texts, String replacement);
    String replaceFirstDirect(long handle, long address, int length, String replacement);
    String replaceAllDirect(long handle, long address, int length, String replacement);
    String[] replaceAllDirectBulk(long handle, long[] addresses, int[] lengths, String replacement);

    // Utility methods
    String quoteMeta(String text);
    int[] programFanout(long handle);
}
