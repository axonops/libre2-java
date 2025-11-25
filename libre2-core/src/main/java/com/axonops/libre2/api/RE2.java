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

import com.axonops.libre2.jni.RE2NativeJNI;

/**
 * Main entry point for RE2 regex operations.
 *
 * Thread-safe: All methods can be called concurrently from multiple threads.
 *
 * @since 1.0.0
 */
public final class RE2 {

    private RE2() {
        // Utility class
    }

    public static Pattern compile(String pattern) {
        return Pattern.compile(pattern);
    }

    public static Pattern compile(String pattern, boolean caseSensitive) {
        return Pattern.compile(pattern, caseSensitive);
    }

    // ========== String Matching Operations ==========

    /**
     * Tests if the entire input matches the pattern (full match).
     *
     * @param pattern regex pattern
     * @param input input string
     * @return true if entire input matches, false otherwise
     */
    public static boolean matches(String pattern, String input) {
        try (Pattern p = compile(pattern)) {
            return p.matches(input);
        }
    }

    /**
     * Full match with capture groups.
     *
     * @param pattern regex pattern
     * @param input input string
     * @return MatchResult with capture groups (use try-with-resources)
     */
    public static MatchResult match(String pattern, String input) {
        Pattern p = compile(pattern);
        return p.match(input);
    }

    /**
     * Finds first match with capture groups.
     *
     * @param pattern regex pattern
     * @param input input string
     * @return MatchResult with capture groups (use try-with-resources)
     */
    public static MatchResult findFirst(String pattern, String input) {
        Pattern p = compile(pattern);
        return p.find(input);
    }

    /**
     * Finds all matches with capture groups.
     *
     * @param pattern regex pattern
     * @param input input string
     * @return list of MatchResults (remember to close each)
     */
    public static java.util.List<MatchResult> findAll(String pattern, String input) {
        Pattern p = compile(pattern);
        return p.findAll(input);
    }

    // ========== Bulk Operations ==========

    /**
     * Tests multiple inputs against pattern (bulk full match).
     *
     * @param pattern regex pattern
     * @param inputs array of input strings
     * @return boolean array (parallel to inputs)
     */
    public static boolean[] matchAll(String pattern, String[] inputs) {
        try (Pattern p = compile(pattern)) {
            return p.matchAll(inputs);
        }
    }

    /**
     * Tests multiple inputs against pattern (bulk full match).
     *
     * @param pattern regex pattern
     * @param inputs collection of input strings
     * @return boolean array (parallel to inputs)
     */
    public static boolean[] matchAll(String pattern, java.util.Collection<String> inputs) {
        try (Pattern p = compile(pattern)) {
            return p.matchAll(inputs);
        }
    }

    /**
     * Full match multiple inputs with capture groups (bulk operation).
     *
     * @param pattern regex pattern
     * @param inputs array of input strings
     * @return array of MatchResults (parallel to inputs, remember to close each)
     */
    public static MatchResult[] matchAllWithGroups(String pattern, String[] inputs) {
        Pattern p = compile(pattern);
        return p.matchAllWithGroups(inputs);
    }

    /**
     * Full match multiple inputs with capture groups (bulk operation).
     *
     * @param pattern regex pattern
     * @param inputs collection of input strings
     * @return array of MatchResults (parallel to inputs, remember to close each)
     */
    public static MatchResult[] matchAllWithGroups(String pattern, java.util.Collection<String> inputs) {
        Pattern p = compile(pattern);
        return p.matchAllWithGroups(inputs);
    }

    /**
     * Searches for pattern in multiple inputs (bulk partial match).
     *
     * @param pattern regex pattern
     * @param inputs array of input strings
     * @return boolean array (parallel to inputs)
     */
    public static boolean[] findAll(String pattern, String[] inputs) {
        try (Pattern p = compile(pattern)) {
            return p.findAll(inputs);
        }
    }

    /**
     * Filters collection to only strings matching the pattern.
     *
     * @param pattern regex pattern
     * @param inputs collection to filter
     * @return new list containing only matching strings
     */
    public static java.util.List<String> filter(String pattern, java.util.Collection<String> inputs) {
        try (Pattern p = compile(pattern)) {
            return p.filter(inputs);
        }
    }

