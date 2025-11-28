# RE2 Pattern Compilation & Result Cache Design - FINAL
## DESIGN FULLY LOCKED - Implementation Ready

---

## Critical Clarifications (FINAL)

### Capacity Limits: "TARGET" Capacity (Soft)
**All three caches use SOFT LIMITS enforced by background threads:**
- Pattern Result Cache: TARGET capacity limit enforced by background thread ONLY
- Pattern Compilation Cache: TARGET capacity limit enforced by background thread ONLY
- Deferred Cache: No explicit capacity limit (grows as needed for leak protection)

**Why "soft"?**
- `put()` and `get()` do NOT check capacity (fast critical path)
- Under load, all caches can exceed configured capacity
- Background eviction thread runs frequently (default 100ms) to bring caches back under capacity
- This is acceptable: temporary overshoot during spikes, cleanup during lower load

**Enforcement Model:**
- Target capacity = configured maximum size in bytes
- Actual size may temporarily exceed target during load spikes
- Background thread enforces target via TTL + LRU eviction
- Eviction runs every `eviction_check_interval_ms` (configurable, default 100ms)

### Utilization Ratio: Can Exceed 1.0
**All three caches can have utilization > 1.0:**
- `utilization_ratio = actual_size_bytes / target_capacity_bytes`
- Can exceed 1.0 (e.g., 1.2 = 120% over target) during load spikes
- Background thread works to bring it back to ≤ 1.0

**Naming Consistency:**
- Pattern Result Cache: `utilization_ratio`
- Pattern Compilation Cache: `utilization_ratio`
- Deferred Cache: `utilization_ratio` (same naming, for consistency)

**Removed Metrics:**
- `current_entries_with_refcount_gt_0` - too expensive to track (would require iterating all entries on every metrics call)
- Not needed for observability (deferred cache entry_count is sufficient for leak monitoring)

---

## Architecture Overview: TWO Main Caches + Deferred

Note: ALL caching can be disable via config (default being enabled). There needs to be a global config to not use caching at all (in addition to disabling the optional Pattern Result Cache)

### Cache 1: Pattern Result Cache (Optional Boolean/Match Cache)
- **Purpose**: Avoid re-evaluating `(pattern, input_string)` combinations
- **What**: `(pattern_hash, input_string_hash) → boolean/match_result`
- **Capacity**: TARGET capacity (soft, enforced by background thread only)
- **TTL**: Per last access (configurable, default 10 minutes)
- **Error Handling**: Non-fatal (log metric, skip, do normal path)
- **Metrics**: 
  - hits, misses, hit_rate
  - get_errors, put_errors
  - TTL evictions, LRU evictions
  - current_entry_count, target_capacity_bytes, actual_size_bytes, utilization_ratio plus any additional metrics worth tracking for observability or debugging

### Cache 2: Pattern Compilation Cache (Reference-Counted)
- **Purpose**: Cache compiled `RE2` patterns for reuse across multiple inputs
- **What**: `pattern_string_hash → compiled_re2_pattern_ptr` with refcount
- **Java API Flow**:
  ```java
  // Pattern p = RE2.compile(pattern_string);
  // ↓ C++ layer:
  //   - Check if pattern already compiled in cache
  //   - If hit: increment refcount, return ptr to Java
  //   - If miss: compile pattern with RE2, store in cache, increment refcount, return ptr to Java

  // Matcher m = p.matcher(input_string);
  // ↓ C++ layer:
  //   - Just get reference to compiled pattern (no refcount change)

  // p.close();
  // ↓ C++ layer:
  //   - Decrement refcount
  //   - If refcount → 0 and entry in deferred cache: immediately DELETE
  //   - Otherwise: just decrement (may be evicted later by background thread)
  ```
- **Capacity**: TARGET capacity (soft, enforced by background thread only)
- **TTL**: Per last access (configurable, default 5 minutes)
- **Refcount**: Managed by Java via Pattern.close() (AutoClosable)
- **Eviction**:
  - If TTL expired OR over capacity:
    - refcount == 0: DELETE compiled pattern
    - refcount > 0: MOVE to Deferred Cache
