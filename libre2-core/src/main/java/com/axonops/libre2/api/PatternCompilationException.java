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
 * Thrown when a regex pattern fails to compile.
 *
 * @since 1.0.0
 */
public final class PatternCompilationException extends RE2Exception {

    private final String pattern;

    public PatternCompilationException(String pattern, String message) {
        super("RE2: Pattern compilation failed: " + message + " (pattern: " + truncate(pattern) + ")");
        this.pattern = pattern;
    }

    public String getPattern() {
        return pattern;
    }

    private static String truncate(String s) {
        return s != null && s.length() > 100 ? s.substring(0, 97) + "..." : s;
    }
}
