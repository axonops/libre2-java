# Decision Log

## Phase 1: Core API

### Decision: Package Naming - "jni" not "native"
- **What:** Package name for JNA bindings
- **Options:** native, jni, binding
- **Chosen:** jni
- **Rationale:** "native" is a Java reserved keyword, cannot be used as package name
- **Impact:** All JNA classes in com.axonops.libre2.jni
- **Date:** 2025-11-17
- **Status:** Implemented

### Decision: Package Root - com.axonops.libre2
- **What:** Root package for project
- **Chosen:** com.axonops.libre2 (not com.github.libre2)
- **Rationale:** Project is for AxonOps organization
- **Impact:** All classes under com.axonops.libre2.*
- **Date:** 2025-11-17
- **Status:** Implemented

### Decision: Native Library Build Process
- **What:** How to build and distribute native libraries
- **Options:**
  - Local builds with Homebrew dependencies
  - GitHub Actions with tarballs
  - GitHub Actions with git commit pinning
- **Chosen:** GitHub Actions with git commit pinning
- **Rationale:** Maximum security for production database use, reproducible, no local C++ toolchain needed
- **Impact:** Libraries built via CI/CD, committed to repo, Java devs never compile C++
- **Date:** 2025-11-17
- **Status:** Implemented

### Decision: Git Commit Pinning vs Release Tarballs
- **What:** Source download method for RE2/Abseil
- **Options:** Release tarballs, git tags, git commit hashes
- **Chosen:** Git commit hashes
- **Rationale:** Cryptographically immutable, industry best practice, supply chain security
- **Impact:** Build script clones and checks out exact commits
- **Date:** 2025-11-17
- **Status:** Implemented

### Decision: Commit Hash Storage Location
- **What:** Where to store RE2_COMMIT and ABSEIL_COMMIT
- **Options:** Hardcoded in build.sh, GitHub Secrets, GitHub Environment Variables
- **Chosen:** GitHub Environment Variables (protected environment "native-builds")
- **Rationale:** Cannot be changed via code edits, visible but protected, audit trail, can add approval requirements
- **Impact:** Requires admin access to change commit pins
- **Date:** 2025-11-17
- **Status:** Implemented

