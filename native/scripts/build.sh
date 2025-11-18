#!/bin/bash
set -e

# Build script for RE2 wrapper library
# This script:
# 1. Downloads RE2 and Abseil source
# 2. Builds them as static libraries
# 3. Links our wrapper into a single shared library with no external dependencies
#
# Detects container runtime (podman or docker) for Linux builds

# SECURITY: All version info provided via GitHub Environment Variables
# These are stored in the 'native-builds' protected environment
# and can only be modified by repository admins.
#
# Required variables:
# - RE2_COMMIT: Git commit hash for RE2
# - RE2_RELEASE_VERSION: Release version (for logging/reference)
# - ABSEIL_COMMIT: Git commit hash for Abseil
# - ABSEIL_RELEASE_VERSION: Release version (for logging/reference)

if [ -z "$RE2_COMMIT" ] || [ -z "$ABSEIL_COMMIT" ] || [ -z "$RE2_RELEASE_VERSION" ] || [ -z "$ABSEIL_RELEASE_VERSION" ]; then
    echo "ERROR: Required environment variables not set"
    echo "Missing one or more of: RE2_COMMIT, RE2_RELEASE_VERSION, ABSEIL_COMMIT, ABSEIL_RELEASE_VERSION"
    echo ""
    echo "These should be provided by the GitHub Actions 'native-builds' environment"
    echo ""
    echo "For local testing, set them manually:"
    echo "  export RE2_COMMIT=927f5d53caf8111721e734cf24724686bb745f55"
    echo "  export RE2_RELEASE_VERSION=2025-11-05"
    echo "  export ABSEIL_COMMIT=d38452e1ee03523a208362186fd42248ff2609f6"
    echo "  export ABSEIL_RELEASE_VERSION=20250814.1"
    exit 1
fi

echo "Building with pinned commits (from protected environment):"
echo "  RE2:    $RE2_COMMIT (release $RE2_RELEASE_VERSION)"
echo "  Abseil: $ABSEIL_COMMIT (release $ABSEIL_RELEASE_VERSION)"

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
    echo "Cloning RE2 at commit $RE2_COMMIT..."
    git clone https://github.com/google/re2.git
    cd re2
    git checkout "$RE2_COMMIT"

    echo "RE2 commit: $(git log -1 --oneline)"

    # SECURITY: Verify commit signature via GitHub API
    echo "Verifying RE2 commit signature..."

    # Debug: Check jq is available
    if ! command -v jq &> /dev/null; then
        echo "✗ ERROR: jq is not installed"
        exit 1
    fi

    # Fetch commit info from GitHub API
    API_URL="https://api.github.com/repos/google/re2/commits/$RE2_COMMIT"
    echo "  Calling: $API_URL"

    API_JSON=$(curl -s "$API_URL")

    # Debug: Show what we got
    echo "  API response length: ${#API_JSON} bytes"

    # Extract verification status
    VERIFIED=$(echo "$API_JSON" | jq -r '.commit.verification.verified // empty')

    echo "  Verification status: '$VERIFIED'"

    if [ "$VERIFIED" = "true" ]; then
        echo "✓ RE2 commit signature verified by GitHub"
    else
        echo "✗ ERROR: RE2 commit signature NOT verified"
        echo "  This commit may not be from a trusted Google engineer"
        exit 1
    fi

    cd ..
fi

# Clone and checkout Abseil at pinned commit
if [ ! -d "abseil-cpp" ]; then
    echo "Cloning Abseil at commit $ABSEIL_COMMIT..."
    git clone https://github.com/abseil/abseil-cpp.git
    cd abseil-cpp
    git checkout "$ABSEIL_COMMIT"

    echo "Abseil commit: $(git log -1 --oneline)"

    # SECURITY: Verify commit signature via GitHub API
    echo "Verifying Abseil commit signature..."

    API_URL="https://api.github.com/repos/abseil/abseil-cpp/commits/$ABSEIL_COMMIT"
    echo "  Calling: $API_URL"

    API_JSON=$(curl -s "$API_URL")
    echo "  API response length: ${#API_JSON} bytes"

    VERIFIED=$(echo "$API_JSON" | jq -r '.commit.verification.verified // empty')
    echo "  Verification status: '$VERIFIED'"

    if [ "$VERIFIED" = "true" ]; then
        echo "✓ Abseil commit signature verified by GitHub"
    else
        echo "✗ ERROR: Abseil commit signature NOT verified"
        echo "  This commit may not be from a trusted Google engineer"
        exit 1
    fi

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

# Build our JNI wrapper
echo "Building JNI wrapper..."
# Script must be run from native/ directory
# Wrapper source is in native/wrapper/re2_jni.cpp
if [ ! -f "../wrapper/re2_jni.cpp" ]; then
    echo "Error: JNI wrapper source not found. Run this script from the native/ directory"
    exit 1
fi
WRAPPER_SRC="$(pwd)/../wrapper/re2_jni.cpp"

# JNI header is in native/jni/
JNI_HEADER_DIR="$(pwd)/../jni"
if [ ! -f "$JNI_HEADER_DIR/com_axonops_libre2_jni_RE2NativeJNI.h" ]; then
    echo "Error: JNI header not found at $JNI_HEADER_DIR"
    echo "Generate it with: javac -h native/jni src/main/java/com/axonops/libre2/jni/RE2NativeJNI.java"
    exit 1
fi

# Find JAVA_HOME for JNI headers
if [ -z "$JAVA_HOME" ]; then
    if [ "$OS" = "darwin" ]; then
        JAVA_HOME=$(/usr/libexec/java_home 2>/dev/null || echo "")
    elif [ "$ARCH" = "aarch64" ] && [ -d "/usr/lib/jvm/java-17-openjdk-arm64" ]; then
        JAVA_HOME="/usr/lib/jvm/java-17-openjdk-arm64"
    elif [ -d "/usr/lib/jvm/java-17-openjdk-amd64" ]; then
        JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
    fi
fi

if [ -z "$JAVA_HOME" ] || [ ! -d "$JAVA_HOME/include" ]; then
    echo "ERROR: JAVA_HOME not set or JNI headers not found"
    echo "Set JAVA_HOME to a JDK installation with include/jni.h"
    exit 1
fi

JNI_INCLUDE="$JAVA_HOME/include"
if [ "$OS" = "darwin" ]; then
    JNI_PLATFORM_INCLUDE="$JAVA_HOME/include/darwin"
else
    JNI_PLATFORM_INCLUDE="$JAVA_HOME/include/linux"
fi

echo "JNI headers: $JNI_INCLUDE"
echo "JNI platform headers: $JNI_PLATFORM_INCLUDE"
echo "JNI wrapper header: $JNI_HEADER_DIR"
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
        -I"$JNI_INCLUDE" \
        -I"$JNI_PLATFORM_INCLUDE" \
        -I"$JNI_HEADER_DIR" \
        -framework CoreFoundation \
        -Wl,-dead_strip

    # Make library relocatable
    install_name_tool -id "@rpath/libre2.dylib" libre2.dylib

    OUTPUT="libre2.dylib"
else
    # Linux - use --whole-archive to ensure ALL Abseil symbols are included
    g++ -std=c++17 -O3 -fPIC -shared \
        -o libre2.so \
        "$WRAPPER_SRC" \
        re2-build/libre2.a \
        -Wl,--whole-archive abseil-build/absl/*/*.a -Wl,--no-whole-archive \
        -Ire2 \
        -Iabseil-cpp \
        -I"$JNI_INCLUDE" \
        -I"$JNI_PLATFORM_INCLUDE" \
        -I"$JNI_HEADER_DIR" \
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
