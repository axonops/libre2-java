# Dockerfile for building RE2 wrapper on Linux
#
# Purpose: Creates a clean build environment with all dependencies needed to compile RE2
#
# What this does:
# 1. Uses Ubuntu 22.04 as base (widely compatible)
# 2. Installs build tools: g++, cmake, curl, git
# 3. Sets up a /build working directory
# 4. Copies our build script and wrapper source
#
# This image will be used in GitHub Actions to build:
# - linux-x86_64/libre2.so
# - linux-aarch64/libre2.so (via QEMU emulation or native ARM runners)

FROM ubuntu:22.04

# Prevent interactive prompts during package installation
ENV DEBIAN_FRONTEND=noninteractive

# Install build dependencies
# - build-essential: g++, make, etc
# - cmake: for building RE2 and Abseil
# - curl: for API calls and downloads
# - git: for cloning RE2 and Abseil repos
# - jq: for JSON parsing (signature verification)
RUN apt-get update && apt-get install -y \
    build-essential \
    cmake \
    curl \
    git \
    jq \
    && rm -rf /var/lib/apt/lists/*

# Set working directory where builds will happen
WORKDIR /build

# Copy our build files
COPY wrapper/re2_wrapper.cpp /build/wrapper/
COPY scripts/build.sh /build/

# Make build script executable
RUN chmod +x /build/build.sh

# Default command: run the build script
# Output will be in /build/build/libre2.so
CMD ["/build/build.sh"]