### Decision: Signature Verification Method
- **What:** How to verify commits are from Google engineers
- **Options:** Local GPG verification, GitHub API verification, Sigstore
- **Chosen:** GitHub API verification (uses GitHub's GPG validation)
- **Rationale:** No GPG key management, GitHub already validated, simple implementation
- **Impact:** Build fails if commit not signed by trusted engineer
- **Date:** 2025-11-17
- **Status:** Implemented

### Decision: JNA Dependency Scope
- **What:** Should JNA be "provided" or "compile" scope
- **Chosen:** provided
- **Rationale:** Users (Cassandra) provide JNA, we just need it for compilation/tests
- **Impact:** JNA not included in JAR, users must provide
- **Date:** 2025-11-17
- **Status:** Implemented

## Phase 2: Pattern Caching

### Decision: Cache Size Default
- **What:** Default maxCacheSize value
- **Options:** 1000, 10000, 50000, 100000
- **Chosen:** 50000
- **Rationale:** Production Cassandra clusters (128GB+), 50K patterns = ~50-200MB (negligible)
- **Impact:** Better hit rates for diverse workloads, minimal memory overhead
- **Date:** 2025-11-17
- **Status:** Implemented

### Decision: maxSimultaneousCompiledPatterns is ACTIVE not Cumulative
- **What:** Does limit apply to total compilations or active patterns?
- **Chosen:** ACTIVE/SIMULTANEOUS count only
- **Rationale:**
  - Allows unlimited compilations over library lifetime
  - Patterns can be freed and recompiled
  - Prevents only SIMULTANEOUS accumulation
  - Critical for long-running Cassandra instances
- **Impact:** After 1 month, 10M queries OK if only 100K simultaneous
- **Date:** 2025-11-17
- **Status:** Implemented (enforcement pending)

### Decision: Reference Counting for Use-After-Free Prevention
- **What:** How to prevent patterns freed while matchers active?
- **Chosen:** Reference counting (AtomicInteger refCount)
- **Rationale:**
  - Matcher increments refCount on creation
  - Matcher decrements on close
  - forceClose() only frees if refCount == 0
  - Industry standard (Java GC, smart pointers, etc.)
- **Impact:** Prevents catastrophic use-after-free under concurrent load
- **Date:** 2025-11-17
- **Status:** Implemented and tested

### Decision: Lock-Free ConcurrentHashMap vs Synchronized LinkedHashMap
- **What:** Cache implementation for high concurrency
- **Options:**
  - Synchronized LinkedHashMap (original)
  - ConcurrentHashMap with atomic timestamps
  - Caffeine cache library
- **Chosen:** ConcurrentHashMap with AtomicLong timestamps
- **Rationale:**
  - Lock-free reads (most common operation)
  - computeIfAbsent for safe concurrent compilation
  - No external dependencies
  - Pattern compilation was inside lock causing bottleneck
- **Impact:** >100K ops/sec with 100 threads, <1μs P50 latency
- **Date:** 2025-11-18
- **Status:** Implemented and tested

### Decision: Soft Limits vs Strict Cache Size Limits
- **What:** Should cache strictly enforce max size or allow temporary overage?
- **Options:**
  - Strict: Block until eviction completes
  - Soft: Allow temporary overage, async eviction
- **Chosen:** Soft limits with async eviction
- **Rationale:**
  - Strict limits would block callers during eviction
  - Soft limits allow ~10-20% overage temporarily
  - Async eviction runs in background, doesn't block cache access
  - Better for high-throughput scenarios
- **Impact:** Cache can temporarily exceed maxSize during high concurrent load
- **Date:** 2025-11-18
- **Status:** Implemented and tested

### Decision: Sample-Based LRU Eviction
- **What:** How to find least-recently-used patterns for eviction?
- **Options:**
  - Full scan O(n)
  - Maintain sorted list O(log n) per access
  - Sample-based O(sample size)
- **Chosen:** Sample-based (sample 500 entries, evict oldest)
- **Rationale:**
  - Full scan of 50K entries = 5-10ms blocking
  - Sample-based is O(500) regardless of cache size
  - Good-enough LRU approximation
  - Industry practice (Redis uses this approach)
- **Impact:** Eviction is fast even with large caches
- **Date:** 2025-11-18
- **Status:** Implemented

### Decision: Native Memory Tracking via RE2::ProgramSize()
- **What:** How to track off-heap memory used by compiled patterns?
- **Options:**
  - Estimate from pattern complexity
  - Call RE2::ProgramSize() at compile time
  - Periodic iteration to sum memory
- **Chosen:** Call ProgramSize() once at compile time, maintain running total
- **Rationale:**
  - O(1) per pattern vs O(n) periodic scan
  - Accurate (from RE2 itself)
  - Enables future memory-based eviction
- **Impact:** Can monitor native memory pressure, ~100ns overhead per compile
- **Date:** 2025-11-18
- **Status:** Implemented and tested

### Decision: Defensive Pattern Validation on Cache Hit
- **What:** Should we validate native pointer before returning cached pattern?
- **Options:**
  - No validation (trust the cache)
  - Always validate with re2_pattern_ok()
  - Configurable validation
- **Chosen:** Configurable, default enabled
- **Rationale:**
  - Prevents crashes from memory corruption
  - Auto-heals by recompiling invalid patterns
  - ~100ns overhead acceptable for safety
  - Can be disabled if overhead unacceptable
- **Impact:** Production resilience against edge cases
- **Date:** 2025-11-18
- **Status:** Implemented

### Decision: Eviction Protection Period
- **What:** Should newly added patterns be protected from immediate eviction?
- **Options:**
  - No protection (evict any pattern meeting LRU criteria)
  - Fixed protection period (e.g., 100ms)
  - Configurable protection period
- **Chosen:** Configurable protection period, default 1000ms
- **Rationale:**
  - Race condition discovered: pattern evicted before caller can use it
  - Pattern.compile() returns pattern, but async LRU evicts it immediately
  - Caller then sees "Pattern is closed" error
  - 1 second gives caller time to use pattern even under heavy load
- **Impact:** Prevents "Pattern is closed" race conditions under concurrent eviction
- **Date:** 2025-11-18
- **Status:** Implemented

## Phase 3: Timeout Support

### Decision: Skip Phase 3 - Timeout Not Needed at Library Level
- **What:** Should libre2-java implement per-pattern timeout support?
- **Options:**
  - ExecutorService-based timeout with Future.get()
  - Cooperative cancellation token
  - No timeout support (skip Phase 3)
- **Chosen:** Skip Phase 3 entirely
- **Rationale:**
  - RE2 already guarantees linear-time complexity (no catastrophic backtracking)
  - The primary reason for regex timeouts (ReDoS) is eliminated
  - SAI index has query-wide timeout, not per-pattern timeout
  - Timeout logic belongs in SAI index code where query context is available
  - Adding timeout here would add complexity for no real benefit
  - Caller can simply stop calling the library when query times out
- **Impact:**
  - Simpler library with no ExecutorService overhead
  - SAI index handles timeout coordination with Cassandra
  - Phase numbering: Skip to Phase 4 (Logging/Metrics)
- **Date:** 2025-11-18
- **Status:** Decision recorded, Phase 3 skipped

## Phase 4: Logging and Metrics

### Decision: Multi-Module Architecture
- **What:** Split into libre2-core + libre2-dropwizard modules
- **Chosen:** Multi-module with shared parent POM
- **Rationale:** 
  - Core stays generic (no framework coupling)
  - Dropwizard module provides convenience
  - Can test with actual frameworks
- **Impact:** Cleaner separation, better testability
- **Date:** 2025-11-20
- **Status:** Implemented

### Decision: Rename from libre2-cassandra-5.0 to libre2-dropwizard
- **What:** Module was originally libre2-cassandra-5.0
- **Chosen:** libre2-dropwizard (generic)
- **Rationale:**
  - Nothing Cassandra-specific (just Dropwizard + auto-JMX)
  - Works with any framework (Cassandra, Spring Boot, standalone)
  - Configurable metric prefix (not hardcoded to Cassandra)
- **Impact:** Generic, reusable by any Dropwizard application
- **Date:** 2025-11-20
- **Status:** Implemented

### Decision: SLF4J Logging in Core
- **What:** Should logging be in core or framework-specific modules?
- **Chosen:** SLF4J in core (provided scope)
- **Rationale:**
  - SLF4J IS the abstraction layer (industry standard)
  - Users choose implementation (logback, log4j2, nop, etc.)
  - Zero forced dependencies
- **Impact:** Works everywhere, fully generic
- **Date:** 2025-11-20
- **Status:** Implemented

### Decision: Pattern Hashing for Privacy
- **What:** Log full patterns or hash them?
- **Chosen:** Hash patterns using Integer.toHexString(pattern.hashCode())
- **Rationale:**
  - Patterns may contain sensitive data (PII, security rules)
  - Logs don't get cluttered with 200-char regex strings
  - Hash is consistent (same pattern = same hash)
- **Impact:** Privacy-conscious logging
- **Date:** 2025-11-20
- **Status:** Implemented

### Decision: Metric Naming Convention
- **What:** How to name metrics for clarity?
- **Chosen:** Hierarchical with suffixes:
  - Counters: `.total.count` (cumulative)
  - Timers: `.latency` (nanoseconds)
  - Gauges: `.current.X` or `.peak.X` + units (.count, .bytes)
- **Rationale:**
  - Before: `cache.size` (ambiguous)
  - After: `cache.patterns.current.count` (clear!)
  - Unambiguous units and semantics
- **Impact:** Self-documenting metrics
- **Date:** 2025-11-20
- **Status:** Implemented

### Decision: Add Deferred Cleanup Metrics
- **What:** Original plan had no deferred cleanup metrics
- **Chosen:** Add 4 metrics for deferred patterns
- **Rationale:**
  - Deferred backlog = potential memory leak risk
  - Need to monitor if cleanup keeping up
  - Peak deferred count/memory = worst-case tracking
- **Impact:** Better observability of deferred cleanup
- **Date:** 2025-11-20
- **Status:** Implemented

### Decision: Freed Counts as Counters (not Gauges)
- **What:** resources.patterns.freed/matchers.freed metric type
- **Originally:** Registered as Gauges (read from ResourceTracker)
- **Chosen:** Implemented as Counters (incremented on free)
- **Rationale:**
  - Semantically they're cumulative (always increasing)
  - Counters more appropriate than Gauges for cumulative counts
  - Pass metricsRegistry explicitly (no unsafe try-catch)
- **Impact:** Semantically correct, safer code
- **Date:** 2025-11-20
- **Status:** Implemented

### Decision: Initialization Warmup Test
- **What:** Should library test itself on initialization?
- **Chosen:** testOnInitialization config (default: true)
- **Rationale:**
  - Catches library issues early
  - Warms up JNI/native code
  - Logs success/failure for operators
- **Impact:** Better startup verification
- **Date:** 2025-11-20
- **Status:** Implemented
