# Phase 0: Final Decisions - Native Cache Migration

**Date:** 2025-11-28
**Status:** DECISIONS LOCKED - READY FOR IMPLEMENTATION

---

## User Decisions Summary

### **Q1: Scope - THREE Caches ✅**
**Decision:** Option A - Implement all 3 caches (Pattern Result + Pattern Compilation + Deferred)

**Rationale:** Full design implementation, native builds take time so better to do it all at once

**Impact:**
- Full scope: ~2,800-3,950 LOC C++ implementation
- ~2,300-3,300 LOC tests
- Higher complexity but complete solution
- Estimated 4-5 weeks implementation + testing

---

### **Q2: Pattern Result Cache - Phase 1 ✅**
**Decision:** Option A - Implement in Phase 1

**Rationale:** Native builds take time, better to implement all features together

**Impact:**
- Pattern Result Cache implementation (~400-600 LOC)
- New feature not in current Java implementation
- Requires careful testing to validate performance benefit
- Additional metrics and configuration

---

### **Q3: Eviction Interval - 100ms (Configurable) ✅**
**Decision:** Option A - 100ms default, but configurable

**Rationale:** Follow design spec, allow tuning if needed

**Impact:**
- 600x faster than current 60s
- Higher CPU overhead - must be efficient
- No full cache scans allowed (must use efficient data structures)
- Configuration parameter: `eviction_check_interval_ms` (default 100)

**Configuration:**
```cpp
struct CacheConfig {
    std::chrono::milliseconds eviction_check_interval_ms = 100;  // Default 100ms
    // ...
};
```

---

### **Q4: Capacity Model - Bytes Only ✅**
**Decision:** Option A - Capacity in bytes (no entry count support)

**Rationale:** More accurate memory control

**Impact:**
- Need accurate size estimation for:
  - Pattern cache entries: `RE2::ProgramSize()` or approximation
  - Result cache entries: `sizeof(MatchResult) + captured_groups.size()`
  - Deferred cache entries: same as pattern cache
- Utilization ratio calculation: `actual_bytes / target_bytes`
- Configuration in bytes (e.g., 100MB = 104857600 bytes)

**Configuration:**
```cpp
struct CacheConfig {
    size_t pattern_result_cache_target_capacity_bytes = 100 * 1024 * 1024;  // 100MB
    size_t pattern_cache_target_capacity_bytes = 100 * 1024 * 1024;         // 100MB
    // ...
};
```

---

### **Q5: Java API - Thin Wrapper with Interface ✅**
**Decision:** Option A - Keep PatternCache as thin JNI wrapper, define interface for mocking

**Rationale:** Allow mocking in tests, breaking changes OK (pre-release)

**Impact:**
- Create interface: `ICacheManager` (or similar)
- `PatternCache` implements interface, delegates all operations to C++ via JNI
- Tests can use mock implementation
- Breaking API changes allowed (pre-release)

**Java Architecture:**
```java
// Interface for mocking
public interface ICacheManager {
    Pattern getOrCompile(String pattern, boolean caseSensitive);
    void releasePattern(String pattern, boolean caseSensitive);
    String getCacheStats(boolean includePoolStats);
    void clearCache(CacheClearType type);
    // ...
}

// Implementation delegates to C++ via JNI
public class PatternCache implements ICacheManager {
    @Override
    public Pattern getOrCompile(String pattern, boolean caseSensitive) {
        // JNI call to C++ getOrCompile()
        long handle = RE2NativeJNI.cacheGetOrCompile(pattern, caseSensitive);
        return new Pattern(handle);
    }
    // ...
}
```

---

### **Q6: Configuration Format - JSON ✅**
**Decision:** Option A - Java serializes RE2Config to JSON, passes to C++

**Rationale:** Clean separation, simple integration

**Impact:**
- Add JSON serialization to `RE2Config.java`
- C++ parses JSON on initialization
- Need JSON library in C++ (nlohmann/json recommended - header-only)
- JNI function: `initializeCache(String configJson)`

