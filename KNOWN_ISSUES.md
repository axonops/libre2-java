# Known Issues and Resolutions

## Issue 1: "native" is Reserved Keyword (RESOLVED)
- **Status:** RESOLVED
- **Severity:** Critical (blocked compilation)
- **Description:** Initial package structure used com.axonops.libre2.native but "native" is a Java reserved keyword
- **Impact:** Compilation failed with "<identifier> expected" errors
- **Resolution:** Renamed package to com.axonops.libre2.jni
- **Date Discovered:** 2025-11-17
- **Date Resolved:** 2025-11-17

## Issue 2: Docker Containers Not Receiving Environment Variables (RESOLVED)
- **Status:** RESOLVED
- **Severity:** High (blocked Linux builds)
- **Description:** GitHub environment variables not passed into Docker containers
- **Impact:** Linux builds failed with "required environment variables not set"
- **Resolution:** Added -e flags to docker run commands to pass RE2_COMMIT, etc.
- **Date Discovered:** 2025-11-17
- **Date Resolved:** 2025-11-17

## Issue 3: macOS Runners Missing jq (RESOLVED)
- **Status:** RESOLVED
- **Severity:** Medium (blocked signature verification)
- **Description:** macOS runners don't have jq pre-installed
- **Impact:** Signature verification failed with empty string
- **Resolution:** Added brew install jq to macOS build steps
- **Date Discovered:** 2025-11-17
- **Date Resolved:** 2025-11-17

## Issue 4: Abseil Commit Not Available in Shallow Clone (RESOLVED)
- **Status:** RESOLVED
- **Severity:** Medium (blocked builds)
- **Description:** git clone --depth 50 didn't include the pinned commit
- **Impact:** Build failed with "unable to read tree"
- **Resolution:** Removed --depth flag to clone full history
- **Date Discovered:** 2025-11-17
- **Date Resolved:** 2025-11-17

## Issue 5: Linux Libraries Had Undefined Abseil Symbols (RESOLVED)
- **Status:** RESOLVED
- **Severity:** Critical (runtime failures on Linux x86_64)
- **Description:** Linux x86_64 library had undefined symbols for Abseil (CycleClock, etc.)
- **Impact:** JNA failed to load library with "undefined symbol" errors
- **Root Cause:** g++ linker was excluding Abseil .a files it thought were unused
- **Resolution:** Added --whole-archive flag to force inclusion of all Abseil symbols
- **Build Script Change:** `-Wl,--whole-archive abseil-build/absl/*/*.a -Wl,--no-whole-archive`
- **Verification:** Confirmed - all platforms now pass tests
- **Date Discovered:** 2025-11-17
- **Date Resolved:** 2025-11-17

## Issue 6: Cache Tests Failing Due to Statistics Not Resetting (OPEN)
- **Status:** OPEN (Phase 2 WIP)
- **Severity:** Medium (blocks Phase 2 completion)
- **Description:** Cache tests expect statistics to reset between tests, but global cache persists
- **Impact:** 6/12 cache tests failing (hitRate, currentSize assertions fail)
- **Root Cause:** Static cache in Pattern class is shared across all tests
- **Potential Solutions:**
  - Add Pattern.resetCacheForTesting() method
  - Make cache non-static and inject via Pattern.setCache()
  - Use @BeforeEach to fully reset cache state
- **Assigned:** Next session
- **Date Discovered:** 2025-11-17
