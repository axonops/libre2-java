# Phase 1: Test Inventory and Analysis Report

**Date:** 2025-11-26
**Branch:** testconsolidation
**Purpose:** Comprehensive inventory of all Maven modules, test directories, test files, and dependencies

---

## Executive Summary

**Total Test Classes:** 30
**Total Test Methods:** 459
**Maven Modules:** 2 (libre2-core, libre2-dropwizard)
**Lines of Test Code:** ~5,761 lines

### Current State
- All tests located in standard `src/test/java` directories
- No integration test separation (everything runs via Surefire)
- Performance/stress tests mixed with unit tests
- Dropwizard dependencies present in core module (provided scope, optional)
- No code coverage tooling configured
- No static analysis (Checkstyle, etc.) configured

---

## Module Structure

### 1. libre2-parent (Root POM)
- **Type:** Parent aggregator POM
- **Packaging:** pom
- **Version:** 1.0.0
- **Modules:**
  - libre2-core
  - libre2-dropwizard
- **Test Framework:** JUnit 5.10.0
- **Test Assertion:** AssertJ 3.24.2
- **Build Plugins:**
  - maven-compiler-plugin 3.11.0 (Java 17)
  - maven-surefire-plugin 3.1.2 (unit tests only)
  - No Failsafe plugin (no integration test separation)
  - No JaCoCo (no coverage)
  - No Checkstyle (no static analysis)

### 2. libre2-core Module
- **Type:** Core library
- **Packaging:** jar
- **Location:** `libre2-core/`
- **Purpose:** Core RE2 bindings, Pattern/Matcher API, caching, metrics
- **Dropwizard Usage:**
  - `metrics-core` (provided scope, optional) in main code
  - Used by `DropwizardMetricsAdapter.java`
  - 13 Dropwizard imports in test code
- **Test Source Directory:** `libre2-core/src/test/java`
- **Test Classes:** 27
- **Test Methods:** 441

#### Test Packages in libre2-core:
```
com.axonops.libre2
├── api/                 # 7 test classes, 148 tests
│   ├── BulkMatchingPerformanceTest.java    (3 tests)  [PERFORMANCE]
│   ├── BulkMatchingTest.java               (47 tests) [INTEGRATION]
│   ├── BulkMatchingTypeSafetyTest.java     (13 tests) [UNIT]
│   ├── ByteBufferApiTest.java              (13 tests) [INTEGRATION]
│   ├── CaptureGroupsTest.java              (31 tests) [INTEGRATION]
│   ├── Phase1ExtensionsTest.java           (15 tests) [INTEGRATION]
│   └── ReplaceOperationsTest.java          (26 tests) [INTEGRATION]
├── cache/               # 14 test classes, 98 tests
│   ├── CacheFullInUseTest.java             (6 tests)  [INTEGRATION]
│   ├── CachePerformanceTest.java           (4 tests)  [PERFORMANCE]
│   ├── CacheTest.java                      (12 tests) [INTEGRATION]
│   ├── ConcurrencyTest.java                (7 tests)  [STRESS]
│   ├── ConcurrentCleanupTest.java          (4 tests)  [STRESS]
│   ├── ConfigurationTest.java              (14 tests) [UNIT]
│   ├── DeferredCleanupTimingTest.java      (3 tests)  [INTEGRATION]
│   ├── EvictionEdgeCasesTest.java          (6 tests)  [INTEGRATION]
│   ├── EvictionWhileInUseTest.java         (6 tests)  [STRESS]
│   ├── IdleEvictionTest.java               (5 tests)  [INTEGRATION]
│   ├── NativeMemoryTrackingTest.java       (17 tests) [INTEGRATION]
│   ├── ResourceLimitConfigurationTest.java (5 tests)  [INTEGRATION]
│   ├── StressTest.java                     (4 tests)  [STRESS]
│   └── ThreadSafetyTest.java               (5 tests)  [STRESS]
├── jni/                 # 1 test class, 48 tests
│   └── RE2NativeJNITest.java               (48 tests) [INTEGRATION]
├── metrics/             # 4 test classes, 27 tests
│   ├── ComprehensiveMetricsTest.java       (9 tests)  [INTEGRATION]
│   ├── MetricsIntegrationTest.java         (9 tests)  [INTEGRATION]
│   ├── NativeMemoryMetricsTest.java        (5 tests)  [INTEGRATION]
│   └── TimerHistogramTest.java             (4 tests)  [UNIT]
├── test/                # 1 helper class
│   └── TestUtils.java                      (test helper, not a test)
└── RE2Test.java         # 1 test class, 77 tests [INTEGRATION]
```

### 3. libre2-dropwizard Module
- **Type:** Dropwizard Metrics integration
- **Packaging:** jar
- **Location:** `libre2-dropwizard/`
- **Purpose:** JMX integration, MetricRegistry wiring for Cassandra/Dropwizard apps
- **Dependencies:**
  - libre2-core (compile scope)
  - metrics-jmx (compile scope)
