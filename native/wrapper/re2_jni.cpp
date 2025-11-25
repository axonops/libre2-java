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

// ========== Bulk Matching Operations ==========

JNIEXPORT jbooleanArray JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_fullMatchBulk(
    JNIEnv *env, jclass cls, jlong handle, jobjectArray texts) {

    if (handle == 0 || texts == nullptr) {
        last_error = "Null pointer";
        return nullptr;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        jsize length = env->GetArrayLength(texts);

        // Allocate result array
        jbooleanArray results = env->NewBooleanArray(length);
        if (results == nullptr) {
            last_error = "Failed to allocate result array";
            return nullptr;
        }

        // Process all strings in native code (single JNI crossing)
        std::vector<jboolean> matches(length);
        for (jsize i = 0; i < length; i++) {
            jstring jstr = (jstring)env->GetObjectArrayElement(texts, i);
            if (jstr == nullptr) {
                matches[i] = JNI_FALSE;
                continue;
            }

            JStringGuard guard(env, jstr);
            if (guard.valid()) {
                matches[i] = RE2::FullMatch(guard.get(), *re) ? JNI_TRUE : JNI_FALSE;
            } else {
                matches[i] = JNI_FALSE;
            }

            env->DeleteLocalRef(jstr);
        }

        // Write results back to Java
        env->SetBooleanArrayRegion(results, 0, length, matches.data());
        return results;

    } catch (const std::exception& e) {
        last_error = std::string("Bulk match exception: ") + e.what();
        return nullptr;
    }
}

JNIEXPORT jbooleanArray JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_partialMatchBulk(
    JNIEnv *env, jclass cls, jlong handle, jobjectArray texts) {

    if (handle == 0 || texts == nullptr) {
        last_error = "Null pointer";
        return nullptr;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        jsize length = env->GetArrayLength(texts);

        jbooleanArray results = env->NewBooleanArray(length);
        if (results == nullptr) {
            last_error = "Failed to allocate result array";
            return nullptr;
        }

        std::vector<jboolean> matches(length);
        for (jsize i = 0; i < length; i++) {
            jstring jstr = (jstring)env->GetObjectArrayElement(texts, i);
            if (jstr == nullptr) {
                matches[i] = JNI_FALSE;
                continue;
            }

            JStringGuard guard(env, jstr);
            if (guard.valid()) {
                matches[i] = RE2::PartialMatch(guard.get(), *re) ? JNI_TRUE : JNI_FALSE;
            } else {
                matches[i] = JNI_FALSE;
            }

            env->DeleteLocalRef(jstr);
        }

        env->SetBooleanArrayRegion(results, 0, length, matches.data());
        return results;

    } catch (const std::exception& e) {
        last_error = std::string("Bulk partial match exception: ") + e.what();
        return nullptr;
    }
}

// ========== Capture Group Operations ==========

JNIEXPORT jobjectArray JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_extractGroups(
    JNIEnv *env, jclass cls, jlong handle, jstring text) {

    if (handle == 0 || text == nullptr) {
        return nullptr;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        JStringGuard guard(env, text);
        if (!guard.valid()) {
            return nullptr;
        }

        int numGroups = re->NumberOfCapturingGroups();
        std::vector<re2::StringPiece> groups(numGroups + 1);  // +1 for full match

        // Match and extract groups
        if (!re->Match(guard.get(), 0, strlen(guard.get()), RE2::UNANCHORED, groups.data(), numGroups + 1)) {
            return nullptr;  // No match
        }

        // Create Java string array
        jclass stringClass = env->FindClass("java/lang/String");
        jobjectArray result = env->NewObjectArray(numGroups + 1, stringClass, nullptr);
        if (result == nullptr) {
            return nullptr;
        }

        // Fill array with groups
        for (int i = 0; i <= numGroups; i++) {
            if (groups[i].data() != nullptr) {
                jstring jstr = env->NewStringUTF(std::string(groups[i].data(), groups[i].size()).c_str());
                env->SetObjectArrayElement(result, i, jstr);
                env->DeleteLocalRef(jstr);
            }
        }

        return result;

    } catch (const std::exception& e) {
        last_error = std::string("Extract groups exception: ") + e.what();
        return nullptr;
    }
}

