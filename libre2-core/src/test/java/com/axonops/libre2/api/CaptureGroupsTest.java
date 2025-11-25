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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for capture group functionality (MatchResult and Pattern capture methods).
 */
@DisplayName("Capture Groups")
class CaptureGroupsTest {

    // ========== MatchResult Basic Tests ==========

    @Test
    @DisplayName("MatchResult should indicate successful match")
    void matchResult_successfulMatch_matched() {
        Pattern pattern = Pattern.compile("(\\d+)");
        try (MatchResult result = pattern.match("123")) {
            assertThat(result.matched()).isTrue();
            assertThat(result.group()).isEqualTo("123");
            assertThat(result.group(0)).isEqualTo("123");
            assertThat(result.group(1)).isEqualTo("123");
        }
    }

    @Test
    @DisplayName("MatchResult should indicate failed match")
    void matchResult_failedMatch_notMatched() {
        Pattern pattern = Pattern.compile("(\\d+)");
        try (MatchResult result = pattern.match("abc")) {
            assertThat(result.matched()).isFalse();
            assertThat(result.groupCount()).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("MatchResult should throw on group access when not matched")
    void matchResult_noMatch_throwsOnGroupAccess() {
        Pattern pattern = Pattern.compile("(\\d+)");
        try (MatchResult result = pattern.match("abc")) {
            assertThatIllegalStateException()
                .isThrownBy(() -> result.group())
                .withMessageContaining("No match");
        }
    }

    // ========== Pattern.match() Tests ==========

    @Test
    @DisplayName("Pattern.match() should extract single group")
    void patternMatch_singleGroup_extracted() {
        Pattern pattern = Pattern.compile("(\\d+)");
        try (MatchResult result = pattern.match("123")) {
            assertThat(result.matched()).isTrue();
            assertThat(result.groupCount()).isEqualTo(1);
            assertThat(result.group(0)).isEqualTo("123");  // Full match
            assertThat(result.group(1)).isEqualTo("123");  // Captured group
        }
    }

    @Test
    @DisplayName("Pattern.match() should extract multiple groups")
    void patternMatch_multipleGroups_extracted() {
        Pattern pattern = Pattern.compile("([a-z]+)@([a-z]+)\\.([a-z]+)");
        try (MatchResult result = pattern.match("user@example.com")) {
            assertThat(result.matched()).isTrue();
            assertThat(result.groupCount()).isEqualTo(3);
            assertThat(result.group()).isEqualTo("user@example.com");
            assertThat(result.group(1)).isEqualTo("user");
            assertThat(result.group(2)).isEqualTo("example");
            assertThat(result.group(3)).isEqualTo("com");
        }
    }

    @Test
    @DisplayName("Pattern.match() should handle date extraction")
    void patternMatch_dateExtraction_works() {
        Pattern pattern = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
        try (MatchResult result = pattern.match("2025-11-24")) {
            assertThat(result.matched()).isTrue();
            assertThat(result.group()).isEqualTo("2025-11-24");
            assertThat(result.group(1)).isEqualTo("2025");
            assertThat(result.group(2)).isEqualTo("11");
            assertThat(result.group(3)).isEqualTo("24");
        }
    }

    @Test
    @DisplayName("Pattern.match() should fail on partial content")
    void patternMatch_partialContent_fails() {
        Pattern pattern = Pattern.compile("(\\d+)");
        try (MatchResult result = pattern.match("abc123def")) {
            assertThat(result.matched()).isFalse();
        }
    }

    // ========== Pattern.find() Tests ==========

    @Test
    @DisplayName("Pattern.find() should find first match in text")
    void patternFind_firstMatch_found() {
        Pattern pattern = Pattern.compile("(\\d+)");
        try (MatchResult result = pattern.find("abc123def456")) {
            assertThat(result.matched()).isTrue();
            assertThat(result.group()).isEqualTo("123");  // First match
            assertThat(result.group(1)).isEqualTo("123");
        }
    }

    @Test
    @DisplayName("Pattern.find() should extract groups from first match")
    void patternFind_firstMatchGroups_extracted() {
        Pattern pattern = Pattern.compile("([a-z]+)@([a-z]+\\.[a-z]+)");
        try (MatchResult result = pattern.find("Contact support@example.com or admin@test.org")) {
            assertThat(result.matched()).isTrue();
            assertThat(result.group()).isEqualTo("support@example.com");  // First email
            assertThat(result.group(1)).isEqualTo("support");
            assertThat(result.group(2)).isEqualTo("example.com");
        }
    }

    @Test
    @DisplayName("Pattern.find() should return failed match when not found")
    void patternFind_notFound_failedMatch() {
        Pattern pattern = Pattern.compile("(\\d+)");
        try (MatchResult result = pattern.find("no digits here")) {
            assertThat(result.matched()).isFalse();
        }
    }

    // ========== Pattern.findAll() Tests ==========

    @Test
    @DisplayName("Pattern.findAll() should find all matches")
    void patternFindAll_multipleMatches_found() {
        Pattern pattern = Pattern.compile("(\\d+)");
        List<MatchResult> matches = pattern.findAll("a1b22c333");
        try {
            assertThat(matches).hasSize(3);
            assertThat(matches.get(0).group()).isEqualTo("1");
            assertThat(matches.get(1).group()).isEqualTo("22");
            assertThat(matches.get(2).group()).isEqualTo("333");
        } finally {
            matches.forEach(MatchResult::close);
        }
    }

    @Test
    @DisplayName("Pattern.findAll() should extract groups from each match")
    void patternFindAll_multipleMatchesWithGroups_extracted() {
        Pattern pattern = Pattern.compile("(\\d{3})-(\\d{4})");
        List<MatchResult> matches = pattern.findAll("Call 555-1234 or 555-5678");
        try {
            assertThat(matches).hasSize(2);

            // First match
            assertThat(matches.get(0).group()).isEqualTo("555-1234");
            assertThat(matches.get(0).group(1)).isEqualTo("555");
            assertThat(matches.get(0).group(2)).isEqualTo("1234");

            // Second match
            assertThat(matches.get(1).group()).isEqualTo("555-5678");
            assertThat(matches.get(1).group(1)).isEqualTo("555");
            assertThat(matches.get(1).group(2)).isEqualTo("5678");
        } finally {
            matches.forEach(MatchResult::close);
        }
    }

    @Test
    @DisplayName("Pattern.findAll() should return empty list for no matches")
    void patternFindAll_noMatches_emptyList() {
        Pattern pattern = Pattern.compile("(\\d+)");
        List<MatchResult> matches = pattern.findAll("no digits");
        try {
            assertThat(matches).isEmpty();
        } finally {
            matches.forEach(MatchResult::close);
        }
    }

    // ========== Named Groups Tests ==========

    @Test
    @DisplayName("Named groups should be accessible by name")
    void namedGroups_accessByName_works() {
        Pattern pattern = Pattern.compile("(?P<year>\\d{4})-(?P<month>\\d{2})-(?P<day>\\d{2})");
        try (MatchResult result = pattern.match("2025-11-24")) {
            assertThat(result.matched()).isTrue();
            assertThat(result.group("year")).isEqualTo("2025");
            assertThat(result.group("month")).isEqualTo("11");
            assertThat(result.group("day")).isEqualTo("24");
        }
    }

    @Test
    @DisplayName("Named groups should also be accessible by index")
    void namedGroups_accessByIndex_works() {
        Pattern pattern = Pattern.compile("(?P<user>[a-z]+)@(?P<domain>[a-z]+\\.[a-z]+)");
        try (MatchResult result = pattern.match("admin@example.com")) {
            assertThat(result.matched()).isTrue();
            // Access by name
            assertThat(result.group("user")).isEqualTo("admin");
            assertThat(result.group("domain")).isEqualTo("example.com");

            // Also accessible by index
            assertThat(result.group(1)).isEqualTo("admin");
            assertThat(result.group(2)).isEqualTo("example.com");
        }
    }

    @Test
    @DisplayName("Non-existent named group should return null")
    void namedGroups_nonExistent_returnsNull() {
        Pattern pattern = Pattern.compile("(?P<found>\\d+)");
        try (MatchResult result = pattern.match("123")) {
            assertThat(result.matched()).isTrue();
            assertThat(result.group("found")).isEqualTo("123");
            assertThat(result.group("notfound")).isNull();
        }
    }

    // ========== Edge Cases ==========

    @Test
    @DisplayName("Pattern with no groups should work")
    void pattern_noGroups_works() {
        Pattern pattern = Pattern.compile("\\d+");  // No parentheses
        try (MatchResult result = pattern.match("123")) {
            assertThat(result.matched()).isTrue();
            assertThat(result.groupCount()).isEqualTo(0);
            assertThat(result.group()).isEqualTo("123");  // Group 0 still available
        }
    }

    @Test
    @DisplayName("Optional groups that don't participate should be null")
    void optionalGroups_notParticipating_null() {
        Pattern pattern = Pattern.compile("(a)?(b)");
        try (MatchResult result = pattern.match("b")) {  // 'a' is optional and doesn't match
            assertThat(result.matched()).isTrue();
            assertThat(result.groupCount()).isEqualTo(2);
            assertThat(result.group(0)).isEqualTo("b");
            assertThat(result.group(1)).isNull();  // Optional 'a' didn't participate
            assertThat(result.group(2)).isEqualTo("b");
        }
    }

    @Test
    @DisplayName("Nested groups should be extracted correctly")
    void nestedGroups_extracted() {
        Pattern pattern = Pattern.compile("((\\d+)-(\\d+))");
        try (MatchResult result = pattern.match("123-456")) {
            assertThat(result.matched()).isTrue();
            assertThat(result.groupCount()).isEqualTo(3);
            assertThat(result.group(1)).isEqualTo("123-456");  // Outer group
            assertThat(result.group(2)).isEqualTo("123");      // First inner
            assertThat(result.group(3)).isEqualTo("456");      // Second inner
        }
    }

    @Test
    @DisplayName("MatchResult.groups() should return defensive copy")
    void matchResult_groupsArray_defensiveCopy() {
        Pattern pattern = Pattern.compile("(\\d+)");
        try (MatchResult result = pattern.match("123")) {
            String[] groups1 = result.groups();
            String[] groups2 = result.groups();

            assertThat(groups1).isNotSameAs(groups2);  // Different array instances
            assertThat(groups1).containsExactly(groups2);  // Same content
        }
    }

    @Test
    @DisplayName("MatchResult should provide input string")
    void matchResult_input_available() {
        Pattern pattern = Pattern.compile("(\\d+)");
        try (MatchResult result = pattern.match("123")) {
            assertThat(result.input()).isEqualTo("123");
        }
    }

    @Test
    @DisplayName("MatchResult should throw on invalid group index")
    void matchResult_invalidIndex_throws() {
        Pattern pattern = Pattern.compile("(\\d+)");
        try (MatchResult result = pattern.match("123")) {
            assertThatIndexOutOfBoundsException()
                .isThrownBy(() -> result.group(5))
                .withMessageContaining("out of bounds");

            assertThatIndexOutOfBoundsException()
                .isThrownBy(() -> result.group(-1))
                .withMessageContaining("out of bounds");
        }
    }

    // ========== Real-World Scenarios ==========

    @Test
    @DisplayName("Extract email components")
    void realWorld_emailExtraction() {
        Pattern pattern = Pattern.compile("([a-z0-9._%+-]+)@([a-z0-9.-]+)\\.([a-z]{2,})");
        try (MatchResult result = pattern.match("john.doe@example.co.uk")) {
            assertThat(result.matched()).isTrue();
            assertThat(result.group(1)).isEqualTo("john.doe");
            assertThat(result.group(2)).isEqualTo("example.co");
            assertThat(result.group(3)).isEqualTo("uk");
        }
    }

    @Test
    @DisplayName("Parse log line with timestamp and level")
    void realWorld_logParsing() {
        Pattern pattern = Pattern.compile("\\[(\\d+)\\] (\\w+): (.+)");
        try (MatchResult result = pattern.find("[1234567890] ERROR: Something went wrong")) {
            assertThat(result.matched()).isTrue();
            assertThat(result.group(1)).isEqualTo("1234567890");  // timestamp
            assertThat(result.group(2)).isEqualTo("ERROR");       // level
            assertThat(result.group(3)).isEqualTo("Something went wrong");  // message
        }
    }

    @Test
    @DisplayName("Extract all URLs from text")
    void realWorld_extractAllUrls() {
        Pattern pattern = Pattern.compile("https?://([a-z0-9.-]+)/([a-z0-9/_-]+)");
        List<MatchResult> matches = pattern.findAll("Visit http://example.com/page1 and https://test.org/page2");
        try {
            assertThat(matches).hasSize(2);

            // First URL
            assertThat(matches.get(0).group()).isEqualTo("http://example.com/page1");
            assertThat(matches.get(0).group(1)).isEqualTo("example.com");
            assertThat(matches.get(0).group(2)).isEqualTo("page1");

            // Second URL
            assertThat(matches.get(1).group()).isEqualTo("https://test.org/page2");
            assertThat(matches.get(1).group(1)).isEqualTo("test.org");
            assertThat(matches.get(1).group(2)).isEqualTo("page2");
        } finally {
            matches.forEach(MatchResult::close);
        }
    }

    @Test
    @DisplayName("Extract all numbers from mixed text")
    void realWorld_extractAllNumbers() {
        Pattern pattern = Pattern.compile("(\\d+)");
        List<MatchResult> matches = pattern.findAll("Item 1 costs $99, item 22 costs $199");
        try {
            assertThat(matches).hasSize(4);
            assertThat(matches.get(0).group(1)).isEqualTo("1");
            assertThat(matches.get(1).group(1)).isEqualTo("99");
            assertThat(matches.get(2).group(1)).isEqualTo("22");
            assertThat(matches.get(3).group(1)).isEqualTo("199");
        } finally {
            matches.forEach(MatchResult::close);
        }
    }

    // ========== Named Groups Advanced Tests ==========

    @Test
    @DisplayName("Mixed named and unnamed groups")
    void namedGroups_mixedWithUnnamed_works() {
        Pattern pattern = Pattern.compile("(\\d{4})-(?P<month>\\d{2})-(\\d{2})");
        try (MatchResult result = pattern.match("2025-11-24")) {
            assertThat(result.matched()).isTrue();
            assertThat(result.group(1)).isEqualTo("2025");       // Unnamed
            assertThat(result.group("month")).isEqualTo("11");   // Named
            assertThat(result.group(2)).isEqualTo("11");         // Also accessible by index
            assertThat(result.group(3)).isEqualTo("24");         // Unnamed
        }
    }

    @Test
    @DisplayName("MatchResult should expose named groups map")
    void matchResult_namedGroupsMap_exposed() {
        Pattern pattern = Pattern.compile("(?P<a>\\d+)-(?P<b>\\d+)");
        try (MatchResult result = pattern.match("123-456")) {
            assertThat(result.namedGroups()).containsKeys("a", "b");
            assertThat(result.namedGroups().get("a")).isEqualTo(1);
            assertThat(result.namedGroups().get("b")).isEqualTo(2);
        }
    }

    // ========== Consistency Tests ==========

    @ParameterizedTest
    @DisplayName("Pattern.match() vs Pattern.matches() consistency")
    @CsvSource({
        "\\d+, 123, true",
        "\\d+, abc, false",
        "[a-z]+, hello, true",
        "[a-z]+, HELLO, false"
    })
    void match_consistentWithMatches(String patternStr, String input, boolean shouldMatch) {
        Pattern pattern = Pattern.compile(patternStr);

        boolean matchesResult = pattern.matches(input);
        try (MatchResult matchResult = pattern.match(input)) {
            assertThat(matchResult.matched()).isEqualTo(matchesResult).isEqualTo(shouldMatch);
        }
    }

    @Test
    @DisplayName("Pattern.find() vs Matcher.find() consistency")
    void find_consistentWithMatcher() {
        Pattern pattern = Pattern.compile("(\\d+)");

        boolean matcherFind;
        try (Matcher m = pattern.matcher("abc123def")) {
            matcherFind = m.find();
        }

        try (MatchResult findResult = pattern.find("abc123def")) {
            assertThat(findResult.matched()).isEqualTo(matcherFind);
        }
    }

    // ========== Empty and Null Tests ==========

    @Test
    @DisplayName("Empty string should work")
    void emptyString_works() {
        Pattern pattern = Pattern.compile(".*");
        try (MatchResult result = pattern.match("")) {
            assertThat(result.matched()).isTrue();
            assertThat(result.group()).isEqualTo("");
        }
    }

    @Test
    @DisplayName("Null input should throw")
    void nullInput_throws() {
        Pattern pattern = Pattern.compile("test");

        assertThatNullPointerException()
            .isThrownBy(() -> pattern.match((String) null));  // Cast to disambiguate

        assertThatNullPointerException()
            .isThrownBy(() -> pattern.find((String) null));  // Cast to disambiguate

        assertThatNullPointerException()
            .isThrownBy(() -> pattern.findAll((String) null));  // Cast to disambiguate
    }
}

