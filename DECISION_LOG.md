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