- **Metrics**:
  - hits (pattern reuse!), misses, hit_rate
  - compilation_errors (RE2::compile() failures)
  - TTL evictions, LRU evictions
  - entries_moved_to_deferred (refcount > 0 at eviction)
  - current_entry_count, target_capacity_bytes, actual_size_bytes, utilization_ratio plus any additional metrics worth tracking for observability or debugging

### Cache 3: Deferred Cache (Leak Protection)
- **Purpose**: Hold compiled patterns with refcount > 0; force evict after X minutes (leak detection)
- **What**: Entries moved from Pattern Cache
- **Capacity**: No enforced limit (grows as needed for leak protection)
- **Eviction**:
  - If refcount == 0: IMMEDIATELY DELETE (leak fixed)
  - If (now - entered_deferred) > deferred_ttl_ms: FORCE DELETE (leak detected!)
- **Metrics**:
  - immediate_evictions (refcount → 0)
  - forced_evictions (TTL expired, leak detected!)
  - forced_evictions_bytes_freed
  - current_entry_count, actual_size_bytes, utilization_ratio

---

## Configuration (ALL CONFIGURABLE, NO HARDCODED VALUES) Pseudocode

```cpp
struct CacheConfig {
    bool cache_enabled = true; //global disable all caching

    // ===== Pattern Result Cache (Optional) =====
    bool pattern_result_cache_enabled = true;
    size_t pattern_result_cache_target_capacity_bytes = 100 * 1024 * 1024;  // 100MB target
    size_t pattern_result_cache_string_threshold_bytes = 10 * 1024;  // Only cache if input < 10KB
    std::chrono::milliseconds pattern_result_cache_ttl_ms = std::chrono::minutes(5);
    
    // ===== Pattern Compilation Cache (Reference-Counted) =====
    size_t pattern_cache_target_capacity_bytes = 100 * 1024 * 1024;  // 100MB target
    std::chrono::milliseconds pattern_cache_ttl_ms = std::chrono::minutes(5);  // Per last access
    
    // ===== Deferred Cache (Leak Protection) =====
    std::chrono::milliseconds deferred_cache_ttl_ms = std::chrono::minutes(10);  // Force evict after
    
    // ===== Background Eviction Thread =====
    bool auto_start_eviction_thread = true;
    std::chrono::milliseconds eviction_check_interval_ms = 100;  // Check every 100ms
    
    // ===== Validation =====
    // - All target_capacity_bytes > 0
    // - All ttl_ms > 0
    // - pattern_cache_ttl_ms < deferred_cache_ttl_ms
    // - pattern_result_cache_string_threshold_bytes > 0
    // - eviction_check_interval_ms > 0
};
```

---

## Metrics (FINAL)

### Pattern Result Cache Metrics Pseudocode
```cpp
struct PatternResultCacheMetrics {
    // Hit/Miss
    std::atomic<uint64_t> hits{0};
    std::atomic<uint64_t> misses{0};
    
    // Errors (non-fatal)
    std::atomic<uint64_t> get_errors{0};
    std::atomic<uint64_t> put_errors{0};
    
    // Evictions (by background thread)
    std::atomic<uint64_t> ttl_evictions{0};
    std::atomic<uint64_t> lru_evictions{0};
    std::atomic<uint64_t> lru_evictions_bytes_freed{0};
    
    // Capacity State (snapshot under lock)
    uint64_t current_entry_count = 0;
    uint64_t target_capacity_bytes = 0;        // From config
    uint64_t actual_size_bytes = 0;            // Current usage
    double utilization_ratio = 0.0;            // actual / target (can exceed 1.0)
    
    // Derived
    double hit_rate() const {
        uint64_t h = hits.load();
        uint64_t m = misses.load();
        return (h + m) > 0 ? (100.0 * h) / (h + m) : 0.0;
    }
};
```

