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

#pragma once

#include "cache/deferred_cache.h"
#include <string>

namespace libre2 {
namespace api {

/**
 * High-level C++ API for RE2 with automatic caching.
 *
 * This facade layer provides simple functions for language wrappers (JNI, Python, Go, etc.)
 * All caching complexity is hidden - just compile, match, and release patterns.
 *
 * Features:
 * - NO automatic lazy init (explicit initCache() call required for caching)
 * - If no initCache() called: patterns compiled directly (no caching, no overhead)
 * - Optional explicit initialization for caching (default or custom config)
 * - Simple pointer-based API (no handles, no complexity)
 * - Works across all language bindings (JNI, Python, Go, Node, etc.)
 *
 * Typical usage (with caching):
 *   initCache();  // Enable caching with defaults
 *   RE2Pattern* p = compilePattern("test.*", true, error);
 *   bool matched = match(p, "test123");
 *   releasePattern(p);
 *   shutdownCache();
 *
 * Typical usage (without caching):
 *   RE2Pattern* p = compilePattern("test.*", true, error);  // Direct compile
 *   bool matched = match(p, "test123");
 *   releasePattern(p);  // Immediate delete
 */

/**
 * Compile RE2 pattern (simple case-sensitive flag).
 *
 * Behavior depends on whether cache is initialized:
 * - If initCache() was called: Uses cache (reuses patterns, refcount management)
 * - If no initCache(): Compiles directly (no cache, immediate cleanup on release)
 *
 * @param pattern regex pattern string
 * @param case_sensitive case sensitivity flag
 * @param error_out output parameter for compilation errors (empty if success)
 * @return compiled pattern pointer, or nullptr on error
 */
cache::RE2Pattern* compilePattern(
    const std::string& pattern,
    bool case_sensitive,
    std::string& error_out);

/**
 * Compile RE2 pattern with full options (JSON).
 *
 * Options affect cache key: different options = different cache entry.
 *
 * JSON format (all fields optional):
 * {
 *   "case_sensitive": true,
 *   "encoding": "UTF8",
 *   "posix_syntax": false,
 *   "longest_match": false,
 *   "literal": false,
 *   "never_nl": false,
 *   "dot_nl": false,
 *   "never_capture": false,
 *   "perl_classes": false,
 *   "word_boundary": false,
 *   "one_line": false,
 *   "max_mem": 8388608
 * }
 *
 * @param pattern regex pattern string
 * @param options_json JSON string with options (empty = defaults)
 * @param error_out output parameter for compilation errors
 * @return compiled pattern pointer, or nullptr on error
 */
cache::RE2Pattern* compilePattern(
    const std::string& pattern,
    const std::string& options_json,
    std::string& error_out);

/**
 * Release compiled pattern (decrements refcount).
 *
 * Thread-safe. Pattern automatically cleaned by eviction when refcount=0.
 *
 * @param pattern compiled pattern to release (nullptr safe)
 */
void releasePattern(cache::RE2Pattern* pattern);

/**
 * Full match - entire text must match pattern.
 *
 * Thread-safe. Uses compiled pattern pointer from compilePattern().
 *
 * @param pattern compiled pattern pointer
 * @param text input text to match
 * @return true if entire text matches pattern
 */
bool fullMatch(cache::RE2Pattern* pattern, std::string_view text);

/**
 * Full match with 1 capture group.
 *
 * @param pattern compiled pattern pointer
 * @param text input text to match
 * @param capture1 output for first capture group (nullptr safe)
 * @return true if match successful
 */
bool fullMatch(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string* capture1);

/**
 * Full match with 2 capture groups.
 *
 * @param pattern compiled pattern pointer
 * @param text input text to match
 * @param capture1 output for first capture group
 * @param capture2 output for second capture group
 * @return true if match successful
 */
bool fullMatch(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string* capture1,
    std::string* capture2);

/**
 * Partial match - find pattern anywhere in text.
 *
 * Thread-safe. Uses compiled pattern pointer from compilePattern().
 *
 * @param pattern compiled pattern pointer
 * @param text input text to search
 * @return true if pattern found in text
 */
bool partialMatch(cache::RE2Pattern* pattern, std::string_view text);

/**
 * Partial match with 1 capture group.
 *
 * @param pattern compiled pattern pointer
 * @param text input text to search
 * @param capture1 output for first capture group (nullptr safe)
 * @return true if match successful
 */
bool partialMatch(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string* capture1);

/**
 * Partial match with 2 capture groups.
 *
 * @param pattern compiled pattern pointer
 * @param text input text to search
 * @param capture1 output for first capture group
 * @param capture2 output for second capture group
 * @return true if match successful
 */
bool partialMatch(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string* capture1,
    std::string* capture2);

//=============================================================================
// CONSUME/SCAN FUNCTIONS (Phase 1.2.1)
//=============================================================================

/**
 * Consume pattern from start of input, advance input on match.
 *
 * Like Perl's m//gc - matches at beginning and consumes matched portion.
 * Updates input_text and input_len to point past the match.
 *
 * @param pattern compiled pattern pointer
 * @param input_text pointer to input text pointer (advanced on match)
 * @param input_len pointer to input length (reduced on match)
 * @return true if match at start, false otherwise (input unchanged)
 */
bool consume(
    cache::RE2Pattern* pattern,
    const char** input_text,
    int* input_len);

/**
 * Consume with 1 capture group.
 *
 * @param capture1 output for first capture group
 * @return true if match, false otherwise
 */
bool consume(
    cache::RE2Pattern* pattern,
    const char** input_text,
    int* input_len,
    std::string* capture1);

/**
 * Consume with 2 capture groups.
 *
 * @param capture1 output for first capture group
 * @param capture2 output for second capture group
 * @return true if match, false otherwise
 */
bool consume(
    cache::RE2Pattern* pattern,
    const char** input_text,
    int* input_len,
    std::string* capture1,
    std::string* capture2);

/**
 * Find pattern anywhere in input, advance past match.
 *
 * Like Perl's m//g - finds pattern anywhere and consumes up to end of match.
 * Updates input_text and input_len to point past the match.
 *
 * @param pattern compiled pattern pointer
 * @param input_text pointer to input text pointer (advanced past match)
 * @param input_len pointer to input length (reduced by consumed amount)
 * @return true if match found, false otherwise (input unchanged)
 */
bool findAndConsume(
    cache::RE2Pattern* pattern,
    const char** input_text,
    int* input_len);

/**
 * FindAndConsume with 1 capture group.
 *
 * @param capture1 output for first capture group
 * @return true if match, false otherwise
 */
bool findAndConsume(
    cache::RE2Pattern* pattern,
    const char** input_text,
    int* input_len,
    std::string* capture1);

/**
 * FindAndConsume with 2 capture groups.
 *
 * @param capture1 output for first capture group
 * @param capture2 output for second capture group
 * @return true if match, false otherwise
 */
bool findAndConsume(
    cache::RE2Pattern* pattern,
    const char** input_text,
    int* input_len,
    std::string* capture1,
    std::string* capture2);

//=============================================================================
// REPLACEMENT FUNCTIONS (Phase 1.2.2)
//=============================================================================

/**
 * Replace first occurrence of pattern with rewrite string.
 *
 * Uses RE2::Replace() - replaces first match, leaves rest unchanged.
 * Rewrite string supports: \\0 (entire match), \\1, \\2, etc (capture groups).
 *
 * @param pattern compiled pattern pointer
 * @param text input text
 * @param rewrite rewrite template string
 * @param result_out output string (receives result)
 * @return true if replacement occurred, false if no match
 */
bool replace(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string_view rewrite,
    std::string* result_out);

/**
 * Replace all occurrences of pattern with rewrite string.
 *
 * Uses RE2::GlobalReplace() - replaces all non-overlapping matches.
 * Rewrite string supports: \\0 (entire match), \\1, \\2, etc (capture groups).
 *
 * @param pattern compiled pattern pointer
 * @param text input text
 * @param rewrite rewrite template string
 * @param result_out output string (receives result)
 * @return number of replacements made (0 if no matches)
 */
int replaceAll(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string_view rewrite,
    std::string* result_out);

/**
 * Extract matched portion with rewrite template.
 *
 * Uses RE2::Extract() - extracts match and applies rewrite template.
 * Unlike replace(), only outputs the rewritten match (not entire text).
 *
 * @param pattern compiled pattern pointer
 * @param text input text
 * @param rewrite rewrite template string
 * @param result_out output string (receives extracted/rewritten result)
 * @return true if match found and extraction succeeded, false otherwise
 */
bool extract(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string_view rewrite,
    std::string* result_out);

//=============================================================================
// UTILITY FUNCTIONS (Phase 1.2.3)
//=============================================================================

/**
 * Quote/escape special regex characters.
 *
 * Uses RE2::QuoteMeta() - escapes all regex metacharacters.
 * Result string, used as regex, will match original string literally.
 *
 * Thread-safe, stateless (no caching).
 *
 * Example: "1.5-2.0?" → "1\\.5\\-2\\.0\\?"
 *
 * @param text input text to quote
 * @return quoted string safe for use as regex pattern
 */
std::string quoteMeta(std::string_view text);

/**
 * Get pattern information and metadata.
 *
 * Returns JSON with complete pattern details:
 * {
 *   "valid": true,
 *   "error": "",
 *   "pattern": "(\\w+):(\\d+)",
 *   "capturing_groups": 2,
 *   "named_groups": {"name": 1, "value": 2},
 *   "group_names": {"1": "name", "2": "value"},
 *   "program_size": 512
 * }
 *
 * @param pattern compiled pattern pointer
 * @return JSON string with pattern metadata
 */
std::string getPatternInfo(cache::RE2Pattern* pattern);

/**
 * Check if pattern is valid.
 *
 * @param pattern compiled pattern pointer
 * @return true if pattern compiled successfully, false if compilation failed
 */
bool isPatternValid(cache::RE2Pattern* pattern);

/**
 * Get current cache metrics as JSON string.
 *
 * Thread-safe. Returns fresh snapshot from all caches.
 *
 * @return JSON string with comprehensive metrics
 */
std::string getMetricsJSON();

/**
 * Initialize cache with configuration (optional).
 *
 * If never called: compile() works without caching (direct RE2 compilation).
 * If called: compile() uses cache for pattern reuse and performance.
 *
 * Can be called at any time before pattern compilation.
 *
 * @param json_config JSON configuration string (empty = defaults)
 * @throws std::runtime_error if already initialized or config invalid
 */
void initCache(const std::string& json_config = "");

/**
 * Shutdown cache and cleanup (optional).
 *
 * Stops eviction thread, clears all caches, releases memory.
 * After shutdown, compile() will fail (no re-initialization).
 *
 * Typically called at process exit or library unload.
 */
void shutdownCache();

/**
 * Check if cache is initialized.
 *
 * @return true if cache initialized (explicitly or via lazy init)
 */
bool isCacheInitialized();

}  // namespace api
}  // namespace libre2