JNIEXPORT jobjectArray JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_extractGroupsBulk(
    JNIEnv *env, jclass cls, jlong handle, jobjectArray texts) {

    if (handle == 0 || texts == nullptr) {
        return nullptr;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        jsize length = env->GetArrayLength(texts);
        int numGroups = re->NumberOfCapturingGroups();

        // Create outer array (one element per input text)
        jclass stringArrayClass = env->FindClass("[Ljava/lang/String;");
        jobjectArray result = env->NewObjectArray(length, stringArrayClass, nullptr);
        if (result == nullptr) {
            return nullptr;
        }

        // Process each text
        for (jsize i = 0; i < length; i++) {
            jstring jstr = (jstring)env->GetObjectArrayElement(texts, i);
            if (jstr == nullptr) {
                continue;
            }

            JStringGuard guard(env, jstr);
            if (!guard.valid()) {
                env->DeleteLocalRef(jstr);
                continue;
            }

            std::vector<re2::StringPiece> groups(numGroups + 1);
            if (re->Match(guard.get(), 0, strlen(guard.get()), RE2::UNANCHORED, groups.data(), numGroups + 1)) {
                // Create string array for this match's groups
                jclass stringClass = env->FindClass("java/lang/String");
                jobjectArray groupArray = env->NewObjectArray(numGroups + 1, stringClass, nullptr);

                for (int j = 0; j <= numGroups; j++) {
                    if (groups[j].data() != nullptr) {
                        jstring groupStr = env->NewStringUTF(std::string(groups[j].data(), groups[j].size()).c_str());
                        env->SetObjectArrayElement(groupArray, j, groupStr);
                        env->DeleteLocalRef(groupStr);
                    }
                }

                env->SetObjectArrayElement(result, i, groupArray);
                env->DeleteLocalRef(groupArray);
            }

            env->DeleteLocalRef(jstr);
        }

        return result;

    } catch (const std::exception& e) {
        last_error = std::string("Extract groups bulk exception: ") + e.what();
        return nullptr;
    }
}

JNIEXPORT jobjectArray JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_findAllMatches(
    JNIEnv *env, jclass cls, jlong handle, jstring text) {

    if (handle == 0 || text == nullptr) {
        return nullptr;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        JStringGuard guard(env, text);
        if (!guard.valid()) {
            return nullptr;
        }

        int numGroups = re->NumberOfCapturingGroups();
        std::vector<std::vector<std::string>> allMatches;

        // Find all non-overlapping matches
        re2::StringPiece input(guard.get());
        std::vector<re2::StringPiece> groups(numGroups + 1);

        while (re->Match(input, 0, input.size(), RE2::UNANCHORED, groups.data(), numGroups + 1)) {
            std::vector<std::string> matchGroups;
            for (int i = 0; i <= numGroups; i++) {
                if (groups[i].data() != nullptr) {
                    matchGroups.push_back(std::string(groups[i].data(), groups[i].size()));
                } else {
                    matchGroups.push_back("");
                }
            }
            allMatches.push_back(matchGroups);

            // Advance past this match
            if (groups[0].size() == 0) {
                break;  // Avoid infinite loop on zero-length match
            }
            input.remove_prefix(groups[0].data() - input.data() + groups[0].size());
        }

        if (allMatches.empty()) {
            return nullptr;
        }

        // Create Java array of arrays
        jclass stringArrayClass = env->FindClass("[Ljava/lang/String;");
        jobjectArray result = env->NewObjectArray(allMatches.size(), stringArrayClass, nullptr);

        for (size_t i = 0; i < allMatches.size(); i++) {
            jclass stringClass = env->FindClass("java/lang/String");
            jobjectArray groupArray = env->NewObjectArray(allMatches[i].size(), stringClass, nullptr);

            for (size_t j = 0; j < allMatches[i].size(); j++) {
                jstring jstr = env->NewStringUTF(allMatches[i][j].c_str());
                env->SetObjectArrayElement(groupArray, j, jstr);
                env->DeleteLocalRef(jstr);
            }

            env->SetObjectArrayElement(result, i, groupArray);
            env->DeleteLocalRef(groupArray);
        }

        return result;

    } catch (const std::exception& e) {
        last_error = std::string("Find all matches exception: ") + e.what();
        return nullptr;
    }
}

