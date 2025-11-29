# JNI Implementation Analysis - Key Learnings

**Date:** 2025-11-29
**Purpose:** Extract design patterns from existing JNI code for wrapper API
**Source:** wrapper/re2_jni.cpp, RE2NativeJNI.java

---

## KEY FINDINGS

### 1. Java API Patterns (What JNI Expects)

**Bulk Operations - String-based:**
```java
// Java passes String[], JNI returns boolean[]
boolean[] fullMatchBulk(long handle, String[] texts);
boolean[] partialMatchBulk(long handle, String[] texts);
```

**Direct Memory - Single:**
```java
// Java passes jlong (memory address) + jint (length)
boolean fullMatchDirect(long handle, long textAddress, int textLength);
boolean partialMatchDirect(long handle, long textAddress, int textLength);
```

**Direct Memory - Bulk:**
```java
// Java passes long[] (addresses) + int[] (lengths)
boolean[] fullMatchDirectBulk(long handle, long[] textAddresses, int[] textLengths);
boolean[] partialMatchDirectBulk(long handle, long[] textAddresses, int[] textLengths);
```

---

## 2. JNI Implementation Patterns (How It Works)

### Pattern A: String-Based Bulk

**JNI Signature:**
```cpp
JNIEXPORT jbooleanArray JNICALL Java_..._fullMatchBulk(
    JNIEnv *env, jclass cls,
    jlong handle,           // Pattern pointer
    jobjectArray texts);    // Java String[] array
```

**Implementation:**
```cpp
// 1. Get array length
jsize length = env->GetArrayLength(texts);

// 2. Allocate result array (in Java heap)
jbooleanArray results = env->NewBooleanArray(length);

// 3. Process each string (loop in C++)
std::vector<jboolean> matches(length);
for (jsize i = 0; i < length; i++) {
    jstring jstr = (jstring)env->GetObjectArrayElement(texts, i);

    // 4. Convert Java String → C++ (with RAII cleanup)
    JStringGuard guard(env, jstr);

    // 5. Call RE2 directly
    matches[i] = RE2::FullMatch(guard.get(), *re) ? JNI_TRUE : JNI_FALSE;

    // 6. Release local ref (prevent memory leak)
    env->DeleteLocalRef(jstr);
}

// 7. Write results back to Java array
env->SetBooleanArrayRegion(results, 0, length, matches.data());
return results;
```

**Key Points:**
- ✅ Loop in C++ (not Java) - minimizes JNI crossings
- ✅ RAII cleanup (JStringGuard)
- ✅ DeleteLocalRef in loop (prevents JNI local ref table overflow)
- ✅ Allocate result array in Java heap (SetBooleanArrayRegion)
- ✅ Null handling (null strings → false)

---

### Pattern B: Direct Memory Single

**JNI Signature:**
```cpp
JNIEXPORT jboolean JNICALL Java_..._fullMatchDirect(
    JNIEnv *env, jclass cls,
    jlong handle,           // Pattern pointer
    jlong textAddress,      // Memory address (from DirectByteBuffer)
    jint textLength);       // Length in bytes
```

**Implementation:**
```cpp
// 1. Validate inputs
if (handle == 0 || textAddress == 0 || textLength < 0) {
    return JNI_FALSE;
}

// 2. Cast jlong → const char*
const char* text = reinterpret_cast<const char*>(textAddress);

// 3. Wrap in StringPiece (ZERO COPY - just pointer + length)
re2::StringPiece input(text, static_cast<size_t>(textLength));

// 4. Call RE2 directly with StringPiece
return RE2::FullMatch(input, *re) ? JNI_TRUE : JNI_FALSE;
```

**Key Points:**
- ✅ **ZERO COPY** - StringPiece is just (pointer, length)
- ✅ jlong used for memory addresses (standard JNI practice)
- ✅ Validation: handle, address, length all checked
- ✅ Simple return (boolean) - cheap to return on-heap

---

### Pattern C: Direct Memory Bulk

