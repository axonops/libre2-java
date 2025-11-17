# Third-Party Licenses

## RE2 Regular Expression Library

libre2-java bundles native binaries of the RE2 regular expression library.

- **Project:** https://github.com/google/re2
- **License:** BSD-3-Clause
- **Copyright:** The RE2 Authors
- **Bundled Location:** src/main/resources/native/

### BSD-3-Clause License (RE2)

Copyright (c) 2003-2024 The RE2 Authors. All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice,
   this list of conditions and the following disclaimer.

2. Redistributions in binary form must reproduce the above copyright
   notice, this list of conditions and the following disclaimer in the
   documentation and/or other materials provided with the distribution.

3. Neither the name of the copyright holder nor the names of its
   contributors may be used to endorse or promote products derived from
   this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

---

## Abseil C++ Library

RE2 depends on the Abseil C++ library, which is statically linked into the native binaries.

- **Project:** https://github.com/abseil/abseil-cpp
- **License:** Apache License 2.0
- **Copyright:** The Abseil Authors
- **Note:** Statically linked into RE2 binaries

---

## JNA (Java Native Access)

libre2-java uses JNA for native library bindings.

- **Project:** https://github.com/java-native-access/jna
- **License:** Apache License 2.0 OR LGPL 2.1
- **Scope:** Provided dependency (not bundled)
- **Note:** Your application provides JNA 5.13.0 or later

---

## SLF4J (Simple Logging Facade for Java)

libre2-java uses SLF4J for logging.

- **Project:** https://www.slf4j.org/
- **License:** MIT License
- **Scope:** Provided dependency (not bundled)
- **Note:** Your application provides SLF4J 2.0+ and an implementation
