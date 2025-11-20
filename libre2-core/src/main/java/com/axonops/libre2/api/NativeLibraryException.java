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
 * Thrown when native library operations fail.
 *
 * @since 1.0.0
 */
public final class NativeLibraryException extends RE2Exception {

    public NativeLibraryException(String message) {
        super("RE2: Native library error: " + message);
    }

    public NativeLibraryException(String message, Throwable cause) {
        super("RE2: Native library error: " + message, cause);
    }
}
