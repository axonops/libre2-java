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

#include "libre2_api.h"
#include "pattern_options.h"
#include "cache/cache_manager.h"
#include "cache/murmur_hash3.h"
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>

namespace libre2 {
namespace api {

//============================================================================
// Global State (NO Lazy Init - Explicit Only)
//============================================================================

static std::atomic<cache::CacheManager*> g_cache_manager{nullptr};
static std::mutex g_init_mutex;

//============================================================================
// Public API
//============================================================================

cache::RE2Pattern* compilePattern(
    const std::string& pattern,
    bool case_sensitive,
    std::string& error_out) {

    // Convert bool case_sensitive to PatternOptions for new API
    api::PatternOptions options = api::PatternOptions::fromCaseSensitive(case_sensitive);

    cache::CacheManager* mgr = g_cache_manager.load(std::memory_order_acquire);

    if (mgr == nullptr) {
        // NO CACHE - Compile directly with raw RE2
        RE2::Options opts = options.toRE2Options();
        opts.set_log_errors(false);

        auto regex = std::make_unique<RE2>(pattern, opts);

        if (!regex->ok()) {
            error_out = regex->error();
            return nullptr;
        }

        // Create RE2Pattern wrapper (no caching, refcount stays 0)
        auto* pattern_wrapper = new cache::RE2Pattern(std::move(regex), pattern, options);
        return pattern_wrapper;
    }

    // CACHE ENABLED - Use cached compilation
    cache::PatternCacheMetrics metrics;  // Local metrics
    auto shared_pattern = mgr->patternCache().getOrCompile(pattern, options, metrics, error_out);

    if (!shared_pattern) {
        return nullptr;  // Compilation error
    }

    // Return raw pointer (cache holds shared_ptr)
    return shared_pattern.get();
}

cache::RE2Pattern* compilePattern(
    const std::string& pattern,
    const std::string& options_json,
    std::string& error_out) {

    // Parse options from JSON
    api::PatternOptions options;
    try {
        options = api::PatternOptions::fromJson(options_json);
    } catch (const std::exception& e) {
        error_out = std::string("Invalid options JSON: ") + e.what();
        return nullptr;
    }

    cache::CacheManager* mgr = g_cache_manager.load(std::memory_order_acquire);

    if (mgr == nullptr) {
        // NO CACHE - Compile directly with raw RE2
        RE2::Options re2_opts = options.toRE2Options();

        auto regex = std::make_unique<RE2>(pattern, re2_opts);

        if (!regex->ok()) {
            error_out = regex->error();
            return nullptr;
        }

        // Create RE2Pattern wrapper (no caching, refcount stays 0)
        auto* pattern_wrapper = new cache::RE2Pattern(std::move(regex), pattern, options);
        return pattern_wrapper;
    }

    // CACHE ENABLED - Use cached compilation
    cache::PatternCacheMetrics metrics;  // Local metrics
    auto shared_pattern = mgr->patternCache().getOrCompile(pattern, options, metrics, error_out);

    if (!shared_pattern) {
        return nullptr;  // Compilation error
    }

    // Return raw pointer (cache holds shared_ptr)
    return shared_pattern.get();
}

void releasePattern(cache::RE2Pattern* pattern) {
    if (!pattern) {
        return;  // Null-safe
    }

    cache::CacheManager* mgr = g_cache_manager.load(std::memory_order_acquire);

    if (mgr == nullptr) {
        // NO CACHE - Pattern was compiled directly, delete immediately
        delete pattern;
        return;
    }

    // CACHE ENABLED - Decrement refcount (eviction cleans up when refcount=0)
    cache::PatternCache::releasePattern(pattern);
}

bool fullMatch(cache::RE2Pattern* pattern, std::string_view text) {
    if (!pattern || !pattern->isValid()) {
        return false;  // Invalid pattern
    }

    return RE2::FullMatch(text, *pattern->compiled_regex);
}

bool fullMatch(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string* capture1) {

    if (!pattern || !pattern->isValid()) {
        return false;  // Invalid pattern
    }

    return RE2::FullMatch(text, *pattern->compiled_regex, capture1);
}

bool fullMatch(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string* capture1,
    std::string* capture2) {

    if (!pattern || !pattern->isValid()) {
        return false;  // Invalid pattern
    }

    return RE2::FullMatch(text, *pattern->compiled_regex, capture1, capture2);
}

bool partialMatch(cache::RE2Pattern* pattern, std::string_view text) {
    if (!pattern || !pattern->isValid()) {
        return false;  // Invalid pattern
    }

    return RE2::PartialMatch(text, *pattern->compiled_regex);
}

bool partialMatch(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string* capture1) {

    if (!pattern || !pattern->isValid()) {
        return false;  // Invalid pattern
    }

    return RE2::PartialMatch(text, *pattern->compiled_regex, capture1);
}

bool partialMatch(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string* capture1,
    std::string* capture2) {

    if (!pattern || !pattern->isValid()) {
        return false;  // Invalid pattern
    }

    return RE2::PartialMatch(text, *pattern->compiled_regex, capture1, capture2);
}

//============================================================================
// CONSUME/SCAN FUNCTIONS (Phase 1.2.1)
//============================================================================

bool consume(
    cache::RE2Pattern* pattern,
    const char** input_text,
    int* input_len) {

    if (!pattern || !pattern->isValid() || !input_text || !input_len) {
        return false;
    }

    // Create string_view from current input
    std::string_view input(*input_text, *input_len);

    // Try to consume from start
    if (RE2::Consume(&input, *pattern->compiled_regex)) {
        // Match succeeded - update input pointers
        *input_text = input.data();
        *input_len = input.size();
        return true;
    }

    // No match - input unchanged
    return false;
}

bool consume(
    cache::RE2Pattern* pattern,
    const char** input_text,
    int* input_len,
    std::string* capture1) {

    if (!pattern || !pattern->isValid() || !input_text || !input_len) {
        return false;
    }

    std::string_view input(*input_text, *input_len);

    if (RE2::Consume(&input, *pattern->compiled_regex, capture1)) {
        *input_text = input.data();
        *input_len = input.size();
        return true;
    }

    return false;
}

bool consume(
    cache::RE2Pattern* pattern,
    const char** input_text,
    int* input_len,
    std::string* capture1,
    std::string* capture2) {

    if (!pattern || !pattern->isValid() || !input_text || !input_len) {
        return false;
    }

    std::string_view input(*input_text, *input_len);

    if (RE2::Consume(&input, *pattern->compiled_regex, capture1, capture2)) {
        *input_text = input.data();
        *input_len = input.size();
        return true;
    }

    return false;
}

bool findAndConsume(
    cache::RE2Pattern* pattern,
    const char** input_text,
    int* input_len) {

    if (!pattern || !pattern->isValid() || !input_text || !input_len) {
        return false;
    }

    std::string_view input(*input_text, *input_len);

    if (RE2::FindAndConsume(&input, *pattern->compiled_regex)) {
        *input_text = input.data();
        *input_len = input.size();
        return true;
    }

    return false;
}

bool findAndConsume(
    cache::RE2Pattern* pattern,
    const char** input_text,
    int* input_len,
    std::string* capture1) {

    if (!pattern || !pattern->isValid() || !input_text || !input_len) {
        return false;
    }

    std::string_view input(*input_text, *input_len);

    if (RE2::FindAndConsume(&input, *pattern->compiled_regex, capture1)) {
        *input_text = input.data();
        *input_len = input.size();
        return true;
    }

    return false;
}

bool findAndConsume(
    cache::RE2Pattern* pattern,
    const char** input_text,
    int* input_len,
    std::string* capture1,
    std::string* capture2) {

    if (!pattern || !pattern->isValid() || !input_text || !input_len) {
        return false;
    }

    std::string_view input(*input_text, *input_len);

    if (RE2::FindAndConsume(&input, *pattern->compiled_regex, capture1, capture2)) {
        *input_text = input.data();
        *input_len = input.size();
        return true;
    }

    return false;
}

//============================================================================
// N-VARIANT MATCHING (Phase 1.2.5a - Unlimited Captures)
//============================================================================

bool fullMatchN(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string* captures[],
    int n_captures) {

    if (!pattern || !pattern->isValid()) {
        return false;
    }

    if (n_captures < 0) {
        return false;
    }

    // Special case: no captures requested
    if (n_captures == 0 || captures == nullptr) {
        return RE2::FullMatch(text, *pattern->compiled_regex);
    }

    // Convert std::string*[] to RE2::Arg[] (required by RE2::FullMatchN)
    std::vector<RE2::Arg> args_vec;
    args_vec.reserve(n_captures);
    for (int i = 0; i < n_captures; i++) {
        args_vec.emplace_back(captures[i]);
    }

    // Build pointer array (RE2::FullMatchN signature)
    std::vector<const RE2::Arg*> args_ptrs(n_captures);
    for (int i = 0; i < n_captures; i++) {
        args_ptrs[i] = &args_vec[i];
    }

    return RE2::FullMatchN(text, *pattern->compiled_regex, args_ptrs.data(), n_captures);
}

bool partialMatchN(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string* captures[],
    int n_captures) {

    if (!pattern || !pattern->isValid()) {
        return false;
    }

    if (n_captures < 0) {
        return false;
    }

    if (n_captures == 0 || captures == nullptr) {
        return RE2::PartialMatch(text, *pattern->compiled_regex);
    }

    std::vector<RE2::Arg> args_vec;
    args_vec.reserve(n_captures);
    for (int i = 0; i < n_captures; i++) {
        args_vec.emplace_back(captures[i]);
    }

    std::vector<const RE2::Arg*> args_ptrs(n_captures);
    for (int i = 0; i < n_captures; i++) {
        args_ptrs[i] = &args_vec[i];
    }

    return RE2::PartialMatchN(text, *pattern->compiled_regex, args_ptrs.data(), n_captures);
}

bool consumeN(
    cache::RE2Pattern* pattern,
    const char** input_text,
    int* input_len,
    std::string* captures[],
    int n_captures) {

    if (!pattern || !pattern->isValid() || !input_text || !input_len) {
        return false;
    }

    if (n_captures < 0) {
        return false;
    }

    std::string_view input(*input_text, *input_len);

    if (n_captures == 0 || captures == nullptr) {
        if (RE2::Consume(&input, *pattern->compiled_regex)) {
            *input_text = input.data();
            *input_len = input.size();
            return true;
        }
        return false;
    }

    std::vector<RE2::Arg> args_vec;
    args_vec.reserve(n_captures);
    for (int i = 0; i < n_captures; i++) {
        args_vec.emplace_back(captures[i]);
    }

    std::vector<const RE2::Arg*> args_ptrs(n_captures);
    for (int i = 0; i < n_captures; i++) {
        args_ptrs[i] = &args_vec[i];
    }

    if (RE2::ConsumeN(&input, *pattern->compiled_regex, args_ptrs.data(), n_captures)) {
        *input_text = input.data();
        *input_len = input.size();
        return true;
    }

    return false;
}

bool findAndConsumeN(
    cache::RE2Pattern* pattern,
    const char** input_text,
    int* input_len,
    std::string* captures[],
    int n_captures) {

    if (!pattern || !pattern->isValid() || !input_text || !input_len) {
        return false;
    }

    if (n_captures < 0) {
        return false;
    }

    std::string_view input(*input_text, *input_len);

    if (n_captures == 0 || captures == nullptr) {
        if (RE2::FindAndConsume(&input, *pattern->compiled_regex)) {
            *input_text = input.data();
            *input_len = input.size();
            return true;
        }
        return false;
    }

    std::vector<RE2::Arg> args_vec;
    args_vec.reserve(n_captures);
    for (int i = 0; i < n_captures; i++) {
        args_vec.emplace_back(captures[i]);
    }

    std::vector<const RE2::Arg*> args_ptrs(n_captures);
    for (int i = 0; i < n_captures; i++) {
        args_ptrs[i] = &args_vec[i];
    }

    if (RE2::FindAndConsumeN(&input, *pattern->compiled_regex, args_ptrs.data(), n_captures)) {
        *input_text = input.data();
        *input_len = input.size();
        return true;
    }

    return false;
}

//============================================================================
// N-VARIANT DIRECT MEMORY (Phase 1.2.5a - Zero-Copy + Unlimited Captures)
//============================================================================

bool fullMatchNDirect(
    cache::RE2Pattern* pattern,
    int64_t text_address,
    int text_length,
    std::string* captures[],
    int n_captures) {

    if (text_address == 0 || text_length < 0) {
        return false;
    }

    const char* text = reinterpret_cast<const char*>(text_address);
    re2::StringPiece sp(text, static_cast<size_t>(text_length));

    // Delegate to standard variant
    return fullMatchN(pattern, sp, captures, n_captures);
}

bool partialMatchNDirect(
    cache::RE2Pattern* pattern,
    int64_t text_address,
    int text_length,
    std::string* captures[],
    int n_captures) {

    if (text_address == 0 || text_length < 0) {
        return false;
    }

    const char* text = reinterpret_cast<const char*>(text_address);
    re2::StringPiece sp(text, static_cast<size_t>(text_length));

    return partialMatchN(pattern, sp, captures, n_captures);
}

bool consumeNDirect(
    cache::RE2Pattern* pattern,
    int64_t* input_address,
    int* input_len,
    std::string* captures[],
    int n_captures) {

    if (!input_address || !input_len || *input_address == 0 || *input_len < 0) {
        return false;
    }

    const char* text = reinterpret_cast<const char*>(*input_address);

    // Use consumeN with const char** (which expects const char**)
    const char* text_ptr = text;
    bool result = consumeN(pattern, &text_ptr, input_len, captures, n_captures);

    if (result) {
        // Update address to new position
        *input_address = reinterpret_cast<int64_t>(text_ptr);
    }

    return result;
}

bool findAndConsumeNDirect(
    cache::RE2Pattern* pattern,
    int64_t* input_address,
    int* input_len,
    std::string* captures[],
    int n_captures) {

    if (!input_address || !input_len || *input_address == 0 || *input_len < 0) {
        return false;
    }

    const char* text = reinterpret_cast<const char*>(*input_address);
    const char* text_ptr = text;
    bool result = findAndConsumeN(pattern, &text_ptr, input_len, captures, n_captures);

    if (result) {
        *input_address = reinterpret_cast<int64_t>(text_ptr);
    }

    return result;
}

//============================================================================
// N-VARIANT BULK (Phase 1.2.5a - Multiple Texts + Unlimited Captures)
//============================================================================

void fullMatchNBulk(
    cache::RE2Pattern* pattern,
    const char** texts,
    const int* text_lens,
    int num_texts,
    std::string** captures_array[],
    int n_captures,
    bool* results_out) {

    if (!pattern || !results_out || num_texts <= 0) {
        return;
    }

    // Process each text
    for (int i = 0; i < num_texts; i++) {
        // Handle null text (mark false, continue - partial success)
        if (!texts || !texts[i] || !text_lens || text_lens[i] < 0) {
            results_out[i] = false;
            continue;
        }

        // Handle null captures for this text
        std::string** captures_for_text = (captures_array && captures_array[i]) ? captures_array[i] : nullptr;

        re2::StringPiece sp(texts[i], static_cast<size_t>(text_lens[i]));
        results_out[i] = fullMatchN(pattern, sp, captures_for_text, n_captures);
    }
}

void partialMatchNBulk(
    cache::RE2Pattern* pattern,
    const char** texts,
    const int* text_lens,
    int num_texts,
    std::string** captures_array[],
    int n_captures,
    bool* results_out) {

    if (!pattern || !results_out || num_texts <= 0) {
        return;
    }

    for (int i = 0; i < num_texts; i++) {
        if (!texts || !texts[i] || !text_lens || text_lens[i] < 0) {
            results_out[i] = false;
            continue;
        }

        std::string** captures_for_text = (captures_array && captures_array[i]) ? captures_array[i] : nullptr;

        re2::StringPiece sp(texts[i], static_cast<size_t>(text_lens[i]));
        results_out[i] = partialMatchN(pattern, sp, captures_for_text, n_captures);
    }
}

//============================================================================
// N-VARIANT BULK+DIRECT (Phase 1.2.5a - Zero-Copy + Multiple + Unlimited)
//============================================================================

void fullMatchNDirectBulk(
    cache::RE2Pattern* pattern,
    const int64_t* text_addresses,
    const int* text_lengths,
    int num_texts,
    std::string** captures_array[],
    int n_captures,
    bool* results_out) {

    if (!pattern || !results_out || num_texts <= 0) {
        return;
    }

    for (int i = 0; i < num_texts; i++) {
        if (!text_addresses || text_addresses[i] == 0 || !text_lengths || text_lengths[i] < 0) {
            results_out[i] = false;
            continue;
        }

        std::string** captures_for_text = (captures_array && captures_array[i]) ? captures_array[i] : nullptr;

        results_out[i] = fullMatchNDirect(pattern, text_addresses[i], text_lengths[i],
                                          captures_for_text, n_captures);
    }
}

void partialMatchNDirectBulk(
    cache::RE2Pattern* pattern,
    const int64_t* text_addresses,
    const int* text_lengths,
    int num_texts,
    std::string** captures_array[],
    int n_captures,
    bool* results_out) {

    if (!pattern || !results_out || num_texts <= 0) {
        return;
    }

    for (int i = 0; i < num_texts; i++) {
        if (!text_addresses || text_addresses[i] == 0 || !text_lengths || text_lengths[i] < 0) {
            results_out[i] = false;
            continue;
        }

        std::string** captures_for_text = (captures_array && captures_array[i]) ? captures_array[i] : nullptr;

        results_out[i] = partialMatchNDirect(pattern, text_addresses[i], text_lengths[i],
                                             captures_for_text, n_captures);
    }
}

//============================================================================
// REPLACEMENT FUNCTIONS (Phase 1.2.2)
//============================================================================

bool replace(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string_view rewrite,
    std::string* result_out) {

    if (!pattern || !pattern->isValid() || !result_out) {
        return false;
    }

    // Copy text to mutable string (RE2::Replace modifies in-place)
    std::string mutable_text(text);

    // Call RE2::Replace (modifies mutable_text)
    bool replaced = RE2::Replace(&mutable_text, *pattern->compiled_regex, rewrite);

    // Store result
    *result_out = std::move(mutable_text);

    return replaced;
}

int replaceAll(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string_view rewrite,
    std::string* result_out) {

    if (!pattern || !pattern->isValid() || !result_out) {
        return -1;  // Error
    }

    // Copy text to mutable string (RE2::GlobalReplace modifies in-place)
    std::string mutable_text(text);

    // Call RE2::GlobalReplace (modifies mutable_text, returns count)
    int count = RE2::GlobalReplace(&mutable_text, *pattern->compiled_regex, rewrite);

    // Store result
    *result_out = std::move(mutable_text);

    return count;
}

bool extract(
    cache::RE2Pattern* pattern,
    std::string_view text,
    std::string_view rewrite,
    std::string* result_out) {

    if (!pattern || !pattern->isValid() || !result_out) {
        return false;
    }

    // Call RE2::Extract (writes to result_out directly)
    return RE2::Extract(text, *pattern->compiled_regex, rewrite, result_out);
}

//============================================================================
// BULK OPERATIONS (Phase 1.2.4)
//============================================================================

void fullMatchBulk(
    cache::RE2Pattern* pattern,
    const char** texts,
    const int* text_lens,
    int num_texts,
    bool* results_out) {

    // Validate pattern and arrays
    if (!pattern || !pattern->isValid() || !texts || !text_lens || !results_out) {
        // Mark all as false on validation failure
        if (results_out) {
            for (int i = 0; i < num_texts; i++) {
                results_out[i] = false;
            }
        }
        return;
    }

    // Process all texts (absorbs logic from old JNI)
    for (int i = 0; i < num_texts; i++) {
        // Handle null/invalid inputs gracefully (mark false, continue)
        if (texts[i] == nullptr || text_lens[i] < 0) {
            results_out[i] = false;
            continue;
        }

        // Use string_view for zero-copy (like StringPiece in old JNI)
        std::string_view text(texts[i], text_lens[i]);
        results_out[i] = RE2::FullMatch(text, *pattern->compiled_regex);
    }
}

void partialMatchBulk(
    cache::RE2Pattern* pattern,
    const char** texts,
    const int* text_lens,
    int num_texts,
    bool* results_out) {

    if (!pattern || !pattern->isValid() || !texts || !text_lens || !results_out) {
        if (results_out) {
            for (int i = 0; i < num_texts; i++) {
                results_out[i] = false;
            }
        }
        return;
    }

    for (int i = 0; i < num_texts; i++) {
        if (texts[i] == nullptr || text_lens[i] < 0) {
            results_out[i] = false;
            continue;
        }

        std::string_view text(texts[i], text_lens[i]);
        results_out[i] = RE2::PartialMatch(text, *pattern->compiled_regex);
    }
}

//============================================================================
// DIRECT MEMORY OPERATIONS (Phase 1.2.4 - Zero-Copy)
//============================================================================

bool fullMatchDirect(
    cache::RE2Pattern* pattern,
    int64_t text_address,
    int text_length) {

    // Validate (from old JNI)
    if (!pattern || !pattern->isValid()) {
        return false;
    }

    if (text_address == 0) {
        return false;
    }

    if (text_length < 0) {
        return false;
    }

    // Zero-copy: cast and wrap in StringPiece (from old JNI)
    const char* text = reinterpret_cast<const char*>(text_address);
    re2::StringPiece input(text, static_cast<size_t>(text_length));

    // Call RE2 with StringPiece (no copies)
    return RE2::FullMatch(input, *pattern->compiled_regex);
}

bool partialMatchDirect(
    cache::RE2Pattern* pattern,
    int64_t text_address,
    int text_length) {

    if (!pattern || !pattern->isValid()) {
        return false;
    }

    if (text_address == 0) {
        return false;
    }

    if (text_length < 0) {
        return false;
    }

    // Zero-copy with StringPiece
    const char* text = reinterpret_cast<const char*>(text_address);
    re2::StringPiece input(text, static_cast<size_t>(text_length));

    return RE2::PartialMatch(input, *pattern->compiled_regex);
}

void fullMatchDirectBulk(
    cache::RE2Pattern* pattern,
    const int64_t* text_addresses,
    const int* text_lengths,
    int num_texts,
    bool* results_out) {

    // Validate
    if (!pattern || !pattern->isValid() || !text_addresses || !text_lengths || !results_out) {
        if (results_out) {
            for (int i = 0; i < num_texts; i++) {
                results_out[i] = false;
            }
        }
        return;
    }

    // Process all with zero-copy (from old JNI fullMatchDirectBulk)
    for (int i = 0; i < num_texts; i++) {
        // Validate each address/length
        if (text_addresses[i] == 0 || text_lengths[i] < 0) {
            results_out[i] = false;
            continue;
        }

        // Zero-copy: StringPiece wrap
        const char* text = reinterpret_cast<const char*>(text_addresses[i]);
        re2::StringPiece input(text, static_cast<size_t>(text_lengths[i]));

        results_out[i] = RE2::FullMatch(input, *pattern->compiled_regex);
    }
}

void partialMatchDirectBulk(
    cache::RE2Pattern* pattern,
    const int64_t* text_addresses,
    const int* text_lengths,
    int num_texts,
    bool* results_out) {

    if (!pattern || !pattern->isValid() || !text_addresses || !text_lengths || !results_out) {
        if (results_out) {
            for (int i = 0; i < num_texts; i++) {
                results_out[i] = false;
            }
        }
        return;
    }

    for (int i = 0; i < num_texts; i++) {
        if (text_addresses[i] == 0 || text_lengths[i] < 0) {
            results_out[i] = false;
            continue;
        }

        const char* text = reinterpret_cast<const char*>(text_addresses[i]);
        re2::StringPiece input(text, static_cast<size_t>(text_lengths[i]));

        results_out[i] = RE2::PartialMatch(input, *pattern->compiled_regex);
    }
}

//============================================================================
// UTILITY FUNCTIONS (Phase 1.2.3)
//============================================================================

std::string quoteMeta(std::string_view text) {
    // RE2::QuoteMeta is thread-safe, stateless
    return RE2::QuoteMeta(text);
}

std::string getPatternInfo(cache::RE2Pattern* pattern) {
    if (!pattern) {
        return R"({"valid":false,"error":"Null pattern"})";
    }

    // Build JSON manually (nlohmann::json available if needed, but simple for now)
    std::ostringstream json;
    json << "{";
    json << "\"valid\":" << (pattern->isValid() ? "true" : "false") << ",";
    json << "\"error\":\"" << (pattern->isValid() ? "" : "Compilation failed") << "\",";
    json << "\"pattern\":\"" << pattern->pattern_string << "\",";
    json << "\"case_sensitive\":" << (pattern->case_sensitive ? "true" : "false") << ",";

    if (pattern->isValid()) {
        const RE2* re = pattern->compiled_regex.get();
        json << "\"capturing_groups\":" << re->NumberOfCapturingGroups() << ",";

        // Named groups
        const auto& named_groups = re->NamedCapturingGroups();
        json << "\"named_groups\":{";
        bool first = true;
        for (const auto& [name, index] : named_groups) {
            if (!first) json << ",";
            json << "\"" << name << "\":" << index;
            first = false;
        }
        json << "},";

        // Group names
        const auto& group_names = re->CapturingGroupNames();
        json << "\"group_names\":{";
        first = true;
        for (const auto& [index, name] : group_names) {
            if (!first) json << ",";
            json << "\"" << index << "\":\"" << name << "\"";
            first = false;
        }
        json << "},";

        json << "\"program_size\":" << re->ProgramSize();
    } else {
        json << "\"capturing_groups\":0,";
        json << "\"named_groups\":{},";
        json << "\"group_names\":{},";
        json << "\"program_size\":0";
    }

    json << "}";
    return json.str();
}

bool isPatternValid(cache::RE2Pattern* pattern) {
    return pattern && pattern->isValid();
}

std::string getMetricsJSON() {
    cache::CacheManager* mgr = g_cache_manager.load(std::memory_order_acquire);

    if (!mgr) {
        // Cache not initialized - return empty metrics
        cache::CacheMetrics empty;
        empty.generated_at = std::chrono::system_clock::now();
        return empty.toJson();
    }

    return mgr->getMetricsJSON();
}

//============================================================================
// PATTERN ANALYSIS FUNCTIONS (Phase 1.2.5b)
//============================================================================

int getNumberOfCapturingGroups(cache::RE2Pattern* pattern) {
    if (!pattern || !pattern->isValid()) {
        return -1;
    }
    return pattern->compiled_regex->NumberOfCapturingGroups();
}

std::string getNamedCapturingGroupsJSON(cache::RE2Pattern* pattern) {
    if (!pattern || !pattern->isValid()) {
        return "{}";
    }

    const std::map<std::string, int>& named_groups =
        pattern->compiled_regex->NamedCapturingGroups();

    std::ostringstream json;
    json << "{";
    bool first = true;
    for (const auto& [name, index] : named_groups) {
        if (!first) json << ",";
        json << "\"" << name << "\":" << index;
        first = false;
    }
    json << "}";
    return json.str();
}

std::string getCapturingGroupNamesJSON(cache::RE2Pattern* pattern) {
    if (!pattern || !pattern->isValid()) {
        return "{}";
    }

    const std::map<int, std::string>& group_names =
        pattern->compiled_regex->CapturingGroupNames();

    std::ostringstream json;
    json << "{";
    bool first = true;
    for (const auto& [index, name] : group_names) {
        if (!first) json << ",";
        json << "\"" << index << "\":\"" << name << "\"";
        first = false;
    }
    json << "}";
    return json.str();
}

int getProgramSize(cache::RE2Pattern* pattern) {
    if (!pattern || !pattern->isValid()) {
        return -1;
    }
    return pattern->compiled_regex->ProgramSize();
}

int getReverseProgramSize(cache::RE2Pattern* pattern) {
    if (!pattern || !pattern->isValid()) {
        return -1;
    }
    return pattern->compiled_regex->ReverseProgramSize();
}

//============================================================================
// STATUS/VALIDATION FUNCTIONS (Phase 1.2.5c)
//============================================================================

bool ok(cache::RE2Pattern* pattern) {
    if (!pattern) {
        return false;
    }
    return pattern->compiled_regex->ok();
}

std::string getPattern(cache::RE2Pattern* pattern) {
    if (!pattern || !pattern->compiled_regex) {
        return "";
    }
    return pattern->compiled_regex->pattern();
}

std::string getError(cache::RE2Pattern* pattern) {
    if (!pattern || !pattern->compiled_regex) {
        return "";
    }
    return pattern->compiled_regex->error();
}

int getErrorCode(cache::RE2Pattern* pattern) {
    if (!pattern || !pattern->compiled_regex) {
        return -1;  // Invalid pattern
    }
    return static_cast<int>(pattern->compiled_regex->error_code());
}

std::string getErrorArg(cache::RE2Pattern* pattern) {
    if (!pattern || !pattern->compiled_regex) {
        return "";
    }
    return pattern->compiled_regex->error_arg();
}

//============================================================================
// REWRITE VALIDATION FUNCTIONS (Phase 1.2.5d)
//============================================================================

bool checkRewriteString(
    cache::RE2Pattern* pattern,
    std::string_view rewrite,
    std::string* error_out) {

    if (!pattern || !pattern->isValid()) {
        if (error_out) {
            *error_out = "Invalid pattern";
        }
        return false;
    }

    if (!error_out) {
        // Need error output parameter
        std::string dummy_error;
        return pattern->compiled_regex->CheckRewriteString(rewrite, &dummy_error);
    }

    return pattern->compiled_regex->CheckRewriteString(rewrite, error_out);
}

int maxSubmatch(std::string_view rewrite) {
    // Static method - no pattern needed
    return RE2::MaxSubmatch(rewrite);
}

bool rewrite(
    cache::RE2Pattern* pattern,
    std::string* out,
    std::string_view rewrite,
    const std::string* captures[],
    int n_captures) {

    if (!pattern || !pattern->isValid() || !out) {
        return false;
    }

    if (n_captures < 0) {
        return false;
    }

    // Convert std::string* array to absl::string_view array for RE2
    std::vector<absl::string_view> vec;
    vec.reserve(n_captures);
    for (int i = 0; i < n_captures; i++) {
        if (captures[i]) {
            vec.emplace_back(*captures[i]);
        } else {
            vec.emplace_back("");  // Null capture → empty string
        }
    }

    return pattern->compiled_regex->Rewrite(out, rewrite, vec.data(), n_captures);
}

void initCache(const std::string& json_config) {
    std::lock_guard<std::mutex> lock(g_init_mutex);

    if (g_cache_manager.load(std::memory_order_acquire) != nullptr) {
        throw std::runtime_error("Cache already initialized");
    }

    // Parse config (or use defaults if empty)
    cache::CacheConfig config;
    if (json_config.empty()) {
        // Default configuration (all caching enabled, auto-start eviction)
        std::string default_json = R"({
            "cache_enabled": true,
            "pattern_result_cache_enabled": true,
            "pattern_result_cache_target_capacity_bytes": 104857600,
            "pattern_result_cache_string_threshold_bytes": 10240,
            "pattern_result_cache_ttl_ms": 300000,
            "pattern_result_cache_use_tbb": false,
            "pattern_cache_target_capacity_bytes": 104857600,
            "pattern_cache_ttl_ms": 300000,
            "pattern_cache_use_tbb": false,
            "pattern_cache_lru_batch_size": 100,
            "deferred_cache_ttl_ms": 600000,
            "auto_start_eviction_thread": true,
            "eviction_check_interval_ms": 100
        })";
        config = cache::CacheConfig::fromJson(default_json);
    } else {
        config = cache::CacheConfig::fromJson(json_config);
    }

    // Create cache manager
    cache::CacheManager* new_mgr = new cache::CacheManager(config);
    g_cache_manager.store(new_mgr, std::memory_order_release);
}

void shutdownCache() {
    cache::CacheManager* mgr = g_cache_manager.exchange(nullptr, std::memory_order_acq_rel);

    if (mgr) {
        delete mgr;  // Destructor stops eviction, clears caches
    }
}

bool isCacheInitialized() {
    return g_cache_manager.load(std::memory_order_acquire) != nullptr;
}

}  // namespace api
}  // namespace libre2
