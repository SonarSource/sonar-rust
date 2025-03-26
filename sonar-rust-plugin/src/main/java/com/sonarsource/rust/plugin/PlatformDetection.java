/*
 * SonarQube Rust Plugin
 * Copyright (C) 2025 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Sonar Source-Available License Version 1, as published by SonarSource SA.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
package com.sonarsource.rust.plugin;

import static com.sonarsource.rust.plugin.PlatformDetection.Platform.DARWIN_AARCH64;
import static com.sonarsource.rust.plugin.PlatformDetection.Platform.DARWIN_X86_64;
import static com.sonarsource.rust.plugin.PlatformDetection.Platform.LINUX_AARCH64;
import static com.sonarsource.rust.plugin.PlatformDetection.Platform.UNSUPPORTED;
import static com.sonarsource.rust.plugin.PlatformDetection.Platform.WIN_X64;

import java.util.Locale;

class PlatformDetection {

  private final SystemWrapper system;

  enum Platform {
    WIN_X64,
    LINUX_X64_MUSL,
    LINUX_AARCH64,
    DARWIN_AARCH64,
    DARWIN_X86_64,
    UNSUPPORTED;
  }

  PlatformDetection() {
    this(new SystemWrapper());
  }

  PlatformDetection(SystemWrapper system) {
    this.system = system;
  }

  /**
   * @return The platform where this code is running
   */
  Platform detect() {
    var osName = system.getOsName();
    var lowerCaseOsName = osName.toLowerCase(Locale.ROOT);
    if (lowerCaseOsName.contains("windows") && isX64()) {
      return WIN_X64;
    } else if (lowerCaseOsName.contains("linux")) {
      if (isX64()) {
        return Platform.LINUX_X64_MUSL;
      } else if (isARM64())  {
        return LINUX_AARCH64;
      }
    } else if (lowerCaseOsName.contains("mac os")) {
      return isARM64() ? DARWIN_AARCH64 : DARWIN_X86_64;
    }
    return UNSUPPORTED;
  }

  String debug() {
    return String.format("os: %s, arch: %s", system.getOsName(), system.getOsArch());
  }

  private boolean isX64() {
    return system.getOsArch().contains("amd64");
  }

  private boolean isARM64() {
    return system.getOsArch().contains("aarch64");
  }

}