### Pattern Compilation Cache Metrics Pseudocode
```cpp
struct PatternCacheMetrics {
    // Hit/Miss (reuse efficiency)
    std::atomic<uint64_t> hits{0};
    std::atomic<uint64_t> misses{0};
    
    // Errors
    std::atomic<uint64_t> compilation_errors{0};
    
    // Evictions (by background thread)
    std::atomic<uint64_t> ttl_evictions{0};
    std::atomic<uint64_t> lru_evictions{0};
    std::atomic<uint64_t> lru_evictions_bytes_freed{0};
    std::atomic<uint64_t> entries_moved_to_deferred{0};  // refcount > 0 at eviction
    
    // Capacity State (snapshot under lock)
    uint64_t current_entry_count = 0;
    uint64_t target_capacity_bytes = 0;        // From config
    uint64_t actual_size_bytes = 0;            // Current usage
    double utilization_ratio = 0.0;            // actual / target (can exceed 1.0)
    
    // Derived
    double hit_rate() const {
        uint64_t h = hits.load();
        uint64_t m = misses.load();
        return (h + m) > 0 ? (100.0 * h) / (h + m) : 0.0;
    }
};
```

### Deferred Cache Metrics Pseudocode
```cpp
struct DeferredCacheMetrics {
    // Evictions (by background thread)
    std::atomic<uint64_t> immediate_evictions{0};        // refcount → 0
    std::atomic<uint64_t> forced_evictions{0};           // TTL expired (LEAK!)
    std::atomic<uint64_t> forced_evictions_bytes_freed{0};
    
    // Capacity State (snapshot under lock)
    uint64_t current_entry_count = 0;
    uint64_t actual_size_bytes = 0;            // Current usage
};
```

### Combined Metrics Export Pseudocode
```cpp
struct CacheMetrics {
    PatternResultCacheMetrics pattern_result_cache;
    PatternCacheMetrics pattern_cache;
    DeferredCacheMetrics deferred_cache;
    std::chrono::system_clock::time_point generated_at;
};
```

### JSON Export Example
```json
{
  "pattern_result_cache": {
    "enabled": true,
    "hits": 100000,
    "misses": 30000,
    "hit_rate": 76.9,
    "get_errors": 5,
    "put_errors": 2,
    "evictions": {
      "ttl": 500,
      "lru": 200,
      "lru_bytes_freed": 5242880
    },
    "capacity": {
      "target_bytes": 104857600,
      "actual_bytes": 125829120,
      "entry_count": 1200,
      "utilization_ratio": 1.20
    }
  },
  "pattern_cache": {
    "hits": 50000,
    "misses": 10000,
    "hit_rate": 83.3,
    "compilation_errors": 2,
    "evictions": {
      "ttl": 10,
      "lru": 25,
      "lru_bytes_freed": 2621440,
      "moved_to_deferred": 3
    },
    "capacity": {
      "target_bytes": 104857600,
      "actual_bytes": 115343360,
      "entry_count": 280,
      "utilization_ratio": 1.10
    }
  },
  "deferred_cache": {
    "evictions": {
      "immediate": 1,
      "forced": 0,
      "forced_bytes_freed": 0
    },
    "capacity": {
      "actual_bytes": 1048576,
      "entry_count": 3
    }
  },
  "generated_at": "2025-11-28T11:17:00Z"
}
```

---

## API Surface (FINAL) Pseudocode

