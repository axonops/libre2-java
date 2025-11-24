# Chronicle Zero-Copy Integration Progress

**Branch:** `feature/chronicle-zero-copy`
**Started:** 2025-11-24
**Last Updated:** 2025-11-24

---

## Token Usage Tracking

| Session | Start | End | Used | Notes |
|---------|-------|-----|------|-------|
| 1 (Sonnet 4.5) | 0 | 120k | 120k | Initial implementation, native build |
| 2 (Sonnet 4.5 1M) | 120k | ~173k | ~53k | Test fixes, benchmarks complete |
| 3 (Sonnet 4.5 1M) | 173k | ~255k | ~82k | Public API exposure via adapter pattern |

---

## Phase 1: Zero-Copy JNI Implementation

### Objectives
1. Update RE2NativeJNI.java with direct memory methods
2. Update re2_jni.cpp with StringPiece-based implementations
3. Create RE2DirectMemory.java helper class
4. Add Chronicle Bytes dependency (shaded to avoid version conflicts)
5. Create comprehensive tests and benchmarks

### Progress Checklist

#### Infrastructure ✅ COMPLETE
- [x] Create feature branch from development
- [x] Create chronicle_progress.md tracking file
- [x] Add Chronicle Bytes dependency with maven-shade-plugin
- [x] Configure shading to relocate Chronicle classes (`net.openhft` → `com.axonops.libre2.shaded.openhft`)
- [x] Add JVM arguments for Chronicle Java 17+ compatibility

#### Java Implementation ✅ COMPLETE
- [x] Add direct memory native method declarations to RE2NativeJNI.java
  - [x] `fullMatchDirect(long handle, long address, int length)`
  - [x] `partialMatchDirect(long handle, long address, int length)`
  - [x] `fullMatchDirectBulk(long handle, long[] addresses, int[] lengths)`
  - [x] `partialMatchDirectBulk(long handle, long[] addresses, int[] lengths)`
  - [x] `extractGroupsDirect(long handle, long address, int length)`
  - [x] `findAllMatchesDirect(long handle, long address, int length)`
- [x] Create RE2DirectMemory.java helper class
  - [x] Chronicle Bytes integration methods
  - [x] Memory lifecycle management
  - [x] Convenience methods for common operations
  - [x] Full JavaDoc documentation

#### Native (C++) Implementation ✅ COMPLETE
- [x] Add direct memory JNI functions to re2_jni.cpp
  - [x] `fullMatchDirect` - uses StringPiece for zero-copy
  - [x] `partialMatchDirect` - uses StringPiece for zero-copy
  - [x] `fullMatchDirectBulk` - bulk operations with direct memory
  - [x] `partialMatchDirectBulk` - bulk operations with direct memory
  - [x] `extractGroupsDirect` - capture groups with zero-copy input
  - [x] `findAllMatchesDirect` - find all with zero-copy input
- [x] Regenerate JNI header file
- [x] Rebuild native library (via GitHub Actions)
- [x] Update workflow to expect 26 functions (was 20)

