# Wrapper API Final Design - Bulk & Off-Heap

**Date:** 2025-11-29
**Critical Understanding:** Move ALL complexity from JNI → Wrapper API

---

## THE KEY INSIGHT

### What We're Fixing

**OLD (Mistake):**
```
Java → JNI (COMPLEX: loops, StringPiece, RE2 calls, local refs) → RE2
```

**Problems:**
- ❌ JNI tied to Java (can't reuse for Python, Go, Node)
- ❌ Complex JNI code (1200 lines, hard to maintain)
- ❌ RE2 logic scattered across JNI

**NEW (Correct):**
```
Java → JNI (THIN: marshal only) → Wrapper API (COMPLEX: RE2 logic) → RE2
```

**Benefits:**
- ✅ Wrapper reusable (Python, Go, Node call same API)
- ✅ JNI simple (~200 lines, just marshalling)
- ✅ RE2 logic centralized in wrapper

**The wrapper API inherits ALL the complexity that was in JNI.**

---

## WRAPPER API DESIGN (absorbs JNI complexity)

### Bulk String-Based Operations

```cpp
/**
 * Full match bulk - process multiple texts.
 *
 * Implements the logic that WAS in JNI fullMatchBulk:
 * - Loop over all texts
 * - Handle null/invalid inputs (mark false, continue)
 * - Call RE2::FullMatch for each
 * - Write results to output array
 *
 * JNI now just marshals String[] → const char** and calls this.
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

void partialMatchBulk(
    cache::RE2Pattern* pattern,
    const char** texts,
    const int* text_lens,
    int num_texts,
    bool* results_out);
```

**Key points:**
- Returns void (results written to results_out)
- Handles nulls gracefully (mark false, continue)
- Loops internally (JNI doesn't loop anymore)
- Uses std::string_view for zero-copy

---

### Direct Memory Operations (Single)

```cpp
/**
 * Full match with direct memory (zero-copy).
 *
 * Implements the logic that WAS in JNI fullMatchDirect:
 * - Cast jlong → const char*
 * - Wrap in re2::StringPiece (zero-copy)
 * - Call RE2::FullMatch
 * - Return boolean
 *
 * JNI now just validates and calls this.
 *
 * @param pattern compiled pattern pointer
 * @param text_address memory address (from DirectByteBuffer)
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

**Key points:**
- Accepts jlong (JNI standard)
- Casts to const char* internally
- Uses re2::StringPiece (zero-copy)
- Validates address != 0

---

### Direct Memory Bulk Operations

```cpp
/**
 * Full match direct bulk (zero-copy + bulk).
 *
 * Implements the logic that WAS in JNI fullMatchDirectBulk:
 * - Loop over address/length pairs
 * - For each: cast → StringPiece → RE2::FullMatch
 * - Handle invalid addresses (mark false)
 * - Write results to output array
 *
 * JNI now just gets arrays and calls this.
 *
 * @param pattern compiled pattern pointer
 * @param text_addresses array of memory addresses
 * @param text_lengths array of lengths
 * @param num_texts number of texts
 * @param results_out pre-allocated bool array
 */
void fullMatchDirectBulk(
    cache::RE2Pattern* pattern,
    const jlong* text_addresses,
    const int* text_lengths,
    int num_texts,
    bool* results_out);

void partialMatchDirectBulk(
    cache::RE2Pattern* pattern,
    const jlong* text_addresses,
    const int* text_lengths,
    int num_texts,
    bool* results_out);
```

---

### Replacement Bulk Operations

```cpp
/**
 * Replace all bulk (string-based).
 *
 * Implements the logic that WAS in JNI replaceAllBulk:
 * - Loop over texts
 * - Call RE2::GlobalReplace for each
 * - Store results in output strings
 *
 * @param pattern compiled pattern pointer
 * @param texts array of input texts
 * @param text_lens array of lengths
 * @param num_texts number of texts
 * @param rewrite rewrite template string
 * @param results_out array of std::string (pre-allocated)
 */
void replaceAllBulk(
    cache::RE2Pattern* pattern,
    const char** texts,
    const int* text_lens,
    int num_texts,
    const char* rewrite,
    std::string* results_out);  // Array of std::string

void replaceAllDirectBulk(
    cache::RE2Pattern* pattern,
    const jlong* text_addresses,
    const int* text_lengths,
    int num_texts,
    const char* rewrite,
    std::string* results_out);
```

---

## NEW JNI LAYER (will be simple)

**Example of NEW JNI (thin):**

```cpp
JNIEXPORT jbooleanArray JNICALL Java_..._fullMatchBulk(
    JNIEnv *env, jclass cls, jlong handle, jobjectArray texts) {

    // 1. Get array length
    jsize len = env->GetArrayLength(texts);

    // 2. Extract strings to C++ (temporary)
    std::vector<const char*> text_ptrs;
    std::vector<int> text_lens;
    std::vector<JStringGuard> guards;  // RAII cleanup

    for (jsize i = 0; i < len; i++) {
        jstring jstr = (jstring)env->GetObjectArrayElement(texts, i);
        guards.emplace_back(env, jstr);
        text_ptrs.push_back(guards.back().get());
        text_lens.push_back(guards.back().get() ? strlen(guards.back().get()) : 0);
    }

    // 3. Call wrapper API (does all the work)
    std::vector<bool> results(len);
    fullMatchBulk(
        reinterpret_cast<cache::RE2Pattern*>(handle),
        text_ptrs.data(),
        text_lens.data(),
        len,
        results.data());

    // 4. Convert results to Java
    jbooleanArray jresults = env->NewBooleanArray(len);
    std::vector<jboolean> jbools(len);
    for (jsize i = 0; i < len; i++) {
        jbools[i] = results[i] ? JNI_TRUE : JNI_FALSE;
    }
    env->SetBooleanArrayRegion(jresults, 0, len, jbools.data());

    return jresults;
    // guards auto-cleanup here (RAII)
}
```

**That's it!** No RE2 calls, no business logic. Just marshal and delegate.

---

## IMPLEMENTATION PLAN

### Functions to Implement (in wrapper API):

**Matching:**
1. fullMatchBulk(texts, lens, count, results_out)
2. partialMatchBulk(texts, lens, count, results_out)
3. fullMatchDirect(address, len) → bool
4. partialMatchDirect(address, len) → bool
5. fullMatchDirectBulk(addresses, lens, count, results_out)
6. partialMatchDirectBulk(addresses, lens, count, results_out)

**Replacement:**
7. replaceAllBulk(texts, lens, count, rewrite, results_out)
8. replaceAllDirectBulk(addresses, lens, count, rewrite, results_out)

**Extraction (from old JNI):**
9. extractGroupsBulk - Extract captures from multiple texts
10. findAllMatches - Find all matches in single text
11. findAllMatchesDirect - Find all in direct memory

**Total:** ~11 functions

---

## SUCCESS CRITERIA

Sub-Phase 1.2.4 complete when:

- ✅ All 11 bulk/direct functions implemented
- ✅ All use StringPiece for zero-copy (direct memory)
- ✅ All handle null/invalid inputs gracefully
- ✅ 110+ tests (all with RE2 comparison)
- ✅ Tests validate: loop(RE2) ≡ bulk(wrapper)
- ✅ All tests passing
- ✅ Ready for simple JNI layer (Phase 2)

---

**This completes Phase 1.2 - full RE2 API coverage in reusable wrapper!**

Ready to implement? 🚀
