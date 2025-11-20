# Logging Configuration for libre2-cassandra

**Module:** libre2-cassandra
**Target:** Apache Cassandra 5.x integration
**Date:** 2025-11-20

---

## Overview

When you use `libre2-cassandra` in your Cassandra deployment, all RE2 logs automatically appear in Cassandra's standard logging system (Logback). This document explains how to configure and monitor these logs.

---

## Log Location

**Default:** `/var/log/cassandra/system.log` (or wherever your Cassandra `system.log` goes)

All RE2 logs are prefixed with **"RE2:"** for easy identification and filtering.

---

## Quick Configuration

### Cassandra 5.x Configuration

**Edit:** `$CASSANDRA_HOME/conf/logback.xml`

**Add this section** (before the `<root>` element):

```xml
<!-- RE2 Regex Library Logging -->
<logger name="com.axonops.libre2" level="INFO"/>
```

**Reload configuration** (no restart needed if using JMX):
```bash
# Via nodetool (if JMX logging management is enabled)
nodetool setlogginglevel com.axonops.libre2 INFO
```

---

## Log Levels

### INFO (Recommended for Production)
```xml
<logger name="com.axonops.libre2" level="INFO"/>
```

**What you see:**
- Library initialization
- Cache configuration
- Background thread lifecycle
- Significant events

**Example output:**
```
INFO  [main] 2025-11-20 10:15:32,123 RE2: Library loaded - version: 1.0.0, platform: linux-x86_64
INFO  [main] 2025-11-20 10:15:32,145 RE2: Cache initialized - maxSize: 50000, idleTimeout: 300s, evictionScan: 60s
```

---

### DEBUG (Troubleshooting)
```xml
<logger name="com.axonops.libre2" level="DEBUG"/>
```

**What you see:**
- Pattern compilation details
- Cache eviction triggers
- Native memory tracking
- Invalid pattern detection/recompilation

**Example output:**
```
DEBUG [PerDiskMemtableFlushWriter:1] 2025-11-20 10:16:05,234 RE2: Pattern compiled - hash: 7a3f2b1c, length: 25, caseSensitive: true, fromCache: false, nativeBytes: 1024
DEBUG [RE2-LRU-Eviction] 2025-11-20 10:20:15,445 RE2: LRU eviction triggered - size: 50001/50000
DEBUG [RE2-Idle-Eviction] 2025-11-20 10:25:00,001 RE2: Idle eviction - removed 15 patterns, freed: 32768 bytes
```

---

### TRACE (Very Verbose - Development Only)
```xml
<logger name="com.axonops.libre2" level="TRACE"/>
```

**What you see:**
- Every cache hit/miss
- Every pattern/matcher created/closed
- Native memory updates
- High-frequency operations

**⚠️ Warning:** TRACE produces A LOT of logs. Use only for debugging specific issues, not in production.

**Example output:**
```
TRACE [ReadStage-1] 2025-11-20 10:16:05,235 RE2: Cache hit - hash: 7a3f2b1c, hitRate: 87.3%
TRACE [ReadStage-2] 2025-11-20 10:16:05,236 RE2: Matcher created - pattern: 7a3f2b1c, refCount: 3
TRACE [ReadStage-1] 2025-11-20 10:16:05,237 RE2: Matcher freed - pattern: 7a3f2b1c, refCount: 2
TRACE [RE2-Deferred-Cleanup] 2025-11-20 10:16:10,001 RE2: Deferred cleanup - scanned: 5, freed: 0
```

---

### WARN (Problems Only)
```xml
<logger name="com.axonops.libre2" level="WARN"/>
```

**What you see:**
- User errors (e.g., trying to close cached patterns)
- Resource limits approaching
- Recoverable issues

**Example output:**
```
WARN  [ReadStage-3] 2025-11-20 10:18:22,567 RE2: User attempted to close cached pattern - hash: 5e2a9f3c (no-op, cache manages lifecycle)
WARN  [RE2-Cache] 2025-11-20 10:20:45,789 RE2: Invalid cached pattern detected - hash: 7f3e2b1a, recompiling
```

---

### ERROR (Failures Only)
```xml
<logger name="com.axonops.libre2" level="ERROR"/>
```

