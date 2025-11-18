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
 * JNI Wrapper for RE2 C++ Library
 *
 * Provides JNI bindings for high-performance native regex operations.
 * Uses JNI instead of JNA for reduced call overhead (~50ns vs ~200ns).
 */

#include <jni.h>
#include <re2/re2.h>
#include <string>
#include "com_axonops_libre2_jni_RE2NativeJNI.h"

// Thread-local error storage
static thread_local std::string last_error;

/**
 * Helper to get UTF-8 string from Java string with RAII cleanup.
 */
class JStringGuard {
public:
    JStringGuard(JNIEnv* env, jstring str) : env_(env), str_(str), chars_(nullptr) {
        if (str != nullptr) {
            chars_ = env->GetStringUTFChars(str, nullptr);
        }
    }

    ~JStringGuard() {
        if (chars_ != nullptr) {
            env_->ReleaseStringUTFChars(str_, chars_);
        }
    }

    const char* get() const { return chars_; }
    bool valid() const { return chars_ != nullptr; }

private:
    JNIEnv* env_;
    jstring str_;
    const char* chars_;

    // Non-copyable
    JStringGuard(const JStringGuard&) = delete;
    JStringGuard& operator=(const JStringGuard&) = delete;
};

extern "C" {

JNIEXPORT jlong JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_compile(
    JNIEnv *env, jclass cls, jstring pattern, jboolean caseSensitive) {

    if (pattern == nullptr) {
        last_error = "Pattern is null";
        return 0;
    }

    JStringGuard guard(env, pattern);
    if (!guard.valid()) {
        last_error = "Failed to get pattern string";
        return 0;
    }

    try {
        RE2::Options options;
        options.set_case_sensitive(caseSensitive == JNI_TRUE);
        options.set_log_errors(false);

        RE2* re = new RE2(guard.get(), options);

        if (!re->ok()) {
            last_error = re->error();
            delete re;
            return 0;
        }

        return reinterpret_cast<jlong>(re);
    } catch (const std::exception& e) {
        last_error = std::string("Exception: ") + e.what();
        return 0;
    }
}

JNIEXPORT void JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_freePattern(
    JNIEnv *env, jclass cls, jlong handle) {

    if (handle != 0) {
        delete reinterpret_cast<RE2*>(handle);
    }
}

JNIEXPORT jboolean JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_fullMatch(
    JNIEnv *env, jclass cls, jlong handle, jstring text) {

    if (handle == 0 || text == nullptr) {
        last_error = "Null pointer";
        return JNI_FALSE;
    }

    JStringGuard guard(env, text);
    if (!guard.valid()) {
        last_error = "Failed to get text string";
        return JNI_FALSE;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        return RE2::FullMatch(guard.get(), *re) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        last_error = std::string("Exception: ") + e.what();
        return JNI_FALSE;
    }
}

JNIEXPORT jboolean JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_partialMatch(
    JNIEnv *env, jclass cls, jlong handle, jstring text) {

    if (handle == 0 || text == nullptr) {
        last_error = "Null pointer";
        return JNI_FALSE;
    }

    JStringGuard guard(env, text);
    if (!guard.valid()) {
        last_error = "Failed to get text string";
        return JNI_FALSE;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        return RE2::PartialMatch(guard.get(), *re) ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        last_error = std::string("Exception: ") + e.what();
        return JNI_FALSE;
    }
}

JNIEXPORT jstring JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_getError(
    JNIEnv *env, jclass cls) {

    if (last_error.empty()) {
        return nullptr;
    }
    return env->NewStringUTF(last_error.c_str());
}

JNIEXPORT jstring JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_getPattern(
    JNIEnv *env, jclass cls, jlong handle) {

    if (handle == 0) {
        return nullptr;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        return env->NewStringUTF(re->pattern().c_str());
    } catch (...) {
        return nullptr;
    }
}

JNIEXPORT jint JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_numCapturingGroups(
    JNIEnv *env, jclass cls, jlong handle) {

    if (handle == 0) {
        last_error = "Pattern is null";
        return -1;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        return static_cast<jint>(re->NumberOfCapturingGroups());
    } catch (const std::exception& e) {
        last_error = std::string("Exception: ") + e.what();
        return -1;
    }
}

JNIEXPORT jboolean JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_patternOk(
    JNIEnv *env, jclass cls, jlong handle) {

    if (handle == 0) {
        return JNI_FALSE;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        return re->ok() ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

JNIEXPORT jlong JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_patternMemory(
    JNIEnv *env, jclass cls, jlong handle) {

    if (handle == 0) {
        return 0;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        return static_cast<jlong>(re->ProgramSize());
    } catch (...) {
        return 0;
    }
}

} // extern "C"
