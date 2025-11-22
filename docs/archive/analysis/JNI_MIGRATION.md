# JNI Migration Plan

## Overview

**Goal:** Migrate from JNA to JNI for maximum performance
**Estimated Effort:** 2-3 days
**Risk Level:** Medium (well-defined scope, testable)

## Why Migrate?

| Factor | JNA | JNI | Impact |
|--------|-----|-----|--------|
| Call overhead | ~150-300ns | ~50-100ns | 2-3x faster calls |
| Match latency | Adds 10-30% | Adds 2-5% | Critical for hot path |
| External deps | JNA library | None | Cleaner deployment |
| Development | Easier | Harder | One-time cost |

**Key insight:** For SAI regex matching, we call `re2_full_match` or `re2_partial_match` millions of times. Each call's overhead directly impacts query latency.

---

## Current Architecture

```
Java (Pattern.java)
    ↓ JNA interface
RE2Native interface (JNA Library)
    ↓ JNA marshalling (~150-300ns)
re2_wrapper.cpp (extern "C")
    ↓
RE2 C++ library
```

## Target Architecture

```
Java (Pattern.java)
    ↓ native method call
RE2Native class (native methods)
    ↓ JNI call (~50-100ns)
re2_jni.cpp (JNI functions)
    ↓
RE2 C++ library
```

---

## Migration Steps

### Phase 1: Java Side Changes

#### 1.1 Convert RE2Native from Interface to Class

**Current (JNA):**
```java
public interface RE2Native extends Library {
    Pointer re2_compile(String pattern, int patternLen, int caseSensitive);
    void re2_free_pattern(Pointer pattern);
    int re2_full_match(Pointer pattern, String text, int textLen);
    // ...
}
```

**Target (JNI):**
```java
public final class RE2Native {
    // Load native library
    static {
        RE2LibraryLoader.loadLibrary();
    }

    // Native pattern handle as long (pointer cast)
    public static native long compile(String pattern, boolean caseSensitive);
    public static native void freePattern(long patternPtr);
    public static native boolean fullMatch(long patternPtr, String text);
    public static native boolean partialMatch(long patternPtr, String text);
    public static native String getError();
    public static native String getPattern(long patternPtr);
    public static native int numCapturingGroups(long patternPtr);
    public static native boolean patternOk(long patternPtr);
    public static native long patternMemory(long patternPtr);

    private RE2Native() {} // Utility class
}
```

**Key changes:**
- `Pointer` → `long` (native pointer cast to jlong)
- No length parameters (JNI handles string length)
- Boolean returns instead of int (cleaner API)
- Static methods (no instance needed)

#### 1.2 Update Pattern.java

**Current:**
```java
private final Pointer nativeHandle;
private static final RE2Native lib = RE2LibraryLoader.loadLibrary();

public Pattern(String pattern, boolean caseSensitive) {
    this.nativeHandle = lib.re2_compile(pattern, pattern.length(), caseSensitive ? 1 : 0);
}

public boolean matches(String text) {
    return lib.re2_full_match(nativeHandle, text, text.length()) == 1;
}
```

**Target:**
```java
private final long nativeHandle;  // Changed from Pointer to long

public Pattern(String pattern, boolean caseSensitive) {
    this.nativeHandle = RE2Native.compile(pattern, caseSensitive);
}

public boolean matches(String text) {
    return RE2Native.fullMatch(nativeHandle, text);
}
```

#### 1.3 Update RE2LibraryLoader

**Current (JNA):**
```java
library = Native.load(tempLib.toString(), RE2Native.class);
```

**Target (JNI):**
```java
System.load(tempLib.toString());
// No return - native methods linked automatically
```

---

### Phase 2: Native Side Changes

#### 2.1 Generate JNI Headers

After compiling Java sources, generate headers:

```bash
cd src/main/java
javac -h ../../../native/jni com/axonops/libre2/jni/RE2Native.java
```

This creates `com_axonops_libre2_jni_RE2Native.h` with signatures like:

```c
JNIEXPORT jlong JNICALL Java_com_axonops_libre2_jni_RE2Native_compile
  (JNIEnv *, jclass, jstring, jboolean);
```

