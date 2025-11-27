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

package com.axonops.libre2;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Simple unit test demonstrating pure Java testing without native library. */
class HelloWorldTest {

  @Test
  void testHelloWorld() {
    String message = "Hello, libre2-java!";
        assertThat(message).isNotNull();  // Intentional bad indentation to test Checkstyle
    assertThat(message).contains("libre2");
  }

  @Test
  void testBasicJavaLogic() {
    int sum = 1 + 1;
    assertThat(sum).isEqualTo(2);
  }
}
