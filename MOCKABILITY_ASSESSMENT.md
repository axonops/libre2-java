# Mockability and Unit Test Strategy Assessment

**Date:** 2025-11-26
**Purpose:** Corrected analysis of what can be unit tested without native library

---

## The Static Method Problem

**All JNI methods are `public static native`:**
```java
public class RE2NativeJNI {
    public static native long compile(String pattern, boolean caseSensitive);
    public static native void freePattern(long handle);
    public static native boolean fullMatch(long handle, String text);
    // ... 26 more static native methods
}
```

**Implications:**
- Cannot use traditional interface-based dependency injection
- Mocking static methods requires:
  - **Mockito 3.4+ with mockito-inline** (can mock statics in JUnit 5)
  - **PowerMock** (deprecated, poor JUnit 5 support)
- Most Pattern/Matcher/RE2 logic IS the native call - minimal business logic to test

---

## What's Actually Unit-Testable?

### Files That DON'T Call Native Code (16 files)

**Pure Java, No Native Dependencies:**

#### 1. Configuration & Builders ✅
- `RE2Config.java` - Builder pattern, validation
- `MetricNames.java` - String constants
- **Already tested:** `ConfigurationTest.java` (14 tests) ✅

#### 2. Metrics Abstractions ✅
- `RE2MetricsRegistry.java` - Interface
- `NoOpMetricsRegistry.java` - No-op implementation
- `DropwizardMetricsAdapter.java` - Adapter (can mock MetricRegistry)
- **Already tested:** `TimerHistogramTest.java` (4 tests) ✅
- **Testable:** Adapter logic without Dropwizard

#### 3. Exception Classes ✅
- `RE2Exception.java` (sealed base)
- `PatternCompilationException.java`
- `NativeLibraryException.java`
- `ResourceException.java`
- `RE2TimeoutException.java`
- **Already tested:** Implicitly in integration tests
- **Testable:** Exception hierarchies, messages, causes

#### 4. Value Objects ✅
- `MatchResult.java` - Holds capture groups, implements AutoCloseable
- `CacheStatistics.java` - Immutable stats record
- **Already tested:** `CaptureGroupsTest.java` (31 tests) ✅
- **Testable:** MatchResult lifecycle, closed state checking

#### 5. Utilities ✅
- `PatternHasher.java` - Pattern hash computation
- `ResourceTracker.java` - Resource tracking logic
- **Testable:** Hash consistency, resource accounting

#### 6. Cache Logic (Partially Testable)
- `PatternCache.java` - Cache management
- `IdleEvictionTask.java` - Background eviction
- **Issue:** Cache stores compiled Patterns (which need native library)
- **Mockable:** LRU eviction logic, idle timeout calculation, statistics
- **Already tested:** `CacheTest.java`, `IdleEvictionTest.java` (integration tests)

---

## What REQUIRES Native Library?

### Files That Call RE2NativeJNI (5 files)

1. **Pattern.java** - Wraps native pattern, all operations call JNI
2. **Matcher.java** - Iterator over Pattern operations
3. **RE2.java** - Static convenience methods (all delegate to Pattern)
4. **RE2LibraryLoader.java** - Loads native library
5. **RE2NativeJNI.java** - JNI method declarations

**Why integration tests are necessary:**
- Pattern compilation, matching, replacement = native operations
- Cannot mock without significant refactoring
- Business logic is minimal (metrics, validation, resource tracking)

---

## Revised Unit vs Integration Test Strategy

### True Unit Tests (No Native Library Required)

**Current Status:** 4 test classes qualify as true unit tests

1. ✅ **ConfigurationTest.java** (14 tests)
   - Tests RE2Config builder
   - No Pattern creation, no native calls

2. ✅ **TimerHistogramTest.java** (4 tests)
   - Tests pure Java histogram logic
   - No native dependencies

3. ✅ **BulkMatchingTypeSafetyTest.java** (13 tests)
   - Tests type safety, null handling
   - **WAIT:** Does this create Patterns? Need to verify

4. ✅ **RE2MetricsConfigTest.java** (6 tests) [in libre2-dropwizard]
   - Tests config factory methods
   - No Pattern creation

**Candidates for Unit Testing (with refactoring):**

