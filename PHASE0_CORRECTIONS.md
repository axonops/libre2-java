# Phase 0: Corrections to Implementation Plan

**Date:** 2025-11-28
**Status:** CORRECTIONS APPLIED

---

## User Feedback & Corrections

### **1. Pattern Size - Use Exact Size, Not Estimation** ✅

**Original Plan:**
```cpp
// Pattern cache entries: RE2::ProgramSize() or approximation
size_t estimateSize(const RE2& re2) const;
```

**Correction:**
- Use **exact size** from RE2 after compilation
- No estimation/approximation needed
- RE2 provides exact program size

**Updated Approach:**
```cpp
size_t getExactSize(const RE2& re2) const {
    // Get exact compiled pattern size from RE2
    return re2.ProgramSize();  // Returns exact bytes
}
```

**Impact:**
- More accurate capacity tracking
- Correct eviction decisions
- No estimation error

---

### **2. Java JNI Wrapper Pattern - Keep Current Architecture** ✅

**Original Plan:**
- Create interface ICacheManager
- PatternCache implements interface

**Correction:**
- **Keep current package-protected JNI pattern**
- **Continue using interfaces for mocking** (same pattern as current code)
- Maintain existing architecture: package-protected JNI class + interface adapter

**Current Pattern (to preserve):**
```java
// Package-protected JNI class (in jni package)
final class RE2NativeJNI {
    static native long compile(String pattern, boolean caseSensitive);
    static native void releasePattern(long handle);
    // ...
}

// Interface for mocking (in jni package)
public interface IRE2Native {
    long compile(String pattern, boolean caseSensitive);
    void releasePattern(long handle);
    // ...
}

// Adapter delegates to JNI (in jni package)
public final class RE2Native implements IRE2Native {
    public static final RE2Native INSTANCE = new RE2Native();

    @Override
    public long compile(String pattern, boolean caseSensitive) {
        return RE2NativeJNI.compile(pattern, caseSensitive);
    }
    // ...
}
```

**Impact:**
- No breaking changes to existing pattern
- Tests can mock IRE2Native interface
- Clean separation maintained

---

### **3. Dependencies - Everything in JAR (Zero System Dependencies)** ✅

**Original Plan:**
- nlohmann/json (header-only) ✅ OK
- MurmurHash3 library

**Correction:**
- **nlohmann/json**: OK (header-only, will be embedded in JAR)
- **MurmurHash3**: Must include source code in our library (no system dependency)

**Updated Approach:**
```
native/
├── wrapper/
│   ├── cache/
│   │   ├── murmur_hash3.h    # Our implementation (inline)
│   │   └── murmur_hash3.cpp  # Our implementation
├── third_party/
│   └── json/
│       └── nlohmann/
│           └── json.hpp      # Downloaded, embedded in JAR
```

**Build:**
- Download nlohmann/json during build
- Include MurmurHash3 source directly
- Everything statically linked into libre2.so/dylib
- JAR contains complete native library (no external deps)

**Impact:**
- Self-contained JAR (as required)
- No runtime dependencies

---

### **4. Metrics Polling - On-Demand Only, No Internal Polling** ✅

**Original Plan:**
- Periodic polling (e.g., every 5 seconds) or on-demand

**Correction:**
- **On-demand only** - called by external monitoring
- **No internal polling** within our library

**Updated Approach:**
```java
// External monitoring calls this on-demand
String metricsJson = RE2Native.INSTANCE.getMetrics();
// Parse and update Dropwizard metrics
```

**Impact:**
- Simpler implementation (no polling thread)
- Monitoring frequency controlled by user
- Lower overhead

---

### **5. TTL Configuration - All Must Be Configurable (No Hardcoded Defaults)** ✅

**Original Plan:**
```cpp
std::chrono::milliseconds pattern_result_cache_ttl_ms{300000};  // Hardcoded 5 min
std::chrono::milliseconds pattern_cache_ttl_ms{300000};         // Hardcoded 5 min
std::chrono::milliseconds deferred_cache_ttl_ms{600000};        // Hardcoded 10 min
```

