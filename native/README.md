# Native Library Build System

This directory contains the build infrastructure for compiling the RE2 wrapper library for all supported platforms.

**IMPORTANT:** Native libraries are built ONLY via [GitHub Actions CI/CD](.github/workflows/build-native.yml). Java developers never compile C++ code - libraries are pre-built and committed to the repository.

---

## For Java Developers

**You don't need to do anything!**

Pre-compiled libraries are already in the repository at:
- `src/main/resources/native/darwin-x86_64/libre2.dylib` (macOS Intel)
- `src/main/resources/native/darwin-aarch64/libre2.dylib` (macOS Apple Silicon)
- `src/main/resources/native/linux-x86_64/libre2.so` (Linux x86_64)
- `src/main/resources/native/linux-aarch64/libre2.so` (Linux ARM64)

Just build the Java project:
```bash
mvn clean package
```

Libraries are automatically embedded in the JAR and loaded at runtime by `RE2LibraryLoader`.

---

## What Gets Built

Our library bundles three components into a single self-contained shared library:

### 1. RE2 - Google's Regex Engine
- **Project:** https://github.com/google/re2
- **Version:** 2025-11-05
- **License:** BSD-3-Clause
- **Why we use it:**
  - Linear time complexity (no catastrophic backtracking)
  - ReDoS safe (prevents regex denial-of-service attacks)
  - Production-grade (used by Google Search, Gmail, etc.)
  - Essential for Cassandra security (processes untrusted regex patterns)

### 2. Abseil - Google's C++ Common Libraries
- **Project:** https://github.com/abseil/abseil-cpp
- **Version:** 20250814.1 (LTS)
- **License:** Apache License 2.0
- **Why we need it:** RE2 depends on Abseil containers and utilities
- **Note:** Statically linked, not exposed in our API

### 3. Our C Wrapper (re2_wrapper.cpp)
- **Purpose:** Provides pure C API that JNA can call from Java
- **Functions:** 8 C functions (re2_compile, re2_free_pattern, re2_full_match, etc.)
- **Size:** ~120 lines of C++ wrapping RE2's C++ API

**Final output:**
- **macOS:** `libre2.dylib` (~875 KB)
- **Linux:** `libre2.so` (~2.7 MB)
- **Dependencies:** Only system libraries (libc, libm, CoreFoundation on macOS)
- **Everything else:** Statically linked inside

---

## Security: Git Commit Pinning

**We build from pinned git commits, not release tarballs.**

### Current Pins

```bash
RE2:    927f5d53caf8111721e734cf24724686bb745f55  # Release 2025-11-05
Abseil: d38452e1ee03523a208362186fd42248ff2609f6  # LTS 20250814.1
```

### Why This Matters for Cassandra

This library runs inside Cassandra, a mission-critical database. Commit pinning provides:

1. **Immutability**: Commits are cryptographically sealed
   - Cannot be changed without changing the hash
   - Stronger than version tags (which can be moved/deleted)

2. **Supply chain security**: Protects against
   - Compromised release tarballs
   - Dependency confusion attacks
   - Upstream repository compromises

3. **Audit trail**: Full verification
   - Inspect exact code being compiled
   - Verify GPG signatures from Google engineers
   - Track what's running in production

4. **Industry best practice**: Used by Kubernetes, Go modules, Bazel, etc.

### Finding Commit Hashes for Updates

**When updating to a new RE2/Abseil version:**

1. Go to the GitHub release page:
   - RE2: https://github.com/google/re2/releases
   - Abseil: https://github.com/abseil/abseil-cpp/releases

2. Click on the release tag (e.g., "2025-11-05")

3. Look for the commit hash:
   - Usually shown near the top: "Commits: `abc123...`"
   - Or in the URL after clicking the commit count
   - Full hash is 40 characters (use the full hash, not abbreviated)

4. Update in `scripts/build.sh`:
   ```bash
   RE2_COMMIT="new_full_40_char_commit_hash_here"
   RE2_VERSION="2025-XX-XX"  # for reference
   ```

5. Test and rebuild (see below)

---

## Rebuilding Libraries (Maintainers Only)

### When to Rebuild

- Updating to a new RE2 or Abseil version
- Modifying the C wrapper (`wrapper/re2_wrapper.cpp`)
- Security updates

### How to Rebuild

**Using GitHub Actions (Automated):**

1. **Navigate to workflow:**
   - Direct link: https://github.com/axonops/libre2-java/actions/workflows/build-native.yml
   - Or: GitHub → Actions → "Build Native Libraries" (left sidebar)

2. **Trigger build:**
   - Click **"Run workflow"** button (top right, above workflow runs)
   - If you don't see this button: use `gh` CLI instead (see below)

3. **Select options:**
   - **Use workflow from:** Select branch with build script changes
   - **Target branch to create PR against:** Usually `development`

4. **Click "Run workflow"**

5. **Wait ~10-15 minutes** for all 4 platforms to build

6. **Review automatically created PR:**
   - Workflow creates branch `native-libs-YYYYMMDD-HHMMSS`
   - Commits all 4 libraries to `src/main/resources/native/`
   - Opens PR against your selected target branch

