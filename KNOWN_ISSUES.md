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