**JNI Signature:**
```cpp
JNIEXPORT jbooleanArray JNICALL Java_..._fullMatchDirectBulk(
    JNIEnv *env, jclass cls,
    jlong handle,           // Pattern pointer
    jlongArray textAddresses,  // Java long[] of addresses
    jintArray textLengths);    // Java int[] of lengths
```

**Implementation:**
```cpp
// 1. Validate and check array lengths match
jsize addressCount = env->GetArrayLength(textAddresses);
jsize lengthCount = env->GetArrayLength(textLengths);
if (addressCount != lengthCount) { return nullptr; }

// 2. Get raw array data (this DOES copy arrays, but NOT text data)
jlong* addresses = env->GetLongArrayElements(textAddresses, nullptr);
jint* lengths = env->GetIntArrayElements(textLengths, nullptr);

// 3. Process all inputs with zero-copy
std::vector<jboolean> matches(addressCount);
for (jsize i = 0; i < addressCount; i++) {
    // Validate each address/length
    if (addresses[i] == 0 || lengths[i] < 0) {
        matches[i] = JNI_FALSE;
        continue;
    }

    // Zero-copy: StringPiece wrap
    const char* text = reinterpret_cast<const char*>(addresses[i]);
    re2::StringPiece input(text, static_cast<size_t>(lengths[i]));
    matches[i] = RE2::FullMatch(input, *re) ? JNI_TRUE : JNI_FALSE;
}

// 4. Release arrays (JNI_ABORT = no write back, we only read)
env->ReleaseLongArrayElements(textAddresses, addresses, JNI_ABORT);
env->ReleaseIntArrayElements(textLengths, lengths, JNI_ABORT);

// 5. Write results
jbooleanArray results = env->NewBooleanArray(addressCount);
env->SetBooleanArrayRegion(results, 0, addressCount, matches.data());
return results;
```

**Key Points:**
- ✅ Array elements copied (addresses + lengths) - **BUT text data is NOT copied**
- ✅ JNI_ABORT flag - don't write back (read-only access)
- ✅ Validate each address/length individually
- ✅ Zero-copy for actual text data (StringPiece)
- ✅ RAII-style cleanup (Release*ArrayElements)

---

## 3. CRITICAL OPTIMIZATIONS

### Optimization 1: Single JNI Crossing

**Old way (inefficient):**
```java
for (String text : texts) {
    results[i] = fullMatch(handle, text);  // N JNI calls
}
```

**New way (efficient):**
```java
boolean[] results = fullMatchBulk(handle, texts);  // 1 JNI call
```

**Why:** JNI crossing overhead ~50-200ns per call. 1000 texts = 50-200μs wasted.

### Optimization 2: Zero-Copy with StringPiece

**Key insight:** RE2 accepts `re2::StringPiece` (pointer + length), not std::string

```cpp
// WRONG - copies data
std::string text_copy(address, length);  // COPY!
RE2::FullMatch(text_copy, *re);

// CORRECT - zero copy
re2::StringPiece input(reinterpret_cast<const char*>(address), length);
RE2::FullMatch(input, *re);  // NO COPY!
```

### Optimization 3: Local Reference Cleanup

```cpp
for (jsize i = 0; i < length; i++) {
    jstring jstr = (jstring)env->GetObjectArrayElement(texts, i);

    // ... use jstr ...

    env->DeleteLocalRef(jstr);  // CRITICAL - prevent local ref table overflow
}
```

**Why:** JNI local ref table limited (512-1024 refs). Without cleanup, bulk operations with 10K strings would crash.

### Optimization 4: Partial Success Handling

```cpp
// If individual text is null or invalid → store false, continue
if (addresses[i] == 0 || lengths[i] < 0) {
    matches[i] = JNI_FALSE;
    continue;  // Don't fail entire operation
}
```

**This contradicts my earlier "all-or-nothing" design!**

**Actual behavior:** Process all inputs, mark failures as false, return full array.

---

