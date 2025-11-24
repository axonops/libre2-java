# Metric Naming Review

## Current Names vs. Improved Names

### Issues with Current Names
1. `cache.size` - ambiguous (size in what? bytes? count?)
2. `resources.patterns_freed` - not clear it's a cumulative count
3. `matching.operations` - not clear it's a counter

### Proposed Improvements

| Current Name | Issue | Improved Name | Type | Clarity |
|-------------|-------|---------------|------|---------|
| `cache.size` | Ambiguous unit | `cache.patterns.count` | Gauge | Count of cached patterns |
| `cache.evictions_lru` | OK | `cache.evictions.lru.count` | Counter | Cumulative LRU evictions |
| `cache.evictions_idle` | OK | `cache.evictions.idle.count` | Counter | Cumulative idle evictions |
| `cache.evictions_deferred` | OK | `cache.evictions.deferred.count` | Counter | Cumulative deferred evictions |
| `cache.native_memory_bytes` | ✅ Good | `cache.native_memory.bytes` | Gauge | Off-heap memory in bytes |
| `cache.native_memory_peak_bytes` | ✅ Good | `cache.native_memory.peak.bytes` | Gauge | Peak memory in bytes |
| `patterns.compiled` | Could be clearer | `patterns.compiled.count` | Counter | Total patterns compiled |
| `patterns.cache_hits` | OK | `patterns.cache.hits.count` | Counter | Cache hit count |
| `patterns.cache_misses` | OK | `patterns.cache.misses.count` | Counter | Cache miss count |
| `patterns.compilation_time` | OK | `patterns.compilation.latency` | Timer | Compilation time (ns) |
| `patterns.invalid_recompiled` | OK | `patterns.invalid.recompiled.count` | Counter | Invalid patterns recompiled |
| `resources.patterns_active` | OK | `resources.patterns.active.count` | Gauge | Active pattern count |
| `resources.matchers_active` | OK | `resources.matchers.active.count` | Gauge | Active matcher count |
| `resources.patterns_freed` | Could be clearer | `resources.patterns.freed.count` | Gauge | Cumulative patterns freed |
| `resources.matchers_freed` | Could be clearer | `resources.matchers.freed.count` | Gauge | Cumulative matchers freed |
| `matching.full_match` | OK | `matching.full_match.latency` | Timer | Full match time (ns) |
| `matching.partial_match` | OK | `matching.partial_match.latency` | Timer | Partial match time (ns) |
| `matching.operations` | Could be clearer | `matching.operations.count` | Counter | Total match operations |
| `errors.compilation_failed` | OK | `errors.compilation.failed.count` | Counter | Compilation failures |
| `errors.native_library` | OK | `errors.native_library.count` | Counter | Native library errors |
| `errors.resource_exhausted` | OK | `errors.resource.exhausted.count` | Counter | Resource limit hits |

### Recommended Changes (Breaking)

**High priority (clarity):**
1. `cache.size` → `cache.patterns.count`
2. `patterns.compiled` → `patterns.compiled.count`
3. `matching.operations` → `matching.operations.count`

**Medium priority (consistency):**
4. Add `.count` suffix to all counters
5. Add `.latency` suffix to all timers
6. Use `.` hierarchy consistently

**Should we make these changes now (breaking) or defer to 1.0?**
