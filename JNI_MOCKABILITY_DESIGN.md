# JNI Mockability Design - Clean Interface Abstraction

**Goal:** Make all native calls mockable for unit testing without breaking existing API

---

## Design: Internal JniAdapter with Package-Private Injection

### 1. Create JniAdapter Interface (Package-Private)

```java
package com.axonops.libre2.jni;

/**
 * Adapter interface for RE2 JNI operations.
 * Package-private for testing - not part of public API.
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
}
```

### 2. Production Implementation (Package-Private)

```java
package com.axonops.libre2.jni;

/**
 * Production JNI adapter - delegates directly to RE2NativeJNI.
 * Package-private - not part of public API.
 */
class DirectJniAdapter implements JniAdapter {

    // Singleton instance
    static final DirectJniAdapter INSTANCE = new DirectJniAdapter();

    private DirectJniAdapter() {
        // Private constructor
    }

    @Override
    public long compile(String pattern, boolean caseSensitive) {
        return RE2NativeJNI.compile(pattern, caseSensitive);
    }

    @Override
    public void freePattern(long handle) {
        RE2NativeJNI.freePattern(handle);
    }

    // ... delegate all 29 methods to RE2NativeJNI
}
```

### 3. Pattern Internal Field (Package-Private Injection Point)

```java
package com.axonops.libre2.api;

public final class Pattern implements AutoCloseable {

    // Package-private for testing - production uses singleton
    final JniAdapter jni;

    private final long nativeHandle;
    private final String pattern;
    // ... other fields

    // PRIVATE constructor - used internally
    private Pattern(JniAdapter jni, String pattern, boolean caseSensitive, PatternCache cache) {
        this.jni = jni;
        this.pattern = pattern;
        this.cache = cache;

        // Compile using adapter
        long handle = jni.compile(pattern, caseSensitive);
        if (handle == 0 || !jni.patternOk(handle)) {
            String error = jni.getError();
            throw new PatternCompilationException("Failed to compile pattern: " + error);
        }
        this.nativeHandle = handle;
        // ... rest of initialization
    }

    // PUBLIC API - unchanged, uses production adapter
    public static Pattern compile(String pattern) {
        return compile(pattern, true);
    }

    public static Pattern compile(String pattern, boolean caseSensitive) {
        // Production code uses singleton DirectJniAdapter
        return compile(pattern, caseSensitive, DirectJniAdapter.INSTANCE);
    }

    // PACKAGE-PRIVATE for testing - inject mock adapter
    static Pattern compile(String pattern, boolean caseSensitive, JniAdapter jni) {
        PatternCache cache = getGlobalCache();
        // ... cache lookup logic
        return new Pattern(jni, pattern, caseSensitive, cache);
    }

    // All operations use this.jni instead of RE2NativeJNI directly
    public boolean match(String input) {
        checkNotClosed();
        Objects.requireNonNull(input, "input cannot be null");

        long startNanos = System.nanoTime();
        boolean result = jni.fullMatch(nativeHandle, input);  // Uses adapter!
        long durationNanos = System.nanoTime() - startNanos;

        // ... metrics recording
        return result;
    }

    // ... all other methods use this.jni
}
```

### 4. Test Usage - Clean and Powerful

```java
package com.axonops.libre2.api;

import com.axonops.libre2.jni.JniAdapter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PatternUnitTest {

    @Test
    void testMatch_callsCorrectJniMethod() {
        // Create mock adapter
        JniAdapter mockJni = mock(JniAdapter.class);

        // Setup expectations
        when(mockJni.compile("test\\d+", true)).thenReturn(12345L);
        when(mockJni.patternOk(12345L)).thenReturn(true);
        when(mockJni.numCapturingGroups(12345L)).thenReturn(0);
        when(mockJni.patternMemory(12345L)).thenReturn(1024L);
        when(mockJni.fullMatch(12345L, "test123")).thenReturn(true);

        // Create pattern with mock adapter (package-private method)
        Pattern pattern = Pattern.compile("test\\d+", true, mockJni);

        // Execute
        boolean result = pattern.match("test123");

        // Verify
        assertThat(result).isTrue();
        verify(mockJni).compile("test\\d+", true);
        verify(mockJni).fullMatch(12345L, "test123");
        verifyNoMoreInteractions(mockJni);
    }

    @Test
    void testReplaceAll_callsCorrectJniMethod() {
        JniAdapter mockJni = mock(JniAdapter.class);

        when(mockJni.compile("\\d+", true)).thenReturn(67890L);
        when(mockJni.patternOk(67890L)).thenReturn(true);
        when(mockJni.numCapturingGroups(67890L)).thenReturn(0);
        when(mockJni.patternMemory(67890L)).thenReturn(512L);
        when(mockJni.replaceAll(67890L, "test123", "XXX")).thenReturn("testXXX");

        Pattern pattern = Pattern.compile("\\d+", true, mockJni);
        String result = pattern.replaceAll("test123", "XXX");

        assertThat(result).isEqualTo("testXXX");
        verify(mockJni).replaceAll(67890L, "test123", "XXX");
    }

    @Test
    void testBulkMatch_callsCorrectBulkJniMethod() {
        JniAdapter mockJni = mock(JniAdapter.class);

        when(mockJni.compile("test", true)).thenReturn(11111L);
        when(mockJni.patternOk(11111L)).thenReturn(true);
        when(mockJni.numCapturingGroups(11111L)).thenReturn(0);
        when(mockJni.patternMemory(11111L)).thenReturn(256L);

        String[] inputs = {"test1", "test2", "other"};
        boolean[] expected = {true, true, false};
        when(mockJni.fullMatchBulk(11111L, inputs)).thenReturn(expected);

        Pattern pattern = Pattern.compile("test", true, mockJni);
        boolean[] results = pattern.matchAll(inputs);

        assertThat(results).isEqualTo(expected);
        verify(mockJni).fullMatchBulk(11111L, inputs);
    }
}
```

