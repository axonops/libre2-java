cmake_minimum_required(VERSION 3.20)
project(libre2_cache VERSION 1.0.0 LANGUAGES CXX)

# C++17 required
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Position-independent code for shared library
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Build type
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

message(STATUS "Build type: ${CMAKE_BUILD_TYPE}")
message(STATUS "C++ compiler: ${CMAKE_CXX_COMPILER_ID} ${CMAKE_CXX_COMPILER_VERSION}")

# Compiler warnings
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    add_compile_options(
        -Wall
        -Wextra
        -Wpedantic
        -Wno-unused-parameter
    )
endif()

# ============================================================================
# Dependencies
# ============================================================================

include(FetchContent)

# nlohmann/json (header-only) - already downloaded
add_library(nlohmann_json INTERFACE)
target_include_directories(nlohmann_json INTERFACE
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party
)

# RE2 and Abseil - built by build.sh script
# We'll link against the static libraries
set(BUILD_DIR "${CMAKE_CURRENT_SOURCE_DIR}/build")

# Find RE2
find_library(RE2_LIBRARY
    NAMES libre2.a
    PATHS ${BUILD_DIR}/re2-build
    NO_DEFAULT_PATH
    REQUIRED
)
message(STATUS "Found RE2 library: ${RE2_LIBRARY}")

# Find Abseil libraries
file(GLOB ABSEIL_LIBRARIES "${BUILD_DIR}/abseil-build/absl/*/*.a")
if(NOT ABSEIL_LIBRARIES)
    message(FATAL_ERROR "Abseil libraries not found. Run build.sh first.")
endif()
list(LENGTH ABSEIL_LIBRARIES ABSEIL_LIB_COUNT)
message(STATUS "Found ${ABSEIL_LIB_COUNT} Abseil libraries")

# Find oneTBB library (built by build.sh)
# TBB builds into platform-specific subdirectory
file(GLOB_RECURSE TBB_LIBRARY_FOUND "${BUILD_DIR}/tbb-build/*/libtbb.a")
if(NOT TBB_LIBRARY_FOUND)
    message(FATAL_ERROR "oneTBB library not found. Run build.sh first.")
endif()
list(GET TBB_LIBRARY_FOUND 0 TBB_LIBRARY)
message(STATUS "Found oneTBB library: ${TBB_LIBRARY}")

# TBB include directory
set(TBB_INCLUDE_DIR "${BUILD_DIR}/oneTBB/include")
if(NOT EXISTS ${TBB_INCLUDE_DIR}/oneapi/tbb/concurrent_hash_map.h)
    message(FATAL_ERROR "oneTBB headers not found at ${TBB_INCLUDE_DIR}. Run build.sh first.")
endif()

# Include directories
set(RE2_INCLUDE_DIR "${BUILD_DIR}/re2")
set(ABSEIL_INCLUDE_DIR "${BUILD_DIR}/abseil-cpp")

if(NOT EXISTS ${RE2_INCLUDE_DIR}/re2/re2.h)
    message(FATAL_ERROR "RE2 headers not found at ${RE2_INCLUDE_DIR}. Run build.sh first.")
endif()

# ============================================================================
# Cache Library
# ============================================================================

add_library(re2_cache STATIC
    # Vendored MurmurHash3 (public domain, from SMHasher)
    third_party/murmurhash3/MurmurHash3.cpp
    # Cache implementation
    wrapper/cache/murmur_hash3.cpp
    wrapper/cache/cache_config.cpp
    wrapper/cache/cache_metrics.cpp
    wrapper/cache/result_cache.cpp
    wrapper/cache/pattern_cache.cpp
    wrapper/cache/deferred_cache.cpp
    wrapper/cache/eviction_thread.cpp
    wrapper/cache/cache_manager.cpp
    # Facade API for language wrappers
    wrapper/pattern_options.cpp
    wrapper/libre2_api.cpp
)

target_include_directories(re2_cache PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/wrapper
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party
    ${RE2_INCLUDE_DIR}
    ${ABSEIL_INCLUDE_DIR}
    ${TBB_INCLUDE_DIR}
)

target_link_libraries(re2_cache PUBLIC
    ${RE2_LIBRARY}
    ${ABSEIL_LIBRARIES}
    ${TBB_LIBRARY}
    nlohmann_json
)

# Platform-specific libraries
if(APPLE)
    target_link_libraries(re2_cache PUBLIC
        "-framework CoreFoundation"
    )
elseif(UNIX)
    target_link_libraries(re2_cache PUBLIC
        pthread
    )
endif()

# ============================================================================
# Testing (optional - enabled by -DBUILD_TESTS=ON)
# ============================================================================

option(BUILD_TESTS "Build C++ unit tests" ON)

if(BUILD_TESTS)
    message(STATUS "Building tests (BUILD_TESTS=ON)")

    # GoogleTest
    FetchContent_Declare(
        googletest
        GIT_REPOSITORY https://github.com/google/googletest.git
        GIT_TAG v1.14.0
        GIT_SHALLOW TRUE
    )
    # Prevent GoogleTest from overriding compiler flags
    set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(googletest)

    # Enable CTest
    enable_testing()
    include(GoogleTest)

    add_subdirectory(tests)
else()
    message(STATUS "Skipping tests (BUILD_TESTS=OFF)")
endif()

# ============================================================================
# Installation (not used - for completeness)
# ============================================================================

install(TARGETS re2_cache
    ARCHIVE DESTINATION lib
    LIBRARY DESTINATION lib
)

install(DIRECTORY wrapper/cache/
    DESTINATION include/libre2/cache
    FILES_MATCHING PATTERN "*.h"
)

# ============================================================================
# Summary
# ============================================================================

message(STATUS "")
message(STATUS "libre2-java Native Cache Build Configuration")
message(STATUS "=============================================")
list(LENGTH ABSEIL_LIBRARIES ABSEIL_LIB_COUNT)
message(STATUS "Build type:       ${CMAKE_BUILD_TYPE}")
message(STATUS "C++ standard:     C++${CMAKE_CXX_STANDARD}")
message(STATUS "Build tests:      ${BUILD_TESTS}")
message(STATUS "RE2 library:      ${RE2_LIBRARY}")
message(STATUS "Abseil libs:      ${ABSEIL_LIB_COUNT} libraries")
message(STATUS "oneTBB library:   ${TBB_LIBRARY}")
message(STATUS "JSON library:     nlohmann/json (header-only)")
message(STATUS "")
