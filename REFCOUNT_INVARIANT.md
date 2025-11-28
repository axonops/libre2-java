# Critical Refcount Invariant - Race Condition Prevention

**Date:** 2025-11-28
**Status:** MANDATORY FOR CORRECTNESS

---

## The Rule

**Increment refcount BEFORE releasing lock. Decrement AFTER use.**

This prevents use-after-free race conditions between cache users and eviction thread.

---

## ✅ CORRECT Implementation

```cpp
std::shared_ptr<RE2Pattern> PatternCache::getOrCompile(const std::string& pattern) {
    std::shared_lock lock(mutex_);
    auto it = cache_.find(key);

    if (it != cache_.end()) {
        // CRITICAL: Increment WHILE lock held
        it->second.pattern->refcount.fetch_add(1);  // ⭐
        it->second.last_access = std::chrono::steady_clock::now();

        auto result = it->second.pattern;
        // Lock released by destructor
        return result;  // Safe - refcount already +1
    }

    // Miss - compile new pattern
    // (refcount initialized to 1 before adding to cache)
}
```

---

## ❌ WRONG Implementation (Race Condition!)

```cpp
std::shared_ptr<RE2Pattern> PatternCache::getOrCompile(const std::string& pattern) {
    std::shared_ptr<RE2Pattern> result;

    {
        std::shared_lock lock(mutex_);
        auto it = cache_.find(key);
        if (it != cache_.end()) {
            result = it->second.pattern;
        }
    }  // ⚠️ Lock released here

    // ⚠️ DANGER ZONE - eviction thread could delete pattern here!
    if (result) {
        result->refcount.fetch_add(1);  // ❌ TOO LATE!
    }

    return result;  // Might be dangling pointer!
}
```

---

## The Race Condition

**Without lock-held increment:**

```
Time  | Thread A (Client)              | Thread B (Eviction)
──────|────────────────────────────────|─────────────────────
T0    | Acquire lock                   |
T1    | Find pattern (refcount=0)      |
T2    | Release lock                   |
T3    |                                | Acquire lock
T4    |                                | Check refcount == 0 ✓
T5    |                                | Delete pattern ☠️
T6    | Increment refcount ❌          |
T7    | Use pattern ☠️ USE-AFTER-FREE  |
```

**With lock-held increment (CORRECT):**

```
Time  | Thread A (Client)              | Thread B (Eviction)
──────|────────────────────────────────|─────────────────────
T0    | Acquire lock                   |
T1    | Find pattern (refcount=0)      |
T2    | Increment refcount → 1 ⭐      | (Lock held)
T3    | Release lock                   |
T4    |                                | Acquire lock
T5    |                                | Check refcount == 1 ✗
T6    |                                | Don't delete (in use)
T7    | Use pattern ✓ SAFE             |
T8    | Decrement refcount → 0         |
T9    |                                | Check refcount == 0 ✓
T10   |                                | Delete pattern ✓ SAFE
```

---

## Implementation in Both Code Paths

### std::unordered_map Path

```cpp
std::shared_ptr<RE2Pattern> getStdMap(uint64_t key) {
    std::shared_lock lock(std_mutex_);

    auto it = std_cache_.find(key);
    if (it != std_cache_.end()) {
        // CRITICAL: Increment BEFORE lock release
        it->second.pattern->refcount.fetch_add(1);
        it->second.last_access = std::chrono::steady_clock::now();
        return it->second.pattern;
    }
    // Lock released by destructor

    return nullptr;
}
```

### TBB concurrent_hash_map Path

```cpp
std::shared_ptr<RE2Pattern> getTBB(uint64_t key) {
    TBBMap::const_accessor acc;

    if (tbb_cache_.find(acc, key)) {
        // CRITICAL: Increment BEFORE accessor destruction
        acc->second.pattern->refcount.fetch_add(1);
        acc->second.last_access = std::chrono::steady_clock::now();
        auto result = acc->second.pattern;
        // acc destructor releases lock
        return result;
    }

    return nullptr;
}
```

---

## Eviction Thread Safety

```cpp
size_t evict(DeferredCache& deferred) {
    std::unique_lock lock(mutex_);  // Exclusive lock for iteration

    for (auto it = cache_.begin(); it != cache_.end(); ) {
        // Check refcount WHILE lock held
        uint32_t rc = it->second.pattern->refcount.load();

        if (rc == 0 && ttl_expired(it->second)) {
            // Safe to evict
            cache_.erase(it++);
        } else if (rc > 0 && ttl_expired(it->second)) {
            // Move to deferred (still in use)
            deferred.add(it->first, it->second.pattern, metrics);
            cache_.erase(it++);
        } else {
            ++it;
        }
    }
}
```

---

## Testing Strategy

### Unit Tests

1. **Test: Refcount incremented on cache hit**
   ```cpp
   auto p1 = cache.getOrCompile("test");  // refcount = 1
   auto p2 = cache.getOrCompile("test");  // Same pattern, refcount = 2
   EXPECT_EQ(p1.get(), p2.get());
   EXPECT_EQ(p1->refcount.load(), 2);
   ```

2. **Test: Pattern not evicted while in use**
   ```cpp
   auto p = cache.getOrCompile("test");   // refcount = 1
   // Trigger eviction (TTL expired)
   cache.evict();
   // Pattern should be in deferred cache, not deleted
   EXPECT_TRUE(p->isValid());
   ```

### Stress Tests

3. **Test: Concurrent access + eviction (NO crashes)**
   ```cpp
   // 100 threads getting patterns
   // 1 thread evicting constantly
   // Run for 10 seconds
   // valgrind --tool=helgrind (thread sanitizer)
   ```

---

## Documentation in Code

I'll add this comment to pattern_cache.cpp:

```cpp
/**
 * CRITICAL REFCOUNT INVARIANT:
 *
 * Increment refcount BEFORE releasing lock.
 * This prevents use-after-free race conditions.
 *
 * Timeline (CORRECT):
 * 1. Acquire lock
 * 2. Find pattern
 * 3. Increment refcount (WHILE locked) ⭐
 * 4. Release lock
 * 5. Return pointer (safe - refcount already +1)
 *
 * If incremented AFTER lock release:
 * - Eviction thread could delete pattern between lock release and increment
 * - Caller would use freed memory → crash
 */
```

---

**Status:** DOCUMENTED - Will be strictly enforced in Pattern Cache implementation

**Next:** Implement Pattern Compilation Cache with this invariant
