# Logging Guide for libre2-java

**Date:** 2025-11-20
**Applies to:** libre2-core and libre2-cassandra

---

## Overview

libre2-java uses **SLF4J (Simple Logging Facade for Java)** for all logging. This is the industry-standard approach for Java libraries, allowing you to use whatever logging implementation your application prefers.

---

## How SLF4J Works

### The Abstraction Layer

```
┌─────────────────────────┐
│   libre2-java library   │
│   (uses SLF4J API)      │
└───────────┬─────────────┘
            │ slf4j-api (interface only)
            ↓
┌─────────────────────────┐
│  Your Application       │
│  (provides impl)        │
├─────────────────────────┤
│  Choose ONE:            │
│  - Logback              │
│  - Log4j2               │
│  - java.util.logging    │
│  - slf4j-nop (disabled) │
│  - slf4j-simple         │
└─────────────────────────┘
```

**Key Points:**
- libre2-java only depends on `slf4j-api` (the interface)
- YOU provide the logging implementation
- Library doesn't force any specific logging framework on you
- If you don't provide an implementation, you'll see a warning (but it still works)

---

## For Different Users

### 1. Cassandra Users (You Already Have Logback)

**Good news:** Cassandra uses Logback, so libre2-java logs automatically work.

**Where logs appear:** `/var/log/cassandra/system.log` (or wherever your Cassandra logs go)

**All logs prefixed with "RE2:"** for easy filtering:
```bash
# View all RE2 activity
grep "RE2:" /var/log/cassandra/system.log

# Watch RE2 logs in real-time
tail -f /var/log/cassandra/system.log | grep "RE2:"

# See cache-related logs
grep "RE2: Cache" /var/log/cassandra/system.log
```

**Configure log level** in `conf/logback.xml`:
```xml
<configuration>
    <!-- Show INFO and above (recommended for production) -->
    <logger name="com.axonops.libre2" level="INFO"/>

    <!-- For debugging RE2 issues -->
    <logger name="com.axonops.libre2" level="DEBUG"/>

    <!-- High-frequency details (cache hits, pattern creation) -->
    <logger name="com.axonops.libre2" level="TRACE"/>

    <!-- Reduce to only errors and warnings -->
    <logger name="com.axonops.libre2" level="WARN"/>
</configuration>
```

---

### 2. Spring Boot Users (You Already Have Logback)

**Good news:** Spring Boot uses Logback by default, so logs work automatically.

**Configure in `application.properties`:**
```properties
# Show INFO and above (default)
logging.level.com.axonops.libre2=INFO

# For debugging
logging.level.com.axonops.libre2=DEBUG

# Reduce verbosity
logging.level.com.axonops.libre2=WARN
```

**Or in `application.yml`:**
```yaml
logging:
  level:
    com.axonops.libre2: INFO
```

---

### 3. Standalone Applications (Choose Your Implementation)

#### Option A: Logback (Recommended)

**Add dependency:**
```xml
<dependency>
    <groupId>ch.qos.logback</groupId>
    <artifactId>logback-classic</artifactId>
    <version>1.4.11</version>
</dependency>
```

**Create `src/main/resources/logback.xml`:**
```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- Configure libre2 logging -->
    <logger name="com.axonops.libre2" level="INFO"/>

    <root level="WARN">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

#### Option B: Log4j2

**Add dependency:**
```xml
<dependency>
    <groupId>org.apache.logging.log4j</groupId>
    <artifactId>log4j-slf4j2-impl</artifactId>
    <version>2.20.0</version>
</dependency>
<dependency>
    <groupId>org.apache.logging.log4j</groupId>
    <artifactId>log4j-core</artifactId>
    <version>2.20.0</version>
