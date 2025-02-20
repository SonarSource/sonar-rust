/*
 * Copyright (C) 2025 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package com.sonarsource.rust.plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

enum Platform {
  WIN_X64,
  LINUX_X64,
  LINUX_X64_MUSL,
  DARWIN_ARM64,
  UNSUPPORTED;

  String pathInJar() {
    return switch (this) {
      case WIN_X64 -> "/analyzer/win-x64/analyzer.exe";
      case LINUX_X64 -> "/analyzer/linux-x64/analyzer";
      case LINUX_X64_MUSL -> "/analyzer/linux-x64-musl/analyzer";
      case DARWIN_ARM64 -> "/analyzer/darwin-arm64/analyzer";
      default -> throw new IllegalStateException("Unsupported platform");
    };
  }

  /**
   * @return The platform where this code is running
   */
  static Platform detect() {
    var osName = getOsName();
    var lowerCaseOsName = osName.toLowerCase(Locale.ROOT);
    if (osName.contains("Windows") && isX64()) {
      return WIN_X64;
    } else if (lowerCaseOsName.contains("linux") && isX64()) {
      return isAlpine() ? LINUX_X64_MUSL : LINUX_X64;
    } else if (lowerCaseOsName.contains("mac os") && isARM64()) {
      return DARWIN_ARM64;
    }
    return UNSUPPORTED;
  }

  private static boolean isX64() {
    return getOsArch().contains("amd64");
  }

  private static boolean isARM64() {
    return getOsArch().contains("aarch64");
  }

  static boolean isAlpine() {
    return Files.exists(Path.of("/etc/alpine-release"));
  }

  private static String getOsName() {
    return System.getProperty("os.name");
  }

  private static String getOsArch() {
    return System.getProperty("os.arch");
  }

}
