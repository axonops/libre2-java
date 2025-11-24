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

package com.axonops.libre2.util;

/**
 * Utility for hashing pattern strings for logging purposes.
 *
 * <p>Pattern hashing provides privacy and readability in logs:
 * <ul>
 *   <li>Privacy: Don't log potentially sensitive regex patterns</li>
 *   <li>Readability: Logs aren't cluttered with long pattern strings</li>
 *   <li>Debuggability: Same pattern always gets same hash, easy to grep/trace</li>
 * </ul>
 *
 * <p>Example: Pattern ".*ERROR.*DATABASE.*" → hash "7a3f2b1c"
 *
 * @since 0.9.1
 */
public final class PatternHasher {

    private PatternHasher() {
        // Utility class
    }

    /**
     * Creates a compact hex hash of a pattern string for logging.
     *
     * <p>Uses {@link String#hashCode()} for consistency and simplicity.
     * The hash is deterministic - same pattern always produces same hash.
     *
     * @param pattern the regex pattern string
     * @return 8-character hex string (e.g., "7a3f2b1c")
     */
    public static String hash(String pattern) {
        if (pattern == null) {
            return "null";
        }
        return Integer.toHexString(pattern.hashCode());
    }

    /**
     * Creates a hash with additional context for case sensitivity.
     *
     * @param pattern the regex pattern string
     * @param caseSensitive whether the pattern is case-sensitive
     * @return hash with case sensitivity indicator (e.g., "7a3f2b1c[CS]" or "7a3f2b1c[CI]")
     */
    public static String hashWithCase(String pattern, boolean caseSensitive) {
        return hash(pattern) + (caseSensitive ? "[CS]" : "[CI]");
    }
}
