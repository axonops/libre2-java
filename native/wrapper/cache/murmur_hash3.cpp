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
#include "murmurhash3/MurmurHash3.h"

namespace libre2 {
namespace hash {

uint64_t murmur3_64(const void* key, int len, uint32_t seed) {
    uint64_t out[2];
    MurmurHash3_x64_128(key, len, seed, out);
    return out[0];  // Return first 64 bits of 128-bit hash
}

}  // namespace hash
}  // namespace libre2
