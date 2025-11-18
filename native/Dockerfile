# Dockerfile for building RE2 wrapper on Linux
#
# Purpose: Creates a clean build environment with all dependencies needed to compile RE2
#
# What this does:
# 1. Uses Rocky Linux 8 as base (GLIBC 2.28 for maximum compatibility with RHEL 8+)
# 2. Installs build tools: g++, cmake, curl, git
# 3. Sets up a /build working directory
# 4. Copies our build script and wrapper source
#
# This image will be used in GitHub Actions to build:
# - linux-x86_64/libre2.so
# - linux-aarch64/libre2.so (via QEMU emulation or native ARM runners)
#
# Using Rocky 8 ensures compatibility with:
# - Rocky Linux 8/9
# - RHEL 8/9
# - Ubuntu 20.04/22.04/24.04
# - Any system with GLIBC 2.28+

FROM rockylinux:8

# Enable PowerTools repo for development packages
RUN dnf install -y dnf-plugins-core && \
    dnf config-manager --set-enabled powertools

# Install build dependencies
# - gcc-c++: C++ compiler
# - make: build automation
# - cmake: for building RE2 and Abseil
# - curl: for API calls and downloads
# - git: for cloning RE2 and Abseil repos
# - jq: for JSON parsing (signature verification)
# - java-17-openjdk-devel: for JNI headers
RUN dnf install -y \
    gcc-c++ \
    make \
    cmake \
    curl \
    git \
    jq \
    java-17-openjdk-devel \
    && dnf clean all

# JAVA_HOME for Rocky Linux
# - x86_64: /usr/lib/jvm/java-17-openjdk
# - aarch64: /usr/lib/jvm/java-17-openjdk

# Set working directory where builds will happen
WORKDIR /build

# Copy our build files
COPY wrapper/re2_jni.cpp /build/wrapper/
COPY jni/com_axonops_libre2_jni_RE2NativeJNI.h /build/jni/
COPY scripts/build.sh /build/

# Make build script executable
RUN chmod +x /build/build.sh

# Default command: run the build script
# Output will be in /build/build/libre2.so
CMD ["/build/build.sh"]