**Flow:**
```java
RE2Config config = RE2Config.builder()
    .maxCacheSize(100_000_000)  // 100MB in bytes
    .build();

String json = config.toJson();
RE2NativeJNI.initializeCache(json);
```

---

### **Q7: Metrics Integration - JSON Export ✅**
**Decision:** Option A - C++ exports JSON, Java parses and updates Dropwizard

**Rationale:** Clean separation, single JNI call

**Impact:**
- C++ function: `std::string getMetricsJSON()`
- JNI function: `String getCacheMetricsJSON()`
- Java parses JSON and updates Dropwizard metrics
- Periodic polling (e.g., every 5 seconds) or on-demand

**Flow:**
```java
String metricsJson = RE2NativeJNI.getCacheMetricsJSON();
CacheMetrics metrics = parseMetricsJson(metricsJson);
updateDropwizardMetrics(metrics);
```

---

### **Q8: Testing Infrastructure - Add to CI ✅**

**Q8a: CI Infrastructure**
**Decision:** No current infrastructure, need to add to GitHub runners

**Approach:**
- Install valgrind/asan in Docker containers (Linux)
- Use leaks/MallocDebug on macOS runners
- Add separate CI job for leak testing
- Use GitHub Actions caching for Docker images

**Q8b: Platforms**
**Decision:** Linux + macOS

**Platform-Specific Tools:**
- **Linux:** valgrind, AddressSanitizer (ASan), LeakSanitizer (LSan)
- **macOS:** leaks, MallocDebug, AddressSanitizer

**Q8c: Frequency**
**Decision:** Run on every CI build

**CI Strategy:**
1. **Regular builds:** Compile + unit tests + integration tests (all platforms)
2. **Leak testing job:** valgrind/asan/leaks on subset of tests (Linux x86_64 + macOS)
3. **Stress testing job:** 24hr stress test (optional, manual trigger)

**Docker Updates:**
```dockerfile
# Add to Dockerfile for Linux
RUN dnf install -y valgrind

# Build with sanitizers
cmake -DCMAKE_BUILD_TYPE=Debug \
      -DCMAKE_CXX_FLAGS="-fsanitize=address -fsanitize=leak -g" \
      ...
```

**GitHub Actions Updates:**
```yaml
# New job: leak-testing
leak-testing:
  runs-on: ubuntu-latest
  steps:
    - name: Build with ASan
      run: cmake -DCMAKE_CXX_FLAGS="-fsanitize=address -fsanitize=leak -g"
    - name: Run tests under valgrind
      run: valgrind --leak-check=full --error-exitcode=1 ./cache_tests
```

---

### **Q9: Backward Compatibility - None ✅**
**Decision:** Option A - Can break APIs (pre-release)

**Rationale:** Project is pre-release, no published versions

**Impact:**
- Free to redesign Java API as needed
- No migration guide needed (no users yet)
- Can remove old PatternCache implementation entirely
- Focus on best design, not compatibility

---

### **Q10: Deferred Cache - Full C++ ✅**
**Decision:** Option A - Separate C++ cache (per design)

**Rationale:** Full migration, consistent with other caches

**Impact:**
- Deferred cache as `std::unordered_map` in C++
- No Java deferred list management
- All eviction logic in C++
- Refcount tracking in C++

---

## Implementation Scope Summary

### **Three C++ Caches**

