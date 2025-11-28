#!/bin/bash
set -e

# Build and run C++ cache tests
# This script builds the cache library and tests locally for development

echo "Building RE2 cache library and tests..."

# Check we're in native/ directory
if [ ! -f "wrapper/re2_jni.cpp" ]; then
    echo "ERROR: Run this script from the native/ directory"
    exit 1
fi

# Check RE2 is built
if [ ! -f "build/re2-build/libre2.a" ]; then
    echo "ERROR: RE2 not built. Run ./scripts/build.sh first."
    exit 1
fi

# Create build directory
BUILD_DIR="cmake-build"
mkdir -p "$BUILD_DIR"
cd "$BUILD_DIR"

# Determine sanitizer mode
SANITIZER=""
if [ "$1" = "asan" ]; then
    SANITIZER="-DENABLE_ASAN=ON"
    echo "Building with AddressSanitizer (leak detection)"
elif [ "$1" = "tsan" ]; then
    SANITIZER="-DENABLE_TSAN=ON"
    echo "Building with ThreadSanitizer (race detection)"
else
    echo "Building without sanitizers (use 'asan' or 'tsan' argument to enable)"
fi

# Configure
cmake .. \
    -DCMAKE_BUILD_TYPE=Debug \
    -DBUILD_TESTS=ON \
    $SANITIZER

# Build
echo "Compiling..."
cmake --build . -j$(nproc 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)

# Run tests
echo ""
echo "Running tests..."
ctest --output-on-failure --verbose

echo ""
echo "✓ All tests passed!"
