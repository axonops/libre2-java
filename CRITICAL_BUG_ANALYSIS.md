# Critical Bug Analysis

## Bug 1: Pattern Count Going Negative

**Symptom:** ResourceTracker.activePatternsCount goes negative during shutdown

**Location:** Shutdown hooks clearing cache

**Root Cause Analysis:**

Looking at Pattern.doCompile() finally block:
```java
} finally {
    if (handle != 0 && !isCompilationSuccessful(handle)) {
        try {
            RE2NativeJNI.freePattern(handle);
        } catch (Exception e) {
            // Silently ignore
        }
    }
}
```

**The Problem:**
- `isCompilationSuccessful(handle)` calls `RE2NativeJNI.patternOk(handle)`
- But if the pattern WAS successfully compiled and wrapped in a Pattern object
- The handle is now owned by the Pattern object
- We shouldn't be freeing it in the finally block!
- This causes DOUBLE FREE!

**Scenario:**
1. Pattern compiles successfully
2. Pattern object created with handle
3. Pattern returned to caller
4. Later, Pattern.forceClose() frees the handle (calls trackPatternFreed)
5. BUT the finally block ALSO tries to free it if patternOk check fails
6. Result: trackPatternFreed() called twice for same pattern!

**Fix:** Don't free in finally if pattern was successfully created!

---

## Bug 2: JMX InstanceAlreadyExistsException

**Symptom:** javax.management.InstanceAlreadyExistsException for same MBean names

**Root Cause:**
- Multiple test classes create JmxReporter
- Same metric names across tests
- JmxReporter doesn't unregister MBeans on stop()
- Second test tries to register same MBean → exception

**Fix:**
- Call jmxReporter.close() instead of stop()
- OR use unique metric prefixes per test class
- OR don't start JmxReporter in tests (just test MetricRegistry)

---

## Bug 3: Compilation Failed is ERROR (should be WARN)

User-provided patterns can be invalid - this is not an error in our library.

**Fix:** Change ERROR to WARN
