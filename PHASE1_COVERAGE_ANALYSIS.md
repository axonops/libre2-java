# Phase 1 Bulk Matching - Coverage Analysis

**Analyzed:** 2025-11-25
**Token:** 502k / 1M

---

## Current Coverage

### Core Bulk Matching Methods

**1. matchAll Variants:**
- ✅ `matchAll(Collection<String>)` → delegates to matchAll(String[])
- ✅ `matchAll(String[])` → **INSTRUMENTED** with MATCHING_BULK_* metrics
- ✅ `matchAll(long[], int[])` → **INSTRUMENTED** with MATCHING_BULK_ZERO_COPY_* metrics
- ❌ **MISSING:** `matchAll(ByteBuffer[])` - should accept array of ByteBuffers

### Filter Operations

**2. filter/filterNot:**
- ✅ `filter(Collection<String>)` → delegates to matchAll(String[])
- ✅ `filterNot(Collection<String>)` → delegates to matchAll(String[])
- ❌ **MISSING:** Zero-copy variants (ByteBuffer[], address/length arrays)

### In-Place Operations

**3. retainMatches/removeMatches:**
- ✅ `retainMatches(Collection<String>)` → delegates to matchAll(String[])
- ✅ `removeMatches(Collection<String>)` → delegates to matchAll(String[])
- ❌ **MISSING:** Zero-copy variants

### Map Operations

**4. filterByKey/filterByValue:**
- ✅ `filterByKey(Map<String, V>)` → delegates to matchAll(String[])
- ✅ `filterByValue(Map<K, String>)` → delegates to matchAll(String[])
- ✅ `filterNotByKey(Map<String, V>)` → delegates to matchAll(String[])
- ✅ `filterNotByValue(Map<K, String>)` → delegates to matchAll(String[])
- ❌ **MISSING:** Zero-copy variants (for Map<ByteBuffer, V> etc.)

**5. retainMatchesByKey/Value, removeMatchesByKey/Value:**
- ✅ All 4 methods delegate to matchAll(String[])
- ❌ **MISSING:** Zero-copy variants

---

## Metrics Instrumentation Status

### ✅ Complete and Correct

**matchAll(String[]):**
```java
// Global metrics
metrics.incrementCounter(MATCHING_OPERATIONS, inputs.length);
metrics.recordTimer(MATCHING_LATENCY, perItemNanos);
metrics.recordTimer(MATCHING_FULL_MATCH_LATENCY, perItemNanos);

// Specific bulk metrics
metrics.incrementCounter(MATCHING_BULK_OPERATIONS);
metrics.incrementCounter(MATCHING_BULK_ITEMS, inputs.length);
metrics.recordTimer(MATCHING_BULK_LATENCY, perItemNanos);
```
**Status:** ✅ CORRECT - records global + specific, per-item latency

**matchAll(long[], int[]):**
```java
// Global metrics
metrics.incrementCounter(MATCHING_OPERATIONS, addresses.length);
metrics.recordTimer(MATCHING_LATENCY, perItemNanos);
metrics.recordTimer(MATCHING_FULL_MATCH_LATENCY, perItemNanos);

// Specific bulk zero-copy metrics
metrics.incrementCounter(MATCHING_BULK_ZERO_COPY_OPERATIONS);
metrics.incrementCounter(MATCHING_BULK_ITEMS, addresses.length);
metrics.recordTimer(MATCHING_BULK_ZERO_COPY_LATENCY, perItemNanos);
```
**Status:** ✅ CORRECT - records global + specific zero-copy, per-item latency

**All filter/map/retain/remove methods:**
- Delegate to matchAll(String[])
- **Status:** ✅ CORRECT - metrics flow through automatically

---

## Missing Functionality

### Critical Gaps

**1. No ByteBuffer[] Support**
**Missing:**
```java
boolean[] matchAll(ByteBuffer[] buffers)
```
**Use case:** Cassandra returns ByteBuffer[] from multi-column queries
**Impact:** Can't do bulk zero-copy on array of ByteBuffers

**2. No Zero-Copy Filter Operations**
**Missing:**
```java
List<ByteBuffer> filter(ByteBuffer[] inputs)  // Filter matching buffers
List<ByteBuffer> filterNot(ByteBuffer[] inputs)
```
**Use case:** Filter array of ByteBuffers from Cassandra
**Impact:** Must convert to String[], losing zero-copy benefit

**3. No findAll Bulk for Zero-Copy**
**Current:** Only have `findAll(long[], int[])` which is partial match
**Missing:**
```java
// Note: We DO have matchAll(long[], int[]) for full match
// And we have findAll(long[], int[]) for partial match on bulk
// So actually this might be complete?
```

Let me verify findAll coverage...

---

## Verification Needed

### findAll Coverage Check

**Current methods:**
- ✅ `findAll(long[], int[])` - bulk partial match with zero-copy

**Question:** Do we need a String[] variant?
```java
boolean[] findAll(String[] inputs)  // Partial match on each string
```

Looking at Pattern methods, I don't see a String[] variant of findAll. Only:
- matchAll(String[]) - full match on array ✅
- findAll(long[], int[]) - partial match on address array ✅

**Missing String variant:**
```java
boolean[] findAll(String[] inputs)  // Partial match bulk
```

This would use `partialMatchBulk` native method which exists!

---

## Assessment

### What's Correct ✅

1. **Core bulk matching:** matchAll with String[], Collection, address arrays - ✅
2. **Metrics instrumentation:** All use Global + Specific pattern - ✅
3. **Per-item latency:** Consistent across all bulk operations - ✅
4. **Delegation:** All filter/map/retain methods delegate correctly - ✅

### What's Missing ❌

**High Priority:**
1. **ByteBuffer[] matchAll** - bulk with array of ByteBuffers
2. **findAll(String[])** - partial match bulk (native method exists, just need Java wrapper)
3. **ByteBuffer filter operations** - filter(ByteBuffer[]), filterNot(ByteBuffer[])

**Medium Priority:**
4. Zero-copy variants of filter/map operations (lower priority - delegation works)

**Low Priority:**
5. Map<ByteBuffer, V> variants (edge case, probably not needed)

---

## Recommendation

**Add these 3 methods to complete Phase 1:**

1. **`boolean[] findAll(String[] inputs)`** - Easy, native method exists
   ```java
   boolean[] results = RE2NativeJNI.partialMatchBulk(nativeHandle, inputs);
   // Add MATCHING_BULK_* metrics
   ```

2. **`boolean[] matchAll(ByteBuffer[] buffers)`** - Important for Cassandra
   ```java
   // Extract addresses from buffers, call matchAll(long[], int[])
   ```

3. **`boolean[] findAll(ByteBuffer[] buffers)`** - Consistency
   ```java
   // Extract addresses from buffers, call findAll(long[], int[])
   ```

**Estimated:** ~30k tokens to add these + tests

---

## Shall I add these now (during native build wait)?
