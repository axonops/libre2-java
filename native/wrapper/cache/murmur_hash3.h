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

#pragma once

#include <cstdint>
#include <string>

namespace libre2 {
namespace hash {

/**
 * MurmurHash3 32-bit variant.
 *
 * @param key pointer to data to hash
 * @param len length of data in bytes
 * @param seed hash seed (use 0 for default)
 * @return 32-bit hash value
 */
uint32_t murmur3_32(const void* key, int len, uint32_t seed);

/**
 * MurmurHash3 64-bit variant.
 *
 * @param key pointer to data to hash
 * @param len length of data in bytes
 * @param seed hash seed (use 0 for default)
 * @return 64-bit hash value
 */
uint64_t murmur3_64(const void* key, int len, uint64_t seed);

/**
 * Convenience function to hash a std::string.
 *
 * @param str string to hash
 * @return 64-bit hash value
 */
inline uint64_t hashString(const std::string& str) {
    return murmur3_64(str.data(), static_cast<int>(str.size()), 0);
}

}  // namespace hash
}  // namespace libre2
