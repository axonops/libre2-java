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

/**
 * C Wrapper for RE2 C++ Library
 *
 * Provides a pure C API for JNA bindings.
 * Compiled into a standalone shared library with statically linked RE2.
 */

#include <re2/re2.h>
#include <string>
#include <cstring>

// Thread-local error storage
static thread_local std::string last_error;

extern "C" {

void* re2_compile(const char* pattern, int pattern_len, int case_sensitive) {
    if (!pattern || pattern_len <= 0) {
        last_error = "Pattern is null or empty";
        return nullptr;
    }

    try {
        RE2::Options options;
        options.set_case_sensitive(case_sensitive != 0);
        options.set_log_errors(false);

        std::string pattern_str(pattern, pattern_len);
        RE2* re = new RE2(pattern_str, options);

        if (!re->ok()) {
            last_error = re->error();
            delete re;
            return nullptr;
        }

        return static_cast<void*>(re);
    } catch (const std::exception& e) {
        last_error = std::string("Exception: ") + e.what();
        return nullptr;
    }
}

void re2_free_pattern(void* pattern) {
    if (pattern) {
        delete static_cast<RE2*>(pattern);
    }
}

int re2_full_match(void* pattern, const char* text, int text_len) {
    if (!pattern || !text) {
        last_error = "Null pointer";
        return -1;
    }

    try {
        RE2* re = static_cast<RE2*>(pattern);
        std::string text_str(text, text_len);
        return RE2::FullMatch(text_str, *re) ? 1 : 0;
    } catch (const std::exception& e) {
        last_error = std::string("Exception: ") + e.what();
        return -1;
    }
}

int re2_partial_match(void* pattern, const char* text, int text_len) {
    if (!pattern || !text) {
        last_error = "Null pointer";
        return -1;
    }

    try {
        RE2* re = static_cast<RE2*>(pattern);
        std::string text_str(text, text_len);
        return RE2::PartialMatch(text_str, *re) ? 1 : 0;
    } catch (const std::exception& e) {
        last_error = std::string("Exception: ") + e.what();
        return -1;
    }
}

const char* re2_get_error() {
    return last_error.empty() ? nullptr : last_error.c_str();
}

const char* re2_get_pattern(void* pattern) {
    if (!pattern) return nullptr;
    try {
        return static_cast<RE2*>(pattern)->pattern().c_str();
    } catch (...) {
        return nullptr;
    }
}

int re2_num_capturing_groups(void* pattern) {
    if (!pattern) {
        last_error = "Pattern is null";
        return -1;
    }
    try {
        return static_cast<RE2*>(pattern)->NumberOfCapturingGroups();
    } catch (const std::exception& e) {
        last_error = std::string("Exception: ") + e.what();
        return -1;
    }
}

int re2_pattern_ok(void* pattern) {
    if (!pattern) return 0;
    try {
        return static_cast<RE2*>(pattern)->ok() ? 1 : 0;
    } catch (...) {
        return 0;
    }
}

} // extern "C"