JNIEXPORT jobjectArray JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_getNamedGroups(
    JNIEnv *env, jclass cls, jlong handle) {

    if (handle == 0) {
        return nullptr;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        const std::map<std::string, int>& namedGroups = re->NamedCapturingGroups();

        if (namedGroups.empty()) {
            return nullptr;
        }

        // Flatten to array: [name1, index1_as_string, name2, index2_as_string, ...]
        jclass stringClass = env->FindClass("java/lang/String");
        jobjectArray result = env->NewObjectArray(namedGroups.size() * 2, stringClass, nullptr);

        int idx = 0;
        for (const auto& entry : namedGroups) {
            jstring name = env->NewStringUTF(entry.first.c_str());
            jstring index = env->NewStringUTF(std::to_string(entry.second).c_str());

            env->SetObjectArrayElement(result, idx++, name);
            env->SetObjectArrayElement(result, idx++, index);

            env->DeleteLocalRef(name);
            env->DeleteLocalRef(index);
        }

        return result;

    } catch (const std::exception& e) {
        last_error = std::string("Get named groups exception: ") + e.what();
        return nullptr;
    }
}

// ========== Replace Operations ==========

JNIEXPORT jstring JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_replaceFirst(
    JNIEnv *env, jclass cls, jlong handle, jstring text, jstring replacement) {

    if (handle == 0 || text == nullptr || replacement == nullptr) {
        return text;  // Return original if invalid
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        JStringGuard textGuard(env, text);
        JStringGuard replGuard(env, replacement);

        if (!textGuard.valid() || !replGuard.valid()) {
            return text;
        }

        std::string result(textGuard.get());
        RE2::Replace(&result, *re, replGuard.get());

        return env->NewStringUTF(result.c_str());

    } catch (const std::exception& e) {
        last_error = std::string("Replace first exception: ") + e.what();
        return text;
    }
}

JNIEXPORT jstring JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_replaceAll(
    JNIEnv *env, jclass cls, jlong handle, jstring text, jstring replacement) {

    if (handle == 0 || text == nullptr || replacement == nullptr) {
        return text;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        JStringGuard textGuard(env, text);
        JStringGuard replGuard(env, replacement);

        if (!textGuard.valid() || !replGuard.valid()) {
            return text;
        }

        std::string result(textGuard.get());
        RE2::GlobalReplace(&result, *re, replGuard.get());

        return env->NewStringUTF(result.c_str());

    } catch (const std::exception& e) {
        last_error = std::string("Replace all exception: ") + e.what();
        return text;
    }
}

JNIEXPORT jobjectArray JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_replaceAllBulk(
    JNIEnv *env, jclass cls, jlong handle, jobjectArray texts, jstring replacement) {

    if (handle == 0 || texts == nullptr || replacement == nullptr) {
        return texts;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        JStringGuard replGuard(env, replacement);
        if (!replGuard.valid()) {
            return texts;
        }

        jsize length = env->GetArrayLength(texts);
        jclass stringClass = env->FindClass("java/lang/String");
        jobjectArray result = env->NewObjectArray(length, stringClass, nullptr);

        for (jsize i = 0; i < length; i++) {
            jstring jstr = (jstring)env->GetObjectArrayElement(texts, i);
            if (jstr == nullptr) {
                continue;
            }

            JStringGuard textGuard(env, jstr);
            if (textGuard.valid()) {
                std::string replaced(textGuard.get());
                RE2::GlobalReplace(&replaced, *re, replGuard.get());

                jstring resultStr = env->NewStringUTF(replaced.c_str());
                env->SetObjectArrayElement(result, i, resultStr);
                env->DeleteLocalRef(resultStr);
            } else {
                env->SetObjectArrayElement(result, i, jstr);
            }

            env->DeleteLocalRef(jstr);
        }

        return result;

    } catch (const std::exception& e) {
        last_error = std::string("Replace all bulk exception: ") + e.what();
        return texts;
    }
}

