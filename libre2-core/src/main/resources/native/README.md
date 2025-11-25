# RE2 Native Library Binaries

This directory contains compiled native binaries of the RE2 regular expression library.

## License

RE2 is licensed under the BSD-3-Clause License.

- **Project:** https://github.com/google/re2
- **License:** BSD-3-Clause
- **Copyright:** The RE2 Authors

For full license text, see [THIRD_PARTY_LICENSES.md](../../../../THIRD_PARTY_LICENSES.md)

## Build Information

These binaries are built from the RE2 project using GitHub Actions CI/CD with:
- **Git commit pinning** for reproducible builds
- **Signature verification** of source commits
- **Static linking** of Abseil C++ dependencies

**Platforms Included:**

| Platform | Architecture | Library |
|----------|-------------|---------|
| Linux | x86_64 | `linux-x86_64/libre2.so` |
| Linux | ARM64 | `linux-aarch64/libre2.so` |
| macOS | Intel | `darwin-x86_64/libre2.dylib` |
| macOS | Apple Silicon | `darwin-aarch64/libre2.dylib` |

## Source Versions

- **RE2:** Built from pinned git commit (see GitHub Actions workflow)
- **Abseil:** Built from pinned git commit (statically linked)

## Attribution

When distributing libre2-java:
1. Include Apache License 2.0 (see LICENSE)
2. Include RE2 BSD-3-Clause license (see THIRD_PARTY_LICENSES.md)
3. Include NOTICE file with attribution

## Security

These binaries are built with supply chain security measures:
- Source commits verified via GitHub API
- No external binary dependencies
- Reproducible builds via CI/CD

See [native/README.md](../../../../native/README.md) for build process details.
