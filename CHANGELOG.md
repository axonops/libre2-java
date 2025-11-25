# Changelog

All notable changes to libre2-java will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] - 2025-11-25

### Major Release - Full Feature Parity with RE2

First production-ready release with comprehensive regex functionality including bulk operations, capture groups, replace operations, and zero-copy support.

### Added

#### Core API Enhancements
- **MatchResult class** - AutoCloseable wrapper for capture group access
  - Indexed groups: `group(int)`
  - Named groups: `group(String)` with RE2 syntax `(?P<name>...)`
  - Group count, input access, defensive array copying
  - Full AutoCloseable pattern for safety

- **Capture group methods** (Pattern.java):
  - `match(String)` - Full match with groups
  - `find(String)` - Find first with groups
  - `findAll(String)` - Find all with groups
  - `matchWithGroups(ByteBuffer)` - Zero-copy capture
  - `findWithGroups(ByteBuffer)` - Zero-copy find with groups
  - `findAllWithGroups(ByteBuffer)` - Zero-copy find all
  - `matchAllWithGroups(String[])` - Bulk capture groups
  - `matchAllWithGroups(Collection)` - Collection variant

- **Replace operations** (Pattern.java):
  - `replaceFirst(String, String)` - Replace first match
  - `replaceAll(String, String)` - Replace all matches
  - `replaceAll(String[], String)` - Bulk replace (array)
  - `replaceAll(Collection, String)` - Bulk replace (collection)
  - Backreference support (`\\1`, `\\2`, etc.)

- **Zero-copy replace operations**:
  - `replaceFirst(long, int, String)` - Off-heap memory
  - `replaceFirst(ByteBuffer, String)` - ByteBuffer with auto-routing
  - `replaceAll(long, int, String)` - Off-heap memory
  - `replaceAll(ByteBuffer, String)` - ByteBuffer with auto-routing
  - `replaceAll(long[], int[], String)` - Bulk off-heap
  - `replaceAll(ByteBuffer[], String)` - Bulk ByteBuffer

- **Bulk matching operations** (Pattern.java):
  - `matchAll(String[])` - Bulk full match
  - `matchAll(Collection)` - Collection variant
  - `findAll(String[])` - Bulk partial match
  - `findAll(Collection)` - Collection variant
  - `filter(Collection)` - Filter matching strings
  - `filterNot(Collection)` - Filter non-matching strings
  - `filterByKey(Map)` - Filter map by key pattern
  - `filterByValue(Map)` - Filter map by value pattern
  - `filterNotByKey(Map)` - Filter non-matching keys
  - `filterNotByValue(Map)` - Filter non-matching values
  - `retainMatches(Collection)` - In-place retain
  - `removeMatches(Collection)` - In-place remove
  - `retainMatchesByKey(Map)` - In-place map filtering
  - `retainMatchesByValue(Map)` - In-place map filtering
  - `removeMatchesByKey(Map)` - In-place map filtering
  - `removeMatchesByValue(Map)` - In-place map filtering

- **Zero-copy matching operations**:
  - `matches(long, int)` - Off-heap memory matching
  - `matches(ByteBuffer)` - ByteBuffer with auto-routing (direct → zero-copy)
  - `find(long, int)` - Off-heap partial match
  - `find(ByteBuffer)` - ByteBuffer partial match
  - `matchAll(ByteBuffer[])` - Bulk ByteBuffer full match
  - `findAll(ByteBuffer[])` - Bulk ByteBuffer partial match
  - `matchAll(long[], int[])` - Bulk off-heap full match
  - `findAll(long[], int[])` - Bulk off-heap partial match

- **RE2.java convenience API** - 28 static methods:
  - `compile(String)` - Pattern compilation
  - `matches(String, String)` - Quick matching
  - `match(String, String)` - Capture groups
  - `findFirst(String, String)` - Find with groups
  - `findAll(String, String)` - Find all with groups
  - `matchAll(String, String[])` - Bulk matching
  - `matchAllWithGroups(String, String[])` - Bulk capture
  - `filter(String, Collection)` - Bulk filtering
  - `filterNot(String, Collection)` - Bulk negative filtering
  - `replaceFirst(String, String, String)` - Quick replace
  - `replaceAll(String, String, String)` - Quick replace all
  - `replaceAll(String, String[], String)` - Bulk replace
  - `matchWithGroups(String, ByteBuffer)` - Zero-copy capture
  - `findWithGroups(String, ByteBuffer)` - Zero-copy find
  - `findAllWithGroups(String, ByteBuffer)` - Zero-copy find all
  - `quoteMeta(String)` - Escape special characters
  - `getProgramFanout(String)` - DFA complexity analysis
  - `getProgramSize(String)` - Compiled pattern size

