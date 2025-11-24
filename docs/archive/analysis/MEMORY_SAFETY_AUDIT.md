# Memory Safety Audit - freePattern() Calls

**Date:** 2025-11-20

## All RE2NativeJNI.freePattern() Call Sites

### 1. RE2LibraryLoader.java:234 (Initialization Test)

**Location:** performInitializationTest() finally block

**Code:**
```java
} finally {
    if (testPatternHandle != 0) {
        try {
            RE2NativeJNI.freePattern(testPatternHandle);
        } catch (Exception e) {
            // Silently ignore
        }
    }
}
```

**Safety:** ✅ SAFE
- In finally block (always executes)
- Null check (handle != 0)
- Exception handling (silent)
- No resource leak possible

---

### 2. Pattern.java:157 (Compilation Error Handling)

**Location:** doCompile() - when pattern compilation fails

**Code:**
```java
if (handle == 0 || !RE2NativeJNI.patternOk(handle)) {
    String error = RE2NativeJNI.getError();
    if (handle != 0) {
        RE2NativeJNI.freePattern(handle);  // ← NOT in finally!
    }
    ResourceTracker.trackPatternFreed(metrics);
    metrics.incrementCounter("errors.compilation.failed.total.count");
    logger.error(...);
    throw new PatternCompilationException(...);
}
```

**Safety:** ⚠️ POTENTIALLY UNSAFE
- NOT in finally block
- What if getError() throws?
- What if metrics.incrementCounter() throws?
- What if logger.error() throws?
- Pattern handle could leak!

**Risk:** Medium - if any exception between freePattern and throw, pattern not freed

**Fix needed:** Wrap in try-finally

---

### 3. Pattern.java:307 (forceClose)

**Location:** forceClose() - called by cache during eviction

**Code:**
```java
if (closed.compareAndSet(false, true)) {
    logger.trace("RE2: Force closing pattern");
    RE2NativeJNI.freePattern(nativeHandle);  // ← NOT in try-catch!

    ResourceTracker.trackPatternFreed(cache.getConfig().metricsRegistry());
}
```

**Safety:** ⚠️ UNSAFE
- NOT in try-catch
- If freePattern() throws, tracking won't happen
- Pattern marked as closed but tracking not updated
- Resource counts will be wrong!

**Risk:** High - resource tracking will desync if freePattern throws

**Fix needed:** Wrap in try-catch, ensure tracking always happens

---

## Recommendations

### CRITICAL: Fix Pattern.java:307 (forceClose)
```java
if (closed.compareAndSet(false, true)) {
    logger.trace("RE2: Force closing pattern");
    try {
        RE2NativeJNI.freePattern(nativeHandle);
    } catch (Exception e) {
        logger.error("RE2: Error freeing pattern handle", e);
        // Continue to tracking - pattern is marked closed
    } finally {
        // Always track freed (even if free threw exception)
        ResourceTracker.trackPatternFreed(cache.getConfig().metricsRegistry());
    }
}
```

### HIGH PRIORITY: Fix Pattern.java:157 (Compilation Error)
```java
long handle = RE2NativeJNI.compile(pattern, caseSensitive);

if (handle == 0 || !RE2NativeJNI.patternOk(handle)) {
    try {
        String error = RE2NativeJNI.getError();
        // Record error
        ResourceTracker.trackPatternFreed(metrics);
        metrics.incrementCounter("errors.compilation.failed.total.count");
        logger.error(...);
        throw new PatternCompilationException(...);
    } finally {
        // Always free handle if non-zero
        if (handle != 0) {
            try {
                RE2NativeJNI.freePattern(handle);
            } catch (Exception e) {
                // Silently ignore - best effort
            }
        }
    }
}
```

---

## Impact if Not Fixed

**forceClose() throws:**
- Pattern marked closed ✓
- Native memory not freed ✗ (LEAK!)
- ResourceTracker not updated ✗ (COUNT WRONG!)
- Metrics not recorded ✗

**Compilation error path throws:**
- ResourceTracker might not decrement ✗
- Pattern handle might leak ✗
- Error metric might not increment ✗

**Both are memory leak risks!**
