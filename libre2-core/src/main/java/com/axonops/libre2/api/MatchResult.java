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

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Result of a regex match operation with capture group access.
 *
 * <p>This class provides access to captured groups from a successful regex match.
 * It is immutable and thread-safe.</p>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * Pattern pattern = Pattern.compile("([a-z]+)@([a-z]+)\\.([a-z]+)");
 * MatchResult result = pattern.match("user@example.com");
 *
 * if (result.matched()) {
 *     String full = result.group();      // "user@example.com"
 *     String user = result.group(1);     // "user"
 *     String domain = result.group(2);   // "example"
 *     String tld = result.group(3);      // "com"
 * }
 * }</pre>
 *
 * <h2>Named Groups</h2>
 * <pre>{@code
 * Pattern pattern = Pattern.compile("(?P<year>\\d{4})-(?P<month>\\d{2})-(?P<day>\\d{2})");
 * MatchResult result = pattern.match("2025-11-24");
 *
 * if (result.matched()) {
 *     String year = result.group("year");   // "2025"
 *     String month = result.group("month"); // "11"
 *     String day = result.group("day");     // "24"
 * }
 * }</pre>
 *
 * @since 1.2.0
 */
public final class MatchResult {

    private final boolean matched;
    private final String input;
    private final String[] groups;
    private final Map<String, Integer> namedGroups;

    /**
     * Creates a MatchResult for a successful match.
     *
     * @param input the original input string
     * @param groups the captured groups (group[0] is full match, group[1+] are capturing groups)
     * @param namedGroups map of named group names to their indices
     */
    MatchResult(String input, String[] groups, Map<String, Integer> namedGroups) {
        this.matched = true;
        this.input = Objects.requireNonNull(input, "input cannot be null");
        this.groups = Objects.requireNonNull(groups, "groups cannot be null");
        this.namedGroups = namedGroups != null ? Collections.unmodifiableMap(namedGroups) : Collections.emptyMap();
    }

    /**
     * Creates a MatchResult for a failed match.
     *
     * @param input the original input string
     */
    MatchResult(String input) {
        this.matched = false;
        this.input = Objects.requireNonNull(input, "input cannot be null");
        this.groups = new String[0];
        this.namedGroups = Collections.emptyMap();
    }

    /**
     * Checks if the match was successful.
     *
     * @return true if a match was found, false otherwise
     */
    public boolean matched() {
        return matched;
    }

    /**
     * Gets the full matched text (same as {@code group(0)}).
     *
     * @return the full matched text, or null if no match
     */
    public String group() {
        return group(0);
    }

    /**
     * Gets a captured group by index.
     *
     * <p>Index 0 is the full match. Index 1+ are capturing groups in order.</p>
     *
     * <p><strong>Example:</strong></p>
     * <pre>{@code
     * Pattern pattern = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
     * MatchResult result = pattern.match("2025-11-24");
     *
     * result.group(0);  // "2025-11-24" (full match)
     * result.group(1);  // "2025" (first group)
     * result.group(2);  // "11" (second group)
     * result.group(3);  // "24" (third group)
     * }</pre>
     *
     * @param index the group index (0 = full match, 1+ = capturing groups)
     * @return the captured group text, or null if group didn't participate in match
     * @throws IllegalStateException if match failed
     * @throws IndexOutOfBoundsException if index is negative or &gt;= groupCount()
     */
    public String group(int index) {
        if (!matched) {
            throw new IllegalStateException("No match found");
        }
        if (index < 0 || index >= groups.length) {
            throw new IndexOutOfBoundsException(
                "Group index " + index + " out of bounds (0 to " + (groups.length - 1) + ")");
        }
        return groups[index];
    }

    /**
     * Gets a captured group by name.
     *
     * <p>Named groups use RE2 syntax: {@code (?P<name>pattern)}</p>
     *
     * <p><strong>Example:</strong></p>
     * <pre>{@code
     * Pattern pattern = Pattern.compile("(?P<user>[a-z]+)@(?P<domain>[a-z]+\\.[a-z]+)");
     * MatchResult result = pattern.match("admin@example.com");
     *
     * result.group("user");    // "admin"
     * result.group("domain");  // "example.com"
     * }</pre>
     *
     * @param name the name of the capturing group
     * @return the captured group text, or null if group didn't participate or doesn't exist
     * @throws IllegalStateException if match failed
     * @throws NullPointerException if name is null
     */
    public String group(String name) {
        if (!matched) {
            throw new IllegalStateException("No match found");
        }
        Objects.requireNonNull(name, "Group name cannot be null");

        Integer index = namedGroups.get(name);
        if (index == null) {
            return null;  // Named group doesn't exist
        }

        return groups[index];
    }

    /**
     * Gets the number of capturing groups in the pattern.
     *
     * <p>This count does NOT include group 0 (the full match). A pattern with
     * no capturing groups returns 0, but you can still access group(0).</p>
     *
     * @return number of capturing groups (excluding group 0)
     */
    public int groupCount() {
        return matched ? groups.length - 1 : 0;
    }

    /**
     * Gets the original input string.
     *
     * @return the input string that was matched against
     */
    public String input() {
        return input;
    }

    /**
     * Gets all captured groups as an array.
     *
     * <p>Array indices: [0] = full match, [1+] = capturing groups.</p>
     *
     * @return array of captured groups, or empty array if no match
     */
    public String[] groups() {
        return groups.clone();  // Defensive copy
    }

    /**
     * Gets the map of named groups to their indices.
     *
     * @return unmodifiable map of group names to indices, or empty map if no named groups
     */
    public Map<String, Integer> namedGroups() {
        return namedGroups;
    }

    @Override
    public String toString() {
        if (!matched) {
            return "MatchResult{matched=false, input=\"" + input + "\"}";
        }
        return "MatchResult{matched=true, input=\"" + input + "\", groups=" + groups.length + "}";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof MatchResult other)) return false;

        return matched == other.matched &&
               input.equals(other.input) &&
               java.util.Arrays.equals(groups, other.groups) &&
               namedGroups.equals(other.namedGroups);
    }

    @Override
    public int hashCode() {
        return Objects.hash(matched, input, java.util.Arrays.hashCode(groups), namedGroups);
    }
}
