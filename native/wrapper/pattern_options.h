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

#include <re2/re2.h>
#include <cstdint>
#include <string>

namespace libre2 {
namespace api {

/**
 * Pattern compilation options (mirrors RE2::Options).
 *
 * Used for:
 * 1. Configuring RE2 pattern compilation
 * 2. Cache key generation (different options = different cache entry)
 * 3. JSON serialization (Java sends options as JSON)
 *
 * All fields optional in JSON - missing fields use defaults.
 */
struct PatternOptions {
    // ========== BOOLEAN OPTIONS (11 flags) ==========
    bool posix_syntax = false;      // POSIX egrep syntax (not Perl)
    bool longest_match = false;     // Leftmost-longest match (not first)
    bool log_errors = false;        // Log parse errors (we disable)
    bool literal = false;           // Treat pattern as literal string (not regex)
    bool never_nl = false;          // Never match \n
    bool dot_nl = false;            // Dot matches everything including \n
    bool never_capture = false;     // Parse all parens as non-capturing
    bool case_sensitive = true;     // Case-sensitive matching
    bool perl_classes = false;      // Allow \d \s \w (POSIX mode only)
    bool word_boundary = false;     // Allow \b \B (POSIX mode only)
    bool one_line = false;          // ^ and $ match only start/end of text (POSIX mode only)

    // ========== ENCODING ==========
    bool utf8 = true;               // true=UTF8, false=Latin1

    // ========== MEMORY LIMIT ==========
    int64_t max_mem = 8388608;      // 8MB default

    // ========== LIBRE2-SPECIFIC OPTIONS (Future Extensions) ==========
    // Reserved space for libre2-specific options that don't exist in RE2
    // Examples (not implemented yet):
    //   - bool enable_result_cache = true;   // Enable result caching
    //   - int64_t timeout_ms = 0;            // Per-pattern timeout
    //   - bool enable_metrics = true;        // Per-pattern metrics
    // When adding new options:
    //   1. Add field here with default value
    //   2. Update computeHash() to include in hash (use bits 45+)
    //   3. Update fromJson() to parse new field
    //   4. Update toRE2Options() if it affects RE2 (or handle separately)

    // ========== CACHED HASH (computed once on initialization) ==========
    // KEY OPTIMIZATION: Hash computed once at construction, not on every cache lookup
    // Cache lookup: O(1) hash retrieval vs O(13) hash computation
    mutable uint64_t cached_hash = 0;  // Computed lazily on first access

    /**
     * Convert to RE2::Options.
     *
     * @return RE2::Options with all fields set from this struct
     */
    RE2::Options toRE2Options() const;

    /**
     * Get hash for cache key (cached, computed once).
     *
     * First call computes hash, subsequent calls return cached value.
     * Thread-safe via mutable field.
     *
     * Packs all 13 options into single uint64_t:
     * - Bits 0-10: Boolean flags (11 bits)
     * - Bits 11-12: Encoding (2 bits, but only need 1)
     * - Bits 13-63: max_mem (lower 32 bits, upper 51 bits available)
     *
     * @return 64-bit hash of all options
     */
    uint64_t hash() const;

    /**
     * Compute hash directly from RE2::Options.
     *
     * Useful when we have RE2::Options but not PatternOptions.
     *
     * @param opts RE2::Options object
     * @return 64-bit hash of all options
     */
    static uint64_t hashFromRE2Options(const RE2::Options& opts);

    /**
     * Parse options from JSON string.
     *
     * JSON format:
     * {
     *   "case_sensitive": true,
     *   "encoding": "UTF8",       // or "Latin1"
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
     * All fields optional - missing fields use defaults.
     *
     * @param json JSON string with options
     * @return PatternOptions struct
     * @throws std::runtime_error if JSON invalid
     */
    static PatternOptions fromJson(const std::string& json);

    /**
     * Create default options.
     *
     * Equivalent to RE2::Options() constructor defaults.
     *
     * @return PatternOptions with all defaults
     */
    static PatternOptions defaults();

    /**
     * Create options from simple case_sensitive flag.
     *
     * Used for backward compatibility with existing API.
     *
     * @param case_sensitive case sensitivity flag
     * @return PatternOptions with case_sensitive set, others default
     */
    static PatternOptions fromCaseSensitive(bool case_sensitive);

    /**
     * Equality comparison (for testing).
     */
    bool operator==(const PatternOptions& other) const = default;

private:
    /**
     * Compute hash from current option values.
     * Called once by hash() and cached.
     */
    uint64_t computeHash() const;
};

}  // namespace api
}  // namespace libre2
