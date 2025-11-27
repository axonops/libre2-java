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

import static org.assertj.core.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for replace operations (replaceFirst, replaceAll, bulk variants). */
@DisplayName("Replace Operations")
class ReplaceOperationsIT {

  // ========== replaceFirst() Tests ==========

  @Test
  @DisplayName("replaceFirst should replace first match")
  void replaceFirst_firstMatch_replaced() {
    Pattern pattern = Pattern.compile("\\d+");
    String result = pattern.replaceFirst("Item 123 costs $456", "XXX");

    assertThat(result).isEqualTo("Item XXX costs $456");
  }

  @Test
  @DisplayName("replaceFirst should return original if no match")
  void replaceFirst_noMatch_returnsOriginal() {
    Pattern pattern = Pattern.compile("\\d+");
    String result = pattern.replaceFirst("No digits here", "XXX");

    assertThat(result).isEqualTo("No digits here");
  }

  @Test
  @DisplayName("replaceFirst should handle empty replacement")
  void replaceFirst_emptyReplacement_removes() {
    Pattern pattern = Pattern.compile("\\d+");
    String result = pattern.replaceFirst("Item 123", "");

    assertThat(result).isEqualTo("Item ");
  }

  // ========== replaceAll() Tests ==========

  @Test
  @DisplayName("replaceAll should replace all matches")
  void replaceAll_allMatches_replaced() {
    Pattern pattern = Pattern.compile("\\d+");
    String result = pattern.replaceAll("Item 123 costs $456", "XXX");

    assertThat(result).isEqualTo("Item XXX costs $XXX");
  }

  @Test
  @DisplayName("replaceAll should return original if no matches")
  void replaceAll_noMatches_returnsOriginal() {
    Pattern pattern = Pattern.compile("\\d+");
    String result = pattern.replaceAll("No digits here", "XXX");

    assertThat(result).isEqualTo("No digits here");
  }

  @Test
  @DisplayName("replaceAll should handle empty replacement")
  void replaceAll_emptyReplacement_removesAll() {
    Pattern pattern = Pattern.compile("\\d+");
    String result = pattern.replaceAll("a1b2c3", "");

    assertThat(result).isEqualTo("abc");
  }

  @Test
  @DisplayName("replaceAll should redact emails")
  void replaceAll_redactEmails_works() {
    Pattern emailPattern = Pattern.compile("[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}");
    String result =
        emailPattern.replaceAll("Contact user@example.com or admin@test.org", "[REDACTED]");

    assertThat(result).isEqualTo("Contact [REDACTED] or [REDACTED]");
  }

  // ========== Backreference Tests ==========

  @Test
  @DisplayName("replaceFirst should support backreferences with \\\\1")
  void replaceFirst_backreferences_work() {
    Pattern pattern = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
    String result = pattern.replaceFirst("Date: 2025-11-24", "\\2/\\3/\\1");

    assertThat(result).isEqualTo("Date: 11/24/2025");
  }

  @Test
  @DisplayName("replaceAll should support backreferences")
  void replaceAll_backreferences_work() {
    Pattern pattern = Pattern.compile("(\\d{3})-(\\d{4})");
    String result = pattern.replaceAll("Call 555-1234 or 555-5678", "(\\1) \\2");

    assertThat(result).isEqualTo("Call (555) 1234 or (555) 5678");
  }

  @Test
  @DisplayName("replaceAll should swap groups with backreferences")
  void replaceAll_swapGroups_works() {
    Pattern pattern = Pattern.compile("([a-z]+)@([a-z]+\\.[a-z]+)");
    String result = pattern.replaceAll("user@example.com", "\\2 (\\1)");

    assertThat(result).isEqualTo("example.com (user)");
  }

  @Test
  @DisplayName("replaceAll should support multiple backreferences")
  void replaceAll_multipleBackrefs_work() {
    Pattern pattern = Pattern.compile("(\\w+)\\s+(\\w+)\\s+(\\w+)");
    String result = pattern.replaceAll("one two three", "\\3-\\2-\\1");

    assertThat(result).isEqualTo("three-two-one");
  }

  // ========== Bulk Replace Tests ==========

  @Test
  @DisplayName("replaceAll(array) should replace in all strings")
  void replaceAll_array_replacesAll() {
    Pattern ssnPattern = Pattern.compile("\\d{3}-\\d{2}-\\d{4}");
    String[] logs = {"User 123-45-6789 logged in", "No PII here", "SSN: 987-65-4321"};

    String[] redacted = ssnPattern.replaceAll(logs, "[REDACTED]");

    assertThat(redacted)
        .containsExactly("User [REDACTED] logged in", "No PII here", "SSN: [REDACTED]");
  }

  @Test
  @DisplayName("replaceAll(collection) should replace in all strings")
  void replaceAll_collection_replacesAll() {
    Pattern pattern = Pattern.compile("\\d+");
    List<String> inputs = Arrays.asList("a1b2", "c3d4", "no digits");

    List<String> results = pattern.replaceAll(inputs, "X");

    assertThat(results).containsExactly("aXbX", "cXdX", "no digits");
  }