#### Testing ✅ COMPLETE
- [x] Create DirectMemoryTest.java - 38 correctness tests (all passing)
- [x] Create ZeroCopyPerformanceTest.java - 11 benchmarks (all passing)
- [x] Fixed Chronicle Bytes memory model (must use direct, not heap)
- [x] Fixed try-with-resources (Chronicle doesn't implement AutoCloseable)

#### Documentation ✅ COMPLETE
- [x] Add comprehensive JavaDoc to all new methods
- [x] Document memory lifecycle requirements
- [x] Document performance characteristics
- [x] Add usage examples in JavaDoc

---

## Benchmark Results

### Expected Performance Gains
| Input Size | Expected Improvement |
|------------|---------------------|
| <100 bytes | 10-30% |
| 1KB-10KB | 30-50% |
| >10KB | 50-100% |

### Actual Results ✅ MEASURED

**Platform:** macOS aarch64 (Apple Silicon)
**Pattern:** Email regex (moderately complex)
**Iterations:** 10,000 per test

| Input Size | String API (ns/op) | Direct API (ns/op) | Speedup |
|------------|-------------------:|------------------:|--------:|
| 64B | 380.20 | 205.75 | **45.9%** |
| 256B | 691.03 | 182.91 | **73.5%** |
| 1KB | 1,848.31 | 194.38 | **89.5%** |
| 4KB | 6,473.85 | 141.00 | **97.8%** |
| 10KB | 15,869.94 | 151.59 | **99.0%** |
| 50KB | 77,418.88 | 148.67 | **99.8%** |
| 100KB | 155,381.58 | 141.25 | **99.9%** |

### Bulk Operations
| Operation | String API (ns/batch) | Direct API (ns/batch) | Speedup |
|-----------|----------------------:|----------------------:|--------:|
| 100 x 1KB | 186,397.42 | 15,928.79 | **91.5%** |

**Key Findings:**
1. **Vastly exceeds expectations** - seeing 99%+ improvement for large inputs vs. expected 50-100%
2. **Consistent performance** - Direct API maintains ~150-200 ns/op regardless of input size
3. **String API degrades linearly** - Copy overhead dominates with larger inputs
4. **Bulk operations excel** - 91.5% faster for batch processing

---

## Known Issues

### Resolved Issues

**Issue 1: Chronicle Bytes requires Java 17+ JVM arguments**
- **Status:** RESOLVED
- **Description:** Chronicle Bytes needs access to JDK internals, requires `--add-opens` flags
- **Solution:** Added argLine configuration to maven-surefire-plugin with required --add-opens arguments
- **Impact:** Tests now run successfully on Java 17+

**Issue 2: Heap-backed Bytes don't support addressForRead()**
- **Status:** RESOLVED
- **Description:** `Bytes.from(String)` creates heap-backed memory which doesn't provide native addresses
- **Solution:** Use `Bytes.allocateElasticDirect()` to create off-heap memory instead
- **Impact:** All tests and helpers use direct memory allocation

**Issue 3: Chronicle Bytes doesn't implement AutoCloseable**
- **Status:** RESOLVED
- **Description:** Cannot use try-with-resources for Chronicle Bytes
- **Solution:** Created `withBytes()` helper method with try-finally and explicit `releaseLast()`
- **Impact:** Clean resource management in all tests

---

## Decision Log

### Decision 1: Maven Shade Plugin for Chronicle Bytes
- **Status:** DECIDED
- **What:** Use maven-shade-plugin to relocate Chronicle Bytes classes
- **Why:** Avoid version conflicts with existing Chronicle libraries in user's JVM
- **Trade-off:** Larger JAR size vs. guaranteed compatibility
- **Relocation:** `net.openhft` → `com.axonops.libre2.shaded.openhft`

### Decision 2: Direct Memory Method Naming
- **Status:** DECIDED
- **What:** Use `*Direct` suffix for zero-copy methods
- **Why:** Clear distinction from String-based methods
- **Examples:** `fullMatchDirect`, `partialMatchDirect`

---

## Files Modified/Created

### New Files (Phase 1 + Phase 2)
- `chronicle_progress.md` - This progress tracking file
- `libre2-core/src/main/java/com/axonops/libre2/jni/RE2DirectMemory.java` - Chronicle Bytes helper (240 lines)
- `libre2-core/src/main/java/com/axonops/libre2/api/ZeroCopyPattern.java` - Public API adapter (280 lines)
- `libre2-core/src/main/java/com/axonops/libre2/api/ZeroCopyRE2.java` - Public API convenience (172 lines)
- `libre2-core/src/test/java/com/axonops/libre2/jni/DirectMemoryTest.java` - 38 JNI layer tests
- `libre2-core/src/test/java/com/axonops/libre2/jni/ZeroCopyPerformanceTest.java` - 11 benchmarks
- `libre2-core/src/test/java/com/axonops/libre2/api/ChroniclePublicApiTest.java` - 33 public API tests

### Modified Files
- `pom.xml` - Chronicle dependency version + shade plugin
- `libre2-core/pom.xml` - Chronicle dependency + shade plugin config + surefire JVM args
- `libre2-core/src/main/java/com/axonops/libre2/jni/RE2NativeJNI.java` - Added 6 Direct methods (+158 lines)
- `native/wrapper/re2_jni.cpp` - Native implementations (+347 lines)
- `native/jni/com_axonops_libre2_jni_RE2NativeJNI.h` - Added 6 function declarations
- `.github/workflows/build-native.yml` - Updated function count 20→26
- `libre2-core/src/main/resources/native/darwin-aarch64/libre2.dylib` - Rebuilt (+2.4KB)
- `libre2-core/src/main/resources/native/darwin-x86_64/libre2.dylib` - Rebuilt (+10.7KB)
- `libre2-core/src/main/resources/native/linux-aarch64/libre2.so` - Rebuilt (+768B)
- `libre2-core/src/main/resources/native/linux-x86_64/libre2.so` - Rebuilt (+13.6KB)

---

## Session Notes

### Session 1 (2025-11-24) - Initial Implementation
**Work completed:**
- Created feature branch `feature/chronicle-zero-copy`
- Created progress tracking file
- Analyzed existing RE2NativeJNI.java and re2_jni.cpp
- Added Chronicle Bytes dependency with maven-shade-plugin
- Implemented 6 new zero-copy JNI methods
- Created RE2DirectMemory helper class
- Created correctness tests (DirectMemoryTest.java)
- Created performance benchmarks (ZeroCopyPerformanceTest.java)
- Updated workflow to expect 26 JNI functions
- Triggered native library rebuild via GitHub Actions

**Current copy points identified:**
1. Java String → JNI GetStringUTFChars() copies to native buffer
2. JStringGuard class manages this copy with RAII
3. RE2 uses StringPiece which is zero-copy when given const char*
4. **Solution:** Pass direct memory address from Chronicle Bytes → skip steps 1 and 2

### Session 2 (2025-11-24) - Testing & Validation
**Work completed:**
- Pulled rebuilt native libraries from GitHub Actions
- Fixed Chronicle Bytes compatibility issues:
  - Added JVM arguments for Java 17+ module access
  - Changed from heap-backed to direct memory allocation
  - Fixed resource management (manual try-finally instead of try-with-resources)
- All 38 correctness tests passing
- All 11 performance benchmarks passing
- **Results:** 45.9% to 99.9% performance improvement depending on input size

**Test results:**
- DirectMemoryTest: 38/38 tests passed ✅
- ZeroCopyPerformanceTest: 11/11 benchmarks passed ✅
- Full test suite: 374/374 tests passed ✅ (no regressions)

### Session 3 (2025-11-24) - Public API Exposure
**Work completed:**
- Created adapter pattern architecture to expose zero-copy without polluting main API
- Implemented `ZeroCopyPattern` - wraps Pattern, adds Chronicle Bytes methods
- Implemented `ZeroCopyRE2` - static convenience methods
- Reverted Chronicle imports from Pattern.java and RE2.java
- Created ChroniclePublicApiTest with 33 integration tests
- All tests passing

**Design decision:**
- Rejected: Adding Chronicle Bytes methods directly to Pattern/RE2 (pollutes API)
- Chosen: Adapter pattern - users opt-in by wrapping Pattern
- Benefits: Clean main API, no shaded package imports, backward compatible

**Test results:**
- ChroniclePublicApiTest: 33/33 tests passed ✅
- Full test suite: 407/407 tests passed ✅ (no regressions)

---

## Phase 1 Summary - COMPLETE ✅

### Achievements

**Zero-Copy Implementation Complete:**
- ✅ 6 new JNI methods for direct memory access
- ✅ RE2DirectMemory helper class for Chronicle Bytes integration
- ✅ Native libraries rebuilt for all 4 platforms
- ✅ 38 correctness tests - all passing
- ✅ 11 performance benchmarks - all passing
- ✅ No regressions in existing tests (374 total)

**Performance Results:**
- **Small inputs (64-256B):** 46-74% faster
- **Medium inputs (1-4KB):** 90-98% faster
- **Large inputs (10-100KB):** 99%+ faster
- **Bulk operations:** 91.5% faster

**Key Insight:** The Direct API maintains constant ~150-200ns/op regardless of input size, while the String API degrades linearly due to copy overhead.

---

## Phase 2: Public API Exposure - COMPLETE ✅

### Objectives

Expose zero-copy functionality through clean public API while keeping Chronicle Bytes optional for users not needing it.

### Design Decision: Adapter Pattern

**Problem:** Adding Chronicle Bytes to Pattern/RE2 methods would:
- Force users to import shaded Chronicle packages
- Pollute main API with optional functionality
- Make simple use cases more complex

**Solution:** Separate adapter classes:
- `ZeroCopyPattern` - Wraps Pattern, adds Chronicle Bytes methods
- `ZeroCopyRE2` - Static convenience methods for Chronicle Bytes
- Main `Pattern` and `RE2` APIs remain Chronicle-free

### Implementation

**New Classes:**
- `ZeroCopyPattern.java` - Adapter class (280 lines)
  - Wraps Pattern via `ZeroCopyPattern.wrap(pattern)`
  - Methods: `matches(Bytes)`, `find(Bytes)`, `matchAll(Bytes[])`, `findAll(Bytes[])`, `extractGroups(Bytes)`, `findAllMatches(Bytes)`
  - Full JavaDoc with usage examples

- `ZeroCopyRE2.java` - Convenience entry point (172 lines)
  - Static methods: `compile()`, `matches()`, `find()`
  - Mirrors RE2 API but for Chronicle Bytes

**Updated Tests:**
- `ChroniclePublicApiTest.java` - 33 public API integration tests
- All tests verify adapter delegates correctly
- All tests verify results match String API

### Test Results

- **ChroniclePublicApiTest:** 33/33 tests passed ✅
- **DirectMemoryTest (JNI layer):** 38/38 tests passed ✅
- **Full test suite:** 407/407 tests passed ✅
- **No regressions**

### Usage Examples

**Option 1: Adapter Pattern (Recommended for repeated matching)**
```java
// Compile once, match many times
Pattern pattern = Pattern.compile("[a-z]+@[a-z]+\\.[a-z]+");
ZeroCopyPattern zeroCopy = ZeroCopyPattern.wrap(pattern);

Bytes<?> bytes = Bytes.allocateElasticDirect();
try {
    bytes.write("user@example.com".getBytes(StandardCharsets.UTF_8));
    boolean matches = zeroCopy.matches(bytes);  // 46-99% faster
} finally {
    bytes.releaseLast();
}
```

**Option 2: Direct Convenience Methods (One-shot matching)**
```java
Bytes<?> bytes = Bytes.allocateElasticDirect();
try {
    bytes.write("12345".getBytes(StandardCharsets.UTF_8));
    boolean matches = ZeroCopyRE2.matches("\\d+", bytes);
} finally {
    bytes.releaseLast();
}
```

**Option 3: Traditional String API (No Chronicle needed)**
```java
// Users who don't need zero-copy can use traditional API
Pattern pattern = Pattern.compile("\\d+");
boolean matches = pattern.matches("12345");  // Simple, no Chronicle
```

### Architecture Benefits

✅ **Clean separation** - Main API has no Chronicle dependencies
✅ **Optional** - Users choose String or zero-copy based on needs
✅ **No breaking changes** - Existing code works unchanged
✅ **Type safety** - No shaded package imports in user code
✅ **Backward compatible** - Pattern/RE2 APIs unchanged

---

## Next Steps (Future Phases)

**Phase 3: Chronicle Map Cache (Optional)**
- Replace PatternCache with Chronicle Map for off-heap caching
- Further reduce GC pressure
- Optional persistence for fast restarts

**Phase 4: NUMA Optimization (Advanced)**
- Per-NUMA-socket caches using Chronicle Thread Affinity
- Topology-aware pattern distribution
- For multi-socket servers only

---