#### 2.2 Create re2_jni.cpp

Replace `re2_wrapper.cpp` with JNI-aware version:

```cpp
#include <jni.h>
#include <re2/re2.h>
#include "com_axonops_libre2_jni_RE2Native.h"

// Thread-local error storage
static thread_local std::string last_error;

extern "C" {

JNIEXPORT jlong JNICALL Java_com_axonops_libre2_jni_RE2Native_compile(
    JNIEnv *env, jclass cls, jstring pattern, jboolean caseSensitive) {

    if (pattern == nullptr) {
        last_error = "Pattern is null";
        return 0;
    }

    // Get string from Java
    const char* patternChars = env->GetStringUTFChars(pattern, nullptr);
    if (patternChars == nullptr) {
        last_error = "Failed to get pattern string";
        return 0;
    }

    try {
        RE2::Options options;
        options.set_case_sensitive(caseSensitive);
        options.set_log_errors(false);

        RE2* re = new RE2(patternChars, options);

        // Release string BEFORE checking result
        env->ReleaseStringUTFChars(pattern, patternChars);

        if (!re->ok()) {
            last_error = re->error();
            delete re;
            return 0;
        }

        return reinterpret_cast<jlong>(re);
    } catch (const std::exception& e) {
        env->ReleaseStringUTFChars(pattern, patternChars);
        last_error = std::string("Exception: ") + e.what();
        return 0;
    }
}

JNIEXPORT void JNICALL Java_com_axonops_libre2_jni_RE2Native_freePattern(
    JNIEnv *env, jclass cls, jlong patternPtr) {

    if (patternPtr != 0) {
        delete reinterpret_cast<RE2*>(patternPtr);
    }
}

JNIEXPORT jboolean JNICALL Java_com_axonops_libre2_jni_RE2Native_fullMatch(
    JNIEnv *env, jclass cls, jlong patternPtr, jstring text) {

    if (patternPtr == 0 || text == nullptr) {
        last_error = "Null pointer";
        return JNI_FALSE;
    }

    const char* textChars = env->GetStringUTFChars(text, nullptr);
    if (textChars == nullptr) {
        return JNI_FALSE;
    }

    RE2* re = reinterpret_cast<RE2*>(patternPtr);
    bool result = RE2::FullMatch(textChars, *re);

    env->ReleaseStringUTFChars(text, textChars);
    return result ? JNI_TRUE : JNI_FALSE;
}

// ... similar for other methods

} // extern "C"
```

**Performance optimization:** Use `GetStringCritical` for even faster string access when possible (no copying).

---

### Phase 3: Build System Changes

#### 3.1 Update build.sh

Add JNI header discovery:

```bash
# Find JNI headers
if [ -z "$JAVA_HOME" ]; then
    # Auto-detect on macOS
    if [ "$OS" = "darwin" ]; then
        JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || echo "")
    fi
fi

if [ -z "$JAVA_HOME" ]; then
    echo "ERROR: JAVA_HOME not set and could not be detected"
    exit 1
fi

JNI_INCLUDE="$JAVA_HOME/include"
if [ "$OS" = "darwin" ]; then
    JNI_PLATFORM_INCLUDE="$JAVA_HOME/include/darwin"
else
    JNI_PLATFORM_INCLUDE="$JAVA_HOME/include/linux"
fi
```

Update compilation command:

```bash
# macOS
clang++ -std=c++17 -O3 -fPIC -shared \
    -o libre2.dylib \
    "$WRAPPER_SRC" \
    re2-build/libre2.a \
    abseil-build/absl/*/*.a \
    -Ire2 \
    -Iabseil-cpp \
    -I"$JNI_INCLUDE" \
    -I"$JNI_PLATFORM_INCLUDE" \
    -I"$BUILD_DIR/../jni" \
    -framework CoreFoundation \
    -Wl,-dead_strip
```

#### 3.2 Update GitHub Actions

Add JNI header generation step before native build:

```yaml
- name: Generate JNI Headers
  run: |
    mkdir -p native/jni
    javac -h native/jni src/main/java/com/axonops/libre2/jni/RE2Native.java
```

