# Quick Start Guide - libre2-java

Get started with high-performance, ReDoS-safe regex matching in 5 minutes.

---

## Installation

### Maven

```xml
<dependency>
    <groupId>com.axonops</groupId>
    <artifactId>libre2-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle

```gradle
implementation 'com.axonops:libre2-core:1.0.0'
```

---

## Basic Pattern Matching

### Using RE2 Static Methods (Simplest)

```java
import com.axonops.libre2.api.RE2;

// Quick one-off matching
boolean matches = RE2.matches("\\d{3}-\\d{4}", "123-4567");  // true

// Find in text
boolean found = RE2.find("[a-z]+@[a-z]+\\.[a-z]+", "Contact: user@example.com");  // true
```

### Using Pattern (For Reuse)

```java
import com.axonops.libre2.api.Pattern;
import com.axonops.libre2.api.Matcher;

// Compile once (automatically cached)
Pattern pattern = Pattern.compile("\\d{3}-\\d{4}");

// Reuse many times
try (Matcher m = pattern.matcher("123-4567")) {
    boolean matches = m.matches();  // true
}

try (Matcher m = pattern.matcher("Call 555-1234")) {
    boolean found = m.find();  // true
}
```

### Case-Insensitive Matching

```java
Pattern pattern = Pattern.compile("hello", false);  // caseSensitive = false

boolean matches = pattern.matches("HELLO");  // true
boolean matches2 = pattern.matches("HeLLo");  // true
```

---

## Capture Groups (Extract Data)

### Basic Group Extraction

```java
import com.axonops.libre2.api.MatchResult;

Pattern emailPattern = Pattern.compile("([a-z]+)@([a-z]+)\\.([a-z]+)");

try (MatchResult result = emailPattern.match("user@example.com")) {
    if (result.matched()) {
        String full = result.group(0);    // "user@example.com"
        String user = result.group(1);    // "user"
        String domain = result.group(2);  // "example"
        String tld = result.group(3);     // "com"
    }
}
```

### Named Groups

```java
Pattern datePattern = Pattern.compile("(?P<year>\\d{4})-(?P<month>\\d{2})-(?P<day>\\d{2})");

try (MatchResult result = datePattern.match("2025-11-25")) {
    if (result.matched()) {
        String year = result.group("year");    // "2025"
        String month = result.group("month");  // "11"
        String day = result.group("day");      // "25"
    }
}
```

### Find All Matches

```java
Pattern numberPattern = Pattern.compile("(\\d+)");
List<MatchResult> matches = numberPattern.findAll("Item 1 costs $99");

try {
    for (MatchResult match : matches) {
        System.out.println(match.group(1));  // "1", "99"
    }
} finally {
    // Important: Close all MatchResults
    matches.forEach(MatchResult::close);
}
```

---

## Bulk Operations (High Throughput)

### Bulk Matching (10-20x Faster Than Iteration)

```java
Pattern phonePattern = Pattern.compile("\\d{3}-\\d{4}");

String[] phones = {
    "123-4567",  // valid
    "invalid",   // invalid
    "999-8888"   // valid
};

boolean[] results = phonePattern.matchAll(phones);
// results = [true, false, true]
```

### Filtering Collections

```java
Pattern validPattern = Pattern.compile("[A-Z]{3}");

List<String> codes = List.of("ABC", "invalid", "XYZ", "123");
List<String> valid = validPattern.filter(codes);
// valid = ["ABC", "XYZ"]

List<String> invalid = validPattern.filterNot(codes);
// invalid = ["invalid", "123"]
```

### Bulk Capture Groups

```java
Pattern emailPattern = Pattern.compile("([a-z]+)@([a-z]+\\.[a-z]+)");

String[] emails = {"user@example.com", "admin@test.org", "invalid"};
MatchResult[] results = emailPattern.matchAllWithGroups(emails);

try {
    for (MatchResult result : results) {
        if (result.matched()) {
            String user = result.group(1);
            String domain = result.group(2);
        }
    }
} finally {
    for (MatchResult r : results) {
        r.close();
    }
}
```

---

## Replace Operations

### Basic Replace

```java
Pattern numberPattern = Pattern.compile("\\d+");

