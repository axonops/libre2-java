#!/bin/bash
set -e

# Build script for RE2 wrapper library
# This script:
# 1. Downloads RE2 and Abseil source
# 2. Builds them as static libraries
# 3. Links our wrapper into a single shared library with no external dependencies
#
# Detects container runtime (podman or docker) for Linux builds

RE2_VERSION="2025-11-05"
ABSEIL_VERSION="20250814.1"

# Detect container runtime (prefer podman, fallback to docker)
if command -v podman &> /dev/null; then
    DOCKER_CMD="podman"
    echo "Using podman"
elif command -v docker &> /dev/null; then
    DOCKER_CMD="docker"
    echo "Using docker"
else
    DOCKER_CMD=""
    echo "Neither podman nor docker found (not needed for native builds)"
fi

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

# Download RE2
if [ ! -d "re2-${RE2_VERSION}" ]; then
    echo "Downloading RE2 ${RE2_VERSION}..."
    curl -L "https://github.com/google/re2/archive/refs/tags/${RE2_VERSION}.tar.gz" -o re2.tar.gz
    tar xzf re2.tar.gz
    rm re2.tar.gz
fi

# Download Abseil
if [ ! -d "abseil-cpp-${ABSEIL_VERSION}" ]; then
    echo "Downloading Abseil ${ABSEIL_VERSION}..."
    curl -L "https://github.com/abseil/abseil-cpp/archive/refs/tags/${ABSEIL_VERSION}.tar.gz" -o abseil.tar.gz
    tar xzf abseil.tar.gz
    rm abseil.tar.gz
fi

# Build Abseil statically
echo "Building Abseil..."
ABSEIL_PREFIX="$BUILD_DIR/abseil-install"
mkdir -p abseil-build
cd abseil-build
cmake ../abseil-cpp-${ABSEIL_VERSION} \
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
cmake ../re2-${RE2_VERSION} \
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
        -Ire2-${RE2_VERSION} \
        -Iabseil-cpp-${ABSEIL_VERSION} \
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
        -Ire2-${RE2_VERSION} \
        -Iabseil-cpp-${ABSEIL_VERSION} \
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