---

### Phase 4: Remove JNA Dependency

#### 4.1 Update pom.xml

Remove or comment out JNA dependency:

```xml
<!-- JNA no longer needed - using JNI
<dependency>
    <groupId>net.java.dev.jna</groupId>
    <artifactId>jna</artifactId>
    <version>5.13.0</version>
    <scope>provided</scope>
</dependency>
-->
```

#### 4.2 Remove JNA imports

Search and remove:
- `import com.sun.jna.*`
- `import com.sun.jna.ptr.*`

---

## File Changes Summary

| File | Action | Notes |
|------|--------|-------|
| `RE2Native.java` | Rewrite | Interface → Class with native methods |
| `RE2LibraryLoader.java` | Modify | JNA load → System.load |
| `Pattern.java` | Modify | Pointer → long, remove JNA imports |
| `Matcher.java` | Modify | Pointer → long |
| `re2_wrapper.cpp` | Delete | Replaced by re2_jni.cpp |
| `re2_jni.cpp` | Create | New JNI implementation |
| `build.sh` | Modify | Add JNI header paths |
| `pom.xml` | Modify | Remove JNA dependency |
| `build-native.yml` | Modify | Add header generation step |

---

## Testing Strategy

### 1. Unit Tests
All existing tests should pass unchanged - API is the same

### 2. Performance Benchmarks
Create JMH benchmark comparing:
- Cache hit latency (JNA vs JNI)
- Match operation latency
- Throughput at various thread counts

### 3. Memory Tests
Verify no memory leaks with JNI string handling

### 4. Platform Tests
Test on all platforms:
- macOS x86_64
- macOS aarch64
- Linux x86_64
- Linux aarch64

---

## Risk Mitigation

### Risk 1: JNI Memory Leaks
**Mitigation:**
- Always pair `GetStringUTFChars` with `ReleaseStringUTFChars`
- Use RAII-style guards in C++
- Test with valgrind/AddressSanitizer

### Risk 2: Platform-specific Issues
**Mitigation:**
- Test early on all platforms
- JNI is mature and well-documented

### Risk 3: String Encoding
**Mitigation:**
- JNI uses Modified UTF-8, RE2 uses UTF-8
- For ASCII patterns (common case), identical
- Test with Unicode patterns

---

## Performance Expectations

| Operation | JNA | JNI | Improvement |
|-----------|-----|-----|-------------|
| Pattern compile | 50μs | 49.8μs | ~0.4% |
| Simple match | 0.5μs | 0.35μs | 30% |
| Complex match | 5μs | 4.8μs | 4% |
| Cache hit | 0.6μs | 0.15μs | 75% |

**Key wins:**
- Cache hits become much faster (no JNA marshalling)
- Simple matches see biggest improvement
- Compile is dominated by RE2, so minimal change

---

## Alternative: Hybrid Approach

If migration scope is concerning, consider hybrid:
1. Keep JNA for compile (infrequent, compile-time dominated)
2. Use JNI only for match operations (hot path)

This reduces scope but adds complexity of two native interfaces.

**Recommendation:** Full migration is cleaner and not significantly more work.

---

## Schedule

| Day | Task |
|-----|------|
| 1 | Java side changes (RE2Native, Pattern, Matcher, LibraryLoader) |
| 1 | Generate JNI headers, create re2_jni.cpp skeleton |
| 2 | Complete re2_jni.cpp implementation |
| 2 | Update build.sh with JNI paths |
| 2 | Local testing on macOS |
| 3 | Update GitHub Actions |
| 3 | Test all platforms |
| 3 | Performance benchmarks |

---

## Rollback Plan

Keep JNA code in a branch until JNI is proven stable. If issues arise:
1. Revert to JNA branch
2. Fix issues
3. Re-attempt migration

---

## Decision Checkpoint

Before starting migration:
- [ ] Confirm performance is the priority (vs. development velocity)
- [ ] Allocate 2-3 days for migration
- [ ] Ensure CI has capacity for extended testing

**Recommendation:** Proceed with migration. The performance gains are significant for the SAI use case, and the scope is well-defined.