// Replace first match
String result1 = numberPattern.replaceFirst("Item 123 costs $456", "XXX");
// "Item XXX costs $456"

// Replace all matches
String result2 = numberPattern.replaceAll("Item 123 costs $456", "XXX");
// "Item XXX costs $XXX"
```

### Backreferences

```java
Pattern phonePattern = Pattern.compile("(\\d{3})-(\\d{4})");

String formatted = phonePattern.replaceAll(
    "Call 555-1234 or 999-8888",
    "(\\1) \\2"
);
// "Call (555) 1234 or (999) 8888"
```

### Bulk Replace (Data Cleaning)

```java
Pattern ssnPattern = Pattern.compile("\\d{3}-\\d{2}-\\d{4}");

String[] logs = {
    "User 123-45-6789 logged in",
    "No PII here",
    "SSN: 987-65-4321"
};

String[] redacted = ssnPattern.replaceAll(logs, "[REDACTED]");
// ["User [REDACTED] logged in", "No PII here", "SSN: [REDACTED]"]
```

---

## Zero-Copy (High Performance)

### DirectByteBuffer (For Off-Heap Data)

```java
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

// Allocate off-heap memory
ByteBuffer buffer = ByteBuffer.allocateDirect(1024);
buffer.put("test123".getBytes(StandardCharsets.UTF_8));
buffer.flip();

Pattern pattern = Pattern.compile("test\\d+");

// Zero-copy matching (no UTF-8 conversion)
boolean matches = pattern.matches(buffer);  // true (46-99% faster for large buffers)
```

### With Capture Groups

```java
ByteBuffer logBuffer = ByteBuffer.allocateDirect(4096);
logBuffer.put("[2025-11-25] ERROR: Failed".getBytes(StandardCharsets.UTF_8));
logBuffer.flip();

Pattern logPattern = Pattern.compile("\\[(\\d{4}-\\d{2}-\\d{2})\\] (\\w+): (.+)");

try (MatchResult result = logPattern.matchWithGroups(logBuffer)) {
    if (result.matched()) {
        String date = result.group(1);   // "2025-11-25"
        String level = result.group(2);  // "ERROR"
        String msg = result.group(3);    // "Failed"
    }
}
```

### Bulk ByteBuffer Processing

```java
ByteBuffer[] buffers = new ByteBuffer[1000];  // From network/file/etc
// ... populate buffers ...

Pattern pattern = Pattern.compile("ERROR.*");
boolean[] hasErrors = pattern.matchAll(buffers);
// Automatically routes: direct buffers → zero-copy, heap buffers → String conversion
```

---

## Utilities

### Escape Special Characters

```java
// Escape regex special chars for literal matching
String literal = "price: $9.99";
String escaped = Pattern.quoteMeta(literal);  // "price: \\$9\\.99"

Pattern p = Pattern.compile(escaped);
boolean matches = p.matches("price: $9.99");  // true
```

### Pattern Analysis

```java
Pattern complexPattern = Pattern.compile("(a|b|c)+\\d{1,5}[x-z]*");

// Get compiled pattern size (off-heap memory)
long bytes = complexPattern.getNativeMemoryBytes();  // e.g., 2048

// Analyze DFA complexity
int[] fanout = complexPattern.getProgramFanout();
// fanout[i] = number of different byte transitions at position i
```

---

## Real-World Examples

### Log Parsing

```java
Pattern logPattern = Pattern.compile("\\[(\\d+)\\] (\\w+): (.+)");

List<MatchResult> matches = logPattern.findAll(
    "[1234567890] ERROR: Failed to connect\n" +
    "[1234567891] INFO: Connected successfully"
);

try {
    for (MatchResult match : matches) {
        long timestamp = Long.parseLong(match.group(1));
        String level = match.group(2);
        String message = match.group(3);
        // Process log entry...
    }
} finally {
    matches.forEach(MatchResult::close);
}
```

### Data Validation (Bulk)

```java
Pattern uuidPattern = Pattern.compile(
    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
    false
);