1. **Exception hierarchy tests** - Create new test class
2. **PatternHasher tests** - Create new test class
3. **ResourceTracker tests** - Create new test class (or mock Pattern)
4. **MatchResult lifecycle tests** - Already covered in CaptureGroupsTest
5. **Cache eviction logic** - Requires mocking Pattern creation

### Integration Tests (Require Native Library)

**All tests that:**
- Compile patterns (Pattern.compile())
- Match text (Pattern.match(), find(), etc.)
- Use JNI layer (RE2NativeJNITest)
- Test metrics with real operations
- Test cache with real Patterns

**Count:** ~370 tests (vast majority)

---

## Mocking Strategy Assessment

### Option 1: Mock Static Methods with Mockito-Inline ❌

**Approach:**
```java
@ExtendWith(MockitoExtension.class)
class PatternUnitTest {
    @Test
    void testSomething() {
        try (MockedStatic<RE2NativeJNI> mocked = mockStatic(RE2NativeJNI.class)) {
            mocked.when(() -> RE2NativeJNI.compile("test", true)).thenReturn(12345L);
            // Test Pattern logic
        }
    }
}
```

**Problems:**
- Requires mockito-inline (adds dependency)
- Verbose setup for every test
- Most Pattern logic IS the native call
- Little business logic to test independently

**Verdict:** Not worth the complexity for minimal gain

### Option 2: Introduce Abstraction Layer ❌

**Approach:**
```java
interface JniAdapter {
    long compile(String pattern, boolean caseSensitive);
    void freePattern(long handle);
    // ... 27 more methods
}

class DirectJniAdapter implements JniAdapter {
    public long compile(String pattern, boolean caseSensitive) {
        return RE2NativeJNI.compile(pattern, caseSensitive);
    }
    // ...
}

// Pattern takes JniAdapter in constructor
class Pattern {
    private final JniAdapter jni;
    Pattern(JniAdapter jni, ...) { this.jni = jni; }
}
```

**Problems:**
- Invasive refactoring (29 methods to wrap)
- Breaks existing API (Pattern constructor changes)
- Adds complexity for every caller
- Testing benefit is minimal

**Verdict:** Too invasive, not worth it

### Option 3: Focus on Pure Java Components ✅

**Approach:**
- Unit test what doesn't need mocking (Config, Metrics, Exceptions, Utilities)
- Integration test everything that touches native code
- Accept that most tests require native library

**Benefits:**
- Clean separation of concerns
- No mocking complexity
- Integration tests already comprehensive (459 tests)
- Can still add unit tests for pure Java components

**Verdict:** This is the right approach ✅

---

## Recommendations

### Phase 3: Unit Test Foundation

**DO:**
1. ✅ Create unit tests for pure Java components:
   - Exception hierarchy tests
   - PatternHasher tests (hash consistency)
   - ResourceTracker tests (if mockable)
   - DropwizardMetricsAdapter tests (mock MetricRegistry)

2. ✅ Separate existing unit tests from integration tests:
   - Move ConfigurationTest to src/test/java (unit)
   - Move TimerHistogramTest to src/test/java (unit)
   - Verify BulkMatchingTypeSafetyTest doesn't create Patterns

3. ✅ Document what's unit vs integration testable

**DON'T:**
- ❌ Introduce JniAdapter abstraction (too invasive)
- ❌ Mock static RE2NativeJNI methods (too complex)
- ❌ Try to unit test Pattern/Matcher/RE2 without native library

### The Reality

**Most of this library IS integration testing by nature:**
- Core functionality is native regex matching
- Java layer is thin wrapper with metrics/caching
- Integration tests are comprehensive (459 tests)
- Pure unit tests have limited scope (~20-30 tests max)

**This is OK!** The library's value IS the native integration.

---

## Updated Test Classification

| Type | Count | Mockable? | Strategy |
|------|-------|-----------|----------|
| **Pure Unit Tests** | 4-6 | ✅ No mocking needed | Keep in src/test/java |
| **Integration Tests** | ~370 | ❌ Require native lib | Move to src/integration-test/java |
| **Performance Tests** | 2 | ❌ Require native lib | Move to perf-test module |
| **Stress Tests** | 4 | ❌ Require native lib | Move to perf-test module |

---

**Conclusion:** Original analysis was incomplete. Static native methods are not practically mockable. Focus on:
1. Pure Java component unit tests
2. Comprehensive integration tests (already have 370+)
3. Clear separation of test types

**End of Corrected Assessment**