- **Utility methods** (Pattern.java):
  - `quoteMeta(String)` - Escape regex special characters for literal matching
  - `getProgramFanout()` - DFA complexity analysis
  - `getNativeMemoryBytes()` - Compiled pattern memory size

#### Native Layer (JNI)
- **29 JNI functions** (was 9):
  - 11 new capture/replace/bulk methods
  - 9 new zero-copy direct memory methods
  - 9 new utilities

#### Metrics
- **55 total metrics** (was 25):
  - Matching operations: 9 metrics (Global + String + Bulk + Zero-Copy)
  - Capture operations: 10 metrics
  - Replace operations: 11 metrics
  - Cache/Resource: 25 metrics
  - Full breakdown: Global (ALL operations) + Specific (String/Bulk/Zero-Copy) for every operation type

#### Testing
- **459 tests** (was 187):
  - 47 bulk matching tests
  - 35 capture group tests
  - 26 replace operation tests
  - 48 JNI layer tests (comprehensive)
  - 15 Phase 1 extension tests
  - 13 bulk type safety tests
  - 9 comprehensive metrics tests
  - 100+ cache, concurrency, and resource tests

### Changed

- **MatchResult** now implements AutoCloseable for consistent resource management
- All capture group test methods updated to use try-with-resources
- Performance optimizations: Per-item latency for all bulk operations (comparability)
- Metrics architecture: Global (ALL) + Specific breakdown pattern throughout

### Fixed

- Method overloading conflicts resolved with `*WithGroups` naming convention
- Duplicate method signature issues
- Edge case handling for null inputs, empty strings, optional groups
- Reference counting prevents Pattern use-after-free
- Local reference cleanup in bulk operations prevents JNI ref table overflow

### Documentation

- Comprehensive Javadoc for all 80+ Pattern methods
- Full Javadoc for 28 RE2 static methods
- Complete JNI layer documentation (29 methods)
- Updated QUICKSTART.md with all new features
- Migration guide from java.util.regex
- Real-world examples (log parsing, PII redaction, bulk validation)
- Performance characteristics documented

### Performance

- Bulk operations: 10-20x faster than iteration (single JNI crossing)
- Zero-copy ByteBuffer: 46-99% faster for large buffers
- Simple patterns: 10-20M matches/sec
- Throughput: 3.6M matches/sec for 10k string bulk operations

---

## [0.9.1] - 2025-11-20

### Initial Release

#### Core Features
- Pattern compilation with automatic caching
- Basic matching operations (fullMatch, partialMatch)
- Matcher API (matches(), find())
- Dual eviction cache (LRU + idle timeout)
- Resource tracking and safety
- Thread-safe operations

#### Metrics (25 total)
- Pattern compilation metrics
- Cache hit/miss tracking
- Resource allocation/deallocation
- Native memory tracking
- Deferred cleanup monitoring

#### Platform Support
- macOS (x86_64, Apple Silicon)
- Linux (x86_64, ARM64)
- JNI-based native wrapper
- Self-contained shared libraries

#### Testing
- 187 comprehensive tests
- Cache behavior tests
- Concurrency tests
- Resource management tests
- Performance benchmarks

---

## [Unreleased]

### Future Considerations
- JMH micro-benchmarks
- Additional integration tests
- Performance profiling in real Cassandra workloads
- Extended Unicode normalization support

---

## Migration Guide

### From 0.9.1 to 1.0.0

**Breaking Changes:**
- None - 1.0.0 is fully backward compatible with 0.9.1
- All 0.9.1 APIs continue to work

**New Features (Opt-In):**
- Use `MatchResult` for capture groups (requires try-with-resources)
- Use bulk APIs for high-throughput scenarios
- Use ByteBuffer APIs for zero-copy operations
- Use replace APIs for find/replace operations
- Use RE2 static methods for convenience

**Performance:**
- No regressions - existing code runs at same speed or faster
- Opt into bulk APIs for 10-20x improvement on arrays

**Recommended Actions:**
1. Update code using `Matcher` iteration to bulk APIs
2. Add try-with-resources for any `MatchResult` usage
3. Monitor new metrics for capture/replace operations
4. Consider DirectByteBuffer for off-heap data sources

---

## Links

- **GitHub:** https://github.com/axonops/libre2-java
- **Issues:** https://github.com/axonops/libre2-java/issues
- **RE2 Documentation:** https://github.com/google/re2