1. **Pattern Result Cache (Optional)**
   - Purpose: Cache `(pattern_hash, input_hash) → MatchResult`
   - Capacity: 100MB (bytes)
   - TTL: 5 minutes
   - String threshold: 10KB (don't cache large inputs)
   - Error handling: Non-fatal (log metric, skip)

2. **Pattern Compilation Cache (Reference-Counted)**
   - Purpose: Cache compiled RE2 patterns
   - Capacity: 100MB (bytes)
   - TTL: 5 minutes
   - Refcount tracking: Increment on getOrCompile, decrement on release
   - Eviction: Move to Deferred if refcount > 0

3. **Deferred Cache (Leak Protection)**
   - Purpose: Hold patterns with refcount > 0 after eviction
   - No capacity limit (leak protection)
   - Forced eviction: 10 minutes (log warning)
   - Immediate eviction: When refcount → 0

### **Background Eviction Thread**
- Interval: 100ms (configurable)
- Handles all 3 caches
- Eviction logic: TTL + LRU to bring caches back under capacity
- Daemon thread

### **Configuration**
- Format: JSON (serialized from Java RE2Config)
- All parameters configurable (no hardcoded values)
- Global `cache_enabled` flag

### **Metrics**
- ~30+ metrics across 3 caches
- JSON export via JNI
- Atomic counters for lock-free updates
- Snapshot metrics under brief locks

### **Thread Safety**
- `std::shared_mutex` per cache (RwLock)
- Lock acquisition order: Pattern → Result → Deferred
- Atomic refcounts

### **Hashing**
- MurmurHash3 for pattern strings and input strings
- Integrate existing library (SMHasher or inline implementation)

### **Testing**
- C++ unit tests (~800-1,200 LOC)
- C++ integration tests (~400-600 LOC)
- Memory leak tests (valgrind/asan) (~200-300 LOC)
- Java integration tests (~600-800 LOC)
- Performance tests (~300-400 LOC)
- CI integration (leak tests on every build)

---

## Estimated Effort

### **Implementation**
- C++ caches: ~2,800-3,950 LOC
- JNI bindings: ~400-600 LOC
- Java updates: ~500-800 LOC
- **Total code:** ~3,700-5,350 LOC

### **Testing**
- C++ tests: ~1,200-1,800 LOC
- Java tests: ~600-800 LOC
- Leak tests: ~200-300 LOC
- Performance tests: ~300-400 LOC
- **Total tests:** ~2,300-3,300 LOC

### **CI/Build**
- Dockerfile updates
- GitHub Actions workflow updates
- Build script updates
- Documentation

### **Timeline**
- **Phase 1 (C++ Implementation):** 2-3 weeks
- **Phase 2 (JNI + Java Integration):** 1-2 weeks
- **Phase 3 (Testing + CI):** 1 week
- **Phase 4 (Documentation + Review):** 3-5 days
- **Total:** 4-6 weeks

---

## Technical Requirements

### **Dependencies**
- **C++ JSON library:** nlohmann/json (header-only, MIT license)
- **C++ hashing library:** MurmurHash3 (public domain)
- **C++ standard:** C++17 (already required)
- **Testing tools:**
  - Linux: valgrind, ASan, LSan
  - macOS: leaks, ASan

### **Build System Updates**
- Update `native/scripts/build.sh` to build cache implementation
- Update `native/Dockerfile` to install valgrind
- Update `.github/workflows/build-native.yml` for leak testing
- Add C++ test compilation and execution

### **JNI Functions (New)**
Estimated ~10-15 new JNI functions:
- `initializeCache(String configJson)`
- `shutdownCache()`
- `cacheGetOrCompile(String pattern, boolean caseSensitive)` → handle
- `cacheReleasePattern(long handle)`
- `cacheGetMatchResult(long patternHandle, String input)` → MatchResult
- `cachePutMatchResult(long patternHandle, String input, MatchResult result)`
- `getCacheMetricsJSON()` → String
- `clearCache(int cacheType)`
- `forceCacheEviction()`
- ... (and more for specific operations)

---

## Next Steps

1. ✅ User decisions captured (COMPLETE)
2. 📋 Create detailed implementation plan with phase breakdown
3. 🌿 Create feature branch structure
4. 💻 Begin Phase 1: C++ cache implementation
5. ✅ Continuous testing and validation
6. 📊 Track progress in session log

---

**Status:** DECISIONS LOCKED - READY FOR DETAILED PLANNING
**Next Action:** Create implementation plan and phase breakdown