/**
 * Replace first match using direct memory address (zero-copy).
 * Uses StringPiece to wrap the raw pointer without copying for input.
 * Note: Output must be copied to std::string since Replace modifies in place.
 */
JNIEXPORT jstring JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_replaceFirstDirect(
    JNIEnv *env, jclass cls, jlong handle, jlong textAddress, jint textLength, jstring replacement) {

    if (handle == 0) {
        last_error = "Pattern handle is null";
        return nullptr;
    }

    if (textAddress == 0) {
        last_error = "Text address is null";
        return nullptr;
    }

    if (textLength < 0) {
        last_error = "Text length is negative";
        return nullptr;
    }

    if (replacement == nullptr) {
        last_error = "Replacement string is null";
        return nullptr;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        JStringGuard replGuard(env, replacement);

        if (!replGuard.valid()) {
            return nullptr;
        }

        // Zero-copy input: wrap the raw pointer in StringPiece
        const char* text = reinterpret_cast<const char*>(textAddress);
        re2::StringPiece input(text, static_cast<size_t>(textLength));

        // Copy to std::string since Replace modifies in place
        std::string result(input.data(), input.size());
        RE2::Replace(&result, *re, replGuard.get());

        return env->NewStringUTF(result.c_str());

    } catch (const std::exception& e) {
        last_error = std::string("Direct replace first exception: ") + e.what();
        return nullptr;
    }
}

/**
 * Replace all matches using direct memory address (zero-copy).
 * Uses StringPiece to wrap the raw pointer without copying for input.
 * Note: Output must be copied to std::string since GlobalReplace modifies in place.
 */
JNIEXPORT jstring JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_replaceAllDirect(
    JNIEnv *env, jclass cls, jlong handle, jlong textAddress, jint textLength, jstring replacement) {

    if (handle == 0) {
        last_error = "Pattern handle is null";
        return nullptr;
    }

    if (textAddress == 0) {
        last_error = "Text address is null";
        return nullptr;
    }

    if (textLength < 0) {
        last_error = "Text length is negative";
        return nullptr;
    }

    if (replacement == nullptr) {
        last_error = "Replacement string is null";
        return nullptr;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        JStringGuard replGuard(env, replacement);

        if (!replGuard.valid()) {
            return nullptr;
        }

        // Zero-copy input: wrap the raw pointer in StringPiece
        const char* text = reinterpret_cast<const char*>(textAddress);
        re2::StringPiece input(text, static_cast<size_t>(textLength));

        // Copy to std::string since GlobalReplace modifies in place
        std::string result(input.data(), input.size());
        RE2::GlobalReplace(&result, *re, replGuard.get());

        return env->NewStringUTF(result.c_str());

    } catch (const std::exception& e) {
        last_error = std::string("Direct replace all exception: ") + e.what();
        return nullptr;
    }
}

/**
 * Bulk replace all using direct memory addresses (zero-copy bulk).
 * Processes multiple memory regions in a single JNI call.
 */
