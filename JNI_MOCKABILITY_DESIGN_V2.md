# JNI Mockability Design V2 - Package-Private Enforcement

**Improvement:** Make RE2NativeJNI package-private so ONLY DirectJniAdapter can access it

---

## Updated Design: Compile-Time Enforcement

### 1. RE2NativeJNI - Package-Private Native Methods

```java
package com.axonops.libre2.jni;

/**
 * JNI bindings to RE2 native library.
 *
 * <p><b>IMPORTANT:</b> All methods are package-private. External code must use
 * Pattern/Matcher/RE2 API. Direct JNI access is only available to DirectJniAdapter.
 *
 * <p>This design enables:
 * <ul>
 *   <li>Mockability - DirectJniAdapter implements JniAdapter interface</li>
 *   <li>Encapsulation - No direct JNI calls from API classes</li>
 *   <li>Testability - Tests can inject mock JniAdapter</li>
 * </ul>
 */
final class RE2NativeJNI {

    private RE2NativeJNI() {
        // Utility class - prevent instantiation
    }

    // ========== Pattern Lifecycle ==========

    /**
     * Compile a pattern. Package-private - use via DirectJniAdapter only.
     */
    static native long compile(String pattern, boolean caseSensitive);

    /**
     * Free compiled pattern. Package-private - use via DirectJniAdapter only.
     */
    static native void freePattern(long handle);

    /**
     * Check if pattern is valid. Package-private - use via DirectJniAdapter only.
     */
    static native boolean patternOk(long handle);

    /**
     * Get last compilation error. Package-private - use via DirectJniAdapter only.
     */
    static native String getError();

    // ... all 29 methods as package-private (no visibility modifier)

    // ========== Matching Operations ==========

    static native boolean fullMatch(long handle, String text);
    static native boolean partialMatch(long handle, String text);
    static native boolean fullMatchDirect(long handle, long address, int length);
    static native boolean partialMatchDirect(long handle, long address, int length);

    // ========== Bulk Operations ==========

    static native boolean[] fullMatchBulk(long handle, String[] texts);
    static native boolean[] partialMatchBulk(long handle, String[] texts);
    static native boolean[] fullMatchDirectBulk(long handle, long[] addresses, int[] lengths);
    static native boolean[] partialMatchDirectBulk(long handle, long[] addresses, int[] lengths);

    // ========== Capture Groups ==========

    static native String[] extractGroups(long handle, String text);
    static native String[][] extractGroupsBulk(long handle, String[] texts);
    static native String[] extractGroupsDirect(long handle, long address, int length);
    static native String[][] extractGroupsDirectBulk(long handle, long[] addresses, int[] lengths);
    static native String[][] findAllMatches(long handle, String text);
    static native String[][] findAllMatchesDirect(long handle, long address, int length);
    static native String[] getNamedGroups(long handle);

    // ========== Replace Operations ==========

    static native String replaceFirst(long handle, String text, String replacement);
    static native String replaceAll(long handle, String text, String replacement);
    static native String[] replaceAllBulk(long handle, String[] texts, String replacement);
    static native String replaceFirstDirect(long handle, long address, int length, String replacement);
    static native String replaceAllDirect(long handle, long address, int length, String replacement);
    static native String[] replaceAllDirectBulk(long handle, long[] addresses, int[] lengths, String replacement);

    // ========== Utility Methods ==========

    static native String quoteMeta(String text);
    static native int[] getProgramFanout(long handle);
    static native long getProgramSize(long handle);
}
```

### 2. JniAdapter Interface (Package-Private)