## 4. WRAPPER API DESIGN (CORRECTED)

Based on JNI analysis, our C++ wrapper API should match JNI's calling pattern:

### Our Wrapper API Should Look Like:

**Bulk - String-based (for JNI to call):**
```cpp
// JNI will call this from fullMatchBulk
void fullMatchBulk(
    cache::RE2Pattern* pattern,
    const char** texts,        // Array of C strings
    const int* text_lens,      // Array of lengths
    int num_texts,             // Number of texts
    bool* results_out);        // Pre-allocated output (JNI allocates)
// Returns: void (results written to results_out)
//          OR: int (number processed, -1 on total failure)
```

**Direct - Single:**
```cpp
// JNI will call this from fullMatchDirect
bool fullMatchDirect(
    cache::RE2Pattern* pattern,
    jlong text_address,        // Memory address (jlong, not void*)
    int text_length);          // Length
// Returns: bool (true/false)
```

**Direct - Bulk:**
```cpp
// JNI will call this from fullMatchDirectBulk
void fullMatchDirectBulk(
    cache::RE2Pattern* pattern,
    const jlong* text_addresses,   // Array of addresses
    const int* text_lengths,       // Array of lengths
    int num_texts,
    bool* results_out);             // Pre-allocated output
// Returns: void (or int for count)
```

---

## 5. KEY DIFFERENCES FROM MY ORIGINAL DESIGN

| Aspect | My Original Design | Correct Design (from JNI) |
|--------|-------------------|---------------------------|
| **Return type (bulk)** | `int` (count) | `void` (results via out param) OR `jbooleanArray` (JNI creates) |
| **Error handling** | All-or-nothing | Partial success (null → false, continue) |
| **Memory type** | `const void*` | `jlong` (standard JNI) |
| **StringPiece** | Not mentioned | **CRITICAL** - zero-copy mechanism |
| **Local refs** | Not mentioned | **MUST** DeleteLocalRef in loops |
| **Array access** | Direct pointers | GetArrayElements + Release (JNI RAII) |

---

## 6. WRAPPER API RESPONSIBILITIES

**What JNI does (should NOT be in wrapper):**
- ❌ JNI array management (GetArrayLength, NewBooleanArray, etc.)
- ❌ Java String → C++ conversion (GetStringUTFChars)
- ❌ Local reference cleanup (DeleteLocalRef)
- ❌ JNI error handling (exceptions → last_error)

**What wrapper SHOULD do (core logic):**
- ✅ Loop over texts
- ✅ Call RE2 for each
- ✅ Handle null/invalid inputs (skip, mark false)
- ✅ Use StringPiece for zero-copy
- ✅ Write results to output array

---

## 7. REVISED WRAPPER API DESIGN

### Bulk String-Based

```cpp
/**
 * Full match on multiple texts (bulk operation).
 *
 * JNI calls this after extracting String[] → const char**.
 *
 * @param pattern compiled pattern
 * @param texts array of text pointers
 * @param text_lens array of lengths (parallel to texts)
 * @param num_texts number of texts
 * @param results_out pre-allocated output array (JNI allocates)
 * @return number of texts processed successfully (num_texts on success, <num_texts on partial)
 */
int fullMatchBulk(
    cache::RE2Pattern* pattern,
    const char** texts,
    const int* text_lens,
    int num_texts,
    bool* results_out);
```

### Direct Memory Single

```cpp
/**
 * Full match with direct memory access (zero-copy).
 *
 * Uses re2::StringPiece to wrap memory address (no copy).
 *
 * @param pattern compiled pattern
 * @param text_address memory address (from Java DirectByteBuffer.address())
 * @param text_length length in bytes
 * @return true if match, false otherwise
 */
bool fullMatchDirect(
    cache::RE2Pattern* pattern,
    jlong text_address,        // jlong, not void*
    int text_length);
```

### Direct Memory Bulk

