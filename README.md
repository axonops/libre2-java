# libre2-java

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

High-performance, thread-safe Java bindings to [Google's RE2 regular expression library](https://github.com/google/re2).

Built for production use in high-concurrency applications requiring safe, predictable regex matching with linear-time guarantees.

---

## Why RE2?

**RE2 is fundamentally different from Java's built-in regex:**

| Feature | Java Regex | RE2 |
|---------|-----------|-----|
| **Time Complexity** | Exponential (backtracking) | **Linear** (automata-based) |
| **ReDoS Safe** | ❌ Vulnerable | ✅ **Immune** |
| **Thread Safety** | Pattern thread-safe, Matcher not | **Both thread-safe** (Pattern shareable, Matcher per-thread) |
| **Performance** | Varies (can hang) | **Predictable** |
| **Use Case** | Trusted patterns | **Untrusted patterns** (user input, logs) |

**Critical for untrusted patterns:** Processing user-supplied regex patterns on large datasets requires linear-time guarantees to prevent timeouts and ReDoS (Regular Expression Denial of Service) attacks.

---

## Quick Start

```java
// Basic usage
boolean matches = RE2.matches("\\d+", "123");  // true

// Reusable pattern (cached automatically)
Pattern pattern = RE2.compile("\\w+@\\w+\\.\\w+");
boolean isEmail = pattern.matches("user@example.com");  // true

// Pattern matching in text
try (Matcher matcher = pattern.matcher("Contact: admin@test.com")) {
    boolean found = matcher.find();  // true
}
```

**That's it!** Patterns are cached automatically. No manual cleanup needed for cached patterns.

---

## Key Features

### 🚀 Performance
- **Automatic caching** with LRU eviction (50,000 pattern default)
- **Linear-time matching** (no catastrophic backtracking)
- **Concurrent compilation** (100+ threads tested)
- **Off-heap execution** (native RE2 library)

### 🔒 Safety
- **Thread-safe** (Pattern shareable, comprehensive analysis performed)
- **Reference counting** (prevents use-after-free under concurrency)
- **ReDoS immune** (linear time guarantee)
- **Resource limits** (configurable max patterns, max matchers)
- **Memory leak prevention** (deferred cleanup, shutdown hooks)

### ⚙️ Configuration
- **7 tunable parameters** (cache size, timeouts, limits)
- **Production defaults** optimized for high-memory deployments (128GB+ RAM)
- **Validated configuration** (catches misconfigurations at startup)
- **Fully documented** (see [CONFIGURATION.md](CONFIGURATION.md))

### 🧪 Testing
- **163 comprehensive tests** (including concurrency, stress, edge cases)
- **Verified on 4 platforms** (macOS x86/ARM, Linux x86/ARM)
- **No memory leaks** (verified with deferred cleanup tests)
- **No deadlocks** (stress tested with 1000+ threads)

---

## Installation

### Requirements
- **Java 17+** (uses sealed classes, records, text blocks)
- **[JNA](https://github.com/java-native-access/jna) 5.13.0+** (provided scope - your application must supply)
- **[SLF4J](https://www.slf4j.org/) 2.0+** (provided scope - for logging)

### Why "Provided" Dependencies?

libre2-java uses **provided** scope for JNA and SLF4J to avoid version conflicts:

- **[JNA (Java Native Access)](https://github.com/java-native-access/jna):** Used to call the native RE2 C++ library. Your application must include JNA in the classpath. Most frameworks (like database engines, application servers) already include JNA.

- **[SLF4J (Simple Logging Facade for Java)](https://www.slf4j.org/):** Used for logging. libre2-java logs cache operations, resource tracking, and errors. Your application provides the SLF4J implementation (Logback, Log4j2, etc.).

### Maven

```xml
<dependency>
    <groupId>com.axonops</groupId>
    <artifactId>libre2-java</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Your application must provide these dependencies: -->

<!-- JNA - Required for native library calls -->
<dependency>
    <groupId>net.java.dev.jna</groupId>
    <artifactId>jna</artifactId>
    <version>5.13.0</version>
</dependency>

<!-- SLF4J API - Required for logging -->
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>2.0.9</version>
</dependency>

<!-- SLF4J implementation (choose one) -->
<dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-classic</artifactId>
    <version>1.4.14</version>
</dependency>
```

**JAR Size:** 2.4 MB (includes native libraries for all 4 platforms)

---

## Basic Usage

### Simple Matching

```java
// One-off matching (pattern compiled and cached automatically)
boolean matches = RE2.matches("hello.*world", "hello beautiful world");  // true

// Case-insensitive
boolean matches = RE2.matches("HELLO", "hello", false);  // true (case-insensitive)
```

### Pattern Reuse (Recommended)

```java
// Compile once, use many times (retrieved from cache on subsequent calls)
Pattern emailPattern = RE2.compile("\\w+@\\w+\\.\\w+");

// Convenience method - handles Matcher creation/closure internally
boolean valid1 = emailPattern.matches("user@example.com");  // true
boolean valid2 = emailPattern.matches("invalid.email");      // false

// Explicit Matcher - always use try-with-resources
try (Matcher m = emailPattern.matcher("user@example.com")) {
    boolean valid = m.matches();  // true
}
```

**Note:** The `pattern.matches(text)` convenience method internally creates a Matcher and closes it. For explicit Matcher use, always use try-with-resources to ensure proper cleanup.

### Searching in Text

```java
Pattern pattern = RE2.compile("ERROR");

// Find pattern anywhere in text
try (Matcher matcher = pattern.matcher(logEntry)) {
    if (matcher.find()) {
        // Pattern found
    }
}

// Full match (entire string must match)
try (Matcher matcher = pattern.matcher("ERROR")) {
    if (matcher.matches()) {
        // Entire string matches
    }
}
```

### Log Processing Example

```java
// Server log analysis
Pattern errorPattern = RE2.compile("\\[ERROR\\].*");

String[] logLines = loadLogFile();  // 1 million lines

for (String line : logLines) {
    try (Matcher m = errorPattern.matcher(line)) {
        if (m.find()) {
            // Process error line
        }
    }
}
// Fast: Linear time, no backtracking, cached pattern
```

---

## Thread Safety

### ✅ Thread-Safe (Can Share Between Threads)

**Pattern:**
```java
// Safe: Share Pattern between threads
Pattern sharedPattern = RE2.compile("\\d+");

// Thread 1
executor.submit(() -> {
    try (Matcher m = sharedPattern.matcher("123")) {
        return m.matches();
    }
});

// Thread 2
executor.submit(() -> {
    try (Matcher m = sharedPattern.matcher("456")) {
        return m.matches();
    }
});
```

### ❌ NOT Thread-Safe (Do Not Share)

**Matcher:**
```java
// UNSAFE: Do not share Matcher between threads
Matcher matcher = pattern.matcher("test");

// Thread 1
matcher.matches();  // ❌ UNSAFE

// Thread 2
matcher.find();  // ❌ UNSAFE - concurrent access to same Matcher

// Fix: Create separate Matcher per thread
```

**See [Thread Safety Guide](THREAD_SAFETY.md) for details.**

---

## Configuration

**Default configuration works for most production deployments.**

For custom configuration:

```java
RE2Config config = RE2Config.builder()
    .maxCacheSize(100000)           // Cache up to 100K patterns
    .idleTimeoutSeconds(600)         // Evict after 10 min idle
    .deferredCleanupIntervalSeconds(2)  // Cleanup every 2 seconds
    .build();

// Note: Configuration currently uses static cache
// Custom config support coming in Phase 3
```

**Configuration Parameters (7 total):**

| Parameter | Default | Purpose |
|-----------|---------|---------|
| cacheEnabled | true | Enable/disable automatic caching |
| maxCacheSize | 50,000 | Max cached patterns (~50-200MB) |
| idleTimeoutSeconds | 300 | Evict patterns idle > 5 minutes |
| evictionScanIntervalSeconds | 60 | Scan for idle patterns every 60s |
| deferredCleanupIntervalSeconds | 5 | Cleanup evicted patterns every 5s |
| maxSimultaneousCompiledPatterns | 100,000 | Max ACTIVE patterns (not cumulative) |
| maxMatchersPerPattern | 10,000 | Max matchers per pattern |

**See [CONFIGURATION.md](CONFIGURATION.md) for detailed tuning guide.**

---

## Performance

**Benchmarks on modern hardware:**

| Operation | Time | Notes |
|-----------|------|-------|
| Pattern compilation (cache miss) | ~100-500 μs | One-time cost |
| Pattern compilation (cache hit) | ~1-5 μs | From cache |
| Simple match | ~5-50 μs | Linear in input size |
| Complex pattern match | ~50-500 μs | Still linear |
| 1MB log file scan | < 500 ms | Tested in test suite |

**vs Java Regex on ReDoS patterns:**

| Pattern | Input | Java Regex | RE2 |
|---------|-------|-----------|-----|
| `(a+)+b` | `"a" × 30 + "x"` | **Hangs** (seconds) | < 1 ms |
| `(a*)*b` | `"a" × 30 + "x"` | **Hangs** | < 1 ms |

**RE2 is always linear time - no surprises.**

---

## Native Libraries

Pre-compiled native libraries embedded in JAR for:
- macOS x86_64 (Intel Macs)
- macOS aarch64 (Apple Silicon)
- Linux x86_64 (x86_64)
- Linux aarch64 (ARM64)

Libraries are:
- **Securely built** (git commit pinning, signature verification)
- **Self-contained** (statically linked, only system dependencies)
- **Auto-detected** (platform detection automatic)
- **Extracted on first use** (to temp directory)

**See [native/README.md](native/README.md) for build process.**

---

## Architecture

### Component Overview

```
┌─────────────────────────────────────────────┐
│            User Application                 │
└──────────────────┬──────────────────────────┘
                   │
                   ▼
         ┌──────────────────┐
         │   RE2 API        │
         │  (Entry Point)   │
         └────────┬─────────┘
                  │
      ┌───────────┴───────────┐
      ▼                       ▼
┌────────────┐         ┌─────────────┐
│  Pattern   │◄────────│ PatternCache│
│ (Thread-   │         │ (LRU + Idle)│
│  Safe)     │         └──────┬──────┘
└─────┬──────┘                │
      │                 ┌─────▼──────────┐
      │                 │ IdleEviction   │
      │                 │ BackgroundTask │
      ▼                 └────────────────┘
┌────────────┐
│  Matcher   │
│ (Per-Thread│
│  Instance) │
└─────┬──────┘
      │
      ▼
┌────────────────┐
│  RE2 Native    │
│  (JNA → C++    │
│   → RE2)       │
└────────────────┘
```

**Key Flows:**
1. **Compilation:** Pattern.compile() → Cache check → Compile if miss → Cache result
2. **Eviction:** LRU (when full) + Idle (background, every 60s) + Deferred cleanup (every 5s)
3. **Matching:** Matcher → Pattern's native pointer → JNA call → RE2 C++ → Result

**See [ARCHITECTURE.md](ARCHITECTURE.md) for detailed design.**

---

## Resource Management

### Automatic (Default - Recommended)

```java
// Patterns from compile() are cached - don't close them
Pattern p = RE2.compile("test");
// ... use pattern ...
// No close() needed - cache manages lifecycle
```

### Manual (Testing Only)

```java
// For tests requiring real resource management
Pattern p = Pattern.compileWithoutCache("test");
try {
    // ... use pattern ...
} finally {
    p.close();  // Must close uncached patterns
}
```

### Cleanup

**Automatic cleanup happens via:**
- **LRU eviction:** When cache exceeds 50K patterns
- **Idle eviction:** Patterns unused for 5+ minutes
- **Deferred cleanup:** Evicted patterns freed every 5 seconds
- **Shutdown hook:** Clean shutdown when JVM exits

**No manual cleanup required in production code.**

---

## Logging

All log messages prefixed with `RE2:` for easy filtering.

**Log Levels:**
- **INFO:** Cache initialization, library loading, eviction summaries
- **DEBUG:** Cache hits/misses, pattern compilation, resource tracking
- **WARN:** Patterns evicted while in use (deferred), config warnings
- **ERROR:** Compilation failures, native library errors

**Example:**
```
INFO  RE2: Pattern cache initialized - maxSize: 50000, idleTimeout: 300s, scanInterval: 60s, deferredCleanup: every 5s
DEBUG RE2: Cache miss - compiling pattern: \d+ (case=true)
DEBUG RE2: Cache hit - pattern: \d+ (case=true)
```

---

## Compatibility

**Tested With:**
- Java 17, 18, 19, 20, 21
- [JNA](https://github.com/java-native-access/jna) 5.13.0+
- [SLF4J](https://www.slf4j.org/) 2.0+

**Platforms:**
- macOS 10.15+ (Intel and Apple Silicon)
- Linux (x86_64 and aarch64)
- **Windows:** Not supported

---

## Building

```bash
# Build JAR (includes all native libraries)
mvn clean package

# Run tests (163 tests)
mvn test

# Build native libraries (maintainers only)
# See native/README.md
```

**For developers:** Native libraries are pre-built and committed. You only compile Java code.

---

## Documentation

- **[CONFIGURATION.md](CONFIGURATION.md)** - Complete configuration guide
- **[ARCHITECTURE.md](ARCHITECTURE.md)** - Design and internals
- **[DESIGN_DECISIONS.md](DESIGN_DECISIONS.md)** - Why native binding (not pure Java)
- **[THREAD_SAFETY.md](THREAD_SAFETY.md)** - Thread safety guarantees
- **[native/README.md](native/README.md)** - Native library build system

---

## Status

**Current Version:** 1.0.0-SNAPSHOT
**Milestones:**
- ✅ v1.0.0-phase1: Core API with native integration
- ✅ v1.0.0-phase2: Full caching, configuration, thread safety verified

**Production Ready:** ✅ For production use in high-concurrency applications

---

## Security

**Native Library Security:**
- Built from **pinned git commits** (cryptographically immutable)
- **Signature verified** (Google engineer signatures checked)
- **No external dependencies** (self-contained, only system libs)
- **Automated builds** (GitHub Actions CI/CD)

**Source:**
- RE2: Commit `927f5d5...` (2025-11-05, signed by Russ Cox)
- Abseil: Commit `d38452e...` (20250814.1 LTS)

**See [native/README.md](native/README.md) for security details.**

---

## License

libre2-java is licensed under the **Apache License 2.0**.

See [LICENSE](LICENSE) file for details.

### Third-Party Licenses

This library bundles the **RE2 regular expression library**:
- **Project:** [google/re2](https://github.com/google/re2)
- **License:** BSD-3-Clause
- **Copyright:** The RE2 Authors

The BSD-3-Clause license is fully compatible with Apache License 2.0.

**Complete License Texts:**
- Apache License 2.0: See [LICENSE](LICENSE)
- RE2 BSD-3-Clause: See [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)

### License Compatibility

libre2-java (Apache 2.0) can be used in:
- Apache 2.0 licensed projects
- Proprietary projects
- Commercial projects
- Any project compatible with Apache 2.0

### Attribution

When distributing libre2-java:
1. Include Apache License 2.0 (LICENSE file)
2. Include RE2 BSD-3-Clause license (THIRD_PARTY_LICENSES.md)
3. Include NOTICE file with attribution

---

## Support

**Issues:** [GitHub Issues](https://github.com/axonops/libre2-java/issues)

---

## Acknowledgments

- **Google RE2 Team:** For the excellent RE2 library
- **Russ Cox:** RE2 maintainer
- **Abseil Team:** For Abseil C++ libraries

---

## See Also

- [Google RE2 Project](https://github.com/google/re2)
- [JNA (Java Native Access)](https://github.com/java-native-access/jna)
- [SLF4J](https://www.slf4j.org/)
- [RE2 Syntax Reference](https://github.com/google/re2/wiki/Syntax)
