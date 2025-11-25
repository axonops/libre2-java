# Baseline Performance Metrics - Before JNI Optimization

**Date:** 2025-11-25
**Branch:** feature/jni-optimization (before changes)
**Platform:** macOS ARM64 (Apple Silicon)
**Test:** BulkMatchingPerformanceTest

---

## Test Results

### Map Filter Performance (10,000 entries)
- **Duration:** 4.1525 ms
- **Filtered:** 5000/10000
- **Per-item:** 0.415 μs

### Filter Performance (10,000 strings)
- **Duration:** 2.76 ms
- **Filtered:** 3334/10000
- **Throughput:** 3,628,117 matches/sec
- **Per-match:** 0.276 μs

### Bulk vs Individual (10,000 strings)
- **Bulk API:** 2.324 ms (0.232 μs per match)
- **Individual API:** 2.569 ms (0.257 μs per match)
- **Speedup:** 1.1x
- **Matches:** 5000/10000

---

## Analysis

**Current Bottleneck:**
- Using GetStringUTFChars for String→C++ transfer
- Per RocksDB analysis: This is the slowest transfer method
- Expected 30-50% overhead from String conversion alone

**Target Improvement:**
- Replace with String→byte[] (Java) + GetByteArrayRegion (C++)
- Expected: 30-60% performance improvement
- Target throughput: 5-8M matches/sec (from current 3.6M)

---

## Optimization Strategy

1. Add byte[] method variants to RE2NativeJNI
2. Update Pattern.java to convert String→byte[] internally
3. Keep existing String methods for API compatibility
4. Use stack allocation (<8KB) for small strings
5. Heap allocation for large strings

**Expected Results:**
- Filter: 2.76ms → 1.5-2.0ms (30-45% improvement)
- Bulk: 2.32ms → 1.2-1.6ms (35-50% improvement)
- Throughput: 3.6M → 5-8M matches/sec

---

## Next: Implement optimizations and re-test