```cpp
/**
 * Full match on multiple memory regions (bulk zero-copy).
 *
 * JNI calls this after extracting long[] → jlong*.
 *
 * @param pattern compiled pattern
 * @param text_addresses array of memory addresses
 * @param text_lengths array of lengths
 * @param num_texts number of texts
 * @param results_out pre-allocated output array
 * @return number processed successfully
 */
int fullMatchDirectBulk(
    cache::RE2Pattern* pattern,
    const jlong* text_addresses,   // jlong*, not void**
    const int* text_lengths,
    int num_texts,
    bool* results_out);
```

---

## 8. STRINGPIECE: THE ZERO-COPY SECRET

**Critical understanding:**

RE2 accepts `re2::StringPiece` OR `absl::string_view` - both are (pointer, length) with NO ownership.

```cpp
// This is what makes direct memory zero-copy:
const char* text = reinterpret_cast<const char*>(textAddress);
re2::StringPiece input(text, textLength);  // NO COPY - just wraps pointer
RE2::FullMatch(input, *re);  // RE2 reads directly from address
```

**Our wrapper must use StringPiece/string_view for all direct memory operations.**

---

## 9. ERROR HANDLING STRATEGY (REVISED)

Based on JNI code analysis:

**For bulk operations:**
```cpp
// NOT all-or-nothing! Process all, mark failures as false
for (int i = 0; i < num_texts; i++) {
    if (texts[i] == nullptr || text_lens[i] < 0) {
        results_out[i] = false;  // Mark as false, continue
        continue;
    }

    results_out[i] = RE2::FullMatch(texts[i], *pattern->compiled_regex);
}
return num_texts;  // Processed all
```

**NOT this (my original incorrect design):**
```cpp
// WRONG - all-or-nothing
if (ANY input invalid) {
    return -1;  // Fail entire operation
}
```

**Why:** Java layer can check results array and handle per-text failures. More flexible.

---

## 10. JNI-SPECIFIC VS WRAPPER-SPECIFIC

### JNI Layer Responsibilities:
- Get Java arrays (GetLongArrayElements, GetIntArrayElements)
- Allocate result arrays (NewBooleanArray)
- Convert Java Strings (GetStringUTFChars)
- Cleanup local refs (DeleteLocalRef)
- Write results back (SetBooleanArrayRegion)
- Handle JNI exceptions

### Wrapper Layer Responsibilities (NEW):
- Loop over inputs
- Call RE2 with StringPiece (zero-copy)
- Handle null/invalid inputs (mark false)
- Store results in output array
- Use pattern pointer (not raw RE2*)

**Clean separation:** JNI does Java↔C++ marshalling, wrapper does RE2 logic.

---

## 11. FINAL API DESIGN (CORRECTED)

### Matching - Bulk (String-based)

```cpp
namespace libre2 {
namespace api {

/**
 * Full match bulk operation.
 *
 * Processes all texts, even if some are null/invalid (marks as false).
 * JNI calls this after converting String[] → const char**.
 *
 * @param pattern compiled pattern pointer
 * @param texts array of text pointers (may contain nulls)
 * @param text_lens array of lengths (parallel to texts)
 * @param num_texts number of texts to process
 * @param results_out pre-allocated output array (size >= num_texts)
 * @return number of texts processed (always num_texts unless pattern null)
 */
int fullMatchBulk(
    cache::RE2Pattern* pattern,
    const char** texts,
    const int* text_lens,
    int num_texts,
    bool* results_out);

int partialMatchBulk(
    cache::RE2Pattern* pattern,
    const char** texts,
    const int* text_lens,
    int num_texts,
    bool* results_out);
```

### Matching - Direct Memory Single

```cpp
/**
 * Full match with direct memory (zero-copy).
 *
 * Uses re2::StringPiece to wrap address without copying.
 *
 * @param pattern compiled pattern pointer
 * @param text_address memory address (jlong from DirectByteBuffer)
 * @param text_length length in bytes
 * @return true if match, false otherwise
 */
bool fullMatchDirect(
    cache::RE2Pattern* pattern,
    jlong text_address,
    int text_length);

bool partialMatchDirect(
    cache::RE2Pattern* pattern,
    jlong text_address,
    int text_length);
```

