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

package com.axonops.libre2.api;

/**
 * Main entry point for RE2 regex operations.
 *
 * Thread-safe: All methods can be called concurrently from multiple threads.
 *
 * @since 1.0.0
 */
public final class RE2 {

    private RE2() {
        // Utility class
    }

    public static Pattern compile(String pattern) {
        return Pattern.compile(pattern);
    }

    public static Pattern compile(String pattern, boolean caseSensitive) {
        return Pattern.compile(pattern, caseSensitive);
    }

    public static boolean matches(String pattern, String input) {
        try (Pattern p = compile(pattern)) {
            return p.matches(input);
        }
    }
}