```cpp
class PatternCache {
public:
    explicit PatternCache(const CacheConfig& config);
    ~PatternCache();
    
    // ===== Pattern Compilation API =====
    // Get or compile pattern; manages refcount internally
    // Returns shared_ptr to compiled RE2Pattern
    std::shared_ptr<RE2Pattern> getOrCompile(const std::string& pattern_string);
    
    // Release reference to compiled pattern (called by Pattern.close() in Java)
    // Decrements refcount; may trigger immediate deferred eviction if → 0
    void releasePattern(std::shared_ptr<RE2Pattern>& pattern_ptr);
    
    // ===== Pattern Result Cache API (Optional) =====
    // Get cached match result (if enabled)
    // Returns std::optional; on error: logs metric, returns nullopt
    std::optional<MatchResult> getMatchResult(
        const std::string& pattern_string,
        const std::string& input_string
    );
    
    // Cache match result (if enabled)
    // On error: logs metric, skips (non-fatal)
    void putMatchResult(
        const std::string& pattern_string,
        const std::string& input_string,
        const MatchResult& result
    );
    
    // ===== Eviction & Lifecycle Control =====
    void forceEvictionCheck();  // Trigger eviction pass now
    void forceClear(CacheClearType type);  // Clear cache(s), ignores refcount
    void startEvictionThread();  // Start background eviction (idempotent)
    void stopEvictionThread();   // Stop and join (waits)
    
    // ===== Observability =====
    CacheMetrics getMetrics();     // Snapshot of all metrics (lock-free atomics + brief locks)
    std::string getMetricsJSON();  // JSON-serialized metrics
    void clearMetrics();           // Reset all counters
    
    const CacheConfig& getConfig() const;
};

enum class CacheClearType {
    PATTERN_RESULT_CACHE,
    PATTERN_CACHE,
    DEFERRED_CACHE,
    BOTH_PATTERN_CACHES,
    ALL
};

struct RE2Pattern {
    std::unique_ptr<RE2> compiled_regex;
    std::atomic<uint32_t> refcount{0};
    std::chrono::steady_clock::time_point last_access;
    std::string pattern_string;
    size_t approx_size_bytes;
};

struct MatchResult {
    bool matched;
    std::vector<std::string> captured_groups;
};
```

---

## Data Structures

### Pattern Cache Entry Pseudocode
```cpp
struct PatternCacheEntry {
    std::shared_ptr<RE2Pattern> pattern;
    std::atomic<uint32_t> refcount{0};
    std::chrono::steady_clock::time_point last_access;
    size_t approx_size_bytes;
    std::chrono::steady_clock::time_point inserted_at;
};
```

### Pattern Result Cache Entry Pseudocode
```cpp
struct ResultCacheEntry {
    MatchResult result;
    std::chrono::steady_clock::time_point last_access;
    size_t approx_size_bytes;
};
```

### Deferred Cache Entry Pseudocode
```cpp
struct DeferredCacheEntry {
    std::shared_ptr<RE2Pattern> pattern;
    std::atomic<uint32_t> refcount;
    std::chrono::steady_clock::time_point entered_deferred;
    size_t approx_size_bytes;
};
```

### Cache Keys Pseudocode 
```cpp
// Pattern Cache key
using PatternCacheKey = uint64_t;  // MurmurHash3(pattern_string)

// Result Cache key
struct ResultCacheKey {
    uint64_t pattern_hash;
    uint64_t string_hash;
    
    bool operator==(const ResultCacheKey& other) const {
        return pattern_hash == other.pattern_hash && string_hash == other.string_hash;
    }
};

struct ResultCacheKeyHash {
    size_t operator()(const ResultCacheKey& k) const {
        return k.pattern_hash ^ (k.string_hash >> 1);
    }
};
```

---

## Thread Safety Model

Careful attention and analysis should be done here. The below are initial suggestions but these should be assesed carefully as part of the analysis and implenetation.

### Lock Strategy
- **Pattern Cache**: `std::shared_mutex` (RwLock)
  - Shared lock: `getOrCompile()` cache hit, metrics snapshot
  - Exclusive lock: new compilation, background eviction
- **Result Cache**: `std::shared_mutex` (RwLock)
  - Shared lock: `getMatchResult()`, metrics snapshot
  - Exclusive lock: `putMatchResult()`, background eviction
- **Deferred Cache**: `std::shared_mutex` (RwLock)
  - Exclusive lock: deferral, background eviction, metrics snapshot