**Correction:**
- **All TTLs must be configurable** (passed via JSON config)
- **Default values**: 5 min (result + pattern), 10 min (deferred)
- But must be parameters, not hardcoded

**Updated Approach:**
```cpp
struct CacheConfig {
    // Pattern Result Cache
    std::chrono::milliseconds pattern_result_cache_ttl_ms;  // NO default in struct

    // Pattern Compilation Cache
    std::chrono::milliseconds pattern_cache_ttl_ms;         // NO default in struct

    // Deferred Cache
    std::chrono::milliseconds deferred_cache_ttl_ms;        // NO default in struct

    // Parse from JSON with defaults
    static CacheConfig fromJson(const std::string& json) {
        // Defaults applied during parsing, not in struct
        auto ttl1 = jsonObj.value("pattern_result_cache_ttl_ms", 300000);
        auto ttl2 = jsonObj.value("pattern_cache_ttl_ms", 300000);
        auto ttl3 = jsonObj.value("deferred_cache_ttl_ms", 600000);
        // ...
    }
};
```

**Java Side:**
```java
public record RE2Config(
    // ...
    long patternResultCacheTtlMs,  // Default in builder, not record
    long patternCacheTtlMs,
    long deferredCacheTtlMs
) {
    public static class Builder {
        private long patternResultCacheTtlMs = 300000;  // 5 min default
        private long patternCacheTtlMs = 300000;        // 5 min default
        private long deferredCacheTtlMs = 600000;       // 10 min default
        // ...
    }
}
```

**Impact:**
- Full configurability (as required)
- Defaults applied at config build time, not hardcoded in structs

---

### **6. MurmurHash3 - Include in Our Library (No System Dependency)** ✅

**Correction:**
- Include MurmurHash3 **source code** in our repository
- Public domain license (no issues)
- No system dependency

**Implementation:**
```cpp
// native/wrapper/cache/murmur_hash3.h
#pragma once
#include <cstdint>

namespace libre2 {
namespace hash {

// MurmurHash3 implementation (public domain)
uint32_t murmur3_32(const void* key, int len, uint32_t seed);
uint64_t murmur3_64(const void* key, int len, uint64_t seed);

// Convenience
inline uint64_t hashString(const std::string& str) {
    return murmur3_64(str.data(), str.size(), 0);
}

}  // namespace hash
}  // namespace libre2
```

**Impact:**
- Self-contained
- No external dependency

---

### **7. Java Testing - Unit AND Integration Tests Required** ✅

**Original Plan:**
- Mentioned integration tests, but not explicit about unit tests

**Correction:**
- **Java unit tests** for all updated classes (PatternCache, Pattern, etc.)
- **Java integration tests** for end-to-end caching behavior
- **Coverage target**: Maintain or exceed current coverage

**Required Tests:**
```
libre2-core/src/test/java/
├── com/axonops/libre2/jni/
│   ├── RE2NativeTest.java           # Unit test for adapter
│   ├── RE2NativeJNITest.java        # Mock-based unit tests
├── com/axonops/libre2/cache/
│   ├── PatternCacheTest.java        # Unit tests for PatternCache
│   ├── RE2ConfigTest.java           # Unit tests for config
├── com/axonops/libre2/integration/
│   ├── CacheIntegrationTest.java    # End-to-end cache behavior
│   ├── MetricsIntegrationTest.java  # Metrics correctness
│   └── LeakDetectionTest.java       # Refcount + cleanup
```

**Impact:**
- Comprehensive test coverage
- Both unit and integration levels

---

### **8. Docker Files - Multiple Dockerfiles OK for Different Scenarios** ✅

**Correction:**
- Can use **multiple Dockerfiles** for different test scenarios
- E.g., one with valgrind/asan, one without

**Proposed Structure:**
```
native/
├── Dockerfile                    # Standard build (current)
├── Dockerfile.leak-test          # With valgrind/asan for leak testing
└── Dockerfile.stress-test        # For long-running stress tests (optional)
```

