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

package com.axonops.libre2.metrics;

import java.util.function.Supplier;

/**
 * No-op metrics registry implementation.
 *
 * <p>Used when metrics are disabled (default). All methods are empty and will be inlined by the JIT
 * compiler, resulting in zero overhead.
 *
 * <p>This is the default implementation in {@link com.axonops.libre2.cache.RE2Config#DEFAULT}.
 *
 * @since 0.9.1
 */
public final class NoOpMetricsRegistry implements RE2MetricsRegistry {

  /** Singleton instance - use this instead of creating new instances. */
  public static final NoOpMetricsRegistry INSTANCE = new NoOpMetricsRegistry();

  private NoOpMetricsRegistry() {
    // Singleton - use INSTANCE
  }

  @Override
  public void incrementCounter(String name) {
    // No-op
  }

  @Override
  public void incrementCounter(String name, long delta) {
    // No-op
  }

  @Override
  public void recordTimer(String name, long durationNanos) {
    // No-op
  }

  @Override
  public void registerGauge(String name, Supplier<Number> valueSupplier) {
    // No-op
  }

  @Override
  public void removeGauge(String name) {
    // No-op
  }
}