### Lock Acquisition Order (Deadlock Prevention)
1. Pattern Cache (if needed)
2. Result Cache (if needed)
3. Deferred Cache (if needed)

Always acquire in this order, release in reverse.

### Refcount
- `std::atomic<uint32_t>` (lock-free)
- Incremented on `getOrCompile()`
- Decremented on `releasePattern()`

### Metrics
- Counters: `std::atomic<T>` (lock-free updates)
- Snapshots (entry_count, actual_size_bytes): Acquired under shared lock (brief)

---

## Background Eviction Thread

### Event Loop Pseudocode

This should only be initialised/run if caching is enabled. Note we are probably adding more metrics in this not defined previously, we should use them.

```cpp
void evictionThreadLoop() {
    while (!stop_requested_) {
        std::this_thread::sleep_for(config_.eviction_check_interval_ms);
        
        // 1. Pattern Result Cache (if enabled)
        if (config_.pattern_result_cache_enabled) {
            std::unique_lock lock(result_cache_mutex_);
            
            // TTL eviction
            auto now = std::chrono::steady_clock::now();
            for (auto it = result_cache_.begin(); it != result_cache_.end(); ) {
                if ((now - it->second.last_access) > config_.pattern_result_cache_ttl_ms) {
                    size_t freed = it->second.approx_size_bytes;
                    result_cache_size_bytes_ -= freed;
                    it = result_cache_.erase(it);
                    metrics_.pattern_result_cache.ttl_evictions.fetch_add(1);
                    metrics_.pattern_result_cache.ttl_evictions_bytes_freed.fetch_add(freed);
                } else {
                    ++it;
                }
            }
            
            // LRU eviction (while over target capacity)
            while (result_cache_size_bytes_ > config_.pattern_result_cache_target_capacity_bytes) {
                auto lru_it = findLRUEntry(result_cache_);
                size_t freed = lru_it->second.approx_size_bytes;
                result_cache_size_bytes_ -= freed;
                result_cache_.erase(lru_it);
                metrics_.pattern_result_cache.lru_evictions.fetch_add(1);
                metrics_.pattern_result_cache.lru_evictions_bytes_freed.fetch_add(freed);
            }

            metrics_.pattern_result_cache.total_bytes_freed.fetch_add(ttl freed + lru freed)

            //We also need a metric counting the total entries freed, like total bytes but the total entries
            metrics_.pattern_result_cache.totals_evictions.fetch_add(the cumalitive count of total number of entries freed);

            
            // Update snapshot metrics
            metrics_.pattern_result_cache.current_entry_count = result_cache_.size();
            metrics_.pattern_result_cache.actual_size_bytes = result_cache_size_bytes_;
            metrics_.pattern_result_cache.utilization_ratio = 
                (double)result_cache_size_bytes_ / config_.pattern_result_cache_target_capacity_bytes;
        }
        
        // 2. Pattern Cache
        {
            std::unique_lock lock(pattern_cache_mutex_);
            
            // TTL eviction
            auto now = std::chrono::steady_clock::now();
            for (auto it = pattern_cache_.begin(); it != pattern_cache_.end(); ) {
                if ((now - it->second.last_access) > config_.pattern_cache_ttl_ms) {
                    if (it->second.refcount.load() == 0) {
                        size_t freed = it->second.approx_size_bytes;
                        pattern_cache_size_bytes_ -= freed;
                        it = pattern_cache_.erase(it);
                        metrics_.pattern_cache.ttl_evictions.fetch_add(1);
                        metrics_.pattern_cache.ttl_evictions_bytes_freed.fetch_add(freed);
                    } else {
                        // Move to deferred cache
                        moveToDeferredCache(it->second);
                        pattern_cache_size_bytes_ -= it->second.approx_size_bytes;
                        it = pattern_cache_.erase(it);
                        metrics_.pattern_cache.ttl_entries_moved_to_deferred.fetch_add(1);
                        metrics_.pattern_cache.ttl_entries_moved_to_deferred_bytes_freed.fetch_add(freed);
                    }
                } else {
                    ++it;
                }
            }
            
            // LRU eviction (while over target capacity)
            while (pattern_cache_size_bytes_ > config_.pattern_cache_target_capacity_bytes) {
                auto lru_it = findLRUEntry(pattern_cache_);
                if (lru_it->second.refcount.load() == 0) {
                    size_t freed = lru_it->second.approx_size_bytes;
                    pattern_cache_size_bytes_ -= freed;
                    pattern_cache_.erase(lru_it);
                    metrics_.pattern_cache.lru_evictions.fetch_add(1);
                    metrics_.pattern_cache.lru_evictions_bytes_freed.fetch_add(freed);
                } else {
                    // Move to deferred
                    moveToDeferredCache(lru_it->second);
                    pattern_cache_size_bytes_ -= lru_it->second.approx_size_bytes;
                    pattern_cache_.erase(lru_it);
                    metrics_.pattern_cache.lru_entries_moved_to_deferred.fetch_add(1);
                    metrics_.pattern_cache.lru_entries_moved_to_deferred_bytes_freed.fetch_add(freed);
                }
            }

            metrics_.pattern_cache.total_bytes_freed.fetch_add(ttl freed + lru freed) //should include deferred freed also

            //We also need a metric counting the total entries freed, like total bytes but the total entries
            metrics_.pattern_cache.totals_evictions.fetch_add(the cumalitive count of number of total entries freed);

            // Update snapshot metrics
            metrics_.pattern_cache.current_entry_count = pattern_cache_.size();
            metrics_.pattern_cache.actual_size_bytes = pattern_cache_size_bytes_;
            metrics_.pattern_cache.utilization_ratio = 
                (double)pattern_cache_size_bytes_ / config_.pattern_cache_target_capacity_bytes;
        }
        
        // 3. Deferred Cache
        {
            std::unique_lock lock(deferred_cache_mutex_);
            
            auto now = std::chrono::steady_clock::now();
            for (auto it = deferred_cache_.begin(); it != deferred_cache_.end(); ) {
                if (it->second.refcount.load() == 0) {
                    // Immediate eviction (leak fixed)
                    deferred_cache_size_bytes_ -= it->second.approx_size_bytes;
                    it = deferred_cache_.erase(it);
                    metrics_.deferred_cache.immediate_evictions.fetch_add(1);
                    metrics_.deferred_cache.immediate_evictions_bytes_freed.fetch_add(freed);

                } else if ((now - it->second.entered_deferred) > config_.deferred_cache_ttl_ms) {
                    // Force eviction (LEAK DETECTED!)
                    size_t freed = it->second.approx_size_bytes;
                    deferred_cache_size_bytes_ -= freed;
                    it = deferred_cache_.erase(it);
                    metrics_.deferred_cache.forced_evictions.fetch_add(1);
                    metrics_.deferred_cache.forced_evictions_bytes_freed.fetch_add(freed);
                    LOG_WARN("Memory leak detected in RE2: pattern held for {} ms", 
                             config_.deferred_cache_ttl_ms.count());
                } else {
                    ++it;
                }
            }
            
            metrics_.deferred_cache.total_bytes_freed.fetch_add(immediate freed + forced freed) //should include deferred freed also

            //We also need a metric counting the total entries freed, like total bytes but the total entries
            metrics_.deferred_cache.itotals_evictions.fetch_add(the cumalitive count of total number of entries freed);

            
            // Update snapshot metrics
            metrics_.deferred_cache.current_entry_count = deferred_cache_.size();
            metrics_.deferred_cache.actual_size_bytes = deferred_cache_size_bytes_;
            metrics_.deferred_cache.utilization_ratio = 
                deferred_cache_size_bytes_ > 0 ? 
                (double)deferred_cache_size_bytes_ / (10 * 1024 * 1024) : 0.0;  // Reference only
        }
    }
}
```