### Matching - Direct Memory Bulk

```cpp
/**
 * Full match direct bulk (zero-copy + bulk).
 *
 * Combines benefits of zero-copy (StringPiece) and bulk (single call).
 *
 * @param pattern compiled pattern pointer
 * @param text_addresses array of memory addresses
 * @param text_lengths array of lengths (parallel)
 * @param num_texts number of texts
 * @param results_out pre-allocated output array
 * @return number processed (num_texts on success)
 */
int fullMatchDirectBulk(
    cache::RE2Pattern* pattern,
    const jlong* text_addresses,
    const int* text_lengths,
    int num_texts,
    bool* results_out);

int partialMatchDirectBulk(
    cache::RE2Pattern* pattern,
    const jlong* text_addresses,
    const int* text_lengths,
    int num_texts,
    bool* results_out);
```

### Replacement - Bulk

Following same pattern, but returns String[] instead of boolean[]:

```cpp
/**
 * Replace all bulk (string-based).
 *
 * @param pattern compiled pattern
 * @param texts array of input texts
 * @param text_lens array of lengths
 * @param num_texts number of texts
 * @param rewrite rewrite template
 * @param results_out array of std::string* (pre-allocated by JNI)
 * @return number processed
 */
int replaceAllBulk(
    cache::RE2Pattern* pattern,
    const char** texts,
    const int* text_lens,
    int num_texts,
    const char* rewrite,
    std::string** results_out);  // Array of string pointers
```

---

## 12. TESTING PATTERN (UPDATED)

**Every bulk/direct test must compare:**

1. **RE2 in loop** (what Java would do without bulk)
2. **Our bulk wrapper** (single call)
3. **Results must match element-by-element**

```cpp
TEST_F(...) {
    // ========== TEST DATA (defined ONCE) ==========
    const std::vector<std::string> TEXTS = {"test1", "no", "test2"};
    // ==============================================

    // ========== EXECUTE RE2 (loop, like Java without bulk) ==========
    std::vector<bool> results_re2;
    for (const auto& text : TEXTS) {
        results_re2.push_back(RE2::FullMatch(text, *p->compiled_regex));
    }
    // ================================================================

    // ========== EXECUTE WRAPPER (bulk call) ==========
    std::vector<const char*> text_ptrs;
    std::vector<int> text_lens;
    for (const auto& t : TEXTS) {
        text_ptrs.push_back(t.data());
        text_lens.push_back(t.size());
    }

    std::vector<bool> results_wrapper(TEXTS.size());
    fullMatchBulk(p, text_ptrs.data(), text_lens.data(),
                  TEXTS.size(), results_wrapper.data());
    // =================================================

    // ========== COMPARE (CRITICAL) ==========
    for (size_t i = 0; i < results_re2.size(); i++) {
        EXPECT_EQ(results_re2[i], results_wrapper[i]);
    }
    // ========================================
}
```

---

## SUMMARY: WHAT I LEARNED

1. ✅ **Bulk API returns results via output parameter** (not return value)
2. ✅ **jlong for addresses** (not void*) - standard JNI
3. ✅ **StringPiece is the zero-copy secret** - MUST use for direct memory
4. ✅ **Partial success allowed** - null inputs → false, continue processing
5. ✅ **DeleteLocalRef critical** - prevent local ref table overflow
6. ✅ **JNI_ABORT flag** - don't write back read-only arrays
7. ✅ **Wrapper does RE2 logic**, JNI does marshalling

---

## NEXT: IMPLEMENT CORRECTED DESIGN

Ready to implement bulk/direct operations with these learnings.

**Key changes from original design:**
- Use jlong (not void*)
- Use StringPiece for zero-copy
- Partial success (not all-or-nothing)
- Results via output parameter
- Match JNI calling patterns exactly

**Proceed?** 🚀
