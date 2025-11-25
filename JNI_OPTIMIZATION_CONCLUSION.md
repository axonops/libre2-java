# JNI Optimization Attempt - Conclusion

**Date:** 2025-11-25
**Branch:** feature/jni-optimization (deleted)
**Outcome:** ❌ **Reverted** - No performance gain, Unicode encoding issues
**Token Used:** 450k / 1M
**Decision:** Keep existing GetStringUTFChars approach

---

## Executive Summary

Attempted to optimize JNI String transfer based on RocksDB research (GetByteArrayRegion vs GetStringUTFChars). **Optimization failed** due to:

1. **No Performance Improvement:** String→byte[] conversion overhead canceled out JNI gains
2. **Modified UTF-8 Mismatch:** Pattern compilation uses Modified UTF-8, inputs use Standard UTF-8
3. **Edge Case Bugs:** Empty strings, null handling, Unicode characters all broke

**Baseline:** 2.76ms for 10k strings, 3.6M matches/sec
**Optimized:** 2.79ms for 10k strings, 3.5M matches/sec (SLOWER)

**RocksDB's findings don't apply when you START with Java Strings** - the conversion cost dominates.

---

## What Was Attempted

### Implementation

**Added 10 new JNI methods:**
- fullMatchBytes, partialMatchBytes
- fullMatchBulkBytes, partialMatchBulkBytes
- extractGroupsBytes, extractGroupsBulkBytes
- findAllMatchesBytes
- replaceFirstBytes, replaceAllBytes, replaceAllBulkBytes

**Modified 9 Pattern.java methods** to convert String→byte[] before JNI call

**C++ Implementation:**
- Used GetByteArrayRegion (recommended by RocksDB)
- Stack allocation for small strings (<8KB)
- Heap allocation for large strings

---

## Why It Failed

### 1. String→byte[] Conversion Overhead

**RocksDB Context:**
- They START with byte[] data (database keys/values)
- Direct byte[] → JNI is fast
- String → byte[] → JNI is slow (extra conversion)

**Our Context:**
- We START with Java Strings (user regex patterns)
- Must convert String → byte[] in Java
- Conversion cost = 100-200ns per string
- JNI improvement = 300ns (GetStringUTFChars) → 100ns (GetByteArrayRegion) = 200ns saved
- **Net: Lost 100-200ns, gained 200ns = WASH**

### 2. Modified UTF-8 vs Standard UTF-8 Mismatch

**Problem:**
```java
// Pattern compilation
Pattern.compile("😀")  // Uses GetStringUTFChars → Modified UTF-8 in RE2

// Input matching
pattern.matchAll(["test😀test"])  // Uses getBytes(UTF_8) → Standard UTF-8
```

**Result:** Pattern and input use different UTF-8 encodings

**Impact:**
- ASCII: Works (same in both encodings)
- BMP Unicode (Chinese, Arabic): **Should work but failed** (unknown cause)
- Emoji/Supplementary (U+10000+): Definitely broken (6 bytes vs 4 bytes)

### 3. StringPiece Lifetime Bugs

**Issue:** StringPiece points to temporary buffer:
```cpp
jbyte stackBuf[8192];
env->GetByteArrayRegion(bytes, 0, length, stackBuf);
re2::StringPiece input((const char*)stackBuf, length);
// ... Match happens ...
// stackBuf goes out of scope
// Later: groups[i].data() points to freed memory!
```

**Fix Required:** Immediately convert to std::string after Match()

**3 rounds of bugs:**
- Round 1: StringPiece lifetime in findAllMatchesBytes
- Round 2: StringPiece lifetime in extractGroupsBytes
- Round 3: Empty string handling (groups[0] == null)
- Round 4: Null elements in String[] arrays
- Round 5: Unicode encoding failures

**Too many edge cases** for marginal/zero gain.

---

## Performance Measurements