- **Test Source Directory:** `libre2-dropwizard/src/test/java`
- **Test Classes:** 3
- **Test Methods:** 18

#### Test Packages in libre2-dropwizard:
```
com.axonops.libre2.dropwizard/
├── JmxIntegrationTest.java     (6 tests)  [INTEGRATION]
├── MetricsEndToEndTest.java    (6 tests)  [INTEGRATION]
└── RE2MetricsConfigTest.java   (6 tests)  [UNIT]
```

---

## Test Classification Analysis

### Test Types Breakdown

| Test Type | Count | Test Methods | Current Location | Target Location |
|-----------|-------|--------------|------------------|-----------------|
| **Unit Tests** | 4 | ~40 | src/test/java | src/test/java |
| **Integration Tests** | 20 | ~370 | src/test/java | src/integration-test/java |
| **Performance Tests** | 2 | ~7 | src/test/java | perf-test module |
| **Stress Tests** | 4 | ~24 | src/test/java | perf-test module |
| **Helper Classes** | 1 | N/A | src/test/java | src/test/java |

#### Classification Rationale:

**Unit Tests** (No native code, mockable):
- `BulkMatchingTypeSafetyTest.java` - Type checking, no native calls
- `ConfigurationTest.java` - Configuration validation
- `TimerHistogramTest.java` - Pure Java histogram logic
- `RE2MetricsConfigTest.java` - Config builder validation

**Integration Tests** (Native code interaction, multi-component):
- All API tests (BulkMatchingTest, CaptureGroupsTest, etc.) - use native RE2
- Most cache tests (CacheTest, EvictionEdgeCasesTest, etc.) - cache + native
- JNI layer test (RE2NativeJNITest) - direct JNI validation
- Metrics tests (ComprehensiveMetricsTest, etc.) - metrics + native
- Dropwizard tests (JmxIntegrationTest, etc.) - JMX + metrics + native
- RE2Test.java - high-level API validation

**Performance Tests** (Benchmarking, throughput measurement):
- `BulkMatchingPerformanceTest.java` - Bulk vs individual timing
- `CachePerformanceTest.java` - Cache lookup performance

**Stress Tests** (High load, concurrency, resource limits):
- `ConcurrencyTest.java` - Concurrent pattern compilation
- `ConcurrentCleanupTest.java` - Concurrent cleanup safety
- `StressTest.java` - 100 threads, 1000 ops/thread
- `EvictionWhileInUseTest.java` - Race condition testing

---

## Dependency Analysis

### Cross-Module Dependencies

**libre2-dropwizard → libre2-core:**
- Compile dependency (required)
- Imports: `Pattern`, `PatternCache`, `RE2Config`, `Matcher`
- Test dependency: Uses `Pattern` API for validation

**libre2-core → Dropwizard Metrics:**
- **Scope:** `provided` (optional)
- **Location:** `DropwizardMetricsAdapter.java`
- **Impact:** Core can work without Dropwizard if metrics not used
- **Issue:** Optional dependency but integrated into core module

### Dropwizard Usage in Core Module

**Production Code:**
```
libre2-core/src/main/java/com/axonops/libre2/metrics/DropwizardMetricsAdapter.java
  - imports: com.codahale.metrics.{Gauge, MetricRegistry}
  - Scope: Optional, provided scope
  - Used by: RE2MetricsRegistry when MetricRegistry provided
```

**Test Code (13 imports):**
```
Metrics tests (ComprehensiveMetricsTest, MetricsIntegrationTest, etc.)
  - Use MetricRegistry for validation
  - Could potentially use interface/abstraction instead
```

### Native Code Dependencies

**All integration tests depend on:**
- Native RE2 library (`libre2.dylib`/`libre2.so`)
- JNI layer (`RE2NativeJNI.java`)
- Platform-specific native loading

**Cannot be unit tested without:**
- Mocking JNI layer
- Abstracting native calls
- Interface-based design

---

## Test Infrastructure

### Test Utilities
- **Location:** `libre2-core/src/test/java/com/axonops/libre2/test/TestUtils.java`
- **Purpose:**
  - Test cache setup/teardown
  - Global cache replacement for test isolation
  - Configuration builders for tests
- **Lines:** ~200 lines
- **Usage:** Used by most integration tests

### Test Configuration
- **Logging:** Logback configured via `src/test/resources/logback-test.xml`
- **Cache Settings:** Tests use custom configs (smaller cache sizes, shorter timeouts)
- **JMX:** Tests disable JMX by default to prevent `InstanceAlreadyExistsException`

---

## Current Test Execution

### Maven Surefire Configuration
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.1.2</version>
    <configuration>
        <!-- JVM arguments for DirectBuffer access -->
        <argLine>
            --add-exports=java.base/sun.nio.ch=ALL-UNNAMED
        </argLine>
    </configuration>
