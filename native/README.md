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
  - Critical for production systems processing untrusted regex patterns

### 2. Abseil - Google's C++ Common Libraries
- **Project:** https://github.com/abseil/abseil-cpp
- **Version:** 20250814.1 (LTS)
- **License:** Apache License 2.0
- **Why we need it:** RE2 depends on Abseil containers and utilities
- **Note:** Statically linked, not exposed in our API

### 3. Our JNI Wrapper (re2_jni.cpp)
- **Purpose:** Provides JNI bindings for direct Java native method calls
- **Functions:** 9 JNI functions (compile, freePattern, fullMatch, etc.)
- **Size:** ~200 lines of C++ wrapping RE2's C++ API
- **Performance:** JNI has ~2-3x lower call overhead than JNA

**Final output:**
- **macOS:** `libre2.dylib` (~875 KB)
- **Linux:** `libre2.so` (~2.7 MB)
- **Dependencies:** Only system libraries (libc, libm, CoreFoundation on macOS)
- **Everything else:** Statically linked inside

---

## Security: Git Commit Pinning

**We build from pinned git commits, not release tarballs.**

### Current Pins

All version information is stored as **GitHub Environment Variables** in the `native-builds` environment:

| Variable | Current Value | Purpose |
|----------|---------------|---------|
| `RE2_COMMIT` | `927f5d53caf8111721e734cf24724686bb745f55` | RE2 git commit (immutable) |
| `RE2_RELEASE_VERSION` | `2025-11-05` | RE2 release version (for logging) |
| `ABSEIL_COMMIT` | `d38452e1ee03523a208362186fd42248ff2609f6` | Abseil git commit (immutable) |
| `ABSEIL_RELEASE_VERSION` | `20250814.1` | Abseil release version (for logging) |

**View/edit at:** https://github.com/axonops/libre2-java/settings/environments/native-builds

**Why environment variables:**
- Cannot be changed by editing code files
- Require repository admin access to modify
- Can be protected with approval requirements
- Audit trail of who changed what
- Single source of truth (no hardcoded versions in code)

### Why This Matters for Production Use

This library is designed for production environments (databases, web services, security tools). Commit pinning provides:

1. **Immutability**: Commits are cryptographically sealed
   - Cannot be changed without changing the hash
   - Stronger than version tags (which can be moved/deleted)

2. **Supply chain security**: Protects against
   - Compromised release tarballs
   - Dependency confusion attacks
   - Upstream repository compromises

3. **Signature verification**: Automated cryptographic validation
   - Build script verifies each commit via GitHub API
   - Confirms commits are signed by trusted Google engineers
   - Build fails immediately if signature invalid or missing
   - No GPG key management needed (GitHub does verification)

4. **Audit trail**: Full verification
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

4. Update GitHub Environment Variables:
   - Go to: https://github.com/axonops/libre2-java/settings/environments/native-builds
   - Update all 4 variables with new values:
     - `RE2_COMMIT`: New commit hash (40 chars)
     - `RE2_RELEASE_VERSION`: New release version (e.g., "2025-12-01")
     - `ABSEIL_COMMIT`: New commit hash (if Abseil also updated)
     - `ABSEIL_RELEASE_VERSION`: New release version (if Abseil also updated)
   - Requires repository admin access
   - Changes are logged in environment audit trail

5. Rebuild via GitHub Actions (see below)

---

## Rebuilding Libraries (Maintainers Only)

### When to Rebuild

- Updating to a new RE2 or Abseil version
- Modifying the C wrapper (`wrapper/re2_wrapper.cpp`)
- Security updates

### How to Rebuild

#### Method 1: GitHub CLI (Recommended - UI can be flakey)

**Trigger the build:**
```bash
# From the repository directory
gh workflow run build-native.yml \
  --ref development \
  -f target_branch=development
```

**Options:**
- `--ref development`: Which branch to run the workflow from (use your branch if testing changes)
- `-f target_branch=development`: Which branch the PR should target (usually `development` or `main`)

**Monitor the build:**
```bash
# List recent runs
gh run list --workflow=build-native.yml --limit 5

# Watch the latest run in real-time
gh run watch

# View specific run
gh run view 19434368550  # Use run ID from list command

# View logs if build fails
gh run view 19434368550 --log
```

**Wait ~10-15 minutes** for all 4 platforms to complete.

**Check for PR:**
```bash
# List recent PRs
gh pr list --limit 5

# View the created PR
gh pr view <PR_NUMBER>
```

**Merge the PR:**
```bash
# Review and merge via CLI
gh pr merge <PR_NUMBER> --squash --delete-branch

# Or merge via GitHub UI
```

#### Method 2: GitHub UI (Alternative)

1. **Navigate to workflow:**
   - Direct link: https://github.com/axonops/libre2-java/actions/workflows/build-native.yml

2. **Trigger build:**
   - Click **"Run workflow"** button (top right, above workflow runs)
   - If button not visible: Hard refresh (Cmd+Shift+R) or use gh CLI above

3. **Select options:**
   - **Use workflow from:** `development` (or your branch)
   - **Target branch to create PR against:** `development`

4. **Click "Run workflow"** and wait ~10-15 minutes

5. **Review and merge the auto-created PR**

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
│   └── re2_jni.cpp               # JNI wrapper (9 JNI functions)
├── jni/
│   └── com_axonops_libre2_jni_RE2NativeJNI.h  # Generated JNI header
├── scripts/
│   └── build.sh                  # Build script (git clone, cmake, link)
├── Dockerfile                    # Ubuntu 22.04 + JDK + build tools
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

## Exported JNI Functions

The wrapper exposes these 9 JNI functions:

```c
// Pattern compilation and lifecycle
jlong   Java_com_axonops_libre2_jni_RE2NativeJNI_compile(JNIEnv*, jclass, jstring, jboolean);
void    Java_com_axonops_libre2_jni_RE2NativeJNI_freePattern(JNIEnv*, jclass, jlong);

// Matching operations
jboolean Java_com_axonops_libre2_jni_RE2NativeJNI_fullMatch(JNIEnv*, jclass, jlong, jstring);
jboolean Java_com_axonops_libre2_jni_RE2NativeJNI_partialMatch(JNIEnv*, jclass, jlong, jstring);

// Pattern info and error handling
jstring Java_com_axonops_libre2_jni_RE2NativeJNI_getError(JNIEnv*, jclass);
jstring Java_com_axonops_libre2_jni_RE2NativeJNI_getPattern(JNIEnv*, jclass, jlong);
jint    Java_com_axonops_libre2_jni_RE2NativeJNI_numCapturingGroups(JNIEnv*, jclass, jlong);
jboolean Java_com_axonops_libre2_jni_RE2NativeJNI_patternOk(JNIEnv*, jclass, jlong);
jlong   Java_com_axonops_libre2_jni_RE2NativeJNI_patternMemory(JNIEnv*, jclass, jlong);
```

All verification steps check these functions are correctly exported.

---

## Links

- **GitHub Workflow:** [.github/workflows/build-native.yml](../.github/workflows/build-native.yml)
- **Build Script:** [scripts/build.sh](scripts/build.sh)
- **JNI Wrapper:** [wrapper/re2_jni.cpp](wrapper/re2_jni.cpp)
- **JNI Header:** [jni/com_axonops_libre2_jni_RE2NativeJNI.h](jni/com_axonops_libre2_jni_RE2NativeJNI.h)
- **RE2 Project:** https://github.com/google/re2
- **Abseil Project:** https://github.com/abseil/abseil-cpp
