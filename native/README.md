# Native Library Build System

This directory contains the native RE2 wrapper library build infrastructure.

**IMPORTANT:** Native libraries are built ONLY via GitHub Actions CI/CD. Java developers never need to compile C++ code locally.

## What Gets Built

### RE2 - Google's Regular Expression Library
- **Project:** https://github.com/google/re2
- **Version:** 2025-11-05 (latest release)
- **License:** BSD-3-Clause
- **What it is:** A fast, safe, thread-friendly regular expression engine
- **Why we use it:**
  - Linear time complexity (no catastrophic backtracking)
  - ReDoS safe (prevents regex denial-of-service attacks)
  - Production-grade (used by Google internally)
  - Much safer than Java's built-in regex for untrusted patterns

### Abseil - Google's C++ Common Libraries
- **Project:** https://github.com/abseil/abseil-cpp
- **Version:** 20250814.1 (LTS release)
- **License:** Apache License 2.0
- **What it is:** Google's core C++ libraries (containers, algorithms, utilities)
- **Why we need it:** RE2 depends on Abseil for core functionality
- **Note:** Statically linked, not exposed in our API

### Our Wrapper (re2_wrapper.cpp)
- **Purpose:** Provides a pure C API that JNA can call from Java
- **Functions:** 8 C functions matching our JNA interface
- **Size:** ~150 lines of C++ code wrapping RE2's C++ API

## Build Output

All three components are compiled and linked into a single shared library:
- **macOS:** `libre2.dylib` (~875 KB)
- **Linux:** `libre2.so` (~2.7 MB)
- **Contains:** RE2 + Abseil + our wrapper (all statically linked)
- **Dependencies:** Only system libraries (libc, libm, CoreFoundation on macOS)

## Overview

The automated build system:
1. Downloads RE2 2025-11-05 and Abseil 20250814.1 source from GitHub
2. Compiles them as static libraries
3. Compiles the C wrapper (`wrapper/re2_wrapper.cpp`)
4. Links everything into self-contained shared libraries
5. Automatically commits libraries to a PR for review

## Supported Platforms

- **darwin-x86_64** → macOS Intel (libre2.dylib)
- **darwin-aarch64** → macOS Apple Silicon (libre2.dylib)
- **linux-x86_64** → Linux x86_64 (libre2.so)
- **linux-aarch64** → Linux ARM64 (libre2.so)

## Building Native Libraries (Maintainers Only)

**When to rebuild:**
- Updating to a new RE2 version
- Modifying `wrapper/re2_wrapper.cpp`
- Adding new platforms

**How to rebuild:**

1. Go to: https://github.com/axonops/libre2-java/actions/workflows/build-native.yml
2. Click **"Run workflow"** button
3. Select inputs:
   - **Use workflow from:** `development` (or your branch)
   - **Target branch to create PR against:** `development` (or target branch)
4. Click **"Run workflow"**
5. Wait ~10-15 minutes for build to complete
6. Workflow will automatically:
   - Build all 4 platforms in parallel
   - Create branch `native-libs-YYYYMMDD-HHMMSS`
   - Commit all libraries to `src/main/resources/native/`
   - Open a Pull Request
7. Review the PR and merge

**That's it!** The libraries are now in the repository.

## Directory Structure

```
native/
├── wrapper/
│   └── re2_wrapper.cpp          # C wrapper for RE2 (JNA interface)
├── scripts/
│   └── build.sh                  # Build script (downloads & compiles everything)
├── Dockerfile                    # Docker image for Linux builds
└── README.md                     # This file
```

## Build Process Details

The `build.sh` script does the following:

1. **Download sources** (~2-3 MB total):
   - RE2 2025-11-05 from github.com/google/re2
   - Abseil 20250814.1 from github.com/abseil/abseil-cpp

2. **Build Abseil** (~5 minutes):
   - Static library
   - Position-independent code (for shared library)
   - C++17 standard
   - Installed to local prefix for RE2 to find

3. **Build RE2** (~2 minutes):
   - Static library
   - Linked against Abseil
   - Testing disabled

4. **Build wrapper** (~1 minute):
   - Compiles `re2_wrapper.cpp`
   - Links against static RE2 and Abseil
   - Produces single shared library with no external dependencies
   - Strips symbols for smaller size

5. **Automatic verification** (in GitHub Actions):
   - Checks library format (Mach-O or ELF)
   - Verifies all 8 C functions are exported
   - Confirms only system dependencies
   - Build fails if verification fails

## For Java Developers

**You don't need to do anything!**

The compiled native libraries are already in the repository at:
- `src/main/resources/native/darwin-x86_64/libre2.dylib`
- `src/main/resources/native/darwin-aarch64/libre2.dylib`
- `src/main/resources/native/linux-x86_64/libre2.so`
- `src/main/resources/native/linux-aarch64/libre2.so`

Just build the Java project normally:
```bash
mvn clean package
```

The libraries are automatically embedded in the JAR and extracted at runtime by `RE2LibraryLoader`.

## Troubleshooting

**GitHub Actions build fails:**
- Check the Actions log for specific errors
- Verify all 4 platform jobs completed successfully
- macOS Intel runners can sometimes be slow/unavailable

**PR creation fails:**
- Check GitHub token permissions
- Verify target branch exists
- Check Actions log for git push errors

**Libraries missing from JAR:**
- Verify the PR was merged
- Check `src/main/resources/native/` has all 4 platform directories
- Run `mvn clean package` to regenerate JAR