JNIEXPORT jobjectArray JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_replaceAllDirectBulk(
    JNIEnv *env, jclass cls, jlong handle, jlongArray textAddresses, jintArray textLengths, jstring replacement) {

    if (handle == 0 || textAddresses == nullptr || textLengths == nullptr || replacement == nullptr) {
        last_error = "Invalid arguments for bulk direct replace";
        return nullptr;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);

        jsize addressCount = env->GetArrayLength(textAddresses);
        jsize lengthCount = env->GetArrayLength(textLengths);

        if (addressCount != lengthCount) {
            last_error = "Address and length arrays must have same length";
            return nullptr;
        }

        JStringGuard replGuard(env, replacement);
        if (!replGuard.valid()) {
            return nullptr;
        }

        jlong* addresses = env->GetLongArrayElements(textAddresses, nullptr);
        jint* lengths = env->GetIntArrayElements(textLengths, nullptr);

        if (addresses == nullptr || lengths == nullptr) {
            if (addresses != nullptr) env->ReleaseLongArrayElements(textAddresses, addresses, JNI_ABORT);
            if (lengths != nullptr) env->ReleaseIntArrayElements(textLengths, lengths, JNI_ABORT);
            last_error = "Failed to get array elements";
            return nullptr;
        }

        jclass stringClass = env->FindClass("java/lang/String");
        jobjectArray results = env->NewObjectArray(addressCount, stringClass, nullptr);

        for (jsize i = 0; i < addressCount; i++) {
            if (addresses[i] == 0 || lengths[i] < 0) {
                // Skip invalid entries
                env->SetObjectArrayElement(results, i, nullptr);
                continue;
            }

            // Zero-copy input: wrap raw pointer in StringPiece
            const char* text = reinterpret_cast<const char*>(addresses[i]);
            re2::StringPiece input(text, static_cast<size_t>(lengths[i]));

            // Copy to std::string for modification
            std::string result(input.data(), input.size());
            RE2::GlobalReplace(&result, *re, replGuard.get());

            jstring resultStr = env->NewStringUTF(result.c_str());
            env->SetObjectArrayElement(results, i, resultStr);
            env->DeleteLocalRef(resultStr);
        }

        env->ReleaseLongArrayElements(textAddresses, addresses, JNI_ABORT);
        env->ReleaseIntArrayElements(textLengths, lengths, JNI_ABORT);

        return results;

    } catch (const std::exception& e) {
        last_error = std::string("Direct bulk replace all exception: ") + e.what();
        return nullptr;
    }
}

// ========== Utility Operations ==========

JNIEXPORT jstring JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_quoteMeta(
    JNIEnv *env, jclass cls, jstring text) {

    if (text == nullptr) {
        return nullptr;
    }

    try {
        JStringGuard guard(env, text);
        if (!guard.valid()) {
            return nullptr;
        }

        std::string escaped = RE2::QuoteMeta(guard.get());
        return env->NewStringUTF(escaped.c_str());

    } catch (const std::exception& e) {
        last_error = std::string("Quote meta exception: ") + e.what();
        return nullptr;
    }
}

JNIEXPORT jintArray JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_programFanout(
    JNIEnv *env, jclass cls, jlong handle) {

    if (handle == 0) {
        return nullptr;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        std::vector<int> histogram;

        // Get fanout histogram (RE2 fills the vector)
        int numFanout = re->ProgramFanout(&histogram);
        if (numFanout == 0 || histogram.empty()) {
            return nullptr;
        }

        // Convert vector<int> to Java int array
        jintArray result = env->NewIntArray(histogram.size());
        if (result == nullptr) {
            return nullptr;
        }

        // Copy data (cast to jint if needed)
        std::vector<jint> jintData(histogram.begin(), histogram.end());
        env->SetIntArrayRegion(result, 0, jintData.size(), jintData.data());
        return result;

    } catch (const std::exception& e) {
        last_error = std::string("Program fanout exception: ") + e.what();
        return nullptr;
    }
}

