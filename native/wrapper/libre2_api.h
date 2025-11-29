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
// N-VARIANT MATCHING (Phase 1.2.5a - Unlimited Captures)
//=============================================================================

/**
 * Full match with N capture groups (unlimited).
 *
 * Unlike the 0/1/2 capture overloads, this function supports unlimited
 * capture groups via array-based API. Caller must pre-allocate capture array.
 *
 * Usage:
 *   std::string cap1, cap2, cap3;
 *   std::string* caps[] = {&cap1, &cap2, &cap3};
 *   bool matched = fullMatchN(pattern, "foo:123:bar", caps, 3);
 *
 * @param pattern compiled pattern pointer
 * @param text input text to match
 * @param captures array of string pointers (pre-allocated by caller)
 * @param n_captures number of captures to extract
 * @return true if entire text matches pattern and all captures extracted
 */
bool fullMatchN(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string* captures[],
    int n_captures);

/**
 * Partial match with N capture groups (unlimited).
 *
 * Like fullMatchN() but allows pattern to match substring of text.
 *
 * @param pattern compiled pattern pointer
 * @param text input text to search
 * @param captures array of string pointers (pre-allocated)
 * @param n_captures number of captures to extract
 * @return true if pattern found in text and all captures extracted
 */
bool partialMatchN(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string* captures[],
    int n_captures);

/**
 * Consume with N capture groups (unlimited).
 *
 * Matches at beginning of input and advances past match.
 * Supports unlimited captures via array-based API.
 *
 * @param pattern compiled pattern pointer
 * @param input_text pointer to input text pointer (advanced on match)
 * @param input_len pointer to input length (reduced on match)
 * @param captures array of string pointers (pre-allocated)
 * @param n_captures number of captures to extract
 * @return true if match at start and all captures extracted
 */
bool consumeN(
    cache::RE2Pattern* pattern,
    const char** input_text,
    int* input_len,
    std::string* captures[],
    int n_captures);

/**
 * FindAndConsume with N capture groups (unlimited).
 *
 * Finds pattern anywhere in input and advances past match.
 * Supports unlimited captures via array-based API.
 *
 * @param pattern compiled pattern pointer
 * @param input_text pointer to input text pointer (advanced past match)
 * @param input_len pointer to input length (reduced by consumed amount)
 * @param captures array of string pointers (pre-allocated)
 * @param n_captures number of captures to extract
 * @return true if match found and all captures extracted
 */
bool findAndConsumeN(
    cache::RE2Pattern* pattern,
    const char** input_text,
    int* input_len,
    std::string* captures[],
    int n_captures);

//=============================================================================
// N-VARIANT DIRECT MEMORY (Phase 1.2.5a - Zero-Copy + Unlimited Captures)
//=============================================================================

/**
 * Full match direct with N captures (zero-copy + unlimited).
 *
 * Combines zero-copy direct memory access with unlimited capture groups.
 *
 * @param pattern compiled pattern pointer
 * @param text_address memory address (from DirectByteBuffer)
 * @param text_length length in bytes
 * @param captures array of string pointers (pre-allocated)
 * @param n_captures number of captures to extract
 * @return true if match and captures extracted
 */
bool fullMatchNDirect(
    cache::RE2Pattern* pattern,
    int64_t text_address,
    int text_length,
    std::string* captures[],
    int n_captures);

/**
 * Partial match direct with N captures (zero-copy + unlimited).
 *
 * @param pattern compiled pattern pointer
 * @param text_address memory address
 * @param text_length length in bytes
 * @param captures array of string pointers (pre-allocated)
 * @param n_captures number of captures to extract
 * @return true if match found and captures extracted
 */
bool partialMatchNDirect(
    cache::RE2Pattern* pattern,
    int64_t text_address,
    int text_length,
    std::string* captures[],
    int n_captures);

/**
 * Consume direct with N captures (zero-copy + unlimited).
 *
 * @param pattern compiled pattern pointer
 * @param input_address pointer to memory address (advanced on match)
 * @param input_len pointer to input length (reduced on match)
 * @param captures array of string pointers (pre-allocated)
 * @param n_captures number of captures to extract
 * @return true if match at start and captures extracted
 */
bool consumeNDirect(
    cache::RE2Pattern* pattern,
    int64_t* input_address,
    int* input_len,
    std::string* captures[],
    int n_captures);

/**
 * FindAndConsume direct with N captures (zero-copy + unlimited).
 *
 * @param pattern compiled pattern pointer
 * @param input_address pointer to memory address (advanced past match)
 * @param input_len pointer to input length (reduced by consumed amount)
 * @param captures array of string pointers (pre-allocated)
 * @param n_captures number of captures to extract
 * @return true if match found and captures extracted
 */
