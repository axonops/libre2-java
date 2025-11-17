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
- **Chosen:** compile
- **Rationale:** Needed for compilation and testing, Cassandra also provides it (no conflict)
- **Impact:** JNA available at compile time
- **Date:** 2025-11-17
- **Status:** Implemented