---

## Benefits of This Design

### ✅ 1. Public API Unchanged
```java
// Users still write this - no breaking changes
Pattern p = Pattern.compile("test\\d+");
boolean match = p.match("test123");
```

### ✅ 2. Full Test Control
```java
// Tests can inject mock and verify exact calls
JniAdapter mock = mock(JniAdapter.class);
Pattern p = Pattern.compile("test", true, mock);
verify(mock).fullMatch(eq(12345L), eq("test123"));
```

### ✅ 3. Package-Private Design
- `JniAdapter` interface is NOT public
- `DirectJniAdapter` is NOT public
- Only `Pattern.compile(pattern, caseSensitive, JniAdapter)` is package-private
- Tests in same package can access it
- Users cannot misuse it

### ✅ 4. Zero Runtime Overhead
- Production code uses singleton `DirectJniAdapter.INSTANCE`
- No interface overhead (JIT inlines static final calls)
- Same performance as direct static calls

### ✅ 5. Comprehensive Test Coverage
Can now unit test:
- ✅ Parameter validation before JNI calls
- ✅ Metrics recording logic
- ✅ Resource tracking
- ✅ Error handling paths
- ✅ Bulk operation batching logic
- ✅ DirectByteBuffer address extraction
- ✅ Cache interaction logic

---

## Implementation Strategy

### Phase 2A: Create Abstraction (Before Test Migration)
1. Create `JniAdapter` interface (package-private)
2. Create `DirectJniAdapter` implementation (package-private)
3. Update `Pattern` to use `jni` field instead of `RE2NativeJNI` static calls
4. Update `Matcher`, `RE2` similarly
5. Run full integration test suite - should all pass (no behavior change)

### Phase 2B: Test Migration (With Mockability)
6. Create new unit tests using mock JniAdapter
7. Migrate existing tests to appropriate directories
8. Verify all tests still pass

---

## File Structure

```
libre2-core/src/main/java/com/axonops/libre2/jni/
├── RE2NativeJNI.java          (unchanged - native methods)
├── RE2LibraryLoader.java      (unchanged - library loading)
├── JniAdapter.java            (NEW - package-private interface)
└── DirectJniAdapter.java      (NEW - package-private singleton)

libre2-core/src/test/java/com/axonops/libre2/api/
├── PatternUnitTest.java       (NEW - mocked JNI tests)
├── MatcherUnitTest.java       (NEW - mocked JNI tests)
└── RE2UnitTest.java           (NEW - mocked JNI tests)
```

---

## Example: Testing Metrics Recording Without Native Library

```java
@Test
void testMatchAll_recordsCorrectMetrics() {
    JniAdapter mockJni = mock(JniAdapter.class);
    RE2MetricsRegistry mockMetrics = mock(RE2MetricsRegistry.class);

    // Setup
    when(mockJni.compile("test", true)).thenReturn(123L);
    when(mockJni.patternOk(123L)).thenReturn(true);
    when(mockJni.numCapturingGroups(123L)).thenReturn(0);
    when(mockJni.patternMemory(123L)).thenReturn(100L);
    when(mockJni.fullMatchBulk(eq(123L), any())).thenReturn(new boolean[]{true, false, true});

    // Create pattern with mock metrics
    PatternCache cache = new PatternCache(RE2Config.builder()
        .metricsRegistry(mockMetrics)
        .build());
    Pattern pattern = Pattern.compile("test", true, mockJni, cache);

    // Execute
    String[] inputs = {"test1", "test2", "test3"};
    pattern.matchAll(inputs);

    // Verify metrics (without running native code!)
    verify(mockMetrics).incrementCounter("re2.matching.operations.total.count", 3);
    verify(mockMetrics).incrementCounter("re2.matching.bulk.operations.total.count", 1);
    verify(mockMetrics).incrementCounter("re2.matching.bulk.items.total.count", 3);
    verify(mockMetrics, times(2)).recordTimer(eq("re2.matching.latency"), anyLong());
}
```

---

## Decision Point

**Do you approve this design?**

If yes, I'll implement it in Phase 2A before any test migration. This gives us:
- ✅ Full mockability of all native calls
- ✅ Ability to assert correct JNI parameters
- ✅ Unit tests for all business logic
- ✅ No public API changes
- ✅ No runtime overhead

**Alternative:** If you have a different approach in mind, I'm open to it. The key requirement is: **mock all native calls to verify correct parameters**.