```java
package com.axonops.libre2.jni;

/**
 * Adapter interface for RE2 JNI operations.
 * Enables mocking for unit tests while maintaining production performance.
 *
 * <p><b>Package-private:</b> Not part of public API. Used internally by Pattern/Matcher/RE2.
 */
interface JniAdapter {
    // Pattern lifecycle
    long compile(String pattern, boolean caseSensitive);
    void freePattern(long handle);
    boolean patternOk(long handle);
    String getError();
    String getPattern(long handle);
    int numCapturingGroups(long handle);
    long patternMemory(long handle);

    // Matching operations
    boolean fullMatch(long handle, String text);
    boolean partialMatch(long handle, String text);
    boolean fullMatchDirect(long handle, long address, int length);
    boolean partialMatchDirect(long handle, long address, int length);

    // Bulk operations
    boolean[] fullMatchBulk(long handle, String[] texts);
    boolean[] partialMatchBulk(long handle, String[] texts);
    boolean[] fullMatchDirectBulk(long handle, long[] addresses, int[] lengths);
    boolean[] partialMatchDirectBulk(long handle, long[] addresses, int[] lengths);

    // Capture groups
    String[] extractGroups(long handle, String text);
    String[][] extractGroupsBulk(long handle, String[] texts);
    String[] extractGroupsDirect(long handle, long address, int length);
    String[][] extractGroupsDirectBulk(long handle, long[] addresses, int[] lengths);
    String[][] findAllMatches(long handle, String text);
    String[][] findAllMatchesDirect(long handle, long address, int length);
    String[] getNamedGroups(long handle);

    // Replace operations
    String replaceFirst(long handle, String text, String replacement);
    String replaceAll(long handle, String text, String replacement);
    String[] replaceAllBulk(long handle, String[] texts, String replacement);
    String replaceFirstDirect(long handle, long address, int length, String replacement);
    String replaceAllDirect(long handle, long address, int length, String replacement);
    String[] replaceAllDirectBulk(long handle, long[] addresses, int[] lengths, String replacement);

    // Utility methods
    String quoteMeta(String text);
    int[] getProgramFanout(long handle);
    long getProgramSize(long handle);
}
```

### 3. DirectJniAdapter (Package-Private, Same Package)

```java
package com.axonops.libre2.jni;

/**
 * Production JNI adapter - delegates to package-private RE2NativeJNI.
 *
 * <p>Singleton instance used by all Pattern/Matcher/RE2 instances in production.
 * Tests can inject mock JniAdapter instead.
 *
 * <p><b>Package-private:</b> Not part of public API. Accessed via Pattern injection.
 */
final class DirectJniAdapter implements JniAdapter {

    /**
     * Singleton instance - used in production.
     * Package-private so Pattern can access it.
     */
    static final DirectJniAdapter INSTANCE = new DirectJniAdapter();

    private DirectJniAdapter() {
        // Private constructor - singleton pattern
    }

    // ========== Pattern Lifecycle ==========

    @Override
    public long compile(String pattern, boolean caseSensitive) {
        return RE2NativeJNI.compile(pattern, caseSensitive);  // ✅ Same package - accessible
    }

    @Override
    public void freePattern(long handle) {
        RE2NativeJNI.freePattern(handle);  // ✅ Same package - accessible
    }

    @Override
    public boolean patternOk(long handle) {
        return RE2NativeJNI.patternOk(handle);  // ✅ Same package - accessible
    }

    @Override
    public String getError() {
        return RE2NativeJNI.getError();  // ✅ Same package - accessible
    }

    // ... delegate all 29 methods to RE2NativeJNI

    // All calls work because DirectJniAdapter is in same package as RE2NativeJNI
}
```

### 4. Pattern Uses JniAdapter (Different Package)

```java
package com.axonops.libre2.api;

import com.axonops.libre2.jni.JniAdapter;
import com.axonops.libre2.jni.DirectJniAdapter;

public final class Pattern implements AutoCloseable {

    // Package-private JniAdapter field
    final JniAdapter jni;

    private final long nativeHandle;
    private final String pattern;
    // ... other fields

    // PRIVATE constructor
    private Pattern(JniAdapter jni, String pattern, boolean caseSensitive, PatternCache cache) {
        this.jni = jni;
        this.pattern = pattern;
        this.cache = cache;

        // Compile using adapter
        long handle = jni.compile(pattern, caseSensitive);  // ✅ Goes through interface

        // ❌ CANNOT do this - RE2NativeJNI is package-private in different package:
        // long handle = RE2NativeJNI.compile(pattern, caseSensitive);  // COMPILE ERROR!

        if (handle == 0 || !jni.patternOk(handle)) {
            String error = jni.getError();
            throw new PatternCompilationException("Failed to compile pattern: " + error);
        }
        this.nativeHandle = handle;
        // ...
    }

    // PUBLIC API - uses production singleton adapter
    public static Pattern compile(String pattern) {
        return compile(pattern, true);
    }

    public static Pattern compile(String pattern, boolean caseSensitive) {
        return compile(pattern, caseSensitive, DirectJniAdapter.INSTANCE);
    }

    // PACKAGE-PRIVATE - tests inject mock adapter
    static Pattern compile(String pattern, boolean caseSensitive, JniAdapter jni) {
        PatternCache cache = getGlobalCache();
        return new Pattern(jni, pattern, caseSensitive, cache);
    }

    // All operations use this.jni (enforced at compile-time)
    public boolean match(String input) {
        checkNotClosed();
        Objects.requireNonNull(input, "input cannot be null");

        long startNanos = System.nanoTime();
        boolean result = jni.fullMatch(nativeHandle, input);  // ✅ Must use adapter
        // boolean result = RE2NativeJNI.fullMatch(...);      // ❌ COMPILE ERROR!
        long durationNanos = System.nanoTime() - startNanos;

        // ... metrics
        return result;
    }
}
```