**What you see:**
- Pattern compilation failures
- Native library errors
- Resource leaks detected

**Example output:**
```
ERROR [ReadStage-4] 2025-11-20 10:22:15,901 RE2: Pattern compilation failed - hash: 2d4f8e1c, error: missing closing ]
ERROR [main] 2025-11-20 10:15:31,999 RE2: Failed to load native library - platform: linux-x86_64
```

---

## Granular Configuration (Fine-Tuning)

You can configure logging for specific RE2 components:

```xml
<!-- Overall default: INFO -->
<logger name="com.axonops.libre2" level="INFO"/>

<!-- Reduce cache logging in production -->
<logger name="com.axonops.libre2.cache" level="WARN"/>

<!-- Debug pattern compilation issues -->
<logger name="com.axonops.libre2.api.Pattern" level="DEBUG"/>

<!-- Very verbose cache activity (for debugging cache behavior) -->
<logger name="com.axonops.libre2.cache.PatternCache" level="TRACE"/>

<!-- Debug native library issues -->
<logger name="com.axonops.libre2.jni" level="DEBUG"/>
```

---

## Filtering and Monitoring

### Filter RE2 Logs

All RE2 logs are prefixed with "RE2:", making them easy to filter:

```bash
# View all RE2 activity
grep "RE2:" /var/log/cassandra/system.log

# Watch RE2 logs in real-time
tail -f /var/log/cassandra/system.log | grep "RE2:"

# See only cache-related logs
grep "RE2: Cache" /var/log/cassandra/system.log

# See pattern compilation logs
grep "RE2: Pattern compiled" /var/log/cassandra/system.log

# See errors only
grep "ERROR.*RE2:" /var/log/cassandra/system.log

# Count cache hits today
grep "$(date +%Y-%m-%d)" /var/log/cassandra/system.log | grep "RE2: Cache hit" | wc -l
```

### Monitor Specific Events

```bash
# Pattern compilation failures
grep "RE2: Pattern compilation failed" /var/log/cassandra/system.log

# Cache evictions (LRU)
grep "RE2: LRU eviction triggered" /var/log/cassandra/system.log

# Native memory usage
grep "RE2: Native memory" /var/log/cassandra/system.log

# Invalid patterns detected
grep "RE2: Invalid cached pattern" /var/log/cassandra/system.log
```

---

## Pattern Hashing (Privacy)

**Why you see hashes instead of actual regex patterns:**
- Regex patterns may contain sensitive data (PII, security rules, etc.)
- Prevents cluttering logs with 200-character regex strings
- Hash is consistent across logs (easy to trace)

**Format:** `hash: 7a3f2b1c` (8-character hex string)
- Same pattern always gets same hash
- Different patterns get different hashes

**If you need to correlate:**
```java
// In your code, log the hash for debugging:
String pattern = ".*ERROR.*";
logger.info("Using pattern with hash: {}", Integer.toHexString(pattern.hashCode()));
```

---

## Common Scenarios

### Scenario 1: Debugging Query Performance

**Problem:** Queries using regex are slow

**Configuration:**
```xml
<logger name="com.axonops.libre2.cache" level="DEBUG"/>
<logger name="com.axonops.libre2.api" level="DEBUG"/>
```

**What to look for:**
- Cache miss rate (are patterns being recompiled?)
- Pattern compilation time (are patterns complex?)
- Cache eviction frequency (is cache too small?)

```bash
# Check cache hit rate
grep "RE2: Cache hit" /var/log/cassandra/system.log | wc -l
grep "RE2: Cache miss" /var/log/cassandra/system.log | wc -l

# Check eviction frequency
grep "RE2: LRU eviction triggered" /var/log/cassandra/system.log

# Check compilation times
grep "RE2: Pattern compiled" /var/log/cassandra/system.log | grep "nativeBytes"
```

---

### Scenario 2: Monitoring Memory Usage

**Goal:** Track RE2's off-heap memory consumption

**Configuration:**
```xml
<logger name="com.axonops.libre2.cache.PatternCache" level="DEBUG"/>
```