</dependency>
```

**Create `src/main/resources/log4j2.xml`:**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN">
    <Appenders>
        <Console name="Console" target="SYSTEM_OUT">
            <PatternLayout pattern="%d{HH:mm:ss.SSS} %-5level %logger{36} - %msg%n"/>
        </Console>
    </Appenders>
    <Loggers>
        <Logger name="com.axonops.libre2" level="info"/>
        <Root level="warn">
            <AppenderRef ref="Console"/>
        </Root>
    </Loggers>
</Configuration>
```

#### Option C: Simple Console (Quick Testing)

**Add dependency:**
```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>2.0.9</version>
</dependency>
```

Logs go to `System.err` automatically. No configuration needed.

#### Option D: No Logging (Disable Completely)

**Add dependency:**
```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-nop</artifactId>
    <version>2.0.9</version>
</dependency>
```

All libre2 logs disappear. **Zero performance overhead.**

---

## Log Levels and What They Mean

### TRACE (Very Verbose - Development Only)
- Every cache hit/miss
- Every pattern created/freed
- Every matcher created/closed
- Native memory updates
- High-frequency operations

**Use when:** Debugging cache behavior or resource leaks

### DEBUG (Verbose - Troubleshooting)
- Pattern compilation success with details
- Cache eviction triggered (LRU/idle)
- Invalid patterns detected and recompiled
- Native memory tracking summaries

**Use when:** Investigating performance issues or unexpected behavior

### INFO (Normal - Production Default)
- Library initialization
- Cache initialization with configuration
- Background thread lifecycle (start/stop)
- Configuration applied
- Significant events

**Use when:** Normal production operation

### WARN (Problems - Recoverable)
- User attempted to close cached pattern (mistake, but harmless)
- Resource limit approaching
- Invalid cached pattern detected (auto-fixed)
- Configuration issues

**Use when:** Want to see potential problems but not errors

### ERROR (Failures - Requires Attention)
- Pattern compilation failed
- Native library errors
- Resource leaks detected
- Fatal initialization errors

**Use when:** Only want to see actual failures

---

## Log Message Format

All libre2 logs follow this format:
```
RE2: <context> - <message> [details]
```

### Examples

```
INFO  RE2: Library loaded - version: 1.0.0, platform: darwin-aarch64
DEBUG RE2: Pattern compiled - hash: 7a3f2b1c, length: 25, caseSensitive: true, fromCache: false, nativeBytes: 1024
DEBUG RE2: Cache hit - hash: 7a3f2b1c, hitRate: 87.3%
TRACE RE2: Matcher created - pattern: 7a3f2b1c, refCount: 3
WARN  RE2: Invalid cached pattern detected - hash: 9c4e1f2a, recompiling
ERROR RE2: Pattern compilation failed - hash: 5d6a8b9c, error: invalid character class
```

### Pattern Hashing (Privacy)

**Why hashes instead of actual patterns?**
- Patterns may contain sensitive data (PII, security rules, etc.)
- Logs don't get cluttered with 200-character regex strings
- Hash is consistent across logs (easy to grep/trace)

**Format:** `Integer.toHexString(pattern.hashCode())`
- Example: `7a3f2b1c` represents pattern ".*ERROR.*"
- Same pattern always gets same hash
- Different patterns get different hashes

---

## Common Logging Scenarios

### Scenario 1: Debug Cache Performance

**Goal:** See cache hits/misses and eviction behavior

**Configuration:**
```xml
<logger name="com.axonops.libre2.cache" level="DEBUG"/>
```

**What you'll see:**
```
DEBUG RE2: Cache hit - hash: 7a3f2b1c, hitRate: 87.3%
DEBUG RE2: Cache miss - hash: 9d2f1e4a, compiling new pattern
DEBUG RE2: LRU eviction triggered - size: 50001/50000, evicting oldest
DEBUG RE2: Evicted pattern - hash: 1c4e8b2f, age: 320s, freed: 2048 bytes
```

### Scenario 2: Monitor Native Memory Usage

**Goal:** Track off-heap memory consumption