</plugin>
```

### Build Commands
- **Compile:** `mvn compile` (compiles all modules)
- **Test:** `mvn test` (runs all tests via Surefire, no separation)
- **Package:** `mvn package` (includes test execution)

### Current Issues
1. **No test type separation**: All tests run together via Surefire
2. **Long test execution**: Performance/stress tests slow down CI
3. **No integration test phase**: Cannot skip integration tests separately
4. **No coverage**: No JaCoCo or similar tool configured
5. **No static analysis**: No Checkstyle, SpotBugs, etc.

---

## Problems Identified

### 1. Test Organization
- **Issue:** Performance tests mixed with unit tests
- **Impact:** Cannot skip slow tests in CI
- **Recommendation:** Move to separate `perf-test` module

### 2. Integration Test Separation
- **Issue:** Integration tests run via Surefire (unit test plugin)
- **Impact:** Cannot run unit tests independently of native library
- **Recommendation:** Use Failsafe plugin, move to `src/integration-test/java`

### 3. Dropwizard in Core
- **Issue:** Core module has optional Dropwizard dependency
- **Impact:** Users who don't use Dropwizard still pull it in (provided scope)
- **Complexity:** Adapter is tightly integrated into core metrics
- **Recommendation:**
  - Option A: Keep as-is (provided scope means users supply it)
  - Option B: Extract to separate module (invasive refactoring)
  - **Decision needed:** Is current separation sufficient?

### 4. Unit Test Coverage
- **Issue:** Only ~4 true unit tests (no native dependency)
- **Impact:** Cannot test business logic without native library present
- **Recommendation:**
  - Create interface for JNI layer
  - Mock native calls for pure unit tests
  - Test configuration, validation, error handling in isolation

### 5. No Code Coverage Metrics
- **Issue:** No coverage tool configured
- **Impact:** Cannot track test coverage, no enforcement
- **Recommendation:** Add JaCoCo with threshold enforcement

### 6. No Static Analysis
- **Issue:** No style checker, no static analysis
- **Impact:** Code style inconsistencies, potential bugs
- **Recommendation:** Add Checkstyle with Google Java Style

---

## Recommendations for Phase 2

### New Module Structure
```
libre2-java/
├── pom.xml                          (parent, adds JaCoCo + Checkstyle)
├── libre2-core/                     (core library)
│   ├── src/main/java/               (production code)
│   ├── src/test/java/               (unit tests only - 4 tests)
│   └── src/integration-test/java/   (integration tests - ~370 tests)
├── libre2-dropwizard/               (Dropwizard integration)
│   ├── src/main/java/
│   ├── src/test/java/               (unit tests)
│   └── src/integration-test/java/   (integration tests)
└── perf-test/                       (NEW MODULE)
    ├── pom.xml                      (depends on libre2-core)
    ├── src/test/java/               (performance + stress tests)
    │   ├── performance/             (BulkMatchingPerformanceTest, etc.)
    │   └── stress/                  (StressTest, ConcurrencyTest, etc.)
    └── README.md                    (how to run, what to expect)
```

### Test Migration Plan

**Move to perf-test module:**
- `BulkMatchingPerformanceTest.java` → `perf-test/src/test/java/performance/`
- `CachePerformanceTest.java` → `perf-test/src/test/java/performance/`
- `StressTest.java` → `perf-test/src/test/java/stress/`
- `ConcurrencyTest.java` → `perf-test/src/test/java/stress/`
- `ConcurrentCleanupTest.java` → `perf-test/src/test/java/stress/`
- `EvictionWhileInUseTest.java` → `perf-test/src/test/java/stress/`

**Move to src/integration-test/java:**
- All API tests (except BulkMatchingTypeSafetyTest)
- Most cache tests (except ConfigurationTest)
- JNI layer test (RE2NativeJNITest)
- Metrics tests (except TimerHistogramTest)
- Dropwizard tests (except RE2MetricsConfigTest)
- RE2Test.java

**Keep in src/test/java (unit tests):**
- `BulkMatchingTypeSafetyTest.java`
- `ConfigurationTest.java`
- `TimerHistogramTest.java`
- `RE2MetricsConfigTest.java`
- `TestUtils.java` (helper)

---

## Next Steps (Awaiting Approval)

### Before Proceeding to Phase 2:
1. **Review this inventory** - Confirm test classification is accurate
2. **Decide on Dropwizard** - Keep in core (provided) or extract?
3. **Approve migration plan** - Confirm target structure is acceptable
4. **Confirm no logic changes** - Phase 2 will only move files, not edit tests

### After Approval:
- Create `testconsolidation/phase-2-migration` branch
- Create `perf-test` module
- Create `src/integration-test/java` directories
- Migrate tests (file moves only, no edits)
- Configure Failsafe plugin for integration tests
- Verify all 459 tests still pass

---

## Token Usage Report

**Phase 1 Token Usage:** ~12,000 tokens (approximately)
**Remaining Budget:** ~945,000 tokens

---

**End of Phase 1 Inventory**
**Status:** ✅ COMPLETE - Awaiting user review and approval to proceed to Phase 2