    /**
     * Filters collection to only strings NOT matching the pattern.
     *
     * @param pattern regex pattern
     * @param inputs collection to filter
     * @return new list containing only non-matching strings
     */
    public static java.util.List<String> filterNot(String pattern, java.util.Collection<String> inputs) {
        try (Pattern p = compile(pattern)) {
            return p.filterNot(inputs);
        }
    }

    // ========== Replace Operations ==========

    /**
     * Replaces first match of pattern in input.
     *
     * @param pattern regex pattern
     * @param input input string
     * @param replacement replacement string (supports \\1, \\2 backreferences)
     * @return input with first match replaced
     */
    public static String replaceFirst(String pattern, String input, String replacement) {
        try (Pattern p = compile(pattern)) {
            return p.replaceFirst(input, replacement);
        }
    }

    /**
     * Replaces all matches of pattern in input.
     *
     * @param pattern regex pattern
     * @param input input string
     * @param replacement replacement string (supports \\1, \\2 backreferences)
     * @return input with all matches replaced
     */
    public static String replaceAll(String pattern, String input, String replacement) {
        try (Pattern p = compile(pattern)) {
            return p.replaceAll(input, replacement);
        }
    }

    /**
     * Replaces all matches in multiple strings (bulk operation).
     *
     * @param pattern regex pattern
     * @param inputs array of input strings
     * @param replacement replacement string (supports backreferences)
     * @return array of strings with matches replaced (parallel to inputs)
     */
    public static String[] replaceAll(String pattern, String[] inputs, String replacement) {
        try (Pattern p = compile(pattern)) {
            return p.replaceAll(inputs, replacement);
        }
    }

    /**
     * Replaces all matches in a collection (bulk operation).
     *
     * @param pattern regex pattern
     * @param inputs collection of input strings
     * @param replacement replacement string (supports backreferences)
     * @return list of strings with matches replaced (same order)
     */
    public static java.util.List<String> replaceAll(String pattern, java.util.Collection<String> inputs, String replacement) {
        try (Pattern p = compile(pattern)) {
            return p.replaceAll(inputs, replacement);
        }
    }

    // ========== ByteBuffer Operations ==========

    /**
     * Tests if ByteBuffer matches pattern (full match, zero-copy if direct).
     *
     * @param pattern regex pattern
     * @param input ByteBuffer containing UTF-8 text
     * @return true if entire buffer matches
     */
    public static boolean matches(String pattern, java.nio.ByteBuffer input) {
        try (Pattern p = compile(pattern)) {
            return p.matches(input);
        }
    }

    /**
     * Full match with capture groups from ByteBuffer (zero-copy if direct).
     *
     * @param pattern regex pattern
     * @param input ByteBuffer containing UTF-8 text
     * @return MatchResult with capture groups (use try-with-resources)
     */
    public static MatchResult matchWithGroups(String pattern, java.nio.ByteBuffer input) {
        Pattern p = compile(pattern);
        return p.matchWithGroups(input);
    }

    /**
     * Finds first match with capture groups from ByteBuffer (zero-copy if direct).
     *
     * @param pattern regex pattern
     * @param input ByteBuffer containing UTF-8 text
     * @return MatchResult with capture groups (use try-with-resources)
     */
    public static MatchResult findWithGroups(String pattern, java.nio.ByteBuffer input) {
        Pattern p = compile(pattern);
        return p.findWithGroups(input);
    }

    /**
     * Finds all matches with capture groups from ByteBuffer (zero-copy if direct).
     *
     * @param pattern regex pattern
     * @param input ByteBuffer containing UTF-8 text
     * @return list of MatchResults (remember to close each)
     */
    public static java.util.List<MatchResult> findAllWithGroups(String pattern, java.nio.ByteBuffer input) {
        Pattern p = compile(pattern);
        return p.findAllWithGroups(input);
    }

    // ========== Utility Operations ==========

    /**
     * Escapes special regex characters for literal matching.
     *
     * @param text text to escape
     * @return escaped text safe for use as literal pattern
     */
    public static String quoteMeta(String text) {
        return RE2NativeJNI.quoteMeta(text);
    }
}
