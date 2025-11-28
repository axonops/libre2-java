# Phase 1: C++ Native Cache Implementation - Detailed Plan

**Date:** 2025-11-28
**Status:** PLANNING COMPLETE - AWAITING APPROVAL TO START
**Estimated Duration:** 2-3 weeks
**Branch:** `feature/native-cache-implementation`

---

## Table of Contents

1. [Phase Overview](#phase-overview)
2. [File Structure](#file-structure)
3. [Implementation Order](#implementation-order)
4. [Detailed Component Specifications](#detailed-component-specifications)
5. [Testing Strategy](#testing-strategy)
6. [Build Integration](#build-integration)
7. [Success Criteria](#success-criteria)

---

## Phase Overview

**Goal:** Implement all three C++ caches with background eviction, JSON config/metrics, and comprehensive testing.

**Scope:**
- ✅ Pattern Result Cache (optional, string threshold, non-fatal errors)
- ✅ Pattern Compilation Cache (reference-counted, deferred on eviction)
- ✅ Deferred Cache (leak protection, forced eviction)
- ✅ Background eviction thread (100ms interval, configurable)
- ✅ Configuration parsing (JSON via nlohmann/json)
- ✅ Metrics export (JSON serialization)
- ✅ MurmurHash3 integration
- ✅ C++ unit tests + integration tests
- ✅ Memory leak tests (valgrind/asan integration)

**Dependencies:**
- nlohmann/json (header-only, will be downloaded during build)
- MurmurHash3 (public domain, inline implementation)
- GoogleTest/CTest for C++ testing (will be added)

**Out of Scope (Phase 2):**
- JNI bindings to Java
- Java PatternCache updates
- Java integration tests
- CI workflow updates (will be in Phase 3)

---

## File Structure

### New Files to Create

```
native/
├── wrapper/
│   ├── cache/
│   │   ├── cache_config.h           # Configuration structs + JSON parsing (150 LOC)
│   │   ├── cache_config.cpp         # Implementation (200 LOC)
│   │   ├── cache_metrics.h          # Metrics structures (250 LOC)
│   │   ├── cache_metrics.cpp        # JSON serialization (300 LOC)
│   │   ├── result_cache.h           # Pattern Result Cache (200 LOC)
│   │   ├── result_cache.cpp         # Implementation (400 LOC)
│   │   ├── pattern_cache.h          # Pattern Compilation Cache (250 LOC)
│   │   ├── pattern_cache.cpp        # Implementation (600 LOC)
│   │   ├── deferred_cache.h         # Deferred Cache (150 LOC)
│   │   ├── deferred_cache.cpp       # Implementation (300 LOC)
│   │   ├── eviction_thread.h        # Background eviction (150 LOC)
│   │   ├── eviction_thread.cpp      # Implementation (350 LOC)
│   │   ├── cache_manager.h          # Orchestrates all caches (200 LOC)
│   │   ├── cache_manager.cpp        # Implementation (400 LOC)
│   │   ├── murmur_hash3.h           # MurmurHash3 inline impl (150 LOC)
│   │   └── murmur_hash3.cpp         # Implementation (200 LOC)
│   └── re2_jni.cpp                  # Will be updated in Phase 2
├── tests/
│   ├── CMakeLists.txt               # Test build configuration (NEW)
│   ├── cache/
│   │   ├── cache_config_test.cpp    # Config parsing tests (200 LOC)
│   │   ├── result_cache_test.cpp    # Result cache unit tests (400 LOC)
│   │   ├── pattern_cache_test.cpp   # Pattern cache unit tests (500 LOC)
│   │   ├── deferred_cache_test.cpp  # Deferred cache tests (300 LOC)
│   │   ├── eviction_thread_test.cpp # Eviction tests (400 LOC)
│   │   ├── cache_manager_test.cpp   # Integration tests (500 LOC)
│   │   ├── leak_test.cpp            # Memory leak tests (300 LOC)
│   │   └── stress_test.cpp          # Stress/concurrency tests (400 LOC)
│   └── main.cpp                     # Test runner (50 LOC)
├── scripts/
│   ├── build.sh                     # Update to build cache + tests
│   └── run_tests.sh                 # NEW: Run C++ tests (with valgrind)
├── CMakeLists.txt                   # NEW: Root CMake for cache + tests
└── README.md                        # Update with testing instructions

third_party/
└── json/
    └── nlohmann/
        └── json.hpp                 # Header-only JSON library (downloaded)
```

**Total New LOC:** ~5,900 (implementation) + ~3,000 (tests) = **~8,900 LOC**

---

## Implementation Order

### **Step 1: Dependencies & Build Setup** (Day 1)

**Files:**
- `native/CMakeLists.txt` - Root CMake configuration
- `native/tests/CMakeLists.txt` - Test build configuration
- `native/scripts/download_deps.sh` - Download nlohmann/json

**Tasks:**
1. Create CMakeLists.txt for cache library
2. Download nlohmann/json to third_party/
3. Set up GoogleTest/CTest integration
4. Verify build on macOS (local)

**Testing:**
- Build succeeds
- Minimal test runs

**Estimated:** 4-6 hours

---

### **Step 2: MurmurHash3 Implementation** (Day 1-2)

**Files:**
- `native/wrapper/cache/murmur_hash3.h`
- `native/wrapper/cache/murmur_hash3.cpp`
- `native/tests/cache/murmur_hash3_test.cpp`

**Tasks:**
1. Implement MurmurHash3 (32-bit and 64-bit variants)
2. Write unit tests (known hash values)
3. Benchmark performance

**Testing:**
- Hash values match reference implementation
- Performance acceptable (<100ns per hash)

**Estimated:** 4-6 hours

---

### **Step 3: Configuration Structures** (Day 2)

**Files:**
- `native/wrapper/cache/cache_config.h`
- `native/wrapper/cache/cache_config.cpp`
- `native/tests/cache/cache_config_test.cpp`

**Implementation:**
```cpp
struct CacheConfig {
    // Global
    bool cache_enabled = true;

    // Pattern Result Cache
    bool pattern_result_cache_enabled = true;
    size_t pattern_result_cache_target_capacity_bytes = 100 * 1024 * 1024;
    size_t pattern_result_cache_string_threshold_bytes = 10 * 1024;
    std::chrono::milliseconds pattern_result_cache_ttl_ms{300000};  // 5 min

    // Pattern Compilation Cache
    size_t pattern_cache_target_capacity_bytes = 100 * 1024 * 1024;
    std::chrono::milliseconds pattern_cache_ttl_ms{300000};  // 5 min

    // Deferred Cache
    std::chrono::milliseconds deferred_cache_ttl_ms{600000};  // 10 min

    // Eviction Thread
    bool auto_start_eviction_thread = true;
    std::chrono::milliseconds eviction_check_interval_ms{100};  // 100ms

    // Parse from JSON
    static CacheConfig fromJson(const std::string& json);

    // Validate configuration
    void validate() const;

    // Serialize to JSON (for debugging)
    std::string toJson() const;
};
```

**Tasks:**
1. Implement config struct
2. JSON parsing using nlohmann/json
3. Validation logic
4. Unit tests (valid/invalid configs)

**Testing:**
- Parse valid JSON correctly
- Reject invalid configurations
- Round-trip JSON serialization

**Estimated:** 6-8 hours

---

### **Step 4: Metrics Structures** (Day 2-3)

**Files:**
- `native/wrapper/cache/cache_metrics.h`
- `native/wrapper/cache/cache_metrics.cpp`
- `native/tests/cache/cache_metrics_test.cpp`

**Implementation:**
```cpp
struct PatternResultCacheMetrics {
    std::atomic<uint64_t> hits{0};
    std::atomic<uint64_t> misses{0};
    std::atomic<uint64_t> get_errors{0};
    std::atomic<uint64_t> put_errors{0};
    std::atomic<uint64_t> ttl_evictions{0};
    std::atomic<uint64_t> lru_evictions{0};
    std::atomic<uint64_t> lru_evictions_bytes_freed{0};
    std::atomic<uint64_t> total_evictions{0};
    std::atomic<uint64_t> total_bytes_freed{0};

    // Snapshot metrics (not atomic, taken under lock)
    uint64_t current_entry_count = 0;
    uint64_t target_capacity_bytes = 0;
    uint64_t actual_size_bytes = 0;
    double utilization_ratio = 0.0;

    double hit_rate() const {
        uint64_t h = hits.load();
        uint64_t m = misses.load();
        return (h + m) > 0 ? (100.0 * h) / (h + m) : 0.0;
    }

    nlohmann::json toJson() const;
};

struct PatternCacheMetrics { /* similar */ };
struct DeferredCacheMetrics { /* similar */ };

struct CacheMetrics {
    PatternResultCacheMetrics pattern_result_cache;
    PatternCacheMetrics pattern_cache;
    DeferredCacheMetrics deferred_cache;
    std::chrono::system_clock::time_point generated_at;

    std::string toJson() const;  // Serialize all metrics
};
```

**Tasks:**
1. Implement metrics structures
2. JSON serialization for all metrics
3. Unit tests (metric updates, JSON format)

**Testing:**
- Metrics update correctly (atomic operations)
- JSON format matches design spec
- Hit rate calculation correct

**Estimated:** 8-10 hours

---

### **Step 5: Pattern Result Cache** (Day 3-4)

**Files:**
- `native/wrapper/cache/result_cache.h`
- `native/wrapper/cache/result_cache.cpp`
- `native/tests/cache/result_cache_test.cpp`

**Implementation:**
```cpp
class ResultCache {
public:
    explicit ResultCache(const CacheConfig& config);
    ~ResultCache();

    // Get cached result (std::nullopt on miss or error)
    std::optional<MatchResult> get(
        const std::string& pattern,
        const std::string& input,
        PatternResultCacheMetrics& metrics);

    // Cache result (non-fatal errors logged)
    void put(
        const std::string& pattern,
        const std::string& input,
        const MatchResult& result,
        PatternResultCacheMetrics& metrics);

    // Eviction (called by background thread)
    void evict(
        PatternResultCacheMetrics& metrics,
        const std::chrono::steady_clock::time_point& now);

    // Clear cache
    void clear();

    // Get snapshot metrics (brief lock)
    void snapshotMetrics(PatternResultCacheMetrics& metrics) const;

private:
    struct ResultCacheEntry {
        MatchResult result;
        std::chrono::steady_clock::time_point last_access;
        size_t approx_size_bytes;
    };

    struct ResultCacheKey {
        uint64_t pattern_hash;
        uint64_t string_hash;
        bool operator==(const ResultCacheKey&) const;
    };

    struct ResultCacheKeyHash {
        size_t operator()(const ResultCacheKey&) const;
    };

    const CacheConfig& config_;
    mutable std::shared_mutex mutex_;
    std::unordered_map<ResultCacheKey, ResultCacheEntry, ResultCacheKeyHash> cache_;
    size_t total_size_bytes_ = 0;

    // Helper: estimate entry size
    size_t estimateSize(const MatchResult& result) const;

    // Helper: find LRU entry
    auto findLRUEntry() -> decltype(cache_.begin());
};
```

**Tasks:**
1. Implement Result Cache with thread-safe operations
2. TTL + LRU eviction logic
3. String threshold check (don't cache large inputs)
4. Size estimation for MatchResult
5. Unit tests (hit/miss, eviction, errors)

**Testing:**
- Cache hit/miss correct
- TTL eviction works
- LRU eviction under capacity
- String threshold enforced
- Non-fatal errors handled
- Thread-safe (concurrent access)

**Estimated:** 12-16 hours

---

### **Step 6: Deferred Cache** (Day 5)

**Files:**
- `native/wrapper/cache/deferred_cache.h`
- `native/wrapper/cache/deferred_cache.cpp`
- `native/tests/cache/deferred_cache_test.cpp`

**Implementation:**
```cpp
class DeferredCache {
public:
    explicit DeferredCache(const CacheConfig& config);
    ~DeferredCache();

    // Add pattern to deferred cache
    void add(
        const std::string& pattern_key,
        std::shared_ptr<RE2Pattern> pattern,
        DeferredCacheMetrics& metrics);

    // Eviction (immediate if refcount=0, forced if TTL expired)
    void evict(
        DeferredCacheMetrics& metrics,
        const std::chrono::steady_clock::time_point& now);

    // Clear all (for shutdown)
    void clear();

    // Snapshot metrics
    void snapshotMetrics(DeferredCacheMetrics& metrics) const;

private:
    struct DeferredCacheEntry {
        std::shared_ptr<RE2Pattern> pattern;
        std::chrono::steady_clock::time_point entered_deferred;
        size_t approx_size_bytes;
    };

    const CacheConfig& config_;
    mutable std::shared_mutex mutex_;
    std::unordered_map<std::string, DeferredCacheEntry> cache_;
    size_t total_size_bytes_ = 0;
};
```

**Tasks:**
1. Implement Deferred Cache
2. Immediate eviction (refcount → 0)
3. Forced eviction (TTL expired, log warning)
4. Unit tests (immediate, forced, leak detection)

**Testing:**
- Patterns added to deferred list
- Immediate eviction when refcount → 0
- Forced eviction after TTL (warning logged)
- Metrics accurate

**Estimated:** 8-10 hours

---

### **Step 7: Pattern Compilation Cache** (Day 6-7)

**Files:**
- `native/wrapper/cache/pattern_cache.h`
- `native/wrapper/cache/pattern_cache.cpp`
- `native/tests/cache/pattern_cache_test.cpp`

**Implementation:**
```cpp
class PatternCache {
public:
    explicit PatternCache(const CacheConfig& config);
    ~PatternCache();

    // Get or compile pattern (increment refcount)
    std::shared_ptr<RE2Pattern> getOrCompile(
        const std::string& pattern_string,
        bool case_sensitive,
        PatternCacheMetrics& metrics,
        std::string& error_msg);

    // Release pattern (decrement refcount)
    void releasePattern(
        const std::string& pattern_string,
        bool case_sensitive,
        PatternCacheMetrics& metrics,
        DeferredCache& deferred_cache);

    // Eviction (TTL + LRU, move to deferred if refcount > 0)
    void evict(
        PatternCacheMetrics& metrics,
        DeferredCache& deferred_cache,
        const std::chrono::steady_clock::time_point& now);

    // Clear cache (move in-use patterns to deferred)
    void clear(DeferredCache& deferred_cache);

    // Snapshot metrics
    void snapshotMetrics(PatternCacheMetrics& metrics) const;

private:
    struct PatternCacheEntry {
        std::shared_ptr<RE2Pattern> pattern;
        std::atomic<uint32_t> refcount{0};
        std::chrono::steady_clock::time_point last_access;
        size_t approx_size_bytes;
    };

    const CacheConfig& config_;
    mutable std::shared_mutex mutex_;
    std::unordered_map<uint64_t, PatternCacheEntry> cache_;  // key: MurmurHash3(pattern+case)
    size_t total_size_bytes_ = 0;

    // Helper: estimate pattern size
    size_t estimateSize(const RE2& re2) const;

    // Helper: find LRU entry
    auto findLRUEntry() -> decltype(cache_.begin());
};
```

**Tasks:**
1. Implement Pattern Cache with refcount tracking
2. getOrCompile with atomic refcount increment
3. releasePattern with atomic refcount decrement
4. TTL + LRU eviction (move to deferred if refcount > 0)
5. Compilation error handling (increment metric, throw)
6. Unit tests (compilation, refcount, eviction)

**Testing:**
- Pattern compilation works
- Refcount incremented on getOrCompile
- Refcount decremented on releasePattern
- Patterns moved to deferred if refcount > 0
- TTL + LRU eviction correct
- Compilation errors tracked
- Thread-safe (concurrent compilation)

**Estimated:** 16-20 hours

---

### **Step 8: Background Eviction Thread** (Day 8)

**Files:**
- `native/wrapper/cache/eviction_thread.h`
- `native/wrapper/cache/eviction_thread.cpp`
- `native/tests/cache/eviction_thread_test.cpp`

**Implementation:**
```cpp
class EvictionThread {
public:
    explicit EvictionThread(const CacheConfig& config);
    ~EvictionThread();

    // Start eviction thread
    void start(
        ResultCache& result_cache,
        PatternCache& pattern_cache,
        DeferredCache& deferred_cache,
        CacheMetrics& metrics);

    // Stop eviction thread (graceful)
    void stop();

    // Trigger immediate eviction check
    void trigger();

    bool isRunning() const;

private:
    void run();  // Main loop

    const CacheConfig& config_;
    std::atomic<bool> running_{false};
    std::thread thread_;
    std::condition_variable cv_;
    std::mutex cv_mutex_;

    // References (set in start())
    ResultCache* result_cache_ = nullptr;
    PatternCache* pattern_cache_ = nullptr;
    DeferredCache* deferred_cache_ = nullptr;
    CacheMetrics* metrics_ = nullptr;
};
```

**Tasks:**
1. Implement eviction thread with 100ms interval
2. Evict all three caches in sequence
3. Graceful shutdown
4. Trigger mechanism for manual eviction
5. Unit tests (eviction timing, shutdown)

**Testing:**
- Thread starts and runs at 100ms interval
- Eviction called on all caches
- Graceful shutdown within 5 seconds
- Trigger works

**Estimated:** 10-12 hours

---

### **Step 9: Cache Manager (Orchestrator)** (Day 9)

**Files:**
- `native/wrapper/cache/cache_manager.h`
- `native/wrapper/cache/cache_manager.cpp`
- `native/tests/cache/cache_manager_test.cpp`

**Implementation:**
```cpp
class CacheManager {
public:
    explicit CacheManager(const CacheConfig& config);
    ~CacheManager();

    // Pattern operations
    std::shared_ptr<RE2Pattern> getOrCompile(
        const std::string& pattern_string,
        bool case_sensitive,
        std::string& error_msg);

    void releasePattern(
        const std::string& pattern_string,
        bool case_sensitive);

    // Result cache operations (if enabled)
    std::optional<MatchResult> getMatchResult(
        const std::string& pattern_string,
        const std::string& input);

    void putMatchResult(
        const std::string& pattern_string,
        const std::string& input,
        const MatchResult& result);

    // Cache management
    void forceClear(CacheClearType type);
    void forceEvictionCheck();

    // Metrics
    std::string getMetricsJSON() const;
    void clearMetrics();

    // Configuration
    const CacheConfig& getConfig() const { return config_; }

private:
    CacheConfig config_;
    CacheMetrics metrics_;

    ResultCache result_cache_;
    PatternCache pattern_cache_;
    DeferredCache deferred_cache_;
    EvictionThread eviction_thread_;
};
```

**Tasks:**
1. Implement CacheManager orchestrator
2. Coordinate all three caches
3. Metrics aggregation
4. JSON metrics export
5. Integration tests (end-to-end)

**Testing:**
- All caches work together
- Metrics aggregated correctly
- JSON export valid
- Clear operations work
- Thread-safe

**Estimated:** 12-14 hours

---

### **Step 10: Integration & Leak Testing** (Day 10-11)

**Files:**
- `native/tests/cache/leak_test.cpp`
- `native/tests/cache/stress_test.cpp`
- `native/scripts/run_leak_tests.sh`

**Tasks:**
1. Comprehensive integration tests
2. Memory leak tests with valgrind/asan
3. Stress tests (1000+ patterns, high concurrency)
4. Performance benchmarking

**Testing:**
- No memory leaks (valgrind clean)
- No race conditions (thread sanitizer clean)
- Performance acceptable (benchmark)
- All edge cases covered

**Estimated:** 16-20 hours

---

## Detailed Component Specifications

### MurmurHash3

**Interface:**
```cpp
uint32_t MurmurHash3_x86_32(const void* key, int len, uint32_t seed);
uint64_t MurmurHash3_x64_64(const void* key, int len, uint64_t seed);

// Convenience functions
inline uint64_t hashString(const std::string& str) {
    return MurmurHash3_x64_64(str.data(), str.size(), 0);
}
```

**Requirements:**
- Inline implementation for performance
- Match reference implementation output
- Seed support for testing

### RE2Pattern Wrapper

**Structure:**
```cpp
struct RE2Pattern {
    std::unique_ptr<RE2> compiled_regex;
    std::atomic<uint32_t> refcount{0};
    std::chrono::steady_clock::time_point last_access;
    std::string pattern_string;
    size_t approx_size_bytes;

    RE2Pattern(const std::string& pattern, bool case_sensitive);

    bool isValid() const {
        return compiled_regex && compiled_regex->ok();
    }

    size_t estimateSize() const {
        // Use RE2::ProgramSize() if available, else approximate
        return compiled_regex ? compiled_regex->ProgramSize() : 0;
    }
};
```

### MatchResult Structure

**Structure:**
```cpp
struct MatchResult {
    bool matched;
    std::vector<std::string> captured_groups;

    size_t estimateSize() const {
        size_t size = sizeof(MatchResult);
        for (const auto& group : captured_groups) {
            size += group.size();
        }
        return size;
    }
};
```

---

## Testing Strategy

### Unit Tests (Per Component)

**Coverage Target:** >90% line coverage

**Test Categories:**
1. **Happy Path:** Normal operations, cache hits/misses
2. **Edge Cases:** Empty strings, very large inputs, null patterns
3. **Error Handling:** Compilation errors, JSON parse errors
4. **Concurrency:** Parallel access, race conditions
5. **Metrics:** Counter increments, snapshot accuracy
6. **Memory:** No leaks, correct cleanup

**Test Framework:** GoogleTest (will be integrated via CMake)

### Integration Tests

**Scenarios:**
1. **End-to-End:** Compile → Cache → Evict → Recompile
2. **Eviction Flow:** TTL eviction → Deferred → Cleanup
3. **Concurrent Load:** 100+ threads compiling/matching
4. **Capacity Limit:** Fill cache to capacity, verify eviction
5. **Metrics Accuracy:** Operations → Metrics → JSON export

### Memory Leak Tests

**Tools:**
- **Linux:** valgrind, AddressSanitizer (ASan), LeakSanitizer (LSan)
- **macOS:** leaks, AddressSanitizer

**Tests:**
1. **Basic Leak Test:** Compile 1000 patterns, verify cleanup
2. **Eviction Leak Test:** Fill cache, evict, verify no leaks
3. **Deferred Leak Test:** Evict in-use patterns, verify cleanup
4. **Thread Leak Test:** Start/stop eviction thread 100 times

**Script:**
```bash
#!/bin/bash
# native/scripts/run_leak_tests.sh

# Linux
if [[ "$OSTYPE" == "linux-gnu"* ]]; then
    valgrind --leak-check=full \
             --show-leak-kinds=all \
             --error-exitcode=1 \
             ./cache_tests

    # Or with ASan (requires rebuild)
    # export ASAN_OPTIONS=detect_leaks=1
    # ./cache_tests_asan

# macOS
elif [[ "$OSTYPE" == "darwin"* ]]; then
    leaks --atExit -- ./cache_tests
fi
```

### Stress Tests

**Scenarios:**
1. **High Throughput:** 10K compilations/sec for 60 seconds
2. **Memory Pressure:** Fill cache to 2x capacity
3. **Concurrency:** 200 threads, 1000 patterns each
4. **Long Running:** 24hr stress test (manual trigger)

---

## Build Integration

### CMakeLists.txt

**Root CMakeLists.txt:**
```cmake
cmake_minimum_required(VERSION 3.20)
project(libre2_cache CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Dependencies
include(FetchContent)

# nlohmann/json
FetchContent_Declare(
    json
    URL https://github.com/nlohmann/json/releases/download/v3.11.2/json.tar.xz
)
FetchContent_MakeAvailable(json)

# RE2 (already built by build.sh)
find_library(RE2_LIB re2 PATHS ${CMAKE_CURRENT_SOURCE_DIR}/build/re2-build)
find_path(RE2_INCLUDE re2/re2.h PATHS ${CMAKE_CURRENT_SOURCE_DIR}/build/re2)

# Cache library
add_library(re2_cache
    wrapper/cache/cache_config.cpp
    wrapper/cache/cache_metrics.cpp
    wrapper/cache/result_cache.cpp
    wrapper/cache/pattern_cache.cpp
    wrapper/cache/deferred_cache.cpp
    wrapper/cache/eviction_thread.cpp
    wrapper/cache/cache_manager.cpp
    wrapper/cache/murmur_hash3.cpp
)

target_include_directories(re2_cache PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/wrapper
    ${RE2_INCLUDE}
)

target_link_libraries(re2_cache PUBLIC
    ${RE2_LIB}
    nlohmann_json::nlohmann_json
)

# Testing
enable_testing()
add_subdirectory(tests)
```

**tests/CMakeLists.txt:**
```cmake
# GoogleTest
FetchContent_Declare(
    googletest
    URL https://github.com/google/googletest/releases/download/v1.14.0/googletest-1.14.0.tar.gz
)
FetchContent_MakeAvailable(googletest)

# Test executable
add_executable(cache_tests
    cache/cache_config_test.cpp
    cache/result_cache_test.cpp
    cache/pattern_cache_test.cpp
    cache/deferred_cache_test.cpp
    cache/eviction_thread_test.cpp
    cache/cache_manager_test.cpp
    cache/leak_test.cpp
    cache/stress_test.cpp
    main.cpp
)

target_link_libraries(cache_tests
    re2_cache
    gtest
    gtest_main
)

# Discover tests
include(GoogleTest)
gtest_discover_tests(cache_tests)

# AddressSanitizer build (optional)
if(ENABLE_ASAN)
    target_compile_options(cache_tests PRIVATE -fsanitize=address -fsanitize=leak -g)
    target_link_options(cache_tests PRIVATE -fsanitize=address -fsanitize=leak)
endif()
```

### Build Script Updates

**native/scripts/build.sh:**
```bash
# After building RE2...

# Build cache library + tests
echo "Building cache library and tests..."
mkdir -p cmake-build
cd cmake-build

cmake .. \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_CXX_STANDARD=17

cmake --build . -j$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)

# Run tests
ctest --output-on-failure

cd ..
```

---

## Success Criteria

### Functional Requirements

- ✅ All three caches implemented and working
- ✅ TTL + LRU eviction functioning correctly
- ✅ Refcount tracking accurate
- ✅ Deferred cache cleanup working
- ✅ Background eviction thread running at 100ms interval
- ✅ JSON configuration parsing works
- ✅ JSON metrics export correct
- ✅ MurmurHash3 hashing correct

### Quality Requirements

- ✅ >90% unit test coverage
- ✅ All integration tests passing
- ✅ Zero memory leaks (valgrind/asan clean)
- ✅ Zero race conditions (thread sanitizer clean)
- ✅ Performance acceptable (benchmarks pass)

### Build Requirements

- ✅ Builds on Linux x86_64 (local Docker)
- ✅ Builds on macOS (local)
- ✅ All tests pass on both platforms
- ✅ CMake configuration correct
- ✅ Dependencies downloaded automatically

---

## Risk Assessment

### High Risk Items

1. **100ms Eviction Interval**
   - **Risk:** High CPU overhead
   - **Mitigation:** Efficient data structures, minimal work per cycle
   - **Contingency:** Make interval configurable, increase if needed

2. **Bytes-Based Capacity**
   - **Risk:** Inaccurate size estimation → incorrect eviction
   - **Mitigation:** Use RE2::ProgramSize(), test with various patterns
   - **Contingency:** Add logging to track actual vs. estimated sizes

3. **Thread Safety**
   - **Risk:** Deadlocks, race conditions
   - **Mitigation:** Strict lock ordering, thread sanitizer testing
   - **Contingency:** Extensive concurrent testing, code review

### Medium Risk Items

1. **Performance**
   - **Risk:** Slower than Java cache
   - **Mitigation:** Benchmark early, optimize hot paths
   - **Contingency:** Profile and optimize, increase eviction interval

2. **Memory Leaks**
   - **Risk:** Leaks in deferred cache or refcount management
   - **Mitigation:** Valgrind/asan on every test
   - **Contingency:** Manual leak tracking, extensive testing

---

## Next Steps

1. **Get Approval:** Confirm this plan before starting implementation
2. **Create Branch:** `feature/native-cache-implementation` off `main`
3. **Start Implementation:** Begin with Step 1 (dependencies)
4. **Continuous Testing:** Run tests after each component
5. **Track Progress:** Update session log daily

---

**Status:** PLAN COMPLETE - AWAITING APPROVAL TO START

**Questions before starting?**