7. **Merge PR** when satisfied

**Using GitHub CLI (if UI doesn't work):**

```bash
gh workflow run build-native.yml --ref development -f target_branch=development
```

**Monitor progress:**
```bash
gh run list --workflow=build-native.yml --limit 5
gh run watch  # Watch most recent run
```

---

## Build Process (Technical Details)

The [build script](scripts/build.sh) executes these steps:

### Step 1: Clone Source (Security-Critical)
```bash
git clone https://github.com/google/re2.git
cd re2
git checkout 927f5d53caf8111721e734cf24724686bb745f55  # Pinned commit
```

Repeat for Abseil. Uses `--depth 50` for faster clones.

### Step 2: Build Abseil (~5 minutes)
- Compiles as static library
- Installs to local prefix for RE2 to find
- Position-independent code (required for shared library)

### Step 3: Build RE2 (~2 minutes)
- Compiles as static library
- Links against Abseil
- Testing disabled (not needed for distribution)

### Step 4: Build Wrapper (~1 minute)
- Compiles `re2_wrapper.cpp`
- Links with static RE2 + Abseil libraries
- Produces single self-contained shared library
- **macOS:** Links CoreFoundation framework, strips dead code
- **Linux:** Static libstdc++, strips sections

### Step 5: Automated Verification
- Checks library format (Mach-O or ELF)
- Verifies all 8 wrapper functions exported
- Confirms only system dependencies
- **Build fails if any check fails**

---

## Platform-Specific Build Methods

| Platform | Build Method | Notes |
|----------|-------------|-------|
| macOS x86_64 | Native on `macos-15-intel` runner | GitHub-hosted Intel Mac |
| macOS aarch64 | Native on `macos-latest` runner | GitHub-hosted Apple Silicon Mac |
| Linux x86_64 | Docker on `ubuntu-latest` runner | Clean Ubuntu 22.04 environment |
| Linux aarch64 | Docker + QEMU on `ubuntu-latest` | Emulation (slower but free) |

**Why Docker for Linux:**
- Reproducible: Locks in Ubuntu 22.04 regardless of runner version
- Explicit dependencies: Dockerfile shows exactly what's needed
- QEMU support: Enables ARM64 cross-compilation

---

## Directory Structure

```
native/
├── wrapper/
│   └── re2_wrapper.cpp          # C wrapper (8 JNA functions)
├── scripts/
│   └── build.sh                  # Build script (git clone, cmake, link)
├── Dockerfile                    # Ubuntu 22.04 + build tools
└── README.md                     # This file

Generated during build (not committed):
├── build/
│   ├── re2/                      # RE2 source (git cloned)
│   ├── abseil-cpp/               # Abseil source (git cloned)
│   ├── re2-build/                # RE2 compiled
│   ├── abseil-build/             # Abseil compiled
│   ├── abseil-install/           # Abseil installed (for RE2)
│   └── libre2.{dylib|so}         # Final library
```

---

## Troubleshooting

**"Run workflow" button not visible in GitHub UI:**
- Try hard refresh (Ctrl+Shift+R / Cmd+Shift+R)
- Use GitHub CLI instead: `gh workflow run build-native.yml --ref development -f target_branch=development`
- Check repo permissions (need write access)

**Build fails with "cmake not found":**
- Should not happen (cmake is in Dockerfile and macOS runners)
- Check GitHub Actions logs for environment issues

**Build fails with git clone errors:**
- Network issue on GitHub's side (rare)
- Re-run the workflow

**ARM64 build takes too long (>30 minutes):**
- QEMU emulation is slow (~15 minutes normal)
- Check if job is actually running or stalled
- Cancel and retry if stalled

**PR creation fails:**
- Check the "Commit Native Libraries" job logs
- Verify `GITHUB_TOKEN` has permissions
- May need to enable "Allow GitHub Actions to create and approve pull requests" in repo settings

**Libraries still using old commits after merge:**
- Check PR actually merged (not just closed)
- Pull latest: `git pull origin development`
- Verify `src/main/resources/native/` has updated files

---

## Exported C Functions

The wrapper exposes these 8 functions for JNA:

```c
void* re2_compile(const char* pattern, int pattern_len, int case_sensitive);
void  re2_free_pattern(void* pattern);
int   re2_full_match(void* pattern, const char* text, int text_len);
int   re2_partial_match(void* pattern, const char* text, int text_len);
const char* re2_get_error();
const char* re2_get_pattern(void* pattern);
int   re2_num_capturing_groups(void* pattern);
int   re2_pattern_ok(void* pattern);
```

All verification steps check these functions are correctly exported.

---

## Links

- **GitHub Workflow:** [.github/workflows/build-native.yml](../.github/workflows/build-native.yml)
- **Build Script:** [scripts/build.sh](scripts/build.sh)
- **C Wrapper:** [wrapper/re2_wrapper.cpp](wrapper/re2_wrapper.cpp)
- **RE2 Project:** https://github.com/google/re2
- **Abseil Project:** https://github.com/abseil/abseil-cpp
