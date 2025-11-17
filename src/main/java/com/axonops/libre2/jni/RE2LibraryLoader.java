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

package com.axonops.libre2.jni;

import com.sun.jna.Native;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loads the native RE2 library for the current platform.
 *
 * Automatically detects platform and extracts the correct
 * library from JAR resources.
 *
 * Supported platforms:
 * - macOS x86_64 (Intel)
 * - macOS aarch64 (Apple Silicon)
 * - Linux x86_64
 * - Linux aarch64 (ARM64)
 *
 * Thread-safe and idempotent.
 *
 * @since 1.0.0
 */
public final class RE2LibraryLoader {
    private static final Logger logger = LoggerFactory.getLogger(RE2LibraryLoader.class);
    private static final AtomicBoolean loaded = new AtomicBoolean(false);
    private static volatile RE2Native library = null;
    private static volatile Exception loadError = null;

    private RE2LibraryLoader() {
        // Utility class
    }

    /**
     * Loads the native library (idempotent).
     *
     * @return RE2Native interface
     * @throws IllegalStateException if library cannot be loaded
     */
    public static RE2Native loadLibrary() {
        if (loaded.get()) {
            if (loadError != null) {
                throw new IllegalStateException("RE2: Previous library load failed", loadError);
            }
            return library;
        }

        synchronized (RE2LibraryLoader.class) {
            if (loaded.get()) {
                if (loadError != null) {
                    throw new IllegalStateException("RE2: Previous library load failed", loadError);
                }
                return library;
            }

            try {
                logger.info("RE2: Loading native library for platform: {}", getPlatformName());

                Platform platform = detectPlatform();
                String resourcePath = getResourcePath(platform);
                String libraryName = getLibraryFileName(platform);

                logger.debug("RE2: Resource path: {}", resourcePath);

                // Extract from JAR to temp directory
                Path tempLib = extractLibrary(resourcePath, libraryName);

                // Load via JNA
                library = Native.load(tempLib.toString(), RE2Native.class);
                loaded.set(true);

                logger.info("RE2: Native library loaded successfully");
                return library;

            } catch (Exception e) {
                loadError = e;
                loaded.set(true);
                logger.error("RE2: Failed to load native library", e);
                throw new IllegalStateException("RE2: Failed to load native library: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Detects the current platform.
     */
    private static Platform detectPlatform() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();

        OS detectedOS;
        if (os.contains("mac") || os.contains("darwin")) {
            detectedOS = OS.MACOS;
        } else if (os.contains("linux")) {
            detectedOS = OS.LINUX;
        } else {
            throw new IllegalStateException("RE2: Unsupported OS: " + os + " (only macOS and Linux supported)");
        }

        Arch detectedArch;
        if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) {
            detectedArch = Arch.X86_64;
        } else if (arch.equals("aarch64") || arch.equals("arm64")) {
            detectedArch = Arch.AARCH64;
        } else {
            throw new IllegalStateException("RE2: Unsupported architecture: " + arch + " (only x86_64 and aarch64 supported)");
        }

        return new Platform(detectedOS, detectedArch);
    }

    private static String getPlatformName() {
        return System.getProperty("os.name") + " " + System.getProperty("os.arch");
    }

    private static String getLibraryFileName(Platform platform) {
        return platform.os == OS.MACOS ? "libre2.dylib" : "libre2.so";
    }

    private static String getResourcePath(Platform platform) {
        String platformDir = switch (platform.os) {
            case MACOS -> "darwin";
            case LINUX -> "linux";
        } + "-" + switch (platform.arch) {
            case X86_64 -> "x86_64";
            case AARCH64 -> "aarch64";
        };

        return "/native/" + platformDir + "/" + getLibraryFileName(platform);
    }

    private static Path extractLibrary(String resourcePath, String fileName) throws IOException {
        try (InputStream in = RE2LibraryLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("RE2: Native library not found in JAR: " + resourcePath);
            }

            Path tempDir = Files.createTempDirectory("libre2-");
            Path libFile = tempDir.resolve(fileName);

            Files.copy(in, libFile, StandardCopyOption.REPLACE_EXISTING);
            libFile.toFile().setExecutable(true);
            libFile.toFile().deleteOnExit();
            tempDir.toFile().deleteOnExit();

            logger.debug("RE2: Library extracted to: {}", libFile);
            return libFile;
        }
    }

    public static boolean isLoaded() {
        return loaded.get() && loadError == null;
    }

    private record Platform(OS os, Arch arch) {}

    private enum OS { MACOS, LINUX }
    private enum Arch { X86_64, AARCH64 }
}