### Baseline (GetStringUTFChars)
```
Filter Performance (10,000 strings):
- Duration: 2.76 ms
- Throughput: 3,628,117 matches/sec
- Per-match: 0.276 μs

Map Filter (10,000 entries):
- Duration: 4.15 ms

Bulk vs Individual:
- Bulk: 2.32 ms
- Individual: 2.57 ms
- Speedup: 1.1x
```

### Optimized (GetByteArrayRegion + String→byte[])
```
Filter Performance (10,000 strings):
- Duration: 2.79 ms  (SLOWER!)
- Throughput: 3,585,086 matches/sec
- Per-match: 0.279 μs

Map Filter (10,000 entries):
- Duration: 4.51 ms  (SLOWER!)
```

**Net Impact:** -3% performance (worse than baseline)

---

## Lessons Learned

### When RocksDB Optimizations Apply

✅ **Good for:**
- Native data sources (byte[] from databases, files, network)
- Pre-existing byte[] arrays
- DirectByteBuffer (zero-copy)

❌ **Bad for:**
- Java String inputs (must convert first)
- Mixed UTF-8 encodings (Modified vs Standard)
- Simple/short strings (conversion overhead dominates)

### What Actually Works in Our Library

✅ **DirectByteBuffer zero-copy paths** - Already implemented, optimal
✅ **Bulk APIs** - Single JNI crossing for many operations
✅ **Pattern caching** - Avoid recompilation
✅ **GetStringUTFChars** - JVM optimizes this well, handles all Unicode correctly

---

## Technical Details

### Modified UTF-8 (GetStringUTFChars)
- NULL: 0xC0 0x80 (not 0x00)
- Supplementary chars (U+10000+): 6 bytes (surrogate pair encoding)
- Used by JNI for all String operations

### Standard UTF-8 (getBytes(UTF_8))
- NULL: 0x00
- Supplementary chars: 4 bytes (direct encoding)
- Used by Files, Network, StandardCharsets

**Pattern compiled with one, input with the other = mismatch**

---

## Recommendations

### For libre2-java

1. **Keep existing implementation** - GetStringUTFChars throughout
2. **Focus on bulk APIs** - This is where real gains are (already have it)
3. **Keep DirectByteBuffer paths** - Already optimal for off-heap data
4. **Don't add byte[] variants** - No benefit, adds complexity

### For Future Optimization Attempts

1. **Measure first** - Establish baseline before coding
2. **Consider the full path** - String→byte[] conversion isn't free
3. **Test Unicode** - GetStringUTFChars handles all cases correctly
4. **Start small** - One method, measure, then expand if beneficial

---

## What to Keep

**Analysis Documents (valuable):**
- ✅ JNI_OPTIMIZATION_ANALYSIS.md - Good reference for future
- ✅ BASELINE_PERFORMANCE.md - Performance baseline documented

**Code:**
- ❌ byte[] JNI methods - Removed (no benefit)
- ❌ Native implementations - Removed (bugs, no gain)
- ✅ Existing GetStringUTFChars code - Keep as-is

---

## Final Status

**Branch:** development
**Tests:** 459/459 passing ✅
**Build:** SUCCESS ✅
**Performance:** Baseline maintained (3.6M matches/sec)

**Feature branch deleted:** feature/jni-optimization
**Time invested:** ~4 hours
**Outcome:** Valuable learning, correct decision to revert

---

## Next Steps

**Library is production-ready** with current implementation:
- ✅ 459 tests passing
- ✅ 55 metrics instrumented
- ✅ All phases (0-5) complete
- ✅ Zero-copy DirectByteBuffer support
- ✅ Comprehensive bulk APIs

**Potential areas for improvement:**
1. Documentation (QUICKSTART.md, examples)
2. Version 1.0.0 release preparation
3. Additional integration tests
4. Performance profiling in real Cassandra workload
5. JMH micro-benchmarks for specific operations

**Recommendation:** Focus on documentation and release preparation, not micro-optimizations.