bool findAndConsumeNDirect(
    cache::RE2Pattern* pattern,
    int64_t* input_address,
    int* input_len,
    std::string* captures[],
    int n_captures);

//=============================================================================
// N-VARIANT BULK (Phase 1.2.5a - Multiple Texts + Unlimited Captures)
//=============================================================================

/**
 * Full match bulk with N captures (multiple texts + unlimited).
 *
 * Process multiple texts with unlimited captures in one call.
 * Each text gets its own capture array.
 *
 * Capture array structure: captures_array[text_idx][capture_idx]
 *
 * Example:
 *   const char* texts[] = {"foo:1", "bar:2"};
 *   int lens[] = {5, 5};
 *   std::string cap0[2], cap1[2];  // 2 texts, 2 captures each
 *   std::string* caps0[] = {&cap0[0], &cap1[0]};
 *   std::string* caps1[] = {&cap0[1], &cap1[1]};
 *   std::string** caps_array[] = {caps0, caps1};
 *   bool results[2];
 *   fullMatchNBulk(pattern, texts, lens, 2, caps_array, 2, results);
 *
 * @param pattern compiled pattern pointer
 * @param texts array of text pointers
 * @param text_lens array of lengths
 * @param num_texts number of texts to process
 * @param captures_array array of capture arrays (one per text)
 * @param n_captures number of captures per text
 * @param results_out pre-allocated bool array (size >= num_texts)
 */
void fullMatchNBulk(
    cache::RE2Pattern* pattern,
    const char** texts,
    const int* text_lens,
    int num_texts,
    std::string** captures_array[],
    int n_captures,
    bool* results_out);

/**
 * Partial match bulk with N captures (multiple texts + unlimited).
 *
 * Same as fullMatchNBulk but uses partial matching.
 *
 * @param pattern compiled pattern pointer
 * @param texts array of text pointers
 * @param text_lens array of lengths
 * @param num_texts number of texts
 * @param captures_array array of capture arrays
 * @param n_captures number of captures per text
 * @param results_out pre-allocated bool array
 */
void partialMatchNBulk(
    cache::RE2Pattern* pattern,
    const char** texts,
    const int* text_lens,
    int num_texts,
    std::string** captures_array[],
    int n_captures,
    bool* results_out);

//=============================================================================
// N-VARIANT BULK+DIRECT (Phase 1.2.5a - Zero-Copy + Multiple + Unlimited)
//=============================================================================

/**
 * Full match direct bulk with N captures (zero-copy + bulk + unlimited).
 *
 * Combines all optimizations: zero-copy, multiple texts, unlimited captures.
 *
 * @param pattern compiled pattern pointer
 * @param text_addresses array of memory addresses
 * @param text_lengths array of lengths
 * @param num_texts number of texts
 * @param captures_array array of capture arrays
 * @param n_captures number of captures per text
 * @param results_out pre-allocated bool array
 */
void fullMatchNDirectBulk(
    cache::RE2Pattern* pattern,
    const int64_t* text_addresses,
    const int* text_lengths,
    int num_texts,
    std::string** captures_array[],
    int n_captures,
    bool* results_out);

/**
 * Partial match direct bulk with N captures (zero-copy + bulk + unlimited).
 *
 * @param pattern compiled pattern pointer
 * @param text_addresses array of memory addresses
 * @param text_lengths array of lengths
 * @param num_texts number of texts
 * @param captures_array array of capture arrays
 * @param n_captures number of captures per text
 * @param results_out pre-allocated bool array
 */
void partialMatchNDirectBulk(
    cache::RE2Pattern* pattern,
    const int64_t* text_addresses,
    const int* text_lengths,
    int num_texts,
    std::string** captures_array[],
    int n_captures,
    bool* results_out);

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
// BULK OPERATIONS (Phase 1.2.4)
//=============================================================================

/**
 * Full match bulk - match multiple texts against single pattern.
 *
 * Absorbs complexity that WAS in JNI layer (now reusable for all languages).
 * Processes all texts even if some are null/invalid (marks as false, continues).
 *
 * Implementation (from old JNI):
 * - Loop over all texts
 * - Handle null/invalid (mark false, continue - NOT all-or-nothing)
 * - Call RE2::FullMatch for each
 * - Write results to output array
 *
 * @param pattern compiled pattern pointer
 * @param texts array of text pointers (may contain nulls)
 * @param text_lens array of lengths (parallel to texts)
 * @param num_texts number of texts to process
 * @param results_out pre-allocated bool array (size >= num_texts)
 */
void fullMatchBulk(
    cache::RE2Pattern* pattern,
    const char** texts,
    const int* text_lens,
    int num_texts,
    bool* results_out);