  @Test
  @DisplayName("replaceAll(array) should support backreferences")
  void replaceAll_arrayBackrefs_work() {
    Pattern pattern = Pattern.compile("(\\d{3})-(\\d{4})");
    String[] inputs = {"555-1234", "555-5678"};

    String[] results = pattern.replaceAll(inputs, "(\\1) \\2");

    assertThat(results).containsExactly("(555) 1234", "(555) 5678");
  }

  @Test
  @DisplayName("replaceAll(array) with empty array should return empty")
  void replaceAll_emptyArray_returnsEmpty() {
    Pattern pattern = Pattern.compile("\\d+");
    String[] results = pattern.replaceAll(new String[0], "XXX");

    assertThat(results).isEmpty();
  }

  @Test
  @DisplayName("replaceAll(collection) with empty collection should return empty")
  void replaceAll_emptyCollection_returnsEmpty() {
    Pattern pattern = Pattern.compile("\\d+");
    List<String> results = pattern.replaceAll(List.of(), "XXX");

    assertThat(results).isEmpty();
  }

  // ========== Edge Cases ==========

  @Test
  @DisplayName("replace with special regex characters in replacement")
  void replace_specialCharsInReplacement_literal() {
    Pattern pattern = Pattern.compile("test");
    String result = pattern.replaceAll("test test", ".$^*+?[]{}()");

    // Replacement is literal, not regex
    assertThat(result).isEqualTo(".$^*+?[]{}() .$^*+?[]{}()");
  }

  @Test
  @DisplayName("replace on empty input should return empty")
  void replace_emptyInput_returnsEmpty() {
    Pattern pattern = Pattern.compile("\\d+");
    String result = pattern.replaceAll("", "XXX");

    assertThat(result).isEmpty();
  }

  @Test
  @DisplayName("replace with unicode should work")
  void replace_unicode_works() {
    // Use simpler emoji pattern
    Pattern pattern = Pattern.compile("test");
    String result = pattern.replaceAll("test🙂test", "OK");

    assertThat(result).isEqualTo("OK🙂OK");
  }

  // ========== Real-World Scenarios ==========

  @Test
  @DisplayName("Sanitize log data - remove sensitive info")
  void realWorld_sanitizeLogs() {
    Pattern ssnPattern = Pattern.compile("\\d{3}-\\d{2}-\\d{4}");
    Pattern ccPattern = Pattern.compile("\\d{4}-\\d{4}-\\d{4}-\\d{4}");

    String log = "User SSN: 123-45-6789, CC: 1234-5678-9012-3456";

    String sanitized = ssnPattern.replaceAll(log, "[SSN-REDACTED]");
    sanitized = ccPattern.replaceAll(sanitized, "[CC-REDACTED]");

    assertThat(sanitized).isEqualTo("User SSN: [SSN-REDACTED], CC: [CC-REDACTED]");
  }

  @Test
  @DisplayName("Reformat phone numbers")
  void realWorld_reformatPhones() {
    Pattern pattern = Pattern.compile("(\\d{3})-(\\d{3})-(\\d{4})");
    String result = pattern.replaceAll("Phone: 555-123-4567", "(\\1) \\2-\\3");

    assertThat(result).isEqualTo("Phone: (555) 123-4567");
  }

  @Test
  @DisplayName("Batch password sanitization")
  void realWorld_batchPasswordSanitization() {
    Pattern passwordPattern = Pattern.compile("password=[^&\\s]+");
    String[] urls = {
      "https://api.com/login?user=admin&password=secret123",
      "https://api.com/data?id=1",
      "https://api.com/auth?password=pass456&token=abc"
    };

    String[] sanitized = passwordPattern.replaceAll(urls, "password=[REDACTED]");

    assertThat(sanitized)
        .containsExactly(
            "https://api.com/login?user=admin&password=[REDACTED]",
            "https://api.com/data?id=1",
            "https://api.com/auth?password=[REDACTED]&token=abc");
  }

  // ========== Validation Tests ==========

  @Test
  @DisplayName("replaceFirst should throw on null input")
  void replaceFirst_nullInput_throws() {
    Pattern pattern = Pattern.compile("test");

    assertThatNullPointerException()
        .isThrownBy(() -> pattern.replaceFirst((String) null, "replacement"))
        .withMessageContaining("null");
  }

  @Test
  @DisplayName("replaceFirst should throw on null replacement")
  void replaceFirst_nullReplacement_throws() {
    Pattern pattern = Pattern.compile("test");

    assertThatNullPointerException()
        .isThrownBy(() -> pattern.replaceFirst("test", null))
        .withMessageContaining("null");
  }

  @Test
  @DisplayName("replaceAll(array) should throw on null array")
  void replaceAll_nullArray_throws() {
    Pattern pattern = Pattern.compile("test");

    assertThatNullPointerException()
        .isThrownBy(() -> pattern.replaceAll((String[]) null, "replacement"))
        .withMessageContaining("null");
  }

  @Test
  @DisplayName("replaceAll(collection) should throw on null collection")
  void replaceAll_nullCollection_throws() {
    Pattern pattern = Pattern.compile("test");

    assertThatNullPointerException()
        .isThrownBy(() -> pattern.replaceAll((java.util.Collection<String>) null, "replacement"))
        .withMessageContaining("null");
  }
}