---

## Error Handling (FINAL) Pseudocode

### Pattern Result Cache Errors (Non-Fatal)
```cpp
std::optional<MatchResult> getMatchResult(...) {
    try {
        if (!config_.pattern_result_cache_enabled) return std::nullopt;
        if (input_string.size() > config_.pattern_result_cache_string_threshold_bytes) 
            return std::nullopt;
        
        std::shared_lock lock(result_cache_mutex_);
        auto it = result_cache_.find(key);
        if (it != result_cache_.end()) {
            metrics_.pattern_result_cache.hits.fetch_add(1);
            return it->second.result;
        }
        metrics_.pattern_result_cache.misses.fetch_add(1);
        return std::nullopt;
    } catch (const std::exception& e) {
        metrics_.pattern_result_cache.get_errors.fetch_add(1);
        LOG_WARN("Result cache get error: {}", e.what());
        return std::nullopt;
    }
}

void putMatchResult(...) {
    try {
        if (!config_.pattern_result_cache_enabled) return;
        if (input_string.size() > config_.pattern_result_cache_string_threshold_bytes) return;
        
        std::unique_lock lock(result_cache_mutex_);
        result_cache_[key] = {result, std::chrono::steady_clock::now(), approx_size};
        result_cache_size_bytes_ += approx_size;
    } catch (const std::exception& e) {
        metrics_.pattern_result_cache.put_errors.fetch_add(1);
        LOG_WARN("Result cache put error: {}", e.what());
    }
}
```