// Validate 10,000 UUIDs in ~2ms
String[] ids = loadIds();  // 10,000 UUIDs
boolean[] valid = uuidPattern.matchAll(ids);

int validCount = 0;
for (boolean v : valid) {
    if (v) validCount++;
}
```

### PII Redaction (Bulk Replace)

```java
Pattern ssnPattern = Pattern.compile("\\d{3}-\\d{2}-\\d{4}");
Pattern ccPattern = Pattern.compile("\\d{4}-\\d{4}-\\d{4}-\\d{4}");

String[] customerData = loadCustomerLogs();  // 100,000 log lines

// Redact SSNs
String[] step1 = ssnPattern.replaceAll(customerData, "[SSN-REDACTED]");

// Redact credit cards
String[] sanitized = ccPattern.replaceAll(step1, "[CC-REDACTED]");

// Process safely: 100,000 lines sanitized in ~20ms
```

---

## Configuration

### Production Configuration (Cassandra)

```java
import com.axonops.libre2.dropwizard.RE2MetricsConfig;

// Cassandra-optimized configuration
RE2Config config = RE2Config.builder()
    .maxCacheSize(50_000)               // 50K patterns (high throughput)
    .idleTimeoutSeconds(300)            // 5 min idle timeout
    .evictionScanIntervalSeconds(60)    // Scan every minute
    .maxSimultaneousCompiledPatterns(10_000)
    .metricsRegistry(RE2MetricsConfig.forCassandra(cassandraMetrics))
    .build();

Pattern.setGlobalCache(new PatternCache(config));
```

### Memory-Constrained Environment

```java
RE2Config config = RE2Config.builder()
    .maxCacheSize(1_000)                // Small cache
    .idleTimeoutSeconds(60)             // Aggressive cleanup (1 min)
    .evictionScanIntervalSeconds(15)    // Frequent scans
    .maxSimultaneousCompiledPatterns(100)
    .build();

Pattern.setGlobalCache(new PatternCache(config));
```

---

## Performance Characteristics

### Throughput (Apple Silicon M-series)

- **Simple patterns:** 10-20M matches/sec
- **Complex patterns:** 1-5M matches/sec
- **Bulk operations (10k strings):** ~2-3ms total (~0.3μs per match)
- **Capture groups:** ~10% overhead vs simple matching
- **Replace operations:** Comparable to matching

### Latency

- **Pattern compilation:** 50-200μs (cached afterward)
- **Cache lookup:** ~50ns (hit), ~100μs (miss + compile)
- **Simple match:** 50-100ns
- **Capture group extraction:** 100-500ns
- **Replace operation:** 200-1000ns

### Memory

- **Pattern size:** 1-10KB compiled (varies by complexity)
- **Cache overhead:** ~16 bytes per entry + pattern size
- **50K patterns:** ~50-500MB depending on complexity

---

## Best Practices

### 1. Always Use try-with-resources

```java
// ✅ GOOD
try (MatchResult result = pattern.match("input")) {
    String group = result.group(1);
}

// ❌ BAD - resource leak
MatchResult result = pattern.match("input");
String group = result.group(1);
// MatchResult never closed!
```

### 2. Prefer Bulk APIs for High Throughput

```java
// ✅ GOOD - Single JNI call, 10-20x faster
boolean[] results = pattern.matchAll(strings);

// ❌ SLOW - Many JNI calls
boolean[] results = new boolean[strings.length];
for (int i = 0; i < strings.length; i++) {
    results[i] = pattern.matches(strings[i]);
}
```

### 3. Use DirectByteBuffer for Off-Heap Data

```java
// ✅ GOOD - Zero-copy, no UTF-8 conversion
ByteBuffer direct = ByteBuffer.allocateDirect(size);
boolean matches = pattern.matches(direct);  // Fast!

// ❌ SLOWER - Heap buffer converted to String
ByteBuffer heap = ByteBuffer.allocate(size);
boolean matches = pattern.matches(heap);  // Still works, just slower
```

### 4. Close All MatchResults

```java
// ✅ GOOD
List<MatchResult> matches = pattern.findAll("text");
try {
    for (MatchResult m : matches) {
        process(m.group(1));
    }
} finally {
    matches.forEach(MatchResult::close);
}