// ========== Zero-Copy Direct Memory Operations ==========
//
// These methods accept raw memory addresses instead of Java Strings,
// enabling true zero-copy regex matching with Chronicle Bytes or
// other off-heap memory systems.
//
// The memory at the provided address is wrapped in RE2::StringPiece
// which is a zero-copy string view - no data is copied.
//
// CRITICAL: The caller MUST ensure the memory remains valid for
// the duration of the call.

/**
 * Full match using direct memory address (zero-copy).
 * Uses StringPiece to wrap the raw pointer without copying.
 */
JNIEXPORT jboolean JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_fullMatchDirect(
    JNIEnv *env, jclass cls, jlong handle, jlong textAddress, jint textLength) {

    if (handle == 0) {
        last_error = "Pattern handle is null";
        return JNI_FALSE;
    }

    if (textAddress == 0) {
        last_error = "Text address is null";
        return JNI_FALSE;
    }

    if (textLength < 0) {
        last_error = "Text length is negative";
        return JNI_FALSE;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);

        // Zero-copy: wrap the raw pointer in StringPiece
        // StringPiece does NOT copy data - it's just a pointer + length
        const char* text = reinterpret_cast<const char*>(textAddress);
        re2::StringPiece input(text, static_cast<size_t>(textLength));

        // Use RE2::FullMatch with StringPiece - no copies involved
        return RE2::FullMatch(input, *re) ? JNI_TRUE : JNI_FALSE;

    } catch (const std::exception& e) {
        last_error = std::string("Direct full match exception: ") + e.what();
        return JNI_FALSE;
    }
}

/**
 * Partial match using direct memory address (zero-copy).
 * Uses StringPiece to wrap the raw pointer without copying.
 */
JNIEXPORT jboolean JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_partialMatchDirect(
    JNIEnv *env, jclass cls, jlong handle, jlong textAddress, jint textLength) {

    if (handle == 0) {
        last_error = "Pattern handle is null";
        return JNI_FALSE;
    }

    if (textAddress == 0) {
        last_error = "Text address is null";
        return JNI_FALSE;
    }

    if (textLength < 0) {
        last_error = "Text length is negative";
        return JNI_FALSE;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);

        // Zero-copy: wrap the raw pointer in StringPiece
        const char* text = reinterpret_cast<const char*>(textAddress);
        re2::StringPiece input(text, static_cast<size_t>(textLength));

        // Use RE2::PartialMatch with StringPiece - no copies involved
        return RE2::PartialMatch(input, *re) ? JNI_TRUE : JNI_FALSE;

    } catch (const std::exception& e) {
        last_error = std::string("Direct partial match exception: ") + e.what();
        return JNI_FALSE;
    }
}

/**
 * Bulk full match using direct memory addresses (zero-copy bulk).
 * Processes multiple memory regions in a single JNI call.
 */
JNIEXPORT jbooleanArray JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_fullMatchDirectBulk(
    JNIEnv *env, jclass cls, jlong handle, jlongArray textAddresses, jintArray textLengths) {

    if (handle == 0 || textAddresses == nullptr || textLengths == nullptr) {
        last_error = "Null pointer";
        return nullptr;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        jsize addressCount = env->GetArrayLength(textAddresses);
        jsize lengthCount = env->GetArrayLength(textLengths);

        if (addressCount != lengthCount) {
            last_error = "Address and length arrays must have same size";
            return nullptr;
        }

        // Allocate result array
        jbooleanArray results = env->NewBooleanArray(addressCount);
        if (results == nullptr) {
            last_error = "Failed to allocate result array";
            return nullptr;
        }

        // Get array elements (this does copy the arrays, but not the text data)
        jlong* addresses = env->GetLongArrayElements(textAddresses, nullptr);
        jint* lengths = env->GetIntArrayElements(textLengths, nullptr);

        if (addresses == nullptr || lengths == nullptr) {
            if (addresses != nullptr) env->ReleaseLongArrayElements(textAddresses, addresses, JNI_ABORT);
            if (lengths != nullptr) env->ReleaseIntArrayElements(textLengths, lengths, JNI_ABORT);
            last_error = "Failed to get array elements";
            return nullptr;
        }

        // Process all inputs with zero-copy text access
        std::vector<jboolean> matches(addressCount);
        for (jsize i = 0; i < addressCount; i++) {
            if (addresses[i] == 0 || lengths[i] < 0) {
                matches[i] = JNI_FALSE;
                continue;
            }

            // Zero-copy: wrap each address in StringPiece
            const char* text = reinterpret_cast<const char*>(addresses[i]);
            re2::StringPiece input(text, static_cast<size_t>(lengths[i]));
            matches[i] = RE2::FullMatch(input, *re) ? JNI_TRUE : JNI_FALSE;
        }

        // Release arrays and write results
        env->ReleaseLongArrayElements(textAddresses, addresses, JNI_ABORT);
        env->ReleaseIntArrayElements(textLengths, lengths, JNI_ABORT);
        env->SetBooleanArrayRegion(results, 0, addressCount, matches.data());

        return results;

    } catch (const std::exception& e) {
        last_error = std::string("Direct bulk full match exception: ") + e.what();
        return nullptr;
    }
}