**What to look for:**
```bash
# Check native memory usage
grep "RE2: Native memory" /var/log/cassandra/system.log | tail -1

# Example output:
# DEBUG RE2: Native memory updated - current: 42.5 MB, peak: 45.1 MB
```

---

### Scenario 3: Production Monitoring (Minimal Logs)

**Goal:** Only see important events and errors

**Configuration:**
```xml
<logger name="com.axonops.libre2" level="INFO"/>
```

**Log volume:** Low (only initialization, configuration changes, errors)

---

### Scenario 4: Troubleshooting Pattern Compilation Failures

**Problem:** Queries failing with pattern compilation errors

**Configuration:**
```xml
<logger name="com.axonops.libre2.api.Pattern" level="DEBUG"/>
```

**What to look for:**
```bash
# Find compilation failures
grep "RE2: Pattern compilation failed" /var/log/cassandra/system.log

# Example output:
# ERROR RE2: Pattern compilation failed - hash: 2d4f8e1c, error: missing closing ]
```

**Next step:** Match the hash with the query pattern in your application logs.

---

## Runtime Log Level Changes (No Restart Needed)

### Via nodetool (if supported)
```bash
# Set to DEBUG
nodetool setlogginglevel com.axonops.libre2 DEBUG

# Set to INFO
nodetool setlogginglevel com.axonops.libre2 INFO

# Set to WARN
nodetool setlogginglevel com.axonops.libre2 WARN
```

### Via JMX (JConsole, VisualVM)
1. Connect to Cassandra JMX port
2. Navigate to: `ch.qos.logback.classic:Name=default,Type=ch.qos.logback.classic.jmx.JMXConfigurator`
3. Find operation: `setLoggerLevel`
4. Parameters: `loggerName=com.axonops.libre2`, `levelString=DEBUG`

---

## Integration with Cassandra Monitoring

### Prometheus + Grafana

If you're exporting Cassandra logs to Prometheus/Grafana:

**Useful queries:**
```promql
# RE2 error rate
rate(cassandra_log_errors_total{logger="com.axonops.libre2"}[5m])

# RE2 pattern compilation failures
cassandra_log_total{logger="com.axonops.libre2", level="ERROR", message=~".*Pattern compilation failed.*"}

# RE2 cache evictions
cassandra_log_total{logger="com.axonops.libre2", level="DEBUG", message=~".*LRU eviction triggered.*"}
```

### ELK Stack (Elasticsearch, Logstash, Kibana)

**Kibana query:**
```
message: "RE2:"
```

**Filter by level:**
```
message: "RE2:" AND level: "ERROR"
```

---

## Performance Impact

**Logging overhead by level:**

| Level | Overhead | Recommendation |
|-------|----------|----------------|
| ERROR | <0.01% | Always safe |
| WARN | <0.01% | Always safe |
| INFO | <0.05% | Safe for production |
| DEBUG | 0.1-0.5% | Use for troubleshooting |
| TRACE | 1-5% | Development only |

**Note:** Overhead is negligible at INFO and below. TRACE can impact performance due to high-frequency logging.

---

## Troubleshooting

### "No RE2 logs appearing"

**Checklist:**
1. ✓ `libre2-cassandra` module on classpath?
2. ✓ Logger configured in `logback.xml`?
3. ✓ Log level not set too high (e.g., ERROR)?
4. ✓ Appender outputting to expected file?

**Verify configuration:**
```bash
grep "com.axonops.libre2" $CASSANDRA_HOME/conf/logback.xml
```

### "Too many logs in production"

**Solution:** Reduce to INFO or WARN
```xml
<logger name="com.axonops.libre2" level="INFO"/>
```

Or disable noisy components:
```xml
<logger name="com.axonops.libre2.cache" level="WARN"/>
```

---

## Summary

✅ **All logs prefixed with "RE2:"** - easy to filter and grep
✅ **Standard Cassandra logging** - appears in system.log automatically
✅ **Configurable levels** - from TRACE (verbose) to ERROR (failures only)
✅ **Runtime adjustable** - change levels without restart via nodetool/JMX
✅ **Privacy-conscious** - pattern hashing prevents sensitive data in logs
✅ **Low overhead** - INFO and below have negligible performance impact

**Recommended for production:** `level="INFO"`