**Configuration:**
```xml
<logger name="com.axonops.libre2.cache.PatternCache" level="DEBUG"/>
```

**What you'll see:**
```
DEBUG RE2: Native memory updated - current: 42.5 MB, peak: 45.1 MB
DEBUG RE2: Pattern freed - hash: 5e2a9f3c, memory: 1024 bytes
```

### Scenario 3: Production Monitoring (Minimal Logs)

**Goal:** Only see important events and errors

**Configuration:**
```xml
<logger name="com.axonops.libre2" level="INFO"/>
```

**What you'll see:**
```
INFO  RE2: Library loaded - version: 1.0.0, platform: linux-x86_64
INFO  RE2: Cache initialized - maxSize: 50000, idleTimeout: 300s
WARN  RE2: Invalid cached pattern detected - hash: 7f3e2b1a, recompiling
ERROR RE2: Pattern compilation failed - hash: 2d4f8e1c, error: missing closing ]
```

### Scenario 4: Performance Testing (No Logs)

**Goal:** Zero logging overhead for benchmarks

**Configuration:**
```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-nop</artifactId>
    <version>2.0.9</version>
    <scope>test</scope>
</dependency>
```

No logs produced, zero overhead.

---

## Troubleshooting Logging Issues

### "SLF4J: Failed to load class org.slf4j.impl.StaticLoggerBinder"

**Problem:** No SLF4J implementation on classpath

**Solution:** Add one of:
- `logback-classic` (recommended)
- `slf4j-simple` (quick testing)
- `slf4j-nop` (disable logging)

### "No logs appearing even with DEBUG level"

**Checklist:**
1. ✓ SLF4J implementation on classpath?
2. ✓ Logger name configured: `com.axonops.libre2`?
3. ✓ Root logger not set too high (e.g., ERROR)?
4. ✓ Appender configured to output somewhere?

### "Too many logs in production"

**Solution:** Reduce to INFO or WARN
```xml
<logger name="com.axonops.libre2" level="INFO"/>
```

Or disable specific noisy packages:
```xml
<logger name="com.axonops.libre2.cache" level="WARN"/>
<logger name="com.axonops.libre2.api" level="INFO"/>
```

---

## For Library Developers

### Adding New Log Statements

**Guidelines:**
1. **Always prefix with "RE2:"**
   ```java
   logger.info("RE2: Pattern compiled - hash: {}, length: {}", hash, length);
   ```

2. **Use pattern hashing for privacy:**
   ```java
   private static String hashPattern(String pattern) {
       return Integer.toHexString(pattern.hashCode());
   }
   logger.debug("RE2: Cache hit - hash: {}", hashPattern(pattern));
   ```

3. **Choose appropriate log level:**
   - TRACE: High-frequency (every cache hit, every matcher created)
   - DEBUG: Moderate-frequency (compilation, eviction, validation)
   - INFO: Low-frequency (initialization, lifecycle)
   - WARN: Recoverable issues
   - ERROR: Actual failures

4. **Structured messages:**
   ```java
   // Good: structured, grep-able
   logger.debug("RE2: Cache hit - hash: {}, hitRate: {}%", hash, hitRate);

   // Bad: unstructured, hard to parse
   logger.debug("Cache hit for " + pattern + " (hit rate is " + hitRate + ")");
   ```

---

## Summary

✅ **SLF4J is the correct choice** - industry standard for Java libraries
✅ **Zero forced dependencies** - you choose the implementation
✅ **Works everywhere** - Cassandra, Spring, standalone, any Java app
✅ **Easy to configure** - standard XML/properties/YAML config
✅ **Easy to filter** - all messages prefixed with "RE2:"
✅ **Privacy-conscious** - pattern hashing prevents sensitive data in logs
✅ **Flexible** - from TRACE (verbose) to disabled (zero overhead)

**Bottom line:** Just add the SLF4J implementation you prefer, configure the log level, and it works.