// ✅ ALSO GOOD - try-with-resources per result
for (String input : inputs) {
    try (MatchResult result = pattern.match(input)) {
        if (result.matched()) {
            process(result.group(1));
        }
    }
}
```

### 5. Monitor Metrics

```java
// Check cache hit rate
CacheStatistics stats = Pattern.getCacheStatistics();
double hitRate = (double) stats.hits() / (stats.hits() + stats.misses());
System.out.println("Cache hit rate: " + (hitRate * 100) + "%");

// Should be >90% in steady state
```

---

## Common Patterns

### Email Validation

```java
Pattern emailPattern = RE2.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
boolean valid = emailPattern.matches("user@example.com");
```

### Extract Email Components

```java
Pattern emailPattern = RE2.compile("([a-z0-9._%+-]+)@([a-z0-9.-]+)\\.([a-z]{2,})");

try (MatchResult result = emailPattern.match("john.doe@example.co.uk")) {
    String username = result.group(1);  // "john.doe"
    String domain = result.group(2);    // "example.co"
    String tld = result.group(3);       // "uk"
}
```

### Phone Number Formatting

```java
Pattern phonePattern = RE2.compile("(\\d{3})-(\\d{4})");

String formatted = phonePattern.replaceAll(
    "Call 555-1234 or 999-8888",
    "(\\1) \\2"
);
// "Call (555) 1234 or (999) 8888"
```

### URL Extraction

```java
Pattern urlPattern = RE2.compile("https?://([a-z0-9.-]+)/([a-z0-9/_-]+)");

List<MatchResult> urls = urlPattern.findAll("Visit http://example.com/page1 and https://test.org/page2");

try {
    for (MatchResult url : urls) {
        String host = url.group(1);
        String path = url.group(2);
    }
} finally {
    urls.forEach(MatchResult::close);
}
```

### IPv4 Address Validation

```java
Pattern ipPattern = RE2.compile("\\b(?:[0-9]{1,3}\\.){3}[0-9]{1,3}\\b");

boolean isValid = ipPattern.find("Server IP: 192.168.1.1");  // true
```

---

## Advanced Features

### Map Filtering

```java
Pattern keyPattern = RE2.compile("user_.*");

Map<String, Integer> data = Map.of(
    "user_123", 1,
    "admin_456", 2,
    "user_789", 3
);

Map<String, Integer> userOnly = keyPattern.filterByKey(data);
// {"user_123": 1, "user_789": 3}
```

### In-Place Collection Filtering

```java
Pattern validPattern = RE2.compile("[A-Z]{3}");

List<String> codes = new ArrayList<>(List.of("ABC", "invalid", "XYZ"));
int removed = validPattern.removeMatches(codes);
// codes = ["invalid"], removed = 2
```

---

## Error Handling

### Invalid Regex

```java
import com.axonops.libre2.api.PatternCompilationException;

try {
    Pattern pattern = RE2.compile("[invalid(");
} catch (PatternCompilationException e) {
    System.err.println("Invalid regex: " + e.getMessage());
    // "Invalid regex: missing )"
}
```

### Resource Limits

```java
import com.axonops.libre2.api.ResourceException;

try {
    Pattern pattern = RE2.compile(".*");
} catch (ResourceException e) {
    // Too many patterns compiled simultaneously
    System.err.println("Resource limit: " + e.getMessage());
}
```

---

## Thread Safety

### Pattern Sharing (Thread-Safe)

```java
// ✅ SAFE - Pattern can be shared across threads
Pattern shared = RE2.compile("\\d+");

// Thread 1
new Thread(() -> {
    try (Matcher m = shared.matcher("123")) {
        m.matches();
    }
}).start();

// Thread 2 (concurrent with Thread 1)
new Thread(() -> {
    try (Matcher m = shared.matcher("456")) {
        m.matches();
    }
}).start();
```

### Matcher Confinement (NOT Thread-Safe)

```java
Pattern pattern = RE2.compile("test.*");