/**
 * Partial match bulk - match multiple texts against single pattern.
 *
 * Same logic as fullMatchBulk but uses RE2::PartialMatch.
 *
 * @param pattern compiled pattern pointer
 * @param texts array of text pointers
 * @param text_lens array of lengths
 * @param num_texts number of texts
 * @param results_out pre-allocated bool array
 */
void partialMatchBulk(
    cache::RE2Pattern* pattern,
    const char** texts,
    const int* text_lens,
    int num_texts,
    bool* results_out);

//=============================================================================
// DIRECT MEMORY OPERATIONS (Phase 1.2.4 - Zero-Copy)
//=============================================================================

/**
 * Full match with direct memory access (zero-copy).
 *
 * Absorbs logic from JNI fullMatchDirect:
 * - Cast jlong → const char*
 * - Wrap in re2::StringPiece (CRITICAL: zero-copy)
 * - Call RE2::FullMatch with StringPiece
 *
 * Uses re2::StringPiece which is (pointer, length) with NO data copy.
 * This enables true zero-copy matching with DirectByteBuffer.
 *
 * @param pattern compiled pattern pointer
 * @param text_address memory address (from Java DirectByteBuffer.address())
 * @param text_length length in bytes
 * @return true if match, false otherwise
 */
bool fullMatchDirect(
    cache::RE2Pattern* pattern,
    int64_t text_address,
    int text_length);

/**
 * Partial match with direct memory access (zero-copy).
 *
 * Same as fullMatchDirect but uses RE2::PartialMatch.
 *
 * @param pattern compiled pattern pointer
 * @param text_address memory address
 * @param text_length length in bytes
 * @return true if match found, false otherwise
 */
bool partialMatchDirect(
    cache::RE2Pattern* pattern,
    int64_t text_address,
    int text_length);

/**
 * Full match direct bulk (zero-copy + bulk).
 *
 * Absorbs logic from JNI fullMatchDirectBulk:
 * - Loop over address/length pairs
 * - For each: cast → StringPiece → RE2::FullMatch
 * - Handle invalid addresses (mark false, continue)
 * - Write results to output
 *
 * Combines zero-copy (StringPiece) with bulk (single call).
 *
 * @param pattern compiled pattern pointer
 * @param text_addresses array of memory addresses
 * @param text_lengths array of lengths
 * @param num_texts number of texts
 * @param results_out pre-allocated bool array
 */
void fullMatchDirectBulk(
    cache::RE2Pattern* pattern,
    const int64_t* text_addresses,
    const int* text_lengths,
    int num_texts,
    bool* results_out);

/**
 * Partial match direct bulk (zero-copy + bulk).
 *
 * Same as fullMatchDirectBulk but uses RE2::PartialMatch.
 *
 * @param pattern compiled pattern pointer
 * @param text_addresses array of memory addresses
 * @param text_lengths array of lengths
 * @param num_texts number of texts
 * @param results_out pre-allocated bool array
 */
void partialMatchDirectBulk(
    cache::RE2Pattern* pattern,
    const int64_t* text_addresses,
    const int* text_lengths,
    int num_texts,
    bool* results_out);

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

//=============================================================================
// PATTERN ANALYSIS FUNCTIONS (Phase 1.2.5b)
//=============================================================================

/**
 * Get number of capturing groups in pattern.
 *
 * Uses RE2::NumberOfCapturingGroups() - returns count of parenthesized groups.
 * The overall match ($0) does not count.
 *
 * Example: Pattern "(\\w+):(\\d+)" returns 2
 *
 * @param pattern compiled pattern pointer
 * @return number of capturing groups, or -1 if pattern invalid
 */
int getNumberOfCapturingGroups(cache::RE2Pattern* pattern);

/**
 * Get named capturing groups as JSON.
 *
 * Uses RE2::NamedCapturingGroups() - returns map of name → index.
 * Returns JSON: {"group_name": 1, "another_name": 2}
 *
 * If a name appears multiple times, returns index of leftmost group.
 *
 * @param pattern compiled pattern pointer
 * @return JSON object mapping group names to indices (empty {} if no named groups)
 */
std::string getNamedCapturingGroupsJSON(cache::RE2Pattern* pattern);

/**
 * Get capturing group names as JSON.
 *
 * Uses RE2::CapturingGroupNames() - returns map of index → name.
 * Returns JSON: {"1": "group_name", "2": "another_name"}
 *
 * Unnamed groups do not appear in the map.
 *
 * @param pattern compiled pattern pointer
 * @return JSON object mapping indices to group names (empty {} if no named groups)
 */
std::string getCapturingGroupNamesJSON(cache::RE2Pattern* pattern);