**Usage:**
```yaml
# .github/workflows/build-native.yml
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Build standard
        run: docker build -f Dockerfile -t libre2-build .

  leak-test:
    runs-on: ubuntu-latest
    steps:
      - name: Build with leak tools
        run: docker build -f Dockerfile.leak-test -t libre2-leak-test .
      - name: Run leak tests
        run: docker run libre2-leak-test ./run_leak_tests.sh
```

**Impact:**
- Cleaner separation of concerns
- Leak testing doesn't slow down standard builds

---

### **9. JNI Function Naming - Remove Unnecessary "cache" Prefix** ✅

**Original Plan:**
```cpp
// Bad naming
cacheGetOrCompile(...)
cacheReleasePattern(...)
cacheGetMatchResult(...)
cachePutMatchResult(...)
getCacheMetricsJSON()
clearCache(int type)
```

**Correction:**

#### **A) `cacheGetOrCompile` → `compile`**
- Caching is **transparent** (enabled/disabled via config)
- Caller doesn't need to know about caching

```cpp
// CORRECT
JNIEXPORT jlong JNICALL Java_..._compile(
    JNIEnv* env, jclass cls, jstring pattern, jboolean caseSensitive);

// Usage:
long handle = RE2NativeJNI.compile(pattern, caseSensitive);
```

---

#### **B) `cacheReleasePattern` → `releasePattern`**
- Decrements refcount (whether cache enabled or not)

```cpp
// CORRECT
JNIEXPORT void JNICALL Java_..._releasePattern(
    JNIEnv* env, jclass cls, jlong handle);

// Usage:
RE2NativeJNI.releasePattern(handle);
```

---

#### **C) `cacheGetMatchResult` → `match`**
- **Returns cached match OR executes match** (transparent)
- Automatically checks result cache, executes if miss, caches result

```cpp
// CORRECT
JNIEXPORT jobject JNICALL Java_..._match(
    JNIEnv* env, jclass cls, jlong patternHandle, jstring input);

// C++ implementation:
MatchResult match(long patternHandle, const std::string& input) {
    // 1. Check result cache (if enabled)
    if (config_.pattern_result_cache_enabled) {
        auto cached = result_cache_.get(pattern, input, metrics_);
        if (cached.has_value()) {
            return cached.value();  // Cache hit
        }
    }

    // 2. Execute match (cache miss or disabled)
    MatchResult result = executeMatch(patternHandle, input);

    // 3. Cache result (if enabled)
    if (config_.pattern_result_cache_enabled) {
        result_cache_.put(pattern, input, result, metrics_);
    }

    return result;
}

// Usage:
MatchResult result = RE2NativeJNI.match(patternHandle, input);
```

---

#### **D) `cachePutMatchResult` → REMOVE (Automatic)**
- **Not exposed to Java**
- Caching happens **automatically** inside `match()` function
- Java has no control over when results are cached

**Why:**
- Result caching is an internal optimization
- Java shouldn't manually manage result cache
- Automatic = transparent to caller

---

#### **E) `getCacheMetricsJSON` → `getMetrics`**
- **Include RE2 library metrics** (if available)
- Return comprehensive JSON with both cache metrics AND RE2 library metrics

```cpp
// CORRECT
JNIEXPORT jstring JNICALL Java_..._getMetrics(JNIEnv* env, jclass cls);

// C++ implementation:
std::string getMetrics() {
    nlohmann::json j;

    // 1. Cache metrics
    j["cache"] = metrics_.toJson();

    // 2. RE2 library metrics (if available)
    // Check if RE2 exposes any metrics/stats
    // Example: pattern complexity, memory usage, etc.
    if (/* RE2 has metrics */) {
        j["re2_library"] = {
            {"patterns_compiled", /* from RE2 */},
            {"total_memory_bytes", /* from RE2 */},
            // ... any RE2-provided metrics
        };
    }

    return j.dump();
}

// Usage:
String metricsJson = RE2NativeJNI.getMetrics();
```