// ❌ WRONG - Matcher shared between threads
Matcher shared = pattern.matcher("input");
new Thread(() -> shared.matches()).start();  // WILL CRASH

// ✅ CORRECT - Each thread gets own matcher
new Thread(() -> {
    try (Matcher m = pattern.matcher("input")) {
        m.matches();
    }
}).start();
```

---

## Metrics Integration

### Available Metrics (55 total)

**Matching:** 9 metrics (global, string, bulk, zero-copy)
**Capture:** 10 metrics (global, string, bulk, zero-copy)
**Replace:** 11 metrics (global, string, bulk, zero-copy)
**Cache:** 25 metrics (hits, misses, evictions, size, memory)

### With Dropwizard

```java
import com.codahale.metrics.MetricRegistry;
import com.axonops.libre2.metrics.DropwizardMetricsAdapter;

MetricRegistry registry = new MetricRegistry();

RE2Config config = RE2Config.builder()
    .metricsRegistry(new DropwizardMetricsAdapter(registry, "myapp.re2"))
    .build();

Pattern.setGlobalCache(new PatternCache(config));

// Metrics available:
// - myapp.re2.matching.operations.total.count
// - myapp.re2.matching.latency
// - myapp.re2.patterns.cache.hits.total.count
// - myapp.re2.cache.patterns.current.count
// ... and 51 more
```

---

## Migration from java.util.regex

### Basic Pattern Matching

```java
// java.util.regex
java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\d+");
java.util.regex.Matcher m = p.matcher("123");
boolean matches = m.matches();

// libre2-java
com.axonops.libre2.api.Pattern p = RE2.compile("\\d+");
try (Matcher m = p.matcher("123")) {
    boolean matches = m.matches();
}

// OR simpler
boolean matches = RE2.matches("\\d+", "123");
```

### Capture Groups

```java
// java.util.regex
java.util.regex.Pattern p = java.util.regex.Pattern.compile("(\\d+)");
java.util.regex.Matcher m = p.matcher("123");
if (m.matches()) {
    String group = m.group(1);
}

// libre2-java
try (MatchResult result = RE2.match("(\\d+)", "123")) {
    if (result.matched()) {
        String group = result.group(1);
    }
}
```

### Replace

```java
// java.util.regex
String result = text.replaceAll("\\d+", "XXX");

// libre2-java
String result = RE2.replaceAll("\\d+", text, "XXX");
```

### Key Differences

1. **Backreferences:** RE2 uses `\\1 \\2` (not `$1 $2`)
2. **Resource management:** libre2 requires try-with-resources for Matcher/MatchResult
3. **Features:** RE2 doesn't support lookahead/lookbehind (intentional for ReDoS safety)
4. **Performance:** RE2 is linear-time (no catastrophic backtracking)

---

## Performance Tips

1. **Reuse patterns** - Compilation is expensive, caching is cheap
2. **Use bulk APIs** - 10-20x faster than iteration for high-throughput
3. **Use DirectByteBuffer** - Zero-copy for off-heap data
4. **Close resources** - Prevents matcher leaks and deferred cleanup
5. **Monitor cache hit rate** - Should be >90% in steady state
6. **Watch deferred cleanup gauge** - Should stay near zero

---

## Further Reading

- [README.md](README.md) - Project overview and features
- [ARCHITECTURE.md](ARCHITECTURE.md) - Cache, metrics, resource management
- [CONFIGURATION.md](CONFIGURATION.md) - Tuning guide
- [LOGGING_GUIDE.md](LOGGING_GUIDE.md) - Logging configuration
- [RE2_GAP_PROGRESS.md](RE2_GAP_PROGRESS.md) - Feature completion status
- [API Javadoc](libre2-core/src/main/java/com/axonops/libre2/api/) - Complete API reference

---

## Support

- **GitHub:** https://github.com/axonops/libre2-java
- **Issues:** https://github.com/axonops/libre2-java/issues
- **License:** Apache 2.0
- **RE2 Documentation:** https://github.com/google/re2/wiki/Syntax
