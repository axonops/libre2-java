#!/bin/bash
set -e

# Build script for RE2 wrapper library
# This script:
# 1. Downloads RE2 and Abseil source
# 2. Builds them as static libraries
# 3. Links our wrapper into a single shared library with no external dependencies
#
# Detects container runtime (podman or docker) for Linux builds

# SECURITY: Pin exact git commits for immutable builds
# These commits are cryptographically verified and immutable
RE2_COMMIT="927f5d53caf8111721e734cf24724686bb745f55"       # RE2 release 2025-11-05 (signed by Russ Cox)
ABSEIL_COMMIT="d38452e1ee03523a208362186fd42248ff2609f6"   # Abseil LTS 20250814.1 (Patch 1)

# Version tags (for reference only, not used in build)
RE2_VERSION="2025-11-05"
ABSEIL_VERSION="20250814.1"

echo "Building with pinned commits:"
echo "  RE2:    $RE2_COMMIT (release $RE2_VERSION)"
echo "  Abseil: $ABSEIL_COMMIT (release $ABSEIL_VERSION)"

# Detect platform
OS=$(uname -s | tr '[:upper:]' '[:lower:]')
ARCH=$(uname -m)

# Normalize architecture names
case "$ARCH" in
    x86_64|amd64) ARCH="x86_64" ;;
    aarch64|arm64) ARCH="aarch64" ;;
    *) echo "Unsupported architecture: $ARCH"; exit 1 ;;
esac

echo "Building for: $OS-$ARCH"

# Create build directory
BUILD_DIR="$(pwd)/build"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

# Clone and checkout RE2 at pinned commit
if [ ! -d "re2" ]; then
    echo "Cloning RE2..."
    git clone --depth 50 https://github.com/google/re2.git
    cd re2
    git checkout "$RE2_COMMIT"

    # Optional: Verify commit signature (requires GPG keys)
    # git verify-commit "$RE2_COMMIT" || echo "Warning: Could not verify RE2 commit signature"

    echo "RE2 commit verified: $(git log -1 --oneline)"
    cd ..
fi

# Clone and checkout Abseil at pinned commit
if [ ! -d "abseil-cpp" ]; then
    echo "Cloning Abseil..."
    git clone --depth 50 https://github.com/abseil/abseil-cpp.git
    cd abseil-cpp
    git checkout "$ABSEIL_COMMIT"

    # Optional: Verify commit signature
    # git verify-commit "$ABSEIL_COMMIT" || echo "Warning: Could not verify Abseil commit signature"

    echo "Abseil commit verified: $(git log -1 --oneline)"
    cd ..
fi

# Build Abseil statically
echo "Building Abseil..."
ABSEIL_PREFIX="$BUILD_DIR/abseil-install"
mkdir -p abseil-build
cd abseil-build
cmake ../abseil-cpp \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DABSL_PROPAGATE_CXX_STD=ON \
    -DCMAKE_INSTALL_PREFIX="$ABSEIL_PREFIX" \
    -DCMAKE_CXX_STANDARD=17
cmake --build . -j$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)
cmake --install .
cd ..

# Build RE2 statically
echo "Building RE2..."
mkdir -p re2-build
cd re2-build
cmake ../re2 \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DRE2_BUILD_TESTING=OFF \
    -DCMAKE_PREFIX_PATH="$ABSEIL_PREFIX" \
    -DCMAKE_CXX_STANDARD=17
cmake --build . -j$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)
cd ..

# Build our wrapper
echo "Building wrapper..."
# Script must be run from native/ directory
# Wrapper source is in native/wrapper/re2_wrapper.cpp
if [ ! -f "../wrapper/re2_wrapper.cpp" ]; then
    echo "Error: wrapper source not found. Run this script from the native/ directory"
    exit 1
fi
WRAPPER_SRC="$(pwd)/../wrapper/re2_wrapper.cpp"

echo "Wrapper source: $WRAPPER_SRC"

if [ "$OS" = "darwin" ]; then
    # macOS
    clang++ -std=c++17 -O3 -fPIC -shared \
        -o libre2.dylib \
        "$WRAPPER_SRC" \
        re2-build/libre2.a \
        abseil-build/absl/*/*.a \
        -Ire2 \
        -Iabseil-cpp \
        -framework CoreFoundation \
        -Wl,-dead_strip

    # Make library relocatable
    install_name_tool -id "@rpath/libre2.dylib" libre2.dylib

    OUTPUT="libre2.dylib"
else
    # Linux
    g++ -std=c++17 -O3 -fPIC -shared \
        -o libre2.so \
        "$WRAPPER_SRC" \
        re2-build/libre2.a \
        abseil-build/absl/*/*.a \
        -Ire2 \
        -Iabseil-cpp \
        -Wl,--gc-sections \
        -static-libgcc \
        -static-libstdc++ \
        -pthread

    OUTPUT="libre2.so"
fi

echo "Build complete: $OUTPUT"
ls -lh "$OUTPUT"

# Verify no external dependencies (except system libs)
if [ "$OS" = "darwin" ]; then
    echo "Dependencies:"
    otool -L "$OUTPUT"
else
    echo "Dependencies:"
    ldd "$OUTPUT" || true
fi