### Pattern Compilation Errors (Fatal) Pseudocode
```cpp
std::shared_ptr<RE2Pattern> getOrCompile(const std::string& pattern_string) {
    // Check cache first...
    
    // Compile new pattern
    try {
        auto regex = std::make_unique<RE2>(pattern_string);
        if (!regex->ok()) {
            metrics_.pattern_cache.compilation_errors.fetch_add(1);
            throw std::runtime_error("RE2 compilation failed: " + regex->error());
        }
        // Store in cache...
    } catch (const std::exception& e) {
        metrics_.pattern_cache.compilation_errors.fetch_add(1);
        throw;  // Propagate to Java via JNI
    }
}
```

---

## Design Summary (FULLY LOCKED)

| Cache | Capacity Model | Eviction | Utilization | Key Features |
|-------|---|---|---|---|
| Pattern Result | TARGET (soft) | TTL + LRU (background) | Can exceed 1.0 | Optional, error-tolerant, no refcount |
| Pattern Compilation | TARGET (soft) | TTL + LRU (background) | Can exceed 1.0 | Reference-counted, leak protection |
| Deferred | No limit | Immediate + Forced (background) | Can exceed 1.0 | Leak detection, forced eviction after X min |

| Metric Category | Result Cache | Pattern Cache | Deferred Cache |
|---|---|---|---|
| Hit/Miss | Yes | Yes | N/A |
| Errors | get/put errors | compilation errors | N/A |
| Evictions | TTL/LRU | TTL/LRU/moved | immediate/forced |
| Capacity State | entry_count, target_bytes, actual_bytes, utilization_ratio | Same | Same |
| Refcount State | N/A | N/A | N/A |

---

## Implementation Notes

- **Hash Function**: MurmurHash3 for both pattern_string and input_string (following Cassandra)
- **Lock Strategy**: Separate `std::shared_mutex` for each cache; Pattern > Result > Deferred acquisition order
- **Refcount Model**: Single atomic per entry, incremented on getOrCompile(), decremented on releasePattern()
- **Background Thread**: Runs every configurable interval (default 100ms), enforces TTL + LRU to bring caches back under capacity
- **Metrics**: All counters are `std::atomic<T>` for lock-free updates; snapshots taken under brief read locks
- **Error Handling**: Result cache errors non-fatal (logged, skipped); compilation errors propagate to Java
- **Configuration**: All parameters externalized and configurable; validation in constructor

---

**Ready for Implementation**: This design is fully specified and ready for Claude Code to begin Phase 1 native C++ implementation with comprehensive testing.
