# Native Library Build System

This directory contains everything needed to build the RE2 wrapper library for all supported platforms.

## Overview

The build system:
1. Downloads RE2 and Abseil source code from GitHub
2. Compiles them as static libraries
3. Compiles our C wrapper (`wrapper/re2_wrapper.cpp`)
4. Links everything into a single shared library with no external dependencies

## Supported Platforms

- **macOS x86_64** (Intel Macs) → `darwin-x86_64/libre2.dylib`
- **macOS aarch64** (Apple Silicon) → `darwin-aarch64/libre2.dylib`
- **Linux x86_64** (Intel/AMD) → `linux-x86_64/libre2.so`
- **Linux aarch64** (ARM64) → `linux-aarch64/libre2.so`

## Building

### Automated (Recommended): GitHub Actions

1. Go to: https://github.com/axonops/libre2-java/actions
2. Select "Build Native Libraries" workflow
3. Click "Run workflow"
4. Wait ~10-15 minutes for all 4 platforms to build
5. Download artifacts from the workflow run
6. Extract and place libraries in `src/main/resources/native/{platform}/`

### Manual: Local Build

**Prerequisites:**
- CMake 3.10+
- C++17 compiler (g++ or clang++)
- curl (for downloading sources)

**Build for your current platform:**
```bash
cd native
./scripts/build.sh
```

Output will be in `native/build/libre2.{dylib|so}`

### Manual: Docker Build (Linux only)

**Build for Linux x86_64:**
```bash
cd native
docker build -t re2-builder .
docker run --rm -v "$(pwd):/output" re2-builder \
  sh -c "./build.sh && cp build/libre2.so /output/"
```

**Build for Linux aarch64:**
```bash
cd native
docker buildx build --platform linux/arm64 -t re2-builder-arm64 --load .
docker run --rm --platform linux/arm64 \
  -v "$(pwd):/output" re2-builder-arm64 \
  sh -c "./build.sh && cp build/libre2.so /output/"
```

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

3. **Build RE2** (~2 minutes):
   - Static library
   - Linked against Abseil
   - Testing disabled

4. **Build wrapper** (~1 minute):
   - Compiles `re2_wrapper.cpp`
   - Links against static RE2 and Abseil
   - Produces single shared library with no external dependencies
   - Strips symbols for smaller size

## Output Verification

After building, verify the library:

**macOS:**
```bash
# Check it's a valid dylib
file build/libre2.dylib

# Check dependencies (should only show system libs)
otool -L build/libre2.dylib
```

**Linux:**
```bash
# Check it's a valid shared library
file build/libre2.so

# Check dependencies (should only show libc, libm, etc)
ldd build/libre2.so
```

## Troubleshooting

**Build fails with "cmake not found":**
- macOS: `brew install cmake`
- Linux: `apt-get install cmake` or `yum install cmake`

**Build fails with compiler errors:**
- Ensure you have C++17 support:
  - g++ 7+ or clang++ 5+

**GitHub Actions builds fail:**
- Check the Actions log for specific errors
- Verify the workflow syntax at https://github.com/axonops/libre2-java/actions

**Libraries are too large:**
- They will be 1-2 MB each (RE2 + Abseil statically linked)
- This is expected and necessary for standalone deployment

## Next Steps

After building all 4 libraries:

1. Create Java resources directory:
   ```bash
   mkdir -p src/main/resources/native/{darwin-x86_64,darwin-aarch64,linux-x86_64,linux-aarch64}
   ```

2. Copy libraries to resources:
   ```bash
   cp libre2.dylib src/main/resources/native/darwin-aarch64/
   # etc for each platform
   ```

3. Libraries will be embedded in the JAR and extracted at runtime by `RE2LibraryLoader`