**Action Item:** Research what metrics RE2 library provides and include them.

---

#### **F) `clearCache(int type)` → `clearCache()` (Clear All)**
- **No type parameter** - clear ALL caches
- Simple, unambiguous

```cpp
// CORRECT
JNIEXPORT void JNICALL Java_..._clearCache(JNIEnv* env, jclass cls);

// C++ implementation:
void clearCache() {
    // Clear all three caches
    result_cache_.clear();
    pattern_cache_.clear();
    deferred_cache_.clear();
}

// Usage:
RE2NativeJNI.clearCache();  // Clears everything
```

**Rationale:**
- Simple and clear
- No ambiguity about what gets cleared
- If user needs granular control, add separate functions later

---

## Updated JNI Function List

**Core Functions:**
```cpp
// Pattern compilation (transparent caching)
jlong compile(JNIEnv*, jclass, jstring pattern, jboolean caseSensitive);
void releasePattern(JNIEnv*, jclass, jlong handle);
jboolean isPatternValid(JNIEnv*, jclass, jlong handle);

// Matching (transparent result caching)
jboolean match(JNIEnv*, jclass, jlong handle, jstring input);
jboolean fullMatch(JNIEnv*, jclass, jlong handle, jstring input);
jobjectArray extractGroups(JNIEnv*, jclass, jlong handle, jstring input);
// ... other match operations

// Bulk operations
jbooleanArray matchBulk(JNIEnv*, jclass, jlong handle, jobjectArray inputs);
// ... other bulk operations

// Configuration & Lifecycle
void initializeCache(JNIEnv*, jclass, jstring configJson);
void shutdownCache(JNIEnv*, jclass);

// Cache Management
void clearCache(JNIEnv*, jclass);  // Clear all caches
void forceEviction(JNIEnv*, jclass);  // Trigger immediate eviction

// Metrics (includes cache + RE2 library metrics)
jstring getMetrics(JNIEnv*, jclass);
void resetMetrics(JNIEnv*, jclass);

// Utility
jstring quoteMeta(JNIEnv*, jclass, jstring text);
jintArray programFanout(JNIEnv*, jclass, jlong handle);
jlong getPatternMemory(JNIEnv*, jclass, jlong handle);
```

**Total:** ~15-18 functions (simplified from original 10-15 estimate)

---

## Research Task: RE2 Library Metrics

**Question:** What metrics does the RE2 library expose?

**Check:**
1. RE2 class methods for stats/metrics
2. Global RE2 statistics
3. Per-pattern metrics (complexity, memory, etc.)

**Possible metrics:**
- `ProgramSize()` - per-pattern memory (already using)
- `NumberOfCapturingGroups()` - per-pattern
- Pattern complexity measures?
- Global compilation count?
- DFA state count?

**Action:** Investigate RE2 source and include any available metrics in `getMetrics()` JSON.

---

## Impact Summary

### Code Changes Required

1. **JNI naming:** Rename all functions (remove "cache" prefix where inappropriate)
2. **match() function:** Implement automatic result cache check + execute + cache
3. **Remove cachePutMatchResult:** Not exposed to Java
4. **getMetrics():** Research and include RE2 library metrics
5. **clearCache():** Remove type parameter, clear all caches
6. **TTL configuration:** Remove hardcoded defaults, make all configurable
7. **Pattern size:** Use exact size from RE2 (already planned)
8. **MurmurHash3:** Include source (already planned)

### Plan Updates

1. ✅ **PHASE0_DECISIONS_FINAL.md** - Update JNI function list
2. ✅ **PHASE1_IMPLEMENTATION_PLAN.md** - Update component specs
3. ✅ **Session log** - Track corrections applied

---

## Next Steps

1. ✅ Document corrections (COMPLETE)
2. 📝 Research RE2 library metrics (TODO)
3. 🔄 Update implementation plan with corrected JNI naming
4. 🚀 Begin Phase 1 implementation

---

**Status:** CORRECTIONS DOCUMENTED - READY TO UPDATE PLAN