/**
 * Bulk partial match using direct memory addresses (zero-copy bulk).
 * Processes multiple memory regions in a single JNI call.
 */
JNIEXPORT jbooleanArray JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_partialMatchDirectBulk(
    JNIEnv *env, jclass cls, jlong handle, jlongArray textAddresses, jintArray textLengths) {

    if (handle == 0 || textAddresses == nullptr || textLengths == nullptr) {
        last_error = "Null pointer";
        return nullptr;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);
        jsize addressCount = env->GetArrayLength(textAddresses);
        jsize lengthCount = env->GetArrayLength(textLengths);

        if (addressCount != lengthCount) {
            last_error = "Address and length arrays must have same size";
            return nullptr;
        }

        // Allocate result array
        jbooleanArray results = env->NewBooleanArray(addressCount);
        if (results == nullptr) {
            last_error = "Failed to allocate result array";
            return nullptr;
        }

        // Get array elements
        jlong* addresses = env->GetLongArrayElements(textAddresses, nullptr);
        jint* lengths = env->GetIntArrayElements(textLengths, nullptr);

        if (addresses == nullptr || lengths == nullptr) {
            if (addresses != nullptr) env->ReleaseLongArrayElements(textAddresses, addresses, JNI_ABORT);
            if (lengths != nullptr) env->ReleaseIntArrayElements(textLengths, lengths, JNI_ABORT);
            last_error = "Failed to get array elements";
            return nullptr;
        }

        // Process all inputs with zero-copy text access
        std::vector<jboolean> matches(addressCount);
        for (jsize i = 0; i < addressCount; i++) {
            if (addresses[i] == 0 || lengths[i] < 0) {
                matches[i] = JNI_FALSE;
                continue;
            }

            // Zero-copy: wrap each address in StringPiece
            const char* text = reinterpret_cast<const char*>(addresses[i]);
            re2::StringPiece input(text, static_cast<size_t>(lengths[i]));
            matches[i] = RE2::PartialMatch(input, *re) ? JNI_TRUE : JNI_FALSE;
        }

        // Release arrays and write results
        env->ReleaseLongArrayElements(textAddresses, addresses, JNI_ABORT);
        env->ReleaseIntArrayElements(textLengths, lengths, JNI_ABORT);
        env->SetBooleanArrayRegion(results, 0, addressCount, matches.data());

        return results;

    } catch (const std::exception& e) {
        last_error = std::string("Direct bulk partial match exception: ") + e.what();
        return nullptr;
    }
}

/**
 * Extract capture groups using direct memory address (zero-copy input).
 * Output strings are necessarily new Java strings.
 */
