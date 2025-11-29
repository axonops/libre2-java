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

#include "pattern_options.h"
#include <nlohmann/json.hpp>
#include <stdexcept>

namespace libre2 {
namespace api {

RE2::Options PatternOptions::toRE2Options() const {
    RE2::Options opts;

    opts.set_posix_syntax(posix_syntax);
    opts.set_longest_match(longest_match);
    opts.set_log_errors(log_errors);
    opts.set_literal(literal);
    opts.set_never_nl(never_nl);
    opts.set_dot_nl(dot_nl);
    opts.set_never_capture(never_capture);
    opts.set_case_sensitive(case_sensitive);
    opts.set_perl_classes(perl_classes);
    opts.set_word_boundary(word_boundary);
    opts.set_one_line(one_line);
    opts.set_encoding(utf8 ? RE2::Options::EncodingUTF8 : RE2::Options::EncodingLatin1);
    opts.set_max_mem(max_mem);

    return opts;
}

uint64_t PatternOptions::hash() const {
    // Lazy initialization - compute once, cache forever
    if (cached_hash == 0) {
        cached_hash = computeHash();
    }
    return cached_hash;
}

uint64_t PatternOptions::computeHash() const {
    uint64_t h = 0;

    // Boolean flags (11 bits: 0-10)
    if (posix_syntax)    h |= (1ULL << 0);
    if (longest_match)   h |= (1ULL << 1);
    if (log_errors)      h |= (1ULL << 2);
    if (literal)         h |= (1ULL << 3);
    if (never_nl)        h |= (1ULL << 4);
    if (dot_nl)          h |= (1ULL << 5);
    if (never_capture)   h |= (1ULL << 6);
    if (case_sensitive)  h |= (1ULL << 7);
    if (perl_classes)    h |= (1ULL << 8);
    if (word_boundary)   h |= (1ULL << 9);
    if (one_line)        h |= (1ULL << 10);

    // Encoding (bit 11: 0=Latin1, 1=UTF8)
    if (utf8) h |= (1ULL << 11);

    // max_mem (bits 13-44: use lower 32 bits of max_mem)
    h |= ((uint64_t)(max_mem & 0xFFFFFFFF) << 13);

    // Ensure we never return 0 (0 means "not computed yet")
    if (h == 0) h = 1;

    return h;
}

uint64_t PatternOptions::hashFromRE2Options(const RE2::Options& opts) {
    uint64_t h = 0;

    // Boolean flags
    if (opts.posix_syntax())    h |= (1ULL << 0);
    if (opts.longest_match())   h |= (1ULL << 1);
    if (opts.log_errors())      h |= (1ULL << 2);
    if (opts.literal())         h |= (1ULL << 3);
    if (opts.never_nl())        h |= (1ULL << 4);
    if (opts.dot_nl())          h |= (1ULL << 5);
    if (opts.never_capture())   h |= (1ULL << 6);
    if (opts.case_sensitive())  h |= (1ULL << 7);
    if (opts.perl_classes())    h |= (1ULL << 8);
    if (opts.word_boundary())   h |= (1ULL << 9);
    if (opts.one_line())        h |= (1ULL << 10);

    // Encoding
    if (opts.encoding() == RE2::Options::EncodingUTF8) h |= (1ULL << 11);

    // max_mem
    h |= ((uint64_t)(opts.max_mem() & 0xFFFFFFFF) << 13);

    return h;
}

PatternOptions PatternOptions::fromJson(const std::string& json) {
    if (json.empty()) {
        return defaults();
    }

    try {
        nlohmann::json j = nlohmann::json::parse(json);

        PatternOptions opts = defaults();

        // Parse each field (all optional)
        if (j.contains("case_sensitive"))  opts.case_sensitive = j["case_sensitive"];
        if (j.contains("posix_syntax"))    opts.posix_syntax = j["posix_syntax"];
        if (j.contains("longest_match"))   opts.longest_match = j["longest_match"];
        if (j.contains("literal"))         opts.literal = j["literal"];
        if (j.contains("never_nl"))        opts.never_nl = j["never_nl"];
        if (j.contains("dot_nl"))          opts.dot_nl = j["dot_nl"];
        if (j.contains("never_capture"))   opts.never_capture = j["never_capture"];
        if (j.contains("perl_classes"))    opts.perl_classes = j["perl_classes"];
        if (j.contains("word_boundary"))   opts.word_boundary = j["word_boundary"];
        if (j.contains("one_line"))        opts.one_line = j["one_line"];
        if (j.contains("max_mem"))         opts.max_mem = j["max_mem"];

        // Encoding (accept "UTF8" or "Latin1" string)
        if (j.contains("encoding")) {
            std::string encoding = j["encoding"];
            opts.utf8 = (encoding == "UTF8");
        }

        return opts;

    } catch (const nlohmann::json::exception& e) {
        throw std::runtime_error(std::string("Invalid options JSON: ") + e.what());
    }
}

PatternOptions PatternOptions::defaults() {
    PatternOptions opts;
    // All fields already initialized with defaults in struct definition
    return opts;
}

PatternOptions PatternOptions::fromCaseSensitive(bool case_sensitive) {
    PatternOptions opts = defaults();
    opts.case_sensitive = case_sensitive;
    return opts;
}

}  // namespace api
}  // namespace libre2
