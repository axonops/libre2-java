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

import com.axonops.libre2.jni.RE2NativeJNI;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Performs regex matching operations.
 *
 * NOT Thread-Safe: Each Matcher instance must be confined to a single thread.
 * Do NOT share Matcher instances between threads.
 *
 * Safe Pattern: Create separate Matcher per thread from shared Pattern.
 * The underlying Pattern CAN be safely shared - only the Matcher cannot.
 *
 * Example:
 * <pre>
 * Pattern sharedPattern = RE2.compile("\\d+");  // Thread-safe, can share
 *
 * // Thread 1
 * try (Matcher m1 = sharedPattern.matcher("123")) {  // Each thread gets own Matcher
 *     m1.matches();
 * }
 *
 * // Thread 2
 * try (Matcher m2 = sharedPattern.matcher("456")) {  // Different Matcher instance
 *     m2.matches();
 * }
 * </pre>
 *
 * @since 1.0.0
 */
public final class Matcher implements AutoCloseable {

    private final Pattern pattern;
    private final String input;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    Matcher(Pattern pattern, String input) {
        this.pattern = Objects.requireNonNull(pattern);
        this.input = Objects.requireNonNull(input);

        // Increment reference count to prevent pattern being freed while in use
        pattern.incrementRefCount();
    }

    public boolean matches() {
        checkNotClosed();

        boolean result = RE2NativeJNI.fullMatch(pattern.getNativeHandle(), input);

        // JNI version returns boolean directly, no error code
        return result;
    }

    public boolean find() {
        checkNotClosed();

        boolean result = RE2NativeJNI.partialMatch(pattern.getNativeHandle(), input);

        // JNI version returns boolean directly, no error code
        return result;
    }

    public Pattern pattern() {
        return pattern;
    }

    public String input() {
        return input;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // Decrement reference count - pattern can now be freed if evicted
            pattern.decrementRefCount();
        }
    }

    private void checkNotClosed() {
        if (closed.get()) {
            throw new IllegalStateException("RE2: Matcher is closed");
        }
    }
}
