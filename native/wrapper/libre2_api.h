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
 * Compile RE2 pattern.
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
