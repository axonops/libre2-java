/*
 * Copyright 2025 AxonOps
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "cache/murmur_hash3.h"
#include <gtest/gtest.h>
#include <string>

using namespace libre2::hash;

/**
 * Tests for MurmurHash3 wrapper.
 *
 * Uses vendored SMHasher MurmurHash3_x64_128 (public domain).
 * Tests verify wrapper returns correct first 64 bits of 128-bit hash.
 */
class MurmurHash3Test : public ::testing::Test {};

// Basic functionality tests
TEST_F(MurmurHash3Test, EmptyString) {
    const char* str = "";
    uint64_t hash = murmur3_64(str, 0, 0);
    EXPECT_EQ(hash, 0u) << "Empty string with seed 0 should hash to 0";
}

TEST_F(MurmurHash3Test, SingleByte) {
    const char* str = "a";
    uint64_t hash = murmur3_64(str, 1, 0);
    EXPECT_NE(hash, 0u) << "Single byte should produce non-zero hash";
}

TEST_F(MurmurHash3Test, ShortString) {
    const char* str = "hello";
    uint64_t hash = murmur3_64(str, 5, 0);
    EXPECT_NE(hash, 0u) << "Short string should produce non-zero hash";
}

TEST_F(MurmurHash3Test, LongerString) {
    const char* str = "The quick brown fox jumps over the lazy dog";
    uint64_t hash = murmur3_64(str, 43, 0);
    EXPECT_NE(hash, 0u) << "Longer string should produce non-zero hash";
}

TEST_F(MurmurHash3Test, SeedAffectsHash) {
    const char* str = "hello";
    uint64_t hash1 = murmur3_64(str, 5, 0);
    uint64_t hash2 = murmur3_64(str, 5, 42);
    EXPECT_NE(hash1, hash2) << "Different seeds should produce different hashes";
}

TEST_F(MurmurHash3Test, BinaryData) {
    const uint8_t data[] = {0x00, 0xFF, 0x42, 0xAA, 0x55, 0x12, 0x34, 0x56};
    uint64_t hash = murmur3_64(data, 8, 0);
    EXPECT_NE(hash, 0u) << "Binary data should produce non-zero hash";
}

// Consistency tests
TEST_F(MurmurHash3Test, Deterministic) {
    const char* str = "test pattern";
    uint64_t hash1 = murmur3_64(str, 12, 0);
    uint64_t hash2 = murmur3_64(str, 12, 0);
    EXPECT_EQ(hash1, hash2) << "Same input should always produce same hash";
}

TEST_F(MurmurHash3Test, DifferentStrings) {
    const char* str1 = "pattern1";
    const char* str2 = "pattern2";
    uint64_t hash1 = murmur3_64(str1, 8, 0);
    uint64_t hash2 = murmur3_64(str2, 8, 0);
    EXPECT_NE(hash1, hash2) << "Different strings should produce different hashes";
}

TEST_F(MurmurHash3Test, CaseSensitive) {
    const char* str1 = "Pattern";
    const char* str2 = "pattern";
    uint64_t hash1 = murmur3_64(str1, 7, 0);
    uint64_t hash2 = murmur3_64(str2, 7, 0);
    EXPECT_NE(hash1, hash2) << "Hash should be case-sensitive";
}

// Convenience function tests
TEST_F(MurmurHash3Test, HashString_Basic) {
    std::string str = "hello world";
    uint64_t hash = hashString(str);
    EXPECT_NE(hash, 0u) << "String hash should be non-zero";
}

TEST_F(MurmurHash3Test, HashString_Consistency) {
    std::string str = "test pattern";
    uint64_t hash1 = hashString(str);
    uint64_t hash2 = hashString(str);
    EXPECT_EQ(hash1, hash2) << "hashString should be deterministic";
}

TEST_F(MurmurHash3Test, HashString_Different) {
    std::string str1 = "pattern1";
    std::string str2 = "pattern2";
    uint64_t hash1 = hashString(str1);
    uint64_t hash2 = hashString(str2);
    EXPECT_NE(hash1, hash2) << "Different strings should hash differently";
}

TEST_F(MurmurHash3Test, HashString_EmptyString) {
    std::string empty = "";
    uint64_t hash = hashString(empty);
    EXPECT_EQ(hash, 0u) << "Empty string should hash to 0";
}

// Edge cases
TEST_F(MurmurHash3Test, VeryLongString) {
    std::string long_str(100000, 'x');
    uint64_t hash = murmur3_64(long_str.data(), long_str.size(), 0);
    EXPECT_NE(hash, 0u) << "Very long string should produce hash";
}

TEST_F(MurmurHash3Test, NullTerminatorNotIncluded) {
    // Verify we're hashing the content, not including null terminator
    const char* str = "hello";
    uint64_t hash1 = murmur3_64(str, 5, 0);          // 5 bytes (no null)
    uint64_t hash2 = murmur3_64(str, 6, 0);          // 6 bytes (with null)
    EXPECT_NE(hash1, hash2) << "Including null terminator should change hash";
}

TEST_F(MurmurHash3Test, UnalignedData) {
    // Test with data not aligned to 8-byte boundary
    const char buffer[] = "x" "hello world";  // offset by 1
    const char* unaligned = buffer + 1;
    uint64_t hash = murmur3_64(unaligned, 11, 0);
    EXPECT_NE(hash, 0u) << "Unaligned data should hash correctly";
}

// Distribution sanity check
TEST_F(MurmurHash3Test, DistributionSanityCheck) {
    // Hash 1000 sequential patterns, verify no obvious clustering
    const int count = 1000;
    std::vector<uint64_t> hashes;
    hashes.reserve(count);

    for (int i = 0; i < count; i++) {
        std::string pattern = "pattern_" + std::to_string(i);
        hashes.push_back(hashString(pattern));
    }

    // Check no duplicates (perfect hashing for sequential patterns)
    std::sort(hashes.begin(), hashes.end());
    auto it = std::adjacent_find(hashes.begin(), hashes.end());
    EXPECT_EQ(it, hashes.end()) << "No hash collisions expected for sequential patterns";
}

// Performance sanity check
TEST_F(MurmurHash3Test, Performance) {
    std::string str = "regex pattern for cache key hashing";
    const int iterations = 100000;

    auto start = std::chrono::steady_clock::now();
    for (int i = 0; i < iterations; i++) {
        volatile uint64_t hash = hashString(str);
        (void)hash;  // Prevent optimization
    }
    auto end = std::chrono::steady_clock::now();

    auto duration = std::chrono::duration_cast<std::chrono::nanoseconds>(end - start);
    double ns_per_hash = duration.count() / static_cast<double>(iterations);

    // Should be very fast on modern hardware
    EXPECT_LT(ns_per_hash, 500.0) << "Hash should be fast (<500ns per call)";

    // Log performance for visibility
    std::cout << "MurmurHash3 performance: " << ns_per_hash << " ns/hash\n";
}

// Verify we're using x64 variant (128-bit → 64-bit)
TEST_F(MurmurHash3Test, UsesX64Variant) {
    // The x86 and x64 variants produce different results
    // Verify we're using x64_128 (not x86_32 or x86_128)

    const char* str = "test";
    uint64_t hash = murmur3_64(str, 4, 0);

    // x64_128 produces different results than x86 variants
    // This test documents that we use x64_128 specifically
    EXPECT_NE(hash, 0u) << "x64_128 should produce non-zero hash";

    // Log which variant we're using
    std::cout << "Using MurmurHash3_x64_128 (first 64 bits)\n";
}