---

## Benefits of Package-Private RE2NativeJNI

### ✅ 1. Compile-Time Enforcement

**Before (public RE2NativeJNI):**
```java
// Pattern.java - could accidentally bypass abstraction
boolean result = RE2NativeJNI.fullMatch(handle, text);  // ✅ Compiles (bad design)
```

**After (package-private RE2NativeJNI):**
```java
// Pattern.java - MUST use adapter
boolean result = RE2NativeJNI.fullMatch(handle, text);  // ❌ COMPILE ERROR!
boolean result = jni.fullMatch(handle, text);           // ✅ Must use interface
```

### ✅ 2. Clear Separation of Concerns

```
com.axonops.libre2.jni/           (JNI layer - isolated)
├── RE2NativeJNI.java             (package-private native methods)
├── JniAdapter.java               (package-private interface)
└── DirectJniAdapter.java         (package-private singleton)

com.axonops.libre2.api/           (Public API - uses interface)
├── Pattern.java                  (uses JniAdapter, cannot access RE2NativeJNI)
├── Matcher.java                  (uses JniAdapter, cannot access RE2NativeJNI)
└── RE2.java                      (uses JniAdapter, cannot access RE2NativeJNI)
```

### ✅ 3. Impossible to Bypass Abstraction

**Users cannot do this:**
```java
// This would compile if RE2NativeJNI were public
long handle = RE2NativeJNI.compile("test", true);  // ❌ COMPILE ERROR - package-private
RE2NativeJNI.freePattern(handle);                  // ❌ COMPILE ERROR - package-private
```

**Must use public API:**
```java
Pattern pattern = Pattern.compile("test");  // ✅ Only way
```

### ✅ 4. Tests Still Work (Same Package)

```java
package com.axonops.libre2.api;  // Different package from RE2NativeJNI

import com.axonops.libre2.jni.JniAdapter;
import org.mockito.Mockito;

class PatternUnitTest {
    @Test
    void testMatch() {
        JniAdapter mock = mock(JniAdapter.class);
        when(mock.compile("test", true)).thenReturn(123L);
        when(mock.fullMatch(123L, "test")).thenReturn(true);

        Pattern p = Pattern.compile("test", true, mock);  // ✅ Package-private method
        boolean result = p.match("test");

        verify(mock).fullMatch(123L, "test");  // ✅ Can verify interface calls
    }
}
```

---

## Implementation Changes

### Change 1: RE2NativeJNI Visibility

```java
// BEFORE (current):
public final class RE2NativeJNI {
    public static native long compile(String pattern, boolean caseSensitive);
    // ...
}

// AFTER (package-private):
final class RE2NativeJNI {
    static native long compile(String pattern, boolean caseSensitive);
    // ... all methods package-private
}
```

### Change 2: Pattern/Matcher/RE2 MUST Use JniAdapter

```java
// BEFORE:
boolean result = RE2NativeJNI.fullMatch(handle, text);

// AFTER:
boolean result = jni.fullMatch(handle, text);
```

**Compiler enforces this change** - any direct RE2NativeJNI calls in Pattern/Matcher/RE2 will fail to compile.

---

## Summary

**Your suggestion is perfect!** Making RE2NativeJNI package-private:

1. ✅ **Works with native methods** - Visibility doesn't affect JNI name mangling
2. ✅ **Enforces abstraction** - Compile error if bypassed
3. ✅ **Zero runtime cost** - Same performance as direct calls
4. ✅ **Enables testing** - Mock JniAdapter interface
5. ✅ **Clean architecture** - JNI layer isolated in one package

**Next Steps:**
1. Implement package-private RE2NativeJNI
2. Create JniAdapter interface and DirectJniAdapter
3. Update Pattern/Matcher/RE2 to use JniAdapter field
4. Verify all existing tests pass (integration tests unchanged)
5. Add new unit tests with mocked JniAdapter

**Approved for implementation?**