/**
 * Get pattern program size (complexity metric).
 *
 * Uses RE2::ProgramSize() - returns approximate "cost" of pattern.
 * Larger numbers = more expensive pattern.
 * Useful for performance analysis.
 *
 * @param pattern compiled pattern pointer
 * @return program size, or -1 if pattern invalid
 */
int getProgramSize(cache::RE2Pattern* pattern);

/**
 * Get reverse program size (complexity metric).
 *
 * Uses RE2::ReverseProgramSize() - size of reverse pattern program.
 * Used for reverse matching operations.
 *
 * @param pattern compiled pattern pointer
 * @return reverse program size, or -1 if pattern invalid
 */
int getReverseProgramSize(cache::RE2Pattern* pattern);

//=============================================================================
// STATUS/VALIDATION FUNCTIONS (Phase 1.2.5c)
//=============================================================================

/**
 * Check if pattern is valid (compiled successfully).
 *
 * Uses RE2::ok() - returns true if error_code() == NoError.
 * Equivalent to isPatternValid() but follows RE2 naming.
 *
 * @param pattern compiled pattern pointer
 * @return true if pattern valid, false if compilation failed
 */
bool ok(cache::RE2Pattern* pattern);

/**
 * Get original pattern string.
 *
 * Uses RE2::pattern() - returns the pattern string used for compilation.
 *
 * Example: If compiled with "(\\d+)", returns "(\\d+)"
 *
 * @param pattern compiled pattern pointer
 * @return original pattern string, or empty string if pattern is null
 */
std::string getPattern(cache::RE2Pattern* pattern);

/**
 * Get error message from compilation.
 *
 * Uses RE2::error() - returns human-readable error message.
 * Empty string if pattern compiled successfully.
 *
 * Example: "missing ): (?P<name>\\w+" → returns error description
 *
 * @param pattern compiled pattern pointer
 * @return error message, or empty string if no error
 */
std::string getError(cache::RE2Pattern* pattern);

/**
 * Get error code from compilation.
 *
 * Uses RE2::error_code() - returns ErrorCode enum value.
 * NoError (0) if pattern compiled successfully.
 *
 * Returns integer (not enum) for language-agnostic bindings.
 *
 * @param pattern compiled pattern pointer
 * @return error code as integer (0 = NoError, see RE2::ErrorCode)
 */
int getErrorCode(cache::RE2Pattern* pattern);

/**
 * Get error argument (offending portion of pattern).
 *
 * Uses RE2::error_arg() - returns the part of pattern that caused error.
 * Empty string if no error.
 *
 * Useful for diagnostics: "Error at position N: <error_arg>"
 *
 * @param pattern compiled pattern pointer
 * @return offending portion of pattern, or empty string if no error
 */
std::string getErrorArg(cache::RE2Pattern* pattern);

//=============================================================================
// REWRITE VALIDATION FUNCTIONS (Phase 1.2.5d)
//=============================================================================

/**
 * Check if rewrite string is valid for this pattern.
 *
 * Uses RE2::CheckRewriteString() - validates rewrite template syntax.
 * Checks that:
 * - Pattern has enough capture groups for all \N tokens in rewrite
 * - Rewrite syntax is valid (no bad escapes)
 *
 * If this returns true, Replace() and Extract() are guaranteed to succeed.
 *
 * Example: Pattern "(\\w+)" with rewrite "\\2" → returns false (only 1 group)
 *
 * @param pattern compiled pattern pointer
 * @param rewrite rewrite template string
 * @param error_out output for error message (if validation fails)
 * @return true if rewrite is valid for this pattern
 */
bool checkRewriteString(
    cache::RE2Pattern* pattern,
    std::string_view rewrite,
    std::string* error_out);

/**
 * Get maximum submatch index referenced in rewrite string.
 *
 * Uses RE2::MaxSubmatch() - static method, pattern-independent.
 * Parses rewrite string and returns highest \N reference.
 *
 * Example: "foo \\2,\\1" → returns 2
 * Example: "no captures" → returns 0
 *
 * @param rewrite rewrite template string
 * @return maximum submatch index referenced, or 0 if no captures
 */
int maxSubmatch(std::string_view rewrite);

/**
 * Apply rewrite template with capture substitutions.
 *
 * Uses RE2::Rewrite() - manually apply rewrite template.
 * Substitutes \0 (entire match), \1, \2, etc. from captures array.
 *
 * Lower-level than replace() - caller provides captures manually.
 * Useful for custom matching workflows.
 *
 * @param pattern compiled pattern pointer
 * @param out output string (result appended here)
 * @param rewrite rewrite template string
 * @param captures array of captured substrings
 * @param n_captures number of captures in array
 * @return true on success, false if rewrite malformed
 */
bool rewrite(
    cache::RE2Pattern* pattern,
    std::string* out,
    std::string_view rewrite,
    const std::string* captures[],
    int n_captures);

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
