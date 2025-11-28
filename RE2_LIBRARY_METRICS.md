# RE2 Library Metrics - Complete Reference

**Date:** 2025-11-28
**Source:** [RE2 re2.h header](https://github.com/google/re2/blob/main/re2/re2.h)
**Status:** DOCUMENTED

---

## Overview

The RE2 C++ library exposes several metrics and introspection methods that should be included in our `getMetrics()` JSON output.

---

## RE2 Class Public Methods

### Status and Configuration

**Already exposed in our JNI:**
- ✅ `bool ok() const` - Pattern validity (currently via `patternOk()`)
- ✅ `const std::string& error() const` - Error message (currently via `getError()`)
- ✅ `const std::string& pattern() const` - Pattern string (currently via `getPattern()`)

**Not yet exposed:**
- ❌ `ErrorCode error_code() const` - Error code enum value
- ❌ `const std::string& error_arg() const` - Problematic pattern fragment
- ❌ `const Options& options() const` - Configuration options

### Program Analysis (Performance/Complexity)

**Already exposed:**
- ✅ `int ProgramSize() const` - Compiled program size (currently via `patternMemory()`)
- ✅ `int ProgramFanout(std::vector<int>* histogram) const` - Fanout distribution (currently via `programFanout()`)

**Not yet exposed:**
- ❌ `int ReverseProgramSize() const` - Reverse program size
- ❌ `int ReverseProgramFanout(std::vector<int>* histogram) const` - Reverse fanout

### Pattern Analysis (Structure)

**Already exposed:**
- ✅ `int NumberOfCapturingGroups() const` - Count of groups (currently via `numCapturingGroups()`)
- ✅ `const std::map<std::string, int>& NamedCapturingGroups() const` - Name→index map (currently via `getNamedGroups()`)

**Not yet exposed:**
- ❌ `const std::map<int, std::string>& CapturingGroupNames() const` - Index→name map (inverse)

---

## Metrics to Include in getMetrics() JSON

### ~~Per-Pattern Metrics (from cache)~~ ❌ NOT in getMetrics()

**Decision:** DO NOT include per-pattern metrics in `getMetrics()` output.

**Rationale:**
- Could have 1000+ cached patterns
- JSON output would be massive
- Performance impact on metrics collection
- Not useful for monitoring (only debugging)

**Alternative:** Separate on-demand API (see below)

### Aggregate RE2 Library Metrics

Global metrics across all patterns:

```json
{
  "re2_library": {
    // Compilation statistics
    "patterns_compiled_successfully": 1000,
    "patterns_failed_compilation": 5,

    // Size statistics
    "total_program_size_bytes": 1024000,
    "avg_program_size_bytes": 1024,
    "max_program_size_bytes": 10240,
    "min_program_size_bytes": 128,

    // Complexity statistics
    "avg_capturing_groups": 2.5,
    "max_capturing_groups": 10,

    // Pattern types
    "patterns_with_named_groups": 50,
    "case_sensitive_patterns": 800,
    "case_insensitive_patterns": 200
  }
}
```

---

## Implementation Decisions

### What to Include in getMetrics()

**Per-Pattern Metrics:**
- ❌ **NOT INCLUDED** - would be too large with 1000+ patterns
- ✅ **Use separate on-demand API** - `getPatternMetrics(handle)` for debugging

**Aggregate RE2 Library Metrics:**
- ✅ **Total program size** across all cached patterns
- ✅ **Average program size** for sizing estimates
- ✅ **Max/min program size** for outlier detection
- ✅ **Compilation success/failure counts**
- ✅ **Pattern type distribution** (case sensitive/insensitive)

### JSON Structure

```json
{
  "cache": {
    // All cache metrics as designed
    "pattern_result_cache": { /* ... */ },
    "pattern_cache": { /* ... */ },
    "deferred_cache": { /* ... */ }
  },

  "re2_library": {
    "program_size": {
      "total_bytes": 1024000,
      "average_bytes": 1024,
      "max_bytes": 10240,
      "min_bytes": 128
    },
    "patterns": {
      "total_compiled": 1000,
      "compilation_failures": 5,
      "case_sensitive": 800,
      "case_insensitive": 200
    },
    "capturing_groups": {
      "avg_per_pattern": 2.5,
      "max_per_pattern": 10,
      "patterns_with_named_groups": 50
    }
  },

  "generated_at": "2025-11-28T12:00:00Z"
}
```

---

## Implementation Plan

### Step 1: Track RE2 Metrics in CacheManager

```cpp
struct RE2LibraryMetrics {
    std::atomic<uint64_t> patterns_compiled{0};
    std::atomic<uint64_t> compilation_failures{0};
    std::atomic<uint64_t> case_sensitive_patterns{0};
    std::atomic<uint64_t> case_insensitive_patterns{0};

    // Snapshot metrics (updated during eviction thread pass)
    uint64_t total_program_size_bytes = 0;
    uint64_t avg_program_size_bytes = 0;
    uint64_t max_program_size_bytes = 0;
    uint64_t min_program_size_bytes = 0;

    double avg_capturing_groups = 0.0;
    uint64_t max_capturing_groups = 0;
    uint64_t patterns_with_named_groups = 0;

    nlohmann::json toJson() const;
};
```

### Step 2: Update CacheManager::getMetrics()

```cpp
std::string CacheManager::getMetrics() const {
    nlohmann::json j;

    // 1. Cache metrics (as designed)
    j["cache"] = metrics_.toJson();

    // 2. RE2 library metrics
    j["re2_library"] = re2_metrics_.toJson();

    // 3. Timestamp
    auto now = std::chrono::system_clock::now();
    j["generated_at"] = formatISO8601(now);

    return j.dump();
}
```

### Step 3: Update Metrics in Eviction Thread

During eviction thread pass, calculate RE2 library statistics:

```cpp
void EvictionThread::run() {
    while (running_) {
        // ... eviction logic ...

        // Update RE2 library metrics (once per cycle)
        updateRE2LibraryMetrics();
    }
}

void updateRE2LibraryMetrics() {
    std::shared_lock lock(pattern_cache_mutex_);

    uint64_t total_size = 0;
    uint64_t max_size = 0;
    uint64_t min_size = UINT64_MAX;
    uint64_t total_groups = 0;
    uint64_t max_groups = 0;
    uint64_t named_groups_count = 0;

    for (const auto& [key, entry] : pattern_cache_) {
        const RE2* re = entry.pattern->compiled_regex.get();

        // Program size
        int prog_size = re->ProgramSize();
        total_size += prog_size;
        max_size = std::max(max_size, (uint64_t)prog_size);
        min_size = std::min(min_size, (uint64_t)prog_size);

        // Capturing groups
        int num_groups = re->NumberOfCapturingGroups();
        total_groups += num_groups;
        max_groups = std::max(max_groups, (uint64_t)num_groups);

        // Named groups
        if (!re->NamedCapturingGroups().empty()) {
            named_groups_count++;
        }
    }

    size_t pattern_count = pattern_cache_.size();

    re2_metrics_.total_program_size_bytes = total_size;
    re2_metrics_.avg_program_size_bytes = pattern_count > 0 ? total_size / pattern_count : 0;
    re2_metrics_.max_program_size_bytes = max_size;
    re2_metrics_.min_program_size_bytes = (min_size == UINT64_MAX) ? 0 : min_size;
    re2_metrics_.avg_capturing_groups = pattern_count > 0 ? (double)total_groups / pattern_count : 0.0;
    re2_metrics_.max_capturing_groups = max_groups;
    re2_metrics_.patterns_with_named_groups = named_groups_count;
}
```

---

## On-Demand Pattern Metrics (For Debugging)

**Separate API for inspecting individual patterns:**

### C++ Implementation

```cpp
// Get detailed metrics for a specific pattern (debugging only)
std::string getPatternMetrics(long patternHandle) {
    RE2* re = reinterpret_cast<RE2*>(patternHandle);
    if (!re || !re->ok()) {
        return R"({"error": "Invalid pattern handle"})";
    }

    nlohmann::json j;

    // Basic info
    j["pattern_string"] = re->pattern();
    j["compiled_successfully"] = re->ok();

    // Size metrics
    j["program_size_bytes"] = re->ProgramSize();
    j["reverse_program_size_bytes"] = re->ReverseProgramSize();

    // Structure metrics
    j["num_capturing_groups"] = re->NumberOfCapturingGroups();

    // Named groups
    auto named_groups = re->NamedCapturingGroups();
    if (!named_groups.empty()) {
        j["named_groups"] = named_groups;
    }

    // Complexity (program fanout)
    std::vector<int> histogram;
    int max_bucket = re->ProgramFanout(&histogram);
    j["program_fanout_max_bucket"] = max_bucket;
    j["program_fanout_histogram"] = histogram;

    // Case sensitivity
    j["case_sensitive"] = re->options().case_sensitive();

    return j.dump();
}
```

### JNI Function

```cpp
JNIEXPORT jstring JNICALL Java_com_axonops_libre2_jni_RE2NativeJNI_getPatternMetrics(
    JNIEnv* env, jclass cls, jlong handle) {

    std::string json = getPatternMetrics(handle);
    return env->NewStringUTF(json.c_str());
}
```

### Java API (Pattern class)

```java
// In Pattern.java (api package)
public final class Pattern implements AutoCloseable {
    private final long nativeHandle;
    private final IRE2Native jni;

    // ... existing methods ...

    /**
     * Gets detailed metrics for this pattern (for debugging).
     *
     * <p>Returns JSON with pattern metadata, size, complexity, and structure.
     * This is intended for debugging and diagnostics only.
     *
     * <p><b>Example output:</b>
     * <pre>{@code
     * {
     *   "pattern_string": "\\d{3}-\\d{4}",
     *   "compiled_successfully": true,
     *   "program_size_bytes": 1024,
     *   "reverse_program_size_bytes": 512,
     *   "num_capturing_groups": 2,
     *   "named_groups": {"area": 1, "num": 2},
     *   "program_fanout_max_bucket": 3,
     *   "program_fanout_histogram": [10, 5, 2, 1, 0, 0],
     *   "case_sensitive": true
     * }
     * }</pre>
     *
     * @return JSON string with pattern metrics
     * @since 1.1.0
     */
    public String getMetrics() {
        checkClosed();
        return jni.getPatternMetrics(nativeHandle);
    }
}
```

### IRE2Native Interface

```java
// In IRE2Native.java (jni package)
public interface IRE2Native {
    // ... existing methods ...

    /**
     * Gets detailed metrics for a specific pattern (debugging only).
     *
     * @param handle native pattern handle
     * @return JSON string with pattern metrics
     */
    String getPatternMetrics(long handle);
}
```

### RE2Native Adapter

```java
// In RE2Native.java (jni package)
public final class RE2Native implements IRE2Native {
    // ... existing methods ...

    @Override
    public String getPatternMetrics(long handle) {
        return RE2NativeJNI.getPatternMetrics(handle);
    }
}
```

### RE2NativeJNI (package-protected)

```java
// In RE2NativeJNI.java (jni package)
final class RE2NativeJNI {
    // ... existing methods ...

    /**
     * Gets detailed metrics for a specific pattern (debugging only).
     */
    static native String getPatternMetrics(long handle);
}
```

### Usage Example

```java
// Debugging: Inspect a specific pattern
Pattern pattern = RE2.compile("\\d{3}-\\d{4}");
String metricsJson = pattern.getMetrics();
System.out.println(metricsJson);

// Output:
// {
//   "pattern_string": "\\d{3}-\\d{4}",
//   "program_size_bytes": 1024,
//   "num_capturing_groups": 2,
//   ...
// }
```

**Decision:** ✅ IMPLEMENT - Add `getPatternMetrics(handle)` for debugging individual patterns.

---

## Sources

- [RE2 C++ API Wiki](https://github.com/google/re2/wiki/CplusplusAPI)
- [RE2 re2.h header](https://github.com/google/re2/blob/main/re2/re2.h)
- [Golang RE2 ProgramSize proposal](https://github.com/golang/go/issues/39413)
- [RE2/J Pattern.programSize()](https://github.com/google/re2j/releases)

---

**Status:** DOCUMENTED - READY FOR IMPLEMENTATION