JNIEXPORT jobjectArray JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_extractGroupsDirect(
    JNIEnv *env, jclass cls, jlong handle, jlong textAddress, jint textLength) {

    if (handle == 0 || textAddress == 0) {
        return nullptr;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);

        // Zero-copy: wrap the raw pointer in StringPiece
        const char* text = reinterpret_cast<const char*>(textAddress);
        re2::StringPiece input(text, static_cast<size_t>(textLength));

        int numGroups = re->NumberOfCapturingGroups();
        std::vector<re2::StringPiece> groups(numGroups + 1);  // +1 for full match

        // Match and extract groups
        if (!re->Match(input, 0, input.size(), RE2::UNANCHORED, groups.data(), numGroups + 1)) {
            return nullptr;  // No match
        }

        // Create Java string array
        jclass stringClass = env->FindClass("java/lang/String");
        jobjectArray result = env->NewObjectArray(numGroups + 1, stringClass, nullptr);
        if (result == nullptr) {
            return nullptr;
        }

        // Fill array with groups (output must be Java strings)
        for (int i = 0; i <= numGroups; i++) {
            if (groups[i].data() != nullptr) {
                jstring jstr = env->NewStringUTF(std::string(groups[i].data(), groups[i].size()).c_str());
                env->SetObjectArrayElement(result, i, jstr);
                env->DeleteLocalRef(jstr);
            }
        }

        return result;

    } catch (const std::exception& e) {
        last_error = std::string("Direct extract groups exception: ") + e.what();
        return nullptr;
    }
}

/**
 * Find all matches using direct memory address (zero-copy input).
 * Output strings are necessarily new Java strings.
 */
JNIEXPORT jobjectArray JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_findAllMatchesDirect(
    JNIEnv *env, jclass cls, jlong handle, jlong textAddress, jint textLength) {

    if (handle == 0 || textAddress == 0) {
        return nullptr;
    }

    try {
        RE2* re = reinterpret_cast<RE2*>(handle);

        // Zero-copy: wrap the raw pointer in StringPiece
        const char* text = reinterpret_cast<const char*>(textAddress);
        re2::StringPiece input(text, static_cast<size_t>(textLength));

        int numGroups = re->NumberOfCapturingGroups();
        std::vector<std::vector<std::string>> allMatches;

        // Find all non-overlapping matches
        std::vector<re2::StringPiece> groups(numGroups + 1);

        while (re->Match(input, 0, input.size(), RE2::UNANCHORED, groups.data(), numGroups + 1)) {
            std::vector<std::string> matchGroups;
            for (int i = 0; i <= numGroups; i++) {
                if (groups[i].data() != nullptr) {
                    matchGroups.push_back(std::string(groups[i].data(), groups[i].size()));
                } else {
                    matchGroups.push_back("");
                }
            }
            allMatches.push_back(matchGroups);

            // Advance past this match
            if (groups[0].size() == 0) {
                break;  // Avoid infinite loop on zero-length match
            }
            input.remove_prefix(groups[0].data() - input.data() + groups[0].size());
        }

        if (allMatches.empty()) {
            return nullptr;
        }

        // Create Java array of arrays
        jclass stringArrayClass = env->FindClass("[Ljava/lang/String;");
        jobjectArray result = env->NewObjectArray(allMatches.size(), stringArrayClass, nullptr);

        for (size_t i = 0; i < allMatches.size(); i++) {
            jclass stringClass = env->FindClass("java/lang/String");
            jobjectArray groupArray = env->NewObjectArray(allMatches[i].size(), stringClass, nullptr);

            for (size_t j = 0; j < allMatches[i].size(); j++) {
                jstring jstr = env->NewStringUTF(allMatches[i][j].c_str());
                env->SetObjectArrayElement(groupArray, j, jstr);
                env->DeleteLocalRef(jstr);
            }

            env->SetObjectArrayElement(result, i, groupArray);
            env->DeleteLocalRef(groupArray);
        }

        return result;

    } catch (const std::exception& e) {
        last_error = std::string("Direct find all matches exception: ") + e.what();
        return nullptr;
    }
}

} // extern "C"
